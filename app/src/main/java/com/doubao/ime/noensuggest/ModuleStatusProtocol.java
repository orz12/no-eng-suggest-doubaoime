package com.doubao.ime.noensuggest;

import android.content.Intent;

/** 模块控制页与被注入豆包输入法进程之间的显式广播协议。 */
public final class ModuleStatusProtocol {
    public static final String MODULE_PACKAGE = "com.doubao.ime.noensuggest";
    public static final String TARGET_PACKAGE = ModuleMain.TARGET_PACKAGE;

    public static final String ACTION_QUERY =
            MODULE_PACKAGE + ".action.QUERY_TARGET";
    public static final String ACTION_REPORT =
            MODULE_PACKAGE + ".action.TARGET_REPORT";
    public static final String ACTION_RESTART =
            MODULE_PACKAGE + ".action.RESTART_TARGET";
    public static final String ACTION_GET_LOGS =
            MODULE_PACKAGE + ".action.GET_LOGS";
    public static final String ACTION_CLEAR_LOGS =
            MODULE_PACKAGE + ".action.CLEAR_LOGS";
    public static final String ACTION_SET_LOGGING =
            MODULE_PACKAGE + ".action.SET_LOGGING";
    public static final String ACTION_LOGS_RESULT =
            MODULE_PACKAGE + ".action.LOGS_RESULT";

    public static final String EXTRA_TOKEN = "protocol_token";
    public static final String EXTRA_TARGET_PACKAGE = "target_package";
    public static final String EXTRA_JAVA_READY = "java_ready";
    public static final String EXTRA_NATIVE_READY = "native_ready";
    public static final String EXTRA_PID = "pid";
    public static final String EXTRA_REPORTED_AT = "reported_at";
    public static final String EXTRA_LOG_PATH = "log_path";
    public static final String EXTRA_LOG_TEXT = "log_text";
    public static final String EXTRA_LOG_CLEARED = "log_cleared";
    public static final String EXTRA_LOG_ENABLED = "log_enabled";
    public static final String EXTRA_LOG_OFFSET = "log_offset";
    public static final String EXTRA_LOG_NEXT_OFFSET = "log_next_offset";
    public static final String EXTRA_LOG_HAS_MORE = "log_has_more";
    public static final String EXTRA_LOG_REQUEST_ID = "log_request_id";
    public static final String EXTRA_HOOK_BUILD = "hook_build";
    /** 进程内实时键盘/干预摘要，多行文本。 */
    public static final String EXTRA_RUNTIME_DETAIL = "runtime_detail";
    public static final String EXTRA_HOOK_OK = "hook_ok";
    public static final String EXTRA_HOOK_TOTAL = "hook_total";
    public static final String EXTRA_HOOK_FAIL = "hook_fail";
    public static final String EXTRA_HOOK_FAIL_NAMES = "hook_fail_names";
    public static final String EXTRA_BEHAVIOR_OFFSET = "behavior_offset";
    public static final String EXTRA_HOOK_SKIP_NAMES = "hook_skip_names";
    public static final String EXTRA_HOOK_STATUS_MAP = "hook_status_map";

    private static final String TOKEN = "doubao-no-en-suggest-v1";

    private ModuleStatusProtocol() {
    }

    public static Intent command(String action) {
        return new Intent(action)
                .setPackage(TARGET_PACKAGE)
                .putExtra(EXTRA_TOKEN, TOKEN);
    }

    public static Intent response(String action) {
        return new Intent(action)
                .setPackage(MODULE_PACKAGE)
                .putExtra(EXTRA_TOKEN, TOKEN)
                .putExtra(EXTRA_TARGET_PACKAGE, TARGET_PACKAGE);
    }

    public static boolean isTrusted(Intent intent) {
        return intent != null && TOKEN.equals(intent.getStringExtra(EXTRA_TOKEN));
    }
}
