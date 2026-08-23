package com.doubao.ime.noensuggest;

import android.app.Application;
import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 加载伴随 SO 并安装 native hook。
 * <p>
 * JNI 必须挂在「加载了 NativeBridge 的 ClassLoader」上，因此用 {@link System#load(String)}
 * （caller=NativeBridge），不要用目标 App 的 Class 作为 load0 fromClass。
 * libkeyboard 符号通过 maps 基址 + 解析磁盘 SO 的 .dynsym（不依赖 dlopen / 版本 RVA）。
 */
final class NativeBridge {
    private static final AtomicBoolean LOADED = new AtomicBoolean(false);
    private static final AtomicBoolean READY = new AtomicBoolean(false);

    private NativeBridge() {
    }

    static native int nativeInstall();

    static native boolean nativeIsReady();

    static native void nativeSetLoggingEnabled(boolean enabled);

    static native void nativeClearEnglishTypingBuffer();

    static native void nativeMarkInputReady(boolean ready);

    static native void nativeForcePasswordBox(boolean enable);

    static native boolean nativeIsEnglishUi();

    static native int nativeGetBoardType();

    static native int nativeGetInputMode();

    /** 请求丢弃 Native 预编辑；真正的 InputModel::Clear 只在目标 Native 回调栈执行。 */
    static void clearEnglishTypingBufferQuiet() {
        if (!READY.get()) {
            return;
        }
        try {
            nativeClearEnglishTypingBuffer();
        } catch (Throwable ignored) {
        }
    }

    /** startInputView 成功后标记就绪，允许安全 discard。 */
    static void markInputReadyQuiet(boolean ready) {
        if (!READY.get()) {
            return;
        }
        try {
            nativeMarkInputReady(ready);
        } catch (Throwable ignored) {
        }
    }

    static void forcePasswordBoxQuiet(boolean enable) {
        if (!READY.get()) {
            return;
        }
        try {
            nativeForcePasswordBox(enable);
        } catch (Throwable ignored) {
        }
    }

    /** boardType==2 或 native inputMode 英文；比 KeyboardJni.IsEnglishKeyboard 稳。 */
    static boolean isEnglishUiQuiet() {
        if (!READY.get()) {
            return false;
        }
        try {
            return nativeIsEnglishUi();
        } catch (Throwable ignored) {
            return false;
        }
    }

    static int getBoardTypeQuiet() {
        if (!READY.get()) {
            return -1;
        }
        try {
            return nativeGetBoardType();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    static int getInputModeQuiet() {
        if (!READY.get()) {
            return -1;
        }
        try {
            return nativeGetInputMode();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    static boolean isReadyQuiet() {
        return READY.get();
    }

    static void setLoggingEnabledQuiet(boolean enabled) {
        FileLogger.setEnabled(enabled);
        if (!LOADED.get()) {
            return;
        }
        try {
            nativeSetLoggingEnabled(enabled);
        } catch (Throwable ignored) {
        }
    }

    static void loadAndInstall(XposedModuleRef module) {
        TargetControlBridge.install();
        if (READY.get()) {
            module.i("NativeBridge already ready");
            TargetControlBridge.reportStatus();
            return;
        }
        try {
            if (!LOADED.get()) {
                if (!ensureNativeLibs(module)) {
                    module.i("NativeBridge defer: libs not loaded yet");
                    return;
                }
                LOADED.set(true);
            }
            nativeSetLoggingEnabled(FileLogger.isEnabled());
            int rc = nativeInstall();
            boolean ready = nativeIsReady();
            module.i("nativeInstall rc=" + rc + " ready=" + ready);
            if (ready) {
                READY.set(true);
                TargetControlBridge.reportStatus();
            } else {
                // 允许 keyboard 再次触发时重试 install（SO 已 load）
                module.i("native not ready, will retry install on next trigger");
            }
        } catch (UnsatisfiedLinkError ule) {
            module.e("JNI missing after load, reset LOADED", ule);
            LOADED.set(false);
            READY.set(false);
        } catch (Throwable t) {
            module.e("NativeBridge.loadAndInstall failed", t);
            LOADED.set(false);
            READY.set(false);
        }
    }

    private static boolean ensureNativeLibs(XposedModuleRef module) throws Exception {
        Context ctx = currentApplication();
        if (ctx == null) {
            module.i("no Application yet");
            return false;
        }
        // 确认 keyboard 已在进程里（maps / 类已初始化即可）
        try {
            Class.forName("com.bytedance.android.doubaoime.KeyboardJni", false, ctx.getClassLoader());
        } catch (ClassNotFoundException e) {
            module.i("KeyboardJni missing");
            return false;
        }

        // 版本目录强制换新 SO，避免 code_cache 复用旧 native
        File dir = new File(ctx.getCodeCacheDir(), "noensuggest_native_v070");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("cannot mkdir " + dir);
        }

        String apk = findModuleApkPath();
        module.i("module apk=" + apk + " extractDir=" + dir.getAbsolutePath());
        if (apk == null) {
            throw new IllegalStateException("module apk path not found");
        }

        File cxx = extractSo(apk, dir, "libc++_shared.so", false);
        File sh = extractSo(apk, dir, "libshadowhook.so", false);
        File ns = extractSo(apk, dir, "libnoensuggest.so", true);

        // 用 NativeBridge 所在 ClassLoader 加载，保证 JNI 能绑到 nativeInstall
        System.load(cxx.getAbsolutePath());
        System.load(sh.getAbsolutePath());
        System.load(ns.getAbsolutePath());
        module.i("System.load ok (module CL): "
                + cxx.getName() + ", " + sh.getName() + ", " + ns.getName());
        return true;
    }

    private static File extractSo(String apkPath, File dir, String soName, boolean force)
            throws Exception {
        String abi = android.os.Build.SUPPORTED_ABIS[0];
        String[] candidates = new String[]{
                "lib/" + abi + "/" + soName,
                "lib/arm64-v8a/" + soName,
        };
        try (ZipFile zip = new ZipFile(apkPath)) {
            ZipEntry entry = null;
            String used = null;
            for (String c : candidates) {
                entry = zip.getEntry(c);
                if (entry != null) {
                    used = c;
                    break;
                }
            }
            if (entry == null) {
                throw new IllegalStateException("SO not in apk: " + soName);
            }
            File out = new File(dir, soName);
            if (!force && out.exists() && out.length() == entry.getSize() && out.canRead()) {
                return out;
            }
            if (out.exists()) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
            }
            try (InputStream in = zip.getInputStream(entry);
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                }
            }
            //noinspection ResultOfMethodCallIgnored
            out.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            out.setExecutable(true, false);
            FileLogger.iRaw("extracted " + used + " -> " + out.getAbsolutePath()
                    + " size=" + out.length());
            return out;
        }
    }

    private static String findModuleApkPath() {
        try {
            Enumeration<java.net.URL> urls =
                    NativeBridge.class.getClassLoader().getResources("META-INF/xposed/module.prop");
            while (urls.hasMoreElements()) {
                String u = urls.nextElement().toString();
                if (u.startsWith("jar:file:") && u.contains("noensuggest")) {
                    int a = "jar:file:".length();
                    int b = u.indexOf('!');
                    if (b > a) {
                        return u.substring(a, b);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            String s = String.valueOf(NativeBridge.class.getClassLoader());
            int zip = s.indexOf("zip file \"");
            while (zip >= 0) {
                int start = zip + "zip file \"".length();
                int end = s.indexOf('"', start);
                if (end > start) {
                    String path = s.substring(start, end);
                    if (path.contains("noensuggest") && path.endsWith(".apk")) {
                        return path;
                    }
                }
                zip = s.indexOf("zip file \"", end + 1);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    static Context currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Application) {
                return (Application) app;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    interface XposedModuleRef {
        void i(String msg);

        void e(String msg, Throwable t);
    }
}
