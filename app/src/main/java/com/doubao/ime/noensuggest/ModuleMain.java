package com.doubao.ime.noensuggest;

import android.util.Log;

import io.github.libxposed.api.XposedModule;

/**
 * LSPosed 模块入口（libxposed API 102）。
 * Phase 2：英文单字母直上屏（ShadowHook + libkeyboard 符号 hook）。
 */
public class ModuleMain extends XposedModule {
    public static final String TAG = "DoubaoNoEnSuggest";
    public static final String TARGET_PACKAGE = "com.bytedance.android.doubaoime";
    private static volatile String sProcessName = "";

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        sProcessName = param.getProcessName();
        log(Log.INFO, TAG, "onModuleLoaded process=" + param.getProcessName()
                + " framework=" + getFrameworkName()
                + " api=" + getApiVersion()
                + " phase=2-native");
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        if (!TARGET_PACKAGE.equals(sProcessName)) {
            return;
        }
        FileLogger.init(this);
        TargetControlBridge.installEventually();
        FileLogger.i(this, "target package loaded: " + param.getPackageName()
                + " first=" + param.isFirstPackage()
                + " cl=" + param.getDefaultClassLoader()
                + " file=" + FileLogger.currentPath());
        if (!param.isFirstPackage()) {
            return;
        }
        try {
            ProbeHooks.install(this, param.getDefaultClassLoader());
            TargetControlBridge.reportStatus();
        } catch (Throwable t) {
            FileLogger.e(this, "ProbeHooks.install failed", t);
        }
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        if (!TARGET_PACKAGE.equals(sProcessName)) {
            return;
        }
        FileLogger.init(this);
        TargetControlBridge.installEventually();
        FileLogger.i(this, "target package ready: " + param.getPackageName()
                + " cl=" + param.getClassLoader()
                + " file=" + FileLogger.currentPath());
        try {
            ProbeHooks.install(this, param.getClassLoader());
            ProbeHooks.tryInstallNative(this);
            TargetControlBridge.reportStatus();
        } catch (Throwable t) {
            FileLogger.e(this, "ProbeHooks on ready failed", t);
        }
    }
}
