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
 * 当前是否应走英文直上屏逻辑。
 * 以 WindowBoardView::GetBoardType==2 或 GetInputMode 英文为准（比 Java IsEnglishKeyboard 稳）。
 */
int noensuggest_is_english_ui(void);

/** 诊断：当前 GetBoardType / GetInputMode。 */
int noensuggest_get_board_type(void);
int noensuggest_get_input_mode(void);

#ifdef __cplusplus
}
#endif
