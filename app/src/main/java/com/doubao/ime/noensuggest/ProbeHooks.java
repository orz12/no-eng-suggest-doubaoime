package com.doubao.ime.noensuggest;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 英文直上屏 + 联想拦截；已关掉 password_box 旁路（会中英态抖动）。
 */
final class ProbeHooks {
    private static final AtomicBoolean LOADLIB_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean JNI_HOOKED = new AtomicBoolean(false);
    private static final AtomicInteger LOAD_KEYBOARD_COUNT = new AtomicInteger();
    private static final AtomicInteger SET_INPUT_MODE_COUNT = new AtomicInteger();
    private static final AtomicInteger UPDATE_PREEDIT_COUNT = new AtomicInteger();
    private static final AtomicInteger CANDIDATE_SNAPSHOT_BLOCK_COUNT = new AtomicInteger();
    private static final AtomicInteger BACKSPACE_TOUCH_COUNT = new AtomicInteger();

    private static volatile XposedModule sModule;
    private static volatile String sLastEngPreedit = "";

    private ProbeHooks() {
    }

    static boolean isInstalled() {
        return JNI_HOOKED.get();
    }

    static void install(XposedModule module, ClassLoader classLoader) {
        sModule = module;
        FileLogger.i(module, "ProbeHooks install (no-password-bypass), cl=" + classLoader);
        hookLoadLibrary(module);
        hookKeyboardJni(module, classLoader);
    }

    private static void hookLoadLibrary(XposedModule module) {
        if (!LOADLIB_HOOKED.compareAndSet(false, true)) {
            return;
        }
        Method target = findLoadLibraryMethod();
        if (target == null) {
            FileLogger.w(module, "loadLibrary method not found; skip library load probe");
            return;
        }
        try {
            module.hook(target).intercept(chain -> {
                String lib = extractLibName(chain);
                Object result = chain.proceed();
                if (lib != null && (lib.contains("keyboard") || "keyboard".equals(lib))) {
                    int n = LOAD_KEYBOARD_COUNT.incrementAndGet();
                    FileLogger.i(module, "loadLibrary hit lib=" + lib + " count=" + n);
                    tryInstallNative(module);
                }
                return result;
            });
            FileLogger.i(module, "hooked " + target);
        } catch (Throwable t) {
            LOADLIB_HOOKED.set(false);
            FileLogger.e(module, "hook loadLibrary failed", t);
        }
    }

    static void tryInstallNative(XposedModule module) {
        final XposedModule m = module != null ? module : sModule;
        if (m == null) {
            return;
        }
        NativeBridge.loadAndInstall(new NativeBridge.XposedModuleRef() {
            @Override
            public void i(String msg) {
                FileLogger.i(m, msg);
            }

            @Override
            public void e(String msg, Throwable t) {
                FileLogger.e(m, msg, t);
            }
        });
    }

    private static Method findLoadLibraryMethod() {
        Method[] candidates = new Method[]{
                tryMethod(Runtime.class, "loadLibrary0", Class.class, String.class),
                tryMethod(Runtime.class, "loadLibrary0", ClassLoader.class, Class.class, String.class),
                tryMethod(Runtime.class, "loadLibrary0", ClassLoader.class, String.class),
                tryMethod(System.class, "loadLibrary", String.class),
        };
        for (Method m : candidates) {
            if (m != null) {
                return m;
            }
        }
        return null;
    }

    private static Method tryMethod(Class<?> clazz, String name, Class<?>... params) {
        try {
            Method m = clazz.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String extractLibName(XposedInterface.Chain chain) {
        List<Object> args = chain.getArgs();
        if (args == null || args.isEmpty()) {
            return null;
        }
        for (int i = args.size() - 1; i >= 0; i--) {
            Object a = args.get(i);
            if (a instanceof String) {
                return (String) a;
            }
        }
        return null;
    }

    private static void hookKeyboardJni(XposedModule module, ClassLoader classLoader) {
        if (JNI_HOOKED.get()) {
            return;
        }
        Class<?> jni;
        try {
            jni = Class.forName("com.bytedance.android.doubaoime.KeyboardJni", false, classLoader);
        } catch (Throwable t) {
            FileLogger.w(module, "KeyboardJni not found yet (will retry): " + t.getMessage());
            return;
        }
        if (!JNI_HOOKED.compareAndSet(false, true)) {
            return;
        }

        // 真正的 Java → InputConnection 最终出口。英文多字符字母数字只可能来自
        // 候选确认或残留词态，单字符直输不受影响。
        hookStatic(module, jni, "DoCommit",
                new Class<?>[]{String.class, int.class, String.class, String.class, String.class},
                chain -> {
                    String text = stringArg(chain, 0);
                    if (isEnglishUi(jni) && isBulkAlnum(text)) {
                        FileLogger.i(module, "block bulk ENG DoCommit len="
                                + text.length() + " text=" + preview(text));
                        return null;
                    }
                    return chain.proceed();
                });

        // 最后一层 composing 出口：英文绝不把 native 词态写进 InputConnection。
        hookStatic(module, jni, "UpdatePreedit", new Class<?>[]{String.class}, chain -> {
            boolean jniEng = isEnglishKeyboard(jni);
            boolean eng = jniEng || NativeBridge.isEnglishUiQuiet();
            String text = stringArg(chain, 0);
            int n = UPDATE_PREEDIT_COUNT.incrementAndGet();
            if (n <= 80 || n % 10 == 0) {
                FileLogger.i(module, "DIAG UpdatePreedit#" + n
                        + " jniEng=" + jniEng
                        + " uiEng=" + eng
                        + " board=" + NativeBridge.getBoardTypeQuiet()
                        + " mode=" + NativeBridge.getInputModeQuiet()
                        + " len=" + (text == null ? -1 : text.length())
                        + " text=" + preview(text)
                        + " last=" + preview(sLastEngPreedit));
            }
            if (!eng) {
                if (text != null && !text.isEmpty() && n <= 80) {
                    FileLogger.i(module, "DIAG UpdatePreedit CN proceed composing");
                }
                sLastEngPreedit = "";
                return chain.proceed();
            }
            if (text == null || text.isEmpty()) {
                // 空串是清 composing 的副作用，必须放行；只吞非空英文词态。
                sLastEngPreedit = "";
                return chain.proceed();
            }
            // unique-mode Native 源头 Hook 应拦住每一次英文按键。这里不再用无事件关联的
            // generation/delta 机制补交字符：该机制会把正文和 Native 词态拆成两套状态，
            // 并可能把下一条回调误认成上一条。若仍看到非空串，只吞掉并记录以便定位。
            sLastEngPreedit = text;
            FileLogger.w(module, "unexpected ENG preedit swallowed after unique hooks len="
                    + text.length() + " text=" + preview(text));
            return null;
        });

        // 上滑退格最终清除正文时会进入 ClearText。记录是否在第一次手势就到达此出口，
        // 并在成功清除后同步撤销 Java 侧的 preedit 镜像，避免后续去重状态残留。
        hookStatic(module, jni, "ClearText", new Class<?>[]{}, chain -> {
            boolean eng = isEnglishUi(jni);
            FileLogger.i(module, "Backspace ClearText BEGIN eng=" + eng
                    + " last=" + preview(sLastEngPreedit));
            Object result = chain.proceed();
            if (eng) {
                sLastEngPreedit = "";
            }
            FileLogger.i(module, "Backspace ClearText END eng=" + eng
                    + " cleared=" + preview(result instanceof String ? (String) result : null));
            return result;
        });

        hookStatic(module, jni, "ClearTextBeforeCursor", new Class<?>[]{}, chain -> {
            boolean eng = isEnglishUi(jni);
            FileLogger.i(module, "Backspace ClearTextBeforeCursor BEGIN eng=" + eng);
            Object result = chain.proceed();
            FileLogger.i(module, "Backspace ClearTextBeforeCursor END eng=" + eng
                    + " cleared=" + preview(result instanceof String ? (String) result : null));
            return result;
        });

        hookStatic(module, jni, "OnBackspaceTouchEvent",
                new Class<?>[]{int.class, int.class, int.class, int.class,
                        int.class, int.class, int.class, int.class}, chain -> {
                    int n = BACKSPACE_TOUCH_COUNT.incrementAndGet();
                    if (isEnglishUi(jni) && (n <= 120 || n % 20 == 0)) {
                        StringBuilder args = new StringBuilder();
                        List<Object> values = chain.getArgs();
                        for (int i = 0; values != null && i < values.size(); i++) {
                            if (i > 0) {
                                args.append(',');
                            }
                            args.append(values.get(i));
                        }
                        FileLogger.i(module, "Backspace touch#" + n + " args=" + args);
                    }
                    return chain.proceed();
                });

        hookStatic(module, jni, "UpdateCompStr",
                new Class<?>[]{String.class, String.class}, chain -> {
                    if (isEnglishUi(jni)) {
                        return "";
                    }
                    return chain.proceed();
                });

        hookStatic(module, jni, "NotifyUpdateAssociations", new Class<?>[]{}, chain -> {
            if (isEnglishUi(jni)) {
                return null;
            }
            return chain.proceed();
        });

        // 候选重构后的真正 Android UI 出口。CandidateToolbarCenter 的若干刷新入口
        // 并不覆盖云候选/延迟刷新，而这两个 snapshot 方法覆盖最终显示。
        hookAllNamed(module, jni, "notifyCandidateBarSnapshot", chain -> {
            if (isEnglishUi(jni)) {
                logCandidateSnapshotBlock(module, "candidate-bar");
                return null;
            }
            return chain.proceed();
        });
        hookAllNamed(module, jni, "notifyMoreCandidateSnapshot", chain -> {
            if (isEnglishUi(jni)) {
                logCandidateSnapshotBlock(module, "more-candidate");
                return null;
            }
            return chain.proceed();
        });
        hookStatic(module, jni, "ShouldShowCandidateInfo", new Class<?>[]{}, chain -> {
            if (isEnglishUi(jni)) {
                return false;
            }
            return chain.proceed();
        });

        // 光标移动/删除会从 Java 重新向 native 发起一条 selection association，
        // 它不经过普通按键 Associate。保留 selection 同步，只关闭其中的联想与纠错字段。
        try {
            Class<?> params = Class.forName(
                    "com.bytedance.android.doubaoime.KeyboardJni$SelectionUpdatedParams",
                    false, classLoader);
            hookInstance(module, jni, "onSelectionUpdated", new Class<?>[]{params}, chain -> {
                if (isEnglishUi(jni)) {
                    Object p = chain.getArg(0);
                    setFieldQuiet(p, "need_association", false);
                    setFieldQuiet(p, "enable_correct", false);
                    setFieldQuiet(p, "has_preedit", false);
                    setFieldQuiet(p, "pre_edit_text", "");
                    setFieldQuiet(p, "is_cursor_change_tag_for_association_disabled", true);
                    FileLogger.i(module, "selection ENG force no-association/no-correction");
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            FileLogger.e(module, "hook onSelectionUpdated failed", t);
        }

        hookStatic(module, jni, "finishPreedit", new Class<?>[]{boolean.class}, chain -> {
            if (isEnglishUi(jni) && boolArg(chain, 0)) {
                try {
                    List<Object> args = chain.getArgs();
                    if (args != null && !args.isEmpty()) {
                        args.set(0, Boolean.FALSE);
                    }
                } catch (Throwable ignored) {
                }
                sLastEngPreedit = "";
                FileLogger.i(module, "finishPreedit ENG force discard");
            }
            try {
                return chain.proceed();
            } catch (Throwable t) {
                // InitWindow 阶段 InputView 可能尚未就绪
                FileLogger.w(module, "finishPreedit proceed failed: " + t.getMessage());
                return null;
            }
        });

        hookInstance(module, jni, "setInputMode", new Class<?>[]{int.class}, chain -> {
            int mode = intArg(chain, 0);
            int n = SET_INPUT_MODE_COUNT.incrementAndGet();
            FileLogger.i(module, "DIAG Java setInputMode#" + n + " mode=" + mode
                    + " beforeIsEng=" + isEnglishKeyboard(jni));
            Object r = chain.proceed();
            FileLogger.i(module, "DIAG Java setInputMode#" + n + " afterIsEng="
                    + isEnglishKeyboard(jni));
            return r;
        });

        hookAllNamed(module, jni, "onFinishInputView", chain -> {
            NativeBridge.markInputReadyQuiet(false);
            return chain.proceed();
        });
        hookAllNamed(module, jni, "onFinishInput", chain -> {
            NativeBridge.markInputReadyQuiet(false);
            return chain.proceed();
        });
        hookInstance(module, jni, "finishInputView", new Class<?>[]{}, chain -> {
            NativeBridge.markInputReadyQuiet(false);
            return chain.proceed();
        });

        hookInstance(module, jni, "IsEnglishKeyboard", new Class<?>[]{}, chain -> {
            Object r = chain.proceed();
            boolean english = r instanceof Boolean && (Boolean) r;
            boolean uiEng = english || NativeBridge.isEnglishUiQuiet();
            int n = SET_INPUT_MODE_COUNT.get();
            if (n <= 5 || (!english && uiEng)) {
                FileLogger.i(module, "DIAG IsEnglishKeyboard -> " + r
                        + " uiEng=" + uiEng
                        + " board=" + NativeBridge.getBoardTypeQuiet());
            }
            return r;
        });

        hookInstance(module, jni, "getAssociations", new Class<?>[]{}, chain -> {
            if (isEnglishUi(jni)) {
                return new String[0];
            }
            return chain.proceed();
        });

        hookInstance(module, jni, "doAssociations", new Class<?>[]{String.class}, chain -> {
            if (isEnglishUi(jni)) {
                return new String[0];
            }
            return chain.proceed();
        });

        hookInstance(module, jni, "isAssociate", new Class<?>[]{}, chain -> {
            if (isEnglishUi(jni)) {
                return false;
            }
            return chain.proceed();
        });

        hookInstance(module, jni, "commitString",
                new Class<?>[]{String.class, boolean.class, String.class}, chain -> {
                    String text = stringArg(chain, 0);
                    if (isEnglishUi(jni) && isBulkAlnum(text)) {
                        FileLogger.i(module, "commitString ENG block bulk len="
                                + text.length() + " text=" + preview(text));
                        return null;
                    }
                    return chain.proceed();
                });

        try {
            Method siv = jni.getDeclaredMethod("startInputView",
                    Class.forName("android.view.inputmethod.EditorInfo", false, classLoader),
                    boolean.class);
            module.hook(siv).intercept(chain -> {
                Boolean realPwd = checkRealPassword(jni, classLoader, chain.getArg(0));
                FileLogger.i(module, "startInputView realPassword=" + realPwd
                        + " eng=" + isEnglishKeyboard(jni)
                        + " board=" + NativeBridge.getBoardTypeQuiet());
                Object r = chain.proceed();
                NativeBridge.markInputReadyQuiet(true);
                TargetControlBridge.install();
                TargetControlBridge.reportStatus();
                return r;
            });
            FileLogger.i(module, "hooked KeyboardJni.startInputView");
        } catch (Throwable t) {
            FileLogger.e(module, "hook startInputView failed", t);
        }

        FileLogger.i(module, "KeyboardJni hooks installed (no password bypass)");
        tryInstallNative(module);
    }

    /**
     * 已废弃：强制 password_box 会导致中英态抖动。联想改由 native hook 拦截。
     */
    @SuppressWarnings("unused")
    private static void applyPasswordPolicy(XposedModule module, Class<?> jni,
                                           ClassLoader classLoader, boolean english) {
        // no-op
    }

    private static void setPasswordFlag(Class<?> jni, boolean value) throws Exception {
        Field f = jni.getDeclaredField("mCurrentEditboxIsPasswordType");
        f.setAccessible(true);
        f.set(null, value);
    }

    private static Boolean readPasswordFlag(Class<?> jni) {
        try {
            Field f = jni.getDeclaredField("mCurrentEditboxIsPasswordType");
            f.setAccessible(true);
            Object v = f.get(null);
            return (v instanceof Boolean) ? (Boolean) v : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Boolean checkRealPassword(Class<?> jni, ClassLoader cl, Object editorInfo) {
        try {
            Method get = jni.getDeclaredMethod("getKeyboardJni");
            Object inst = get.invoke(null);
            Method cp = jni.getDeclaredMethod("checkPassword",
                    Class.forName("android.view.inputmethod.EditorInfo", false, cl));
            cp.setAccessible(true);
            Object v = cp.invoke(inst, editorInfo);
            return (v instanceof Boolean) ? (Boolean) v : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Boolean checkRealPasswordFromService(Class<?> jni, ClassLoader cl) {
        try {
            Field ime = jni.getDeclaredField("mImeService");
            ime.setAccessible(true);
            Object service = ime.get(null);
            if (service == null) {
                return false;
            }
            Method getInfo = service.getClass().getMethod("getCurrentInputEditorInfo");
            Object editor = getInfo.invoke(service);
            Boolean r = checkRealPassword(jni, cl, editor);
            return r != null ? r : Boolean.FALSE;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isEnglishKeyboard(Class<?> jni) {
        try {
            Method get = jni.getDeclaredMethod("getKeyboardJni");
            Object inst = get.invoke(null);
            Method isEng = jni.getDeclaredMethod("IsEnglishKeyboard");
            Object r = isEng.invoke(inst);
            return (r instanceof Boolean) && (Boolean) r;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Java IsEnglishKeyboard 或不可靠时，用 native boardType/mode 兜底。 */
    private static boolean isEnglishUi(Class<?> jni) {
        boolean jniEng = isEnglishKeyboard(jni);
        boolean nativeEng = NativeBridge.isEnglishUiQuiet();
        return jniEng || nativeEng;
    }

    private static boolean isBulkAlnum(String text) {
        if (text == null || text.length() <= 1) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetterOrDigit(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static void hookStatic(XposedModule module, Class<?> clazz, String name,
                                   Class<?>[] params, XposedInterface.Hooker hooker) {
        try {
            Method m = clazz.getDeclaredMethod(name, params);
            module.hook(m).intercept(hooker);
            FileLogger.i(module, "hooked static " + clazz.getSimpleName() + "." + name);
        } catch (NoSuchMethodException e) {
            FileLogger.w(module, "missing static " + name + ": " + e.getMessage());
        } catch (Throwable t) {
            FileLogger.e(module, "hook static " + name + " failed", t);
        }
    }

    private static void hookInstance(XposedModule module, Class<?> clazz, String name,
                                     Class<?>[] params, XposedInterface.Hooker hooker) {
        try {
            Method m = clazz.getDeclaredMethod(name, params);
            module.hook(m).intercept(hooker);
            FileLogger.i(module, "hooked " + clazz.getSimpleName() + "." + name);
        } catch (NoSuchMethodException e) {
            FileLogger.w(module, "missing " + name + ": " + e.getMessage());
        } catch (Throwable t) {
            FileLogger.e(module, "hook " + name + " failed", t);
        }
    }

    private static void hookAllNamed(XposedModule module, Class<?> clazz, String name,
                                     XposedInterface.Hooker hooker) {
        int hooked = 0;
        for (Method method : clazz.getDeclaredMethods()) {
            if (!name.equals(method.getName())) {
                continue;
            }
            try {
                method.setAccessible(true);
                module.hook(method).intercept(hooker);
                hooked++;
            } catch (Throwable t) {
                FileLogger.e(module, "hook named " + method + " failed", t);
            }
        }
        if (hooked == 0) {
            FileLogger.w(module, "missing named method " + name);
        } else {
            FileLogger.i(module, "hooked named " + clazz.getSimpleName() + "." + name
                    + " count=" + hooked);
        }
    }

    private static void setFieldQuiet(Object target, String name, Object value) {
        if (target == null) {
            return;
        }
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Throwable ignored) {
        }
    }

    private static void logCandidateSnapshotBlock(XposedModule module, String source) {
        int n = CANDIDATE_SNAPSHOT_BLOCK_COUNT.incrementAndGet();
        if (n <= 80 || n % 20 == 0) {
            FileLogger.i(module, "block ENG " + source + " snapshot #" + n);
        }
    }

    private static String stringArg(XposedInterface.Chain chain, int index) {
        Object a = chain.getArg(index);
        return a instanceof String ? (String) a : null;
    }

    private static int intArg(XposedInterface.Chain chain, int index) {
        Object a = chain.getArg(index);
        return a instanceof Integer ? (Integer) a : Integer.MIN_VALUE;
    }

    private static boolean boolArg(XposedInterface.Chain chain, int index) {
        Object a = chain.getArg(index);
        return a instanceof Boolean && (Boolean) a;
    }

    private static String preview(String text) {
        if (text == null) {
            return "null";
        }
        String flat = text.replace('\n', ' ');
        if (flat.length() <= 24) {
            return flat;
        }
        return flat.substring(0, 24) + "…";
    }
}
