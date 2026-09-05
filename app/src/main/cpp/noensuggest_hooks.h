#pragma once

#include <cstdint>

#ifdef __cplusplus
extern "C" {
#endif

/** 安装 ShadowHook；返回 0 成功。可重复调用（幂等）。 */
int noensuggest_install_hooks(void);

/** 当前是否已安装成功 */
int noensuggest_is_ready(void);

/** 开关 native logcat 与文件日志；默认关闭。 */
void noensuggest_set_logging_enabled(int enabled);

/** 英文下丢弃 native 预编辑（FinishPreedit discard；勿 ClearInput）。 */
void noensuggest_clear_english_typing_buffer(void);

/** startInputView 后置 true；InitWindow 阶段保持 false，避免 discard NPE。 */
void noensuggest_mark_input_ready(int ready);

/** 强制/取消 InputBoxScreenModel::SetPasswordBox，用于密码框旁路探测。 */
void noensuggest_force_password_box(int enable);

/**
 * 当前是否为英文键盘 UI（board/mode），不含翻译面板旁路。
 * 以 WindowBoardView::GetBoardType==2 或 GetInputMode 英文为准（比 Java IsEnglishKeyboard 稳）。
 */
int noensuggest_is_english_ui(void);

/**
 * 翻译面板是否激活（TranslateModel activation 或 InputBoxTranslate 启用，
 * 或正处于 DelayRefreshResponse 调用栈）。
 */
int noensuggest_is_translate_active(void);

/**
 * 是否应施加英文直上屏按键路径（含翻译面板内）。
 * 翻译场景下仍返回 1，避免上滑/长按先留下按下字母；
 * 吞 UpdatePreedit / Clear 另由翻译闸门旁路，避免死锁。
 */
int noensuggest_should_apply_english_direct(void);

/** 诊断：当前 GetBoardType / GetInputMode。 */
int noensuggest_get_board_type(void);
int noensuggest_get_input_mode(void);



/** Native Hook 安装统计（成功 / 总数 / 失败数）。 */
int noensuggest_hook_ok_count(void);
int noensuggest_hook_total_count(void);
int noensuggest_hook_fail_count(void);

/** 失败项短名，逗号分隔；无失败时返回空串。 */
const char *noensuggest_hook_fail_names(void);
const char *noensuggest_hook_skip_names(void);
const char *noensuggest_hook_status_map(void);

/** 已解析的 keyboard_behavior 字段偏移；失败为 0。 */
size_t noensuggest_behavior_offset(void);

#ifdef __cplusplus
}
#endif
