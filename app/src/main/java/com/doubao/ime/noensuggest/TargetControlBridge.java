package com.doubao.ime.noensuggest;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import java.util.concurrent.atomic.AtomicBoolean;

/** 注册在豆包输入法进程内，向模块控制页提供状态、日志和重启能力。 */
final class TargetControlBridge {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean RETRYING = new AtomicBoolean(false);
    private static volatile Context sContext;

    private TargetControlBridge() {
    }

    static void installEventually() {
        install();
        if (INSTALLED.get() || !RETRYING.compareAndSet(false, true)) {
            return;
        }
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            private int attempts;

            @Override
            public void run() {
                install();
                if (INSTALLED.get() || ++attempts >= 24) {
                    RETRYING.set(false);
                    return;
                }
                handler.postDelayed(this, 250L);
            }
        }, 250L);
    }

    static void install() {
        if (INSTALLED.get()) {
            return;
        }
        Context context = NativeBridge.currentApplication();
        if (context == null || !ModuleMain.TARGET_PACKAGE.equals(context.getPackageName())) {
            return;
        }
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        sContext = context.getApplicationContext();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ModuleStatusProtocol.ACTION_QUERY);
        filter.addAction(ModuleStatusProtocol.ACTION_RESTART);
        filter.addAction(ModuleStatusProtocol.ACTION_GET_LOGS);
        filter.addAction(ModuleStatusProtocol.ACTION_CLEAR_LOGS);
        filter.addAction(ModuleStatusProtocol.ACTION_SET_LOGGING);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                sContext.registerReceiver(RECEIVER, filter, Context.RECEIVER_EXPORTED);
            } else {
                //noinspection UnspecifiedRegisterReceiverFlag
                sContext.registerReceiver(RECEIVER, filter);
            }
            FileLogger.iRaw("TargetControlBridge registered");
            reportStatus();
        } catch (Throwable t) {
            INSTALLED.set(false);
            FileLogger.iRaw("TargetControlBridge register failed: " + t);
        }
    }

    static void reportStatus() {
        Context context = sContext;
        if (context == null) {
            return;
        }
        Intent report = ModuleStatusProtocol.response(ModuleStatusProtocol.ACTION_REPORT)
                .putExtra(ModuleStatusProtocol.EXTRA_JAVA_READY, ProbeHooks.isInstalled())
                .putExtra(ModuleStatusProtocol.EXTRA_NATIVE_READY, NativeBridge.isReadyQuiet())
                .putExtra(ModuleStatusProtocol.EXTRA_PID, Process.myPid())
                .putExtra(ModuleStatusProtocol.EXTRA_REPORTED_AT, System.currentTimeMillis())
                .putExtra(ModuleStatusProtocol.EXTRA_LOG_PATH, FileLogger.currentPath())
                .putExtra(ModuleStatusProtocol.EXTRA_LOG_ENABLED, FileLogger.isEnabled())
                .putExtra(ModuleStatusProtocol.EXTRA_HOOK_BUILD, "v27 / 0.7.5");
        context.sendBroadcast(report);
    }

    private static void sendLogs(long offset, int requestId, boolean cleared) {
        Context context = sContext;
        if (context == null) {
            return;
        }
        FileLogger.LogChunk chunk = FileLogger.readChunk(offset, 16 * 1024);
        Intent result = ModuleStatusProtocol.response(ModuleStatusProtocol.ACTION_LOGS_RESULT)
                .putExtra(ModuleStatusProtocol.EXTRA_LOG_TEXT, chunk.text)
                .putExtra(ModuleStatusProtocol.EXTRA_LOG_PATH, FileLogger.currentPath())
                .putExtra(ModuleStatusProtocol.EXTRA_LOG_CLEARED, cleared)
                .putExtra(ModuleStatusProtocol.EXTRA_LOG_ENABLED, FileLogger.isEnabled())
                .putExtra(ModuleStatusProtocol.EXTRA_LOG_NEXT_OFFSET, chunk.nextOffset)
                .putExtra(ModuleStatusProtocol.EXTRA_LOG_HAS_MORE, chunk.hasMore)
                .putExtra(ModuleStatusProtocol.EXTRA_LOG_REQUEST_ID, requestId)
                .putExtra(ModuleStatusProtocol.EXTRA_REPORTED_AT, System.currentTimeMillis());
        context.sendBroadcast(result);
    }

    private static final BroadcastReceiver RECEIVER = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ModuleStatusProtocol.isTrusted(intent)) {
                return;
            }
            String action = intent.getAction();
            if (ModuleStatusProtocol.ACTION_QUERY.equals(action)) {
                reportStatus();
                return;
            }
            if (ModuleStatusProtocol.ACTION_GET_LOGS.equals(action)) {
                long offset = intent.getLongExtra(ModuleStatusProtocol.EXTRA_LOG_OFFSET, 0L);
                int requestId = intent.getIntExtra(
                        ModuleStatusProtocol.EXTRA_LOG_REQUEST_ID, 0);
                new Thread(() -> sendLogs(offset, requestId, false),
                        "noensuggest-log-read").start();
                return;
            }
            if (ModuleStatusProtocol.ACTION_CLEAR_LOGS.equals(action)) {
                int requestId = intent.getIntExtra(
                        ModuleStatusProtocol.EXTRA_LOG_REQUEST_ID, 0);
                new Thread(() -> {
                    FileLogger.clear();
                    sendLogs(0L, requestId, true);
                }, "noensuggest-log-clear").start();
                return;
            }
            if (ModuleStatusProtocol.ACTION_SET_LOGGING.equals(action)) {
                boolean enabled = intent.getBooleanExtra(
                        ModuleStatusProtocol.EXTRA_LOG_ENABLED, false);
                NativeBridge.setLoggingEnabledQuiet(enabled);
                FileLogger.iRaw(enabled ? "logging enabled by module UI" : "logging disabled");
                reportStatus();
                return;
            }
            if (ModuleStatusProtocol.ACTION_RESTART.equals(action)) {
                FileLogger.iRaw("restart requested by module UI");
                reportStatus();
                scheduleProcessWake(context);
                new Handler(Looper.getMainLooper()).postDelayed(
                        () -> Process.killProcess(Process.myPid()), 180L);
            }
        }
    };

    private static void scheduleProcessWake(Context context) {
        try {
            Intent service = new Intent().setComponent(new ComponentName(
                    ModuleMain.TARGET_PACKAGE,
                    ModuleMain.TARGET_PACKAGE + ".ImeService"));
            PendingIntent pending = PendingIntent.getService(
                    context,
                    27015,
                    service,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarm != null) {
                alarm.set(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + 900L,
                        pending);
            }
        } catch (Throwable t) {
            FileLogger.iRaw("schedule process wake failed: " + t);
        }
    }
}
