package com.doubao.ime.noensuggest;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** 持久化豆包输入法进程最近一次成功握手，供控制页冷启动时显示。 */
public final class ModuleStatusReceiver extends BroadcastReceiver {
    public static final String PREFS = "module_status";
    public static final String KEY_LAST_SEEN = "last_seen";
    public static final String KEY_JAVA_READY = "java_ready";
    public static final String KEY_NATIVE_READY = "native_ready";
    public static final String KEY_PID = "pid";
    public static final String KEY_LOG_PATH = "log_path";
    public static final String KEY_HOOK_BUILD = "hook_build";
    public static final String KEY_LOGGING_ENABLED = "ui_logging_enabled_v1";
    public static final String KEY_TARGET_LOGGING_ENABLED = "target_logging_enabled_v1";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ModuleStatusProtocol.ACTION_REPORT.equals(intent.getAction())
                || !ModuleStatusProtocol.isTrusted(intent)
                || !ModuleStatusProtocol.TARGET_PACKAGE.equals(
                intent.getStringExtra(ModuleStatusProtocol.EXTRA_TARGET_PACKAGE))) {
            return;
        }
        long reportedAt = intent.getLongExtra(
                ModuleStatusProtocol.EXTRA_REPORTED_AT, System.currentTimeMillis());
        boolean targetLogging = intent.getBooleanExtra(
                ModuleStatusProtocol.EXTRA_LOG_ENABLED, false);
        SharedPreferences preferences =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor =
                preferences.edit();
        editor.putLong(KEY_LAST_SEEN, reportedAt);
        editor.putBoolean(KEY_JAVA_READY,
                intent.getBooleanExtra(ModuleStatusProtocol.EXTRA_JAVA_READY, false));
        editor.putBoolean(KEY_NATIVE_READY,
                intent.getBooleanExtra(ModuleStatusProtocol.EXTRA_NATIVE_READY, false));
        editor.putInt(KEY_PID, intent.getIntExtra(ModuleStatusProtocol.EXTRA_PID, -1));
        editor.putString(KEY_LOG_PATH,
                intent.getStringExtra(ModuleStatusProtocol.EXTRA_LOG_PATH));
        editor.putString(KEY_HOOK_BUILD,
                intent.getStringExtra(ModuleStatusProtocol.EXTRA_HOOK_BUILD));
        editor.putBoolean(KEY_TARGET_LOGGING_ENABLED, targetLogging);
        editor.apply();
        boolean desiredLogging = preferences.getBoolean(KEY_LOGGING_ENABLED, false);
        if (targetLogging != desiredLogging) {
            context.sendBroadcast(ModuleStatusProtocol.command(
                            ModuleStatusProtocol.ACTION_SET_LOGGING)
                    .putExtra(ModuleStatusProtocol.EXTRA_LOG_ENABLED, desiredLogging));
        }
    }
}
