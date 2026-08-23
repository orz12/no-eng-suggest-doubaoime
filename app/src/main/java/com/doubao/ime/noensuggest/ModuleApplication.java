package com.doubao.ime.noensuggest;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/** 通过 libxposed 官方服务获取框架激活、作用域和运行目标状态。 */
public final class ModuleApplication extends Application
        implements XposedServiceHelper.OnServiceListener {
    public interface StateListener {
        void onFrameworkStateChanged();
    }

    private static final Set<StateListener> LISTENERS = new CopyOnWriteArraySet<>();
    private static volatile XposedService sService;
    private static volatile ModuleApplication sInstance;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        XposedServiceHelper.registerListener(this);
    }

    @Override
    public void onServiceBind(XposedService service) {
        sService = service;
        notifyListeners();
    }

    @Override
    public void onServiceDied(XposedService service) {
        if (sService == service) {
            sService = null;
            notifyListeners();
        }
    }

    public static XposedService getXposedService() {
        return sService;
    }

    /**
     * 主动调用当前 binder 的公开接口以验证连接是否仍然可用。
     * XposedServiceHelper 官方只允许注册一次 listener，没有额外的 rebind API；
     * binder 尚未到达时保留 listener 等待后续 onServiceBind。
     */
    public static void recheckXposedService() {
        ModuleApplication app = sInstance;
        if (app == null) {
            return;
        }
        XposedService service = sService;
        new Thread(() -> {
            if (service != null) {
                try {
                    service.getApiVersion();
                    service.getFrameworkName();
                    service.getFrameworkVersion();
                    service.getScope();
                } catch (Throwable ignored) {
                    if (sService == service) {
                        sService = null;
                    }
                }
            }
            app.notifyListeners();
        }, "xposed-service-recheck").start();
    }

    public static void addStateListener(StateListener listener) {
        LISTENERS.add(listener);
    }

    public static void removeStateListener(StateListener listener) {
        LISTENERS.remove(listener);
    }

    private void notifyListeners() {
        mainHandler.post(() -> {
            for (StateListener listener : LISTENERS) {
                listener.onFrameworkStateChanged();
            }
        });
    }
}
