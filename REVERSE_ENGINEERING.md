# 英文隐藏预编辑问题：逆向结论与排查记录

本文固化豆包输入法 1.4.1 中“英文字符已经上屏，但退格仍优先删除不可见
composition”的排查过程。后续修改 Native Hook 时必须保留这些结论，避免再次把
UI 隐藏、回调拦截或事后救援误当成源头修复。

## 最终根因

模块曾以 `SHADOWHOOK_MODE_SHARED` 安装所有 Native Hook，但代理函数：

1. 没有调用 `SHADOWHOOK_STACK_SCOPE()` 或 `SHADOWHOOK_POP_STACK()`；
2. 直接通过 `orig_*` trampoline 调用原函数，没有使用 `SHADOWHOOK_CALL_PREV()`。

ShadowHook 官方文档明确说明：shared 模式缺少代理栈清理时，同一 Hook 的代理通常
只执行一次。当前线程上遗留的 proxy call state 会让后续调用被判定为循环重入，
ShadowHook 随后直接调用真实原函数。

这与实机日志完全一致：`ButtonChar::CommitInput`、英文字符抬键、
`InputModel::Impl::Input` 和 `KeyboardCallbackImpl::UpdatePreedit` 等代理大多只出现
第一次命中，随后同线程按键不再经过代理，却持续产生 Java `UpdatePreedit`。

本模块对每个目标地址只安装一个代理，且现有代码统一通过 `orig_*` 调用原函数，
因此选择 `SHADOWHOOK_MODE_UNIQUE`。这与现有调用模型匹配，也避免几十个代理中任意
一个遗漏 shared-mode 栈清理。

官方依据：
[ShadowHook Manual - Proxy functions in shared mode](https://github.com/bytedance/android-inline-hook/blob/main/doc/manual.md#proxy-functions-in-shared-mode)

## 已确认的真实输入链

普通字符在按下阶段进入输入引擎，而不是等到抬键：

```text
ButtonChar::OnButtonDown                 0x2b0730
  -> ButtonChar::CommitInput             0x2b0408
  -> BoardController::CommitKeycode      0x222580
  -> InputModel::CommitKeycode           0x2400a0
  -> InputModel::Impl::CommitKeycode     0x2525a8
  -> InputModel::Impl::Input             0x2531c8
  -> shell 虚接口 +0x40（OIME Input）
```

OIME 返回预编辑的链路：

```text
OIME Input
  -> KeyboardCallbackImpl 虚表 +0x58
  -> KeyboardCallbackImpl::UpdatePreedit 0x2715b8
  -> KeyboardJni.UpdatePreedit
  -> Android InputConnection composing
```

英文字符抬键的字符串提交链：

```text
ButtonEnglishChar::OnButtonUp            0x2c976c
  -> BoardController::CommitString       0x222474
  -> InputModel::CommitString            0x23fbe4
  -> InputModel::Impl::CommitString      0x24e4c0
  -> DoCommitStringToInputBox
```

这条字符串链仍经过 `InputModel`，但不经过 `InputModel::Impl::Input` 的 OIME 按键输入
接口。不能把“经过 InputModel”直接等同于“重新进入 OIME 组词”。

清理链：

```text
InputModel::Clear                        0x23fb94
  -> InputModel::Impl::Clear             0x24c6e8
  -> shell 虚接口 +0x60（OIME Clear）
```

因此 `InputModel::Clear` 确实会清理 OIME，而不只是清除候选 UI。旧版本的问题是下一
次按键因 ShadowHook shared 栈未释放而绕过代理，又重新进入了 OIME。

## 日志证据

一次典型复现中：

1. 切到英文后，只有首键记录了 `ButtonChar::CommitInput`、英文字符抬键和直接提交。
2. 随后的字符没有对应源头 Hook 日志，却出现了累计 `te`、`s't`、`t'T`、`t'Tt`、
   `G'h` 等 `UpdatePreedit`。
3. 大写、Shift 大写、长按大写和普通小写都进入同一残留 OIME 状态机。大写只是让
   问题更明显，并不存在一个独立的“大写 compose 引擎”。
4. 连续退格时，预编辑串从 `GhG` 缩短到 `Gh`、`G`、空串，之后才开始删除编辑器
   正文，证明不可见 composition 确实仍存在。
5. 每类 Native Hook 通常只打印 `#1`，与 ShadowHook 官方描述的“代理只执行一次”
   完全对应。

## 被排除或修正的假设

### 仅隐藏候选和下划线即可

错误。候选 UI 和 `setComposingText` 可以被隐藏，但 OIME 仍可能持有完整词态。退格、
空格和切换语言仍会读取它。

### 大写或长按存在独立根因

错误。小写同样形成累计 preedit。Shift、长按和符号路径只是不同载荷或放大器。

### `InputModel::Clear` 没有清到底层引擎

错误。汇编确认它调用 OIME Clear 虚接口。清理后再次出现 composition，是后续按键
绕过 Hook 重新进入 OIME，而不是 Clear 只清了界面。

### 虚表调用天然绕过函数入口 Hook

错误。虚表最终仍指向同一函数体。`KeyboardCallbackImpl::UpdatePreedit` 首次命中
已证明函数入口可被 Hook。

### 依靠 Java preedit 差值救援可以长期兜底

不可靠。Native 救援计数只是全局 generation，不是某条 preedit 的事件 ID。实机中
Native 救援 `te` 后，Java 把下一条 `s` 误判为已处理回调，随后又把 `s't` 当成完整
增量提交。该方案会让编辑器正文和 Native 词态分叉，并可能重复上屏。

从 0.9.13 起，Java 层只吞掉并记录异常的非空英文 preedit，不再按 generation 或
累计文本差值补交字符。实际字符必须在 Native 源头直提。

## 0.9.13 修复后暴露的过度拦截

切换到 unique 模式后，所有代理不再只命中一次；这也使几个原本被 shared 模式失效
掩盖的过度拦截每次都生效：

1. Java 在英文阶段调用并 Hook `setAssociationEnabled(false)`，这是输入法实例的全局
   联想开关，不是只对英文请求生效。切回中文后没有对称恢复，因此中文联想消失。
2. `ButtonChar::OnButtonLongPress` 原函数把 `keyboard_behavior` 设为 `5`，表示长按
   气泡正在接管后续 Move/Up。旧代理在原函数返回后立即写回 `1`；日志直接出现
   `reset keyboard behavior 5->1 reason=LongPress`，后续轻微移动遂落入光标手势。
3. `English26Layout::OnButtonEnglishCharClicked` 并非英文组词入口。1.4.1 汇编
   `0x2cb350` 显示它会检查一次性大写状态，通过布局对象虚接口 `+0x290` 写回 `false`
   并刷新按键标签。跳过整个函数会让单次 Shift 表现为 Caps Lock。
4. `English26Layout::OnSelectionUpdated` 同时承担正常布局和选区同步。英文关联已有
   专用 Associate/Candidate 闸门，不应为了禁联想而跳过整个布局回调。

从 0.9.14 起，模块只在 `ButtonChar::CommitInput` 等源头阻止字符进入 OIME，保留
`OnButtonUp`、长按手势、英文布局点击和选区更新原始尾链。全局联想开关不再被修改；
切回中文后也不再执行英文 `FinishPreedit`、`MarkSkip` 或候选清理。

0.9.14 实机日志进一步修正了一个假设：源头 `CommitInput` 被消费后，普通按键的原始
`OnButtonUp` 只执行 `ButtonChar::OnButtonUp` 和 `OnButtonEnglishCharClicked` 等 UI
尾链，没有产生 `BoardController::CommitString`，因此字符不会上屏；长按和上滑能输入
是因为它们另行经过 `InputModel::Impl::Input` 兜底。0.9.15 对普通
`keyboard_behavior=1` 恢复已验证的按键对象单字符提交，然后继续调用原始
`OnButtonUp`；长按 `behavior=5` 仍不提前提交。不能在“单字符直提”和“原 UI 尾链”
之间二选一，两者分别负责正文和交互状态。

0.9.15 在密码框会重复上屏一次。1.4.1 `0x2c976c` 与 1.4.2 `0x2d0c60` 的
`ButtonEnglishChar::OnButtonUp` 开头都先调用当前输入框虚表 `+0x40`
（`IsPasswordBox`，Screen 实现读 `this+0x68`）。仅当密码框为真且
`keyboard_behavior==1` 时，原函数自己 `BoardController::CommitString`；普通文本框
则只走 `ButtonChar::OnButtonUp`。因此模块预提交 + 原密码框提交会各写一次。长按
`behavior=5` 和上滑不走这条密码框 CommitString，所以不会重复。0.9.16 在
`IsPasswordBox` 为真时跳过模块预提交，只保留原抬键提交。

## 当前实现约束

1. ShadowHook 初始化必须保持 `SHADOWHOOK_MODE_UNIQUE`；若将来改回 shared，必须
   同时把所有代理改为 `SHADOWHOOK_CALL_PREV`，并保证每个返回路径完成 proxy stack
   清理。
2. 英文普通字符必须在 `ButtonChar::CommitInput`、keycode 或 `Impl::Input` 源头被
   消费，不能依赖 Java `UpdatePreedit` 补交。
3. Native `KeyboardCallbackImpl::UpdatePreedit` 只作为异常兜底：吞掉非空词态、
   记录、救援最后一个尚未直提的字符并清引擎。
4. Java `UpdatePreedit` 只允许空串继续清 composing；非空英文串只记录和吞掉。
5. 中文模式必须立即执行原始逻辑，不得清理或改写中文拼音 composition。
6. 不得调用或改写全局 `setAssociationEnabled` 来实现英文禁联想；只能在确认英文状态
   后拦截具体的 Associate、Notify 和 Candidate 请求。
7. 不得在 `OnButtonLongPress` 返回后重置 `keyboard_behavior`；值 `5` 必须保留到
   原始 Move/Up 手势状态机自行结束。
8. 不得跳过 `English26Layout::OnButtonEnglishCharClicked`；单次 Shift 回落依赖该回调。
9. 普通 `behavior=1` 按键必须先走受控单字符提交，再继续原始 `OnButtonUp` 尾链；
   仅调用原尾链不会上屏，仅自行提交后 return 又会破坏 Shift 和长按状态。
10. 密码框不得再走模块预提交。原 `OnButtonUp` 在 `IsPasswordBox && behavior==1`
    时已经 `CommitString`；重复预提交会让密码框每个字母上屏两次。

## 回归测试清单

每次修改 Native 输入链后至少验证：

- 连续多轮输入普通小写，退格第一次就删除正文字符；
- Shift 大写与大小写交替输入；
- 长按气泡选择大写；
- 字母之间插入标点和上滑符号；
- 输入后上滑退格一次清空；
- 英文切中文、关闭输入视图和切换应用时不重复提交整词；
- 中文拼音预编辑、候选和联想保持原样；
- 日志中同一个 Native Hook 的计数持续出现 `#2`、`#3`，而不是长期停在 `#1`；
- 正常英文直输过程中不再出现非空 `unexpected ENG preedit`；
- 密码框英文点按、Shift 后点按只上屏一次；长按气泡和上滑仍只上屏一次。
