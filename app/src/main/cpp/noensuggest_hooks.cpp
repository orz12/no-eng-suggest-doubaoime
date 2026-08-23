#include "noensuggest_hooks.h"

#include <android/log.h>
#include <elf.h>
#include <fcntl.h>
#include <pthread.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#include "shadowhook.h"

#define LOG_TAG "DoubaoNoEnSuggest"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

constexpr const char *kFileLogPath = "/sdcard/Download/DoubaoNoEnSuggest.log";
constexpr int kBehaviorNormal = 1;
/** ButtonEnglishChar 上 commit 字符串成员默认偏移；安装时会从 OnButtonUp 扫描覆盖。 */
constexpr size_t kCommitStrOffsetFallback = 0x630;
/** 仅用于扫描失败时记录；实际写入必须从 GetKeyboardBehavior 指令解出。 */
constexpr size_t kBehaviorOffsetKnown141 = 0x4b0;

std::atomic<bool> g_ready{false};
std::atomic<bool> g_installing{false};
std::atomic<bool> g_logging_enabled{false};
/** startInputView 之后才允许 FinishPreedit discard，避免 InitWindow NPE。 */
std::atomic<bool> g_input_ready{false};
std::atomic<int> g_assoc_bypass{0};
std::atomic<int> g_block_commit{0};
thread_local int64_t g_lower_direct_at_ms = 0;
std::atomic<int> g_last_direct_ascii{0};
std::atomic<int64_t> g_last_direct_ascii_at_ms{0};
/** unique-mode 源头拦截后的异常 preedit 次数，仅用于日志诊断。 */
std::atomic<int> g_native_leak_rescue_count{0};
/** 仅我们主动 CommitString（直上屏/长按）时放行；其它英文 alnum 提交一律拦。 */
std::atomic<int> g_allow_direct_commit{0};
/**
 * 诊断闩锁：仅打日志，不参与逻辑判断（0.4.2 用它拦联想会误伤中文）。
 */
std::atomic<bool> g_diag_last_board_was_english{false};
size_t g_commit_str_off = kCommitStrOffsetFallback;
size_t g_behavior_off = 0;
pthread_mutex_t g_file_mu = PTHREAD_MUTEX_INITIALIZER;

using FnInputModelGetInstance = void *(*)();
using FnInputModelGetInputMode = int (*)(void *);
using FnInputModelMarkSkip = void (*)(void *);
using FnInputModelClear = void (*)(void *, int, int);
using FnWindowBoardGetInstance = void *(*)();
using FnGetKeyboardBehavior = int (*)(void *);
using FnBoardControllerGetInstance = void *(*)();
using FnBoardCommitString = void (*)(void *, const void *);
using FnBoardCommitAppendSymbol = void (*)(void *, const void *, int);
using FnBoardCommitSymbol = void (*)(void *, const void *, int);
using FnBoardFinishPreedit = void (*)(void *, int, int);
using FnBoardUpdatePreedit = void (*)(void *, const void *);
using FnHideTipBubble = void (*)(void *);
using FnOnButtonUp = void (*)(void *, uint64_t);
using FnClicked = void (*)(void *);
using FnVoidSelf = void (*)(void *);
using FnAssociateOuter = void (*)(void *, int, const void *);
using FnAssocRegs = int (*)(void *, void *, void *, void *, void *, void *, void *, void *);
using FnOnAssociate = void (*)(void *, int, int, int);
using FnBoardAssociate = void (*)(void *);
using FnDoCommit = void (*)(void *, const void *, int, const void *, const void *, const void *);
using FnInputBoxGetInstance = void *(*)();
using FnInputBoxSetPassword = void (*)(void *, int);
using FnInputBoxIsPassword = int (*)(void *);
using FnGetBoardType = int (*)(void *);
using FnSetInputMode3 = void (*)(void *, int, int, int);
using FnSetBoardTypeMode = void (*)(void *, int, int);
using FnSetBoardTypeBoard = void (*)(void *, int, int);
using FnSetKeepComposition = void (*)(void *, int);
using FnCandUpdateDisplay = void (*)(void *, int, int, int);
using FnCandUpdateCandidate = void (*)(void *, int, int);
using FnCandOnAssociated = void (*)(void *, int, int, int);
using FnInputCommitKeycode = void (*)(void *, int, const void *, int, uint64_t, const void *);
using FnInputPushCommitKeycode =
        void (*)(void *, int, const void *, int, uint64_t, const void *, int);
using FnImplInput =
        void (*)(void *, int, const void *, int, uint64_t, const void *, void *);
using FnImplCommitString =
        void (*)(void *, const void *, int, int, const void *, const void *, const void *, int);
using FnAssociateSelectText =
        void (*)(void *, const void *, const void *, const void *, int, int, const void *,
                 const void *);
using FnNotifyInt = void (*)(void *, int);
using FnCandidateSnapshot =
        void (*)(void *, const void *, const void *, int, int, int, int, int, int, int);
using FnJniDoUpClearAction = void (*)(void *, void *, int, unsigned char);

FnInputModelGetInstance InputModel_GetInstance = nullptr;
FnInputModelGetInputMode InputModel_GetInputMode = nullptr;
FnInputModelGetInputMode InputModel_IsTyping = nullptr;
FnInputModelMarkSkip InputModel_MarkSkip = nullptr;
FnInputModelClear InputModel_Clear = nullptr;
FnWindowBoardGetInstance WindowBoardView_GetInstance = nullptr;
FnGetKeyboardBehavior WindowBoardView_GetKeyboardBehavior = nullptr;
FnGetBoardType WindowBoardView_GetBoardType = nullptr;
FnBoardControllerGetInstance BoardController_GetInstance = nullptr;
FnHideTipBubble ButtonChar_HideTipBubble = nullptr;
FnVoidSelf CandidateRefresh_Clear = nullptr;
FnVoidSelf CorrectionManager_ClearCorrections = nullptr;
FnInputModelGetInstance CandidateRefresh_GetInstance = nullptr;
FnInputModelGetInstance CorrectionManager_GetInstance = nullptr;
FnInputBoxGetInstance InputBoxScreenModel_GetInstance = nullptr;
FnInputBoxGetInstance InputBoxModelManager_GetCurrent = nullptr;
FnInputBoxSetPassword InputBoxScreenModel_SetPasswordBox = nullptr;
FnInputBoxIsPassword InputBoxScreenModel_IsPasswordBox = nullptr;

void *sym_EngChar_OnButtonUp = nullptr;
void *sym_BtnChar_OnButtonUp = nullptr;
void *sym_BtnChar_CommitInput = nullptr;
void *sym_Board_CommitKeycode = nullptr;
void *sym_Board_PushCommitKeycode = nullptr;
void *sym_InputModel_CommitKeycode = nullptr;
void *sym_InputModel_PushCommitKeycode = nullptr;
void *sym_Impl_Input = nullptr;
void *sym_Impl_CommitString = nullptr;
void *sym_OnUpdateEnglishPreCommit = nullptr;
void *sym_PushCommit_OnButtonUp = nullptr;
void *sym_BtnChar_LongPress = nullptr;
void *sym_English26_Clicked = nullptr;
void *sym_English26_OnSelectionUpdated = nullptr;
void *sym_Jni_DoUpClearAction = nullptr;
void *sym_InputBoxScreen_UpClear = nullptr;
void *sym_InputBoxTranslate_UpClear = nullptr;
void *sym_Backspace_OnButtonDown = nullptr;
void *sym_Backspace_ShowUpClear = nullptr;
void *sym_Backspace_OnButtonUp = nullptr;
void *sym_Space_OnButtonUp = nullptr;
void *sym_Cand_UpdateDisplay = nullptr;
void *sym_Cand_UpdateCandidate = nullptr;
void *sym_Cand_OnAssociated = nullptr;
void *sym_Cand_SwitchToIdle = nullptr;
void *sym_Cand_UpdateComposition = nullptr;
void *sym_CandidateRefresh_Notify = nullptr;
void *sym_CandidateRefresh_NotifyCommit = nullptr;
void *sym_CandidateContainer_Snapshot = nullptr;
void *sym_CandidateComposition_Update = nullptr;
void *sym_Callback_UpdatePreedit = nullptr;
void *sym_AssociateOuter = nullptr;
void *sym_ImplAssociate1 = nullptr;
void *sym_ImplAssociate2 = nullptr;
void *sym_AssociateSelectText = nullptr;
void *sym_ImplAssociateSelectText = nullptr;
void *sym_OnAssociate = nullptr;
void *sym_BoardAssociate = nullptr;
void *sym_NotifyUpdateAssociations = nullptr;
void *sym_Board_FinishPreedit = nullptr;
void *sym_Board_UpdatePreedit = nullptr;
void *sym_Board_CommitString = nullptr;
void *sym_Board_CommitAppendSymbol = nullptr;
void *sym_Board_CommitSymbol = nullptr;
void *sym_DoCommit = nullptr;
void *sym_InputModel_SetInputMode = nullptr;
void *sym_WindowBoard_SetBoardTypeMode = nullptr;
void *sym_WindowBoard_SetBoardTypeBoard = nullptr;
void *sym_SwitchCnEn_OnButtonUp = nullptr;
void *sym_SwitchBoard_OnButtonUp = nullptr;
void *sym_SetKeepComposition = nullptr;

FnOnButtonUp orig_OnButtonUp = nullptr;
FnOnButtonUp orig_ButtonChar_OnButtonUp = nullptr;
FnOnButtonUp orig_ButtonChar_CommitInput = nullptr;
FnInputCommitKeycode orig_Board_CommitKeycode = nullptr;
FnInputCommitKeycode orig_Board_PushCommitKeycode = nullptr;
FnInputCommitKeycode orig_InputModel_CommitKeycode = nullptr;
FnInputPushCommitKeycode orig_InputModel_PushCommitKeycode = nullptr;
FnImplInput orig_Impl_Input = nullptr;
FnImplCommitString orig_Impl_CommitString = nullptr;
FnVoidSelf orig_OnUpdateEnglishPreCommit = nullptr;
FnOnButtonUp orig_PushCommit_OnButtonUp = nullptr;
FnOnButtonUp orig_ButtonChar_LongPress = nullptr;
FnOnButtonUp orig_SwitchCnEn_OnButtonUp = nullptr;
FnJniDoUpClearAction orig_Jni_DoUpClearAction = nullptr;
FnVoidSelf orig_InputBoxScreen_UpClear = nullptr;
FnVoidSelf orig_InputBoxTranslate_UpClear = nullptr;
FnOnButtonUp orig_Backspace_OnButtonDown = nullptr;
FnVoidSelf orig_Backspace_ShowUpClear = nullptr;
FnOnButtonUp orig_Backspace_OnButtonUp = nullptr;
FnOnButtonUp orig_Space_OnButtonUp = nullptr;
FnClicked orig_English26_Clicked = nullptr;
FnVoidSelf orig_English26_OnSelectionUpdated = nullptr;
FnCandUpdateDisplay orig_Cand_UpdateDisplay = nullptr;
FnCandUpdateCandidate orig_Cand_UpdateCandidate = nullptr;
FnCandOnAssociated orig_Cand_OnAssociated = nullptr;
FnCandUpdateCandidate orig_Cand_UpdateComposition = nullptr;
FnNotifyInt orig_CandidateRefresh_Notify = nullptr;
FnVoidSelf orig_CandidateRefresh_NotifyCommit = nullptr;
FnCandidateSnapshot orig_CandidateContainer_Snapshot = nullptr;
FnCandUpdateCandidate orig_CandidateComposition_Update = nullptr;
FnVoidSelf Cand_SwitchToIdle = nullptr;
using FnGetCandInstance = void *(*)();
FnGetCandInstance CandToolbar_GetInstance = nullptr;
FnBoardUpdatePreedit orig_Callback_UpdatePreedit = nullptr;
FnAssociateOuter orig_AssociateOuter = nullptr;
FnAssocRegs orig_ImplAssociate1 = nullptr;
FnAssocRegs orig_ImplAssociate2 = nullptr;
FnAssociateSelectText orig_AssociateSelectText = nullptr;
FnAssociateSelectText orig_ImplAssociateSelectText = nullptr;
FnOnAssociate orig_OnAssociate = nullptr;
FnBoardAssociate orig_BoardAssociate = nullptr;
FnVoidSelf orig_NotifyUpdateAssociations = nullptr;
FnBoardFinishPreedit orig_Board_FinishPreedit = nullptr;
FnBoardUpdatePreedit orig_Board_UpdatePreedit = nullptr;
FnBoardCommitString orig_Board_CommitString = nullptr;
FnBoardCommitAppendSymbol orig_Board_CommitAppendSymbol = nullptr;
FnBoardCommitSymbol orig_Board_CommitSymbol = nullptr;
FnDoCommit orig_DoCommit = nullptr;
FnSetInputMode3 orig_InputModel_SetInputMode = nullptr;
FnSetBoardTypeMode orig_WindowBoard_SetBoardTypeMode = nullptr;
FnSetBoardTypeBoard orig_WindowBoard_SetBoardTypeBoard = nullptr;
FnOnButtonUp orig_SwitchBoard_OnButtonUp = nullptr;
FnSetKeepComposition orig_SetKeepComposition = nullptr;
FnSetKeepComposition InputModel_SetKeepComposition = nullptr;

void file_log(const char *msg) {
    pthread_mutex_lock(&g_file_mu);
    int fd = open(kFileLogPath, O_WRONLY | O_CREAT | O_APPEND, 0666);
    if (fd >= 0) {
        dprintf(fd, "[native pid=%d tid=%d] %s\n", getpid(), gettid(), msg);
        close(fd);
    }
    pthread_mutex_unlock(&g_file_mu);
}

void log_both(const char *msg) {
    if (!g_logging_enabled.load()) {
        return;
    }
    ALOGI("%s", msg);
    file_log(msg);
}

void log_rate(const char *fmt_tag, int n) {
    if (n <= 40 || (n % 20) == 0) {
        char buf[96];
        snprintf(buf, sizeof(buf), "%s #%d", fmt_tag, n);
        log_both(buf);
    }
}

bool is_english_mode(int mode) {
    return mode == 2 || mode == 0x102 || ((mode & 0xff) == 2);
}

int current_board_type();
int current_input_mode();
bool is_english_ui();
bool is_password_box();
void discard_preedit_once(const char *reason);
void mark_skip_associate();
void force_candidate_idle(const char *reason);
void clear_english_candidate_state(const char *reason);
void clear_english_engine_state(const char *reason);
void reset_keyboard_behavior(const char *reason);
int64_t monotonic_ms();

int current_board_type() {
    void *wb = WindowBoardView_GetInstance ? WindowBoardView_GetInstance() : nullptr;
    if (wb == nullptr || WindowBoardView_GetBoardType == nullptr) {
        return -1;
    }
    return WindowBoardView_GetBoardType(wb);
}

/** 诊断：记录切板/模式，不改变功能逻辑。 */
void diag_note_mode_change(const char *src, int mode_or_board, bool as_english_guess) {
    bool prev = g_diag_last_board_was_english.exchange(as_english_guess);
    char buf[192];
    snprintf(buf, sizeof(buf), "DIAG modeChange src=%s value=%d guessEng=%d prevGuess=%d", src,
             mode_or_board, as_english_guess ? 1 : 0, prev ? 1 : 0);
    log_both(buf);
}

void diag_key_snapshot(const char *tag, int n, int mode, int behavior, bool eng_char_key,
                       bool will_direct) {
    char buf[256];
    snprintf(buf, sizeof(buf),
             "DIAG key %s#%d mode=%d boardType=%d behavior=%d engMode=%d engKey=%d "
             "diagGuessEng=%d willDirect=%d",
             tag, n, mode, current_board_type(), behavior, is_english_mode(mode) ? 1 : 0,
             eng_char_key ? 1 : 0, g_diag_last_board_was_english.load() ? 1 : 0,
             will_direct ? 1 : 0);
    log_both(buf);
}

struct KeyboardMaps {
    uintptr_t base = 0;
    char path[512]{};
};

/** 从 /proc/self/maps 取 libkeyboard.so 基址与绝对路径。 */
bool find_keyboard_maps(KeyboardMaps *out) {
    if (out == nullptr) {
        return false;
    }
    out->base = 0;
    out->path[0] = '\0';
    FILE *f = fopen("/proc/self/maps", "r");
    if (f == nullptr) {
        log_both("fopen /proc/self/maps failed");
        return false;
    }
    char line[1024];
    int hits = 0;
    while (fgets(line, sizeof(line), f) != nullptr) {
        if (strstr(line, "libkeyboard.so") == nullptr) {
            continue;
        }
        ++hits;
        unsigned long start = 0;
        unsigned long end = 0;
        unsigned long offset = 0;
        char perms[8] = {};
        if (sscanf(line, "%lx-%lx %7s %lx", &start, &end, perms, &offset) != 4) {
            continue;
        }
        if (offset != 0) {
            continue;
        }
        if (out->base == 0 || start < out->base) {
            out->base = static_cast<uintptr_t>(start);
            const char *p = strchr(line, '/');
            if (p != nullptr) {
                size_t n = strlen(p);
                while (n > 0 && (p[n - 1] == '\n' || p[n - 1] == '\r')) {
                    --n;
                }
                if (n >= sizeof(out->path)) {
                    n = sizeof(out->path) - 1;
                }
                memcpy(out->path, p, n);
                out->path[n] = '\0';
            }
        }
    }
    fclose(f);
    char buf[640];
    snprintf(buf, sizeof(buf), "maps libkeyboard hits=%d base=0x%lx path=%s", hits,
             static_cast<unsigned long>(out->base), out->path[0] ? out->path : "(none)");
    log_both(buf);
    return out->base != 0 && out->path[0] != '\0';
}

struct DynSymIndex {
    uintptr_t load_base = 0;
    uint8_t *file = nullptr;
    size_t file_sz = 0;
    const Elf64_Sym *syms = nullptr;
    size_t nsyms = 0;
    const char *strtab = nullptr;
    size_t strsz = 0;
};

void dynsym_free(DynSymIndex *idx) {
    if (idx == nullptr) {
        return;
    }
    free(idx->file);
    idx->file = nullptr;
    idx->syms = nullptr;
    idx->strtab = nullptr;
    idx->nsyms = 0;
    idx->strsz = 0;
    idx->file_sz = 0;
}

bool dynsym_load(DynSymIndex *idx, uintptr_t load_base, const char *path) {
    dynsym_free(idx);
    idx->load_base = load_base;
    FILE *f = fopen(path, "rb");
    if (f == nullptr) {
        char buf[640];
        snprintf(buf, sizeof(buf), "fopen SO failed path=%s", path);
        log_both(buf);
        return false;
    }
    if (fseek(f, 0, SEEK_END) != 0) {
        fclose(f);
        return false;
    }
    long sz = ftell(f);
    if (sz <= 0) {
        fclose(f);
        return false;
    }
    if (fseek(f, 0, SEEK_SET) != 0) {
        fclose(f);
        return false;
    }
    auto *buf = reinterpret_cast<uint8_t *>(malloc(static_cast<size_t>(sz)));
    if (buf == nullptr) {
        fclose(f);
        return false;
    }
    size_t nread = fread(buf, 1, static_cast<size_t>(sz), f);
    fclose(f);
    if (nread != static_cast<size_t>(sz)) {
        free(buf);
        return false;
    }
    if (sz < static_cast<long>(sizeof(Elf64_Ehdr)) || buf[0] != 0x7f || buf[1] != 'E' ||
        buf[2] != 'L' || buf[3] != 'F' || buf[4] != 2) {
        log_both("SO is not ELF64");
        free(buf);
        return false;
    }
    const auto *eh = reinterpret_cast<const Elf64_Ehdr *>(buf);
    if (eh->e_shoff == 0 || eh->e_shentsize != sizeof(Elf64_Shdr) || eh->e_shnum == 0) {
        log_both("SO missing section headers");
        free(buf);
        return false;
    }
    if (eh->e_shoff + static_cast<uint64_t>(eh->e_shnum) * sizeof(Elf64_Shdr) >
        static_cast<uint64_t>(sz)) {
        log_both("SO section header OOB");
        free(buf);
        return false;
    }
    const auto *sh = reinterpret_cast<const Elf64_Shdr *>(buf + eh->e_shoff);
    const Elf64_Shdr *dynsym = nullptr;
    for (uint16_t i = 0; i < eh->e_shnum; ++i) {
        if (sh[i].sh_type == SHT_DYNSYM) {
            dynsym = &sh[i];
            break;
        }
    }
    if (dynsym == nullptr || dynsym->sh_link >= eh->e_shnum || dynsym->sh_entsize == 0) {
        log_both("SO .dynsym missing");
        free(buf);
        return false;
    }
    const Elf64_Shdr *dynstr = &sh[dynsym->sh_link];
    if (dynsym->sh_offset + dynsym->sh_size > static_cast<uint64_t>(sz) ||
        dynstr->sh_offset + dynstr->sh_size > static_cast<uint64_t>(sz)) {
        log_both("SO dynsym/dynstr OOB");
        free(buf);
        return false;
    }
    idx->file = buf;
    idx->file_sz = static_cast<size_t>(sz);
    idx->syms = reinterpret_cast<const Elf64_Sym *>(buf + dynsym->sh_offset);
    idx->nsyms = static_cast<size_t>(dynsym->sh_size / dynsym->sh_entsize);
    idx->strtab = reinterpret_cast<const char *>(buf + dynstr->sh_offset);
    idx->strsz = static_cast<size_t>(dynstr->sh_size);
    char msg[96];
    snprintf(msg, sizeof(msg), "ELF dynsym loaded nsyms=%zu strsz=%zu", idx->nsyms, idx->strsz);
    log_both(msg);
    return true;
}

void *dynsym_find(const DynSymIndex *idx, const char *name, bool required) {
    if (idx == nullptr || idx->syms == nullptr || idx->strtab == nullptr || name == nullptr) {
        return nullptr;
    }
    for (size_t i = 0; i < idx->nsyms; ++i) {
        const Elf64_Sym &s = idx->syms[i];
        if (s.st_name == 0 || s.st_name >= idx->strsz) {
            continue;
        }
        if (s.st_shndx == SHN_UNDEF) {
            continue;
        }
        if (strcmp(idx->strtab + s.st_name, name) != 0) {
            continue;
        }
        void *p = reinterpret_cast<void *>(idx->load_base + static_cast<uintptr_t>(s.st_value));
        char buf[192];
        snprintf(buf, sizeof(buf), "sym OK %s @%p (rva=0x%lx)", name, p,
                 static_cast<unsigned long>(s.st_value));
        log_both(buf);
        return p;
    }
    char buf[256];
    snprintf(buf, sizeof(buf), "%s sym %s", required ? "FAIL" : "miss", name);
    log_both(buf);
    return nullptr;
}

/**
 * 从 ButtonEnglishChar::OnButtonUp 前若干条指令中扫描
 * `ADD Xd, Xn, #imm`（ARM64），推断 this+imm 上的当前 commit 字符串偏移。
 */
size_t discover_commit_str_offset(void *on_button_up) {
    if (on_button_up == nullptr) {
        return kCommitStrOffsetFallback;
    }
    const auto *insns = reinterpret_cast<const uint32_t *>(on_button_up);
    size_t found = 0;
    for (int i = 0; i < 96; ++i) {
        uint32_t insn = insns[i];
        // ADD (immediate), 64-bit, shift=0 → 0x91000000 / mask 0xFFC00000
        if ((insn & 0xFFC00000u) != 0x91000000u) {
            continue;
        }
        uint32_t imm = (insn >> 10) & 0xFFFu;
        if (imm < 0x600 || imm > 0x700 || (imm & 7u) != 0u) {
            continue;
        }
        if (imm == kCommitStrOffsetFallback) {
            return imm;
        }
        if (found == 0) {
            found = imm;
        }
    }
    return found != 0 ? found : kCommitStrOffsetFallback;
}

/**
 * GetKeyboardBehavior 在 1.4.1 是 `ldr w0, [x0, #imm]; ret`。
 * 从指令解偏移，避免把历史版本的 WindowBoardView 成员偏移写死。
 */
size_t discover_behavior_offset(void *get_behavior) {
    if (get_behavior == nullptr) {
        return 0;
    }
    const auto *insns = reinterpret_cast<const uint32_t *>(get_behavior);
    for (int i = 0; i < 8; ++i) {
        uint32_t insn = insns[i];
        // LDR W0, [X0, #imm12 * 4]
        if ((insn & 0xFFC003FFu) != 0xB9400000u) {
            continue;
        }
        size_t off = static_cast<size_t>((insn >> 10) & 0xFFFu) * 4u;
        if (off >= 0x100 && off <= 0x1000 && (off & 3u) == 0u) {
            return off;
        }
    }
    return 0;
}

bool resolve_symbols() {
    KeyboardMaps km;
    if (!find_keyboard_maps(&km)) {
        return false;
    }

    DynSymIndex idx{};
    // Android linker namespace 常导致 dlopen 失败；直接解析磁盘 SO 的 .dynsym + maps 基址。
    if (!dynsym_load(&idx, km.base, km.path)) {
        return false;
    }

    InputModel_GetInstance = reinterpret_cast<FnInputModelGetInstance>(
            dynsym_find(&idx, "_ZN8keyboard10InputModel11GetInstanceEv", true));
    InputModel_GetInputMode = reinterpret_cast<FnInputModelGetInputMode>(
            dynsym_find(&idx, "_ZNK8keyboard10InputModel12GetInputModeEv", true));
    InputModel_IsTyping = reinterpret_cast<FnInputModelGetInputMode>(
            dynsym_find(&idx, "_ZNK8keyboard10InputModel8IsTypingEv", false));
    InputModel_MarkSkip = reinterpret_cast<FnInputModelMarkSkip>(dynsym_find(
            &idx, "_ZN8keyboard10InputModel34MarkSkipNextEnglishCommitAssociateEv", true));
    InputModel_Clear = reinterpret_cast<FnInputModelClear>(
            dynsym_find(&idx, "_ZN8keyboard10InputModel5ClearEbb", true));
    WindowBoardView_GetInstance = reinterpret_cast<FnWindowBoardGetInstance>(
            dynsym_find(&idx, "_ZN2ui15WindowBoardView11GetInstanceEv", true));
    WindowBoardView_GetKeyboardBehavior = reinterpret_cast<FnGetKeyboardBehavior>(
            dynsym_find(&idx, "_ZNK2ui15WindowBoardView19GetKeyboardBehaviorEv", true));
    WindowBoardView_GetBoardType = reinterpret_cast<FnGetBoardType>(
            dynsym_find(&idx, "_ZNK2ui15WindowBoardView12GetBoardTypeEv", false));
    BoardController_GetInstance = reinterpret_cast<FnBoardControllerGetInstance>(
            dynsym_find(&idx, "_ZN10controller15BoardController11GetInstanceEv", true));
    ButtonChar_HideTipBubble = reinterpret_cast<FnHideTipBubble>(
            dynsym_find(&idx, "_ZN2ui10ButtonChar13HideTipBubbleEv", false));
    CandidateRefresh_GetInstance = reinterpret_cast<FnInputModelGetInstance>(dynsym_find(
            &idx, "_ZN8keyboard23CandidateRefreshManager11GetInstanceEv", false));
    CandidateRefresh_Clear = reinterpret_cast<FnVoidSelf>(
            dynsym_find(&idx, "_ZN8keyboard23CandidateRefreshManager5ClearEv", false));
    CorrectionManager_GetInstance = reinterpret_cast<FnInputModelGetInstance>(
            dynsym_find(&idx, "_ZN4oime17CorrectionManager11GetInstanceEv", false));
    CorrectionManager_ClearCorrections = reinterpret_cast<FnVoidSelf>(
            dynsym_find(&idx, "_ZN4oime17CorrectionManager16ClearCorrectionsEv", false));
    InputBoxScreenModel_GetInstance = reinterpret_cast<FnInputBoxGetInstance>(
            dynsym_find(&idx, "_ZN5input19InputBoxScreenModel11GetInstanceEv", false));
    InputBoxModelManager_GetCurrent = reinterpret_cast<FnInputBoxGetInstance>(dynsym_find(
            &idx, "_ZN5input20InputBoxModelManager23GetCurrentInputBoxModelEv", false));
    InputBoxScreenModel_SetPasswordBox = reinterpret_cast<FnInputBoxSetPassword>(
            dynsym_find(&idx, "_ZN5input19InputBoxScreenModel14SetPasswordBoxEb", false));
    InputBoxScreenModel_IsPasswordBox = reinterpret_cast<FnInputBoxIsPassword>(
            dynsym_find(&idx, "_ZN5input19InputBoxScreenModel13IsPasswordBoxEv", false));

    sym_EngChar_OnButtonUp =
            dynsym_find(&idx, "_ZN2ui17ButtonEnglishChar10OnButtonUpENS_8tagPOINTE", true);
    sym_BtnChar_OnButtonUp =
            dynsym_find(&idx, "_ZN2ui10ButtonChar10OnButtonUpENS_8tagPOINTE", true);
    sym_BtnChar_CommitInput =
            dynsym_find(&idx, "_ZN2ui10ButtonChar11CommitInputENS_8tagPOINTE", true);
    sym_Board_CommitKeycode = dynsym_find(
            &idx,
            "_ZN10controller15BoardController13CommitKeycodeEiRKNSt6__ndk14pairIssEEbmRKNS1_"
            "12basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE",
            false);
    sym_Board_PushCommitKeycode = dynsym_find(
            &idx,
            "_ZN10controller15BoardController17PushCommitKeycodeEiRKNSt6__ndk14pairIssEEbmRKNS1_"
            "12basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE",
            false);
    sym_InputModel_CommitKeycode = dynsym_find(
            &idx,
            "_ZN8keyboard10InputModel13CommitKeycodeEiRKNSt6__ndk14pairIssEEbmRKNS1_"
            "12basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE",
            false);
    sym_InputModel_PushCommitKeycode = dynsym_find(
            &idx,
            "_ZN8keyboard10InputModel17PushCommitKeycodeEiRKNSt6__ndk14pairIssEEbmRKNS1_"
            "12basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEEb",
            false);
    sym_Impl_Input = dynsym_find(
            &idx,
            "_ZN8keyboard10InputModel4Impl5InputEiRKNSt6__ndk14pairIssEEbmRKNS2_"
            "12basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEENS2_8functionIFvSE_EEE",
            false);
    sym_Impl_CommitString = dynsym_find(
            &idx,
            "_ZN8keyboard10InputModel4Impl12CommitStringERKNSt6__ndk112basic_stringIcNS2_"
            "11char_traitsIcEENS2_9allocatorIcEEEEbbSA_SA_SA_b",
            false);
    sym_OnUpdateEnglishPreCommit = dynsym_find(
            &idx, "_ZN8keyboard10InputModel26OnUpdateEnglish26PreCommitEv", false);
    sym_PushCommit_OnButtonUp =
            dynsym_find(&idx, "_ZN2ui23ButtonEnglishPushCommit10OnButtonUpENS_8tagPOINTE", false);
    sym_BtnChar_LongPress =
            dynsym_find(&idx, "_ZN2ui10ButtonChar17OnButtonLongPressENS_8tagPOINTE", false);
    sym_English26_Clicked =
            dynsym_find(&idx, "_ZN2ui15English26Layout26OnButtonEnglishCharClickedEv", false);
    sym_English26_OnSelectionUpdated =
            dynsym_find(&idx, "_ZN2ui15English26Layout18OnSelectionUpdatedEv", false);
    sym_Jni_DoUpClearAction = dynsym_find(&idx, "Jni_DoUpClearAction", false);
    sym_InputBoxScreen_UpClear =
            dynsym_find(&idx, "_ZN5input19InputBoxScreenModel7UpClearEv", false);
    sym_InputBoxTranslate_UpClear =
            dynsym_find(&idx, "_ZN5input22InputBoxTranslateModel7UpClearEv", false);
    sym_Backspace_OnButtonDown =
            dynsym_find(&idx, "_ZN2ui15ButtonBackspace12OnButtonDownENS_8tagPOINTE", false);
    sym_Backspace_ShowUpClear =
            dynsym_find(&idx, "_ZN2ui15ButtonBackspace11ShowUpClearEv", false);
    sym_Backspace_OnButtonUp =
            dynsym_find(&idx, "_ZN2ui15ButtonBackspace10OnButtonUpENS_8tagPOINTE", false);
    sym_Space_OnButtonUp =
            dynsym_find(&idx, "_ZN2ui11ButtonSpace10OnButtonUpENS_8tagPOINTE", true);
    sym_Cand_UpdateDisplay = dynsym_find(
            &idx, "_ZN6center22CandidateToolbarCenter22UpdateCandidateDisplayEbbb", false);
    sym_Cand_UpdateCandidate = dynsym_find(
            &idx, "_ZN6center22CandidateToolbarCenter15UpdateCandidateEbb", false);
    sym_Cand_OnAssociated = dynsym_find(
            &idx, "_ZN6center22CandidateToolbarCenter12OnAssociatedEN8keyboard11InputStatusEbb",
            false);
    sym_Cand_UpdateComposition = dynsym_find(
            &idx, "_ZN6center22CandidateToolbarCenter17UpdateCompositionEbb", false);
    sym_Cand_SwitchToIdle = dynsym_find(
            &idx, "_ZN6center22CandidateToolbarCenter30SwitchToIdleForAssociateConfigEv", false);
    Cand_SwitchToIdle = reinterpret_cast<FnVoidSelf>(sym_Cand_SwitchToIdle);
    CandToolbar_GetInstance = reinterpret_cast<FnGetCandInstance>(dynsym_find(
            &idx, "_ZN6center22CandidateToolbarCenter11GetInstanceEv", false));
    sym_CandidateRefresh_Notify = dynsym_find(
            &idx, "_ZN8keyboard23CandidateRefreshManager21NotifyRefreshListenerEi", false);
    sym_CandidateRefresh_NotifyCommit = dynsym_find(
            &idx, "_ZN8keyboard23CandidateRefreshManager27NotifyCommitStringListenersEv", false);
    sym_CandidateContainer_Snapshot = dynsym_find(
            &idx,
            "_ZN6center24CandidateContainerCenter27BuildAndPushAndroidSnapshotERKNSt6__ndk16vector"
            "INS0_14CorrectionItemENS1_9allocatorIS3_EEEERKNS2_IN5shell8CandDataENS4_ISA_EEEEbbibibb",
            false);
    sym_CandidateComposition_Update = dynsym_find(
            &idx, "_ZN6center26CandidateCompositionCenter10UpdateCompEbb", false);
    sym_Callback_UpdatePreedit = dynsym_find(
            &idx,
            "_ZN8keyboard20KeyboardCallbackImpl13UpdatePreeditERKNSt6__ndk112basic_stringIcNS1_"
            "11char_traitsIcEENS1_9allocatorIcEEEE",
            false);
    sym_AssociateOuter = dynsym_find(
            &idx,
            "_ZN8keyboard10InputModel9AssociateEbRKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_"
            "9allocatorIcEEEE",
            false);
    sym_ImplAssociate1 = dynsym_find(
            &idx,
            "_ZN8keyboard10InputModel4Impl9AssociateERKNSt6__ndk112basic_stringIcNS2_11char_traitsIcEENS2_"
            "9allocatorIcEEEEibSA_bSA_b",
            false);
    sym_ImplAssociate2 = dynsym_find(
            &idx,
            "_ZN8keyboard10InputModel4Impl9AssociateEibRKNSt6__ndk112basic_stringIcNS2_11char_traitsIcEENS2_"
            "9allocatorIcEEEEbSA_b",
            false);
    sym_AssociateSelectText = dynsym_find(
            &idx,
            "_ZN8keyboard10InputModel19AssociateSelectTextERKNSt6__ndk112basic_stringIcNS1_"
            "11char_traitsIcEENS1_9allocatorIcEEEERKNS1_6vectorIS7_NS5_IS7_EEEES9_bbS9_S9_",
            false);
    sym_ImplAssociateSelectText = dynsym_find(
            &idx,
            "_ZN8keyboard10InputModel4Impl19AssociateSelectTextERKNSt6__ndk112basic_stringIcNS2_"
            "11char_traitsIcEENS2_9allocatorIcEEEERKNS2_6vectorIS8_NS6_IS8_EEEESA_bbSA_SA_",
            false);
    sym_OnAssociate = dynsym_find(&idx, "_ZN8keyboard10InputModel11OnAssociateEbbb", false);
    sym_BoardAssociate =
            dynsym_find(&idx, "_ZN10controller15BoardController9AssociateEv", false);
    sym_NotifyUpdateAssociations = dynsym_find(
            &idx, "_ZN8keyboard20KeyboardCallbackImpl24NotifyUpdateAssociationsEv", false);
    sym_Board_FinishPreedit =
            dynsym_find(&idx, "_ZN10controller15BoardController13FinishPreeditEbb", true);
    sym_Board_UpdatePreedit = dynsym_find(
            &idx,
            "_ZN10controller15BoardController13UpdatePreeditERKNSt6__ndk112basic_stringIcNS1_"
            "11char_traitsIcEENS1_9allocatorIcEEEE",
            false);
    sym_Board_CommitString = dynsym_find(
            &idx,
            "_ZN10controller15BoardController12CommitStringERKNSt6__ndk112basic_stringIcNS1_"
            "11char_traitsIcEENS1_9allocatorIcEEEE",
            true);
    sym_Board_CommitAppendSymbol = dynsym_find(
            &idx,
            "_ZN10controller15BoardController18CommitAppendSymbolERKNSt6__ndk112basic_stringIcNS1_"
            "11char_traitsIcEENS1_9allocatorIcEEEEb",
            true);
    sym_Board_CommitSymbol = dynsym_find(
            &idx,
            "_ZN10controller15BoardController12CommitSymbolERKNSt6__ndk112basic_stringIcNS1_"
            "11char_traitsIcEENS1_9allocatorIcEEEEb",
            false);
    sym_DoCommit = dynsym_find(
            &idx,
            "_ZN8keyboard20KeyboardCallbackImpl8DoCommitERKNSt6__ndk112basic_stringIcNS1_"
            "11char_traitsIcEENS1_9allocatorIcEEEEiS9_S9_S9_",
            true);
    sym_InputModel_SetInputMode =
            dynsym_find(&idx, "_ZN8keyboard10InputModel12SetInputModeENS_9InputModeEbb", false);
    sym_WindowBoard_SetBoardTypeMode = dynsym_find(
            &idx, "_ZN2ui15WindowBoardView12SetBoardTypeEN8keyboard9InputModeEb", false);
    sym_WindowBoard_SetBoardTypeBoard = dynsym_find(
            &idx, "_ZN2ui15WindowBoardView12SetBoardTypeENS_14InputBoardTypeEb", false);
    sym_SwitchCnEn_OnButtonUp =
            dynsym_find(&idx, "_ZN2ui26ButtonSwitchChineseEnglish10OnButtonUpENS_8tagPOINTE", false);
    sym_SwitchBoard_OnButtonUp =
            dynsym_find(&idx, "_ZN2ui17ButtonSwitchBoard10OnButtonUpENS_8tagPOINTE", false);
    sym_SetKeepComposition = dynsym_find(
            &idx, "_ZN8keyboard10InputModel33SetKeepCompositionOnEnglishSwitchEb", false);
    InputModel_SetKeepComposition =
            reinterpret_cast<FnSetKeepComposition>(sym_SetKeepComposition);

    dynsym_free(&idx);

    if (InputModel_GetInstance == nullptr || InputModel_GetInputMode == nullptr ||
        InputModel_Clear == nullptr || sym_BtnChar_CommitInput == nullptr ||
        BoardController_GetInstance == nullptr || WindowBoardView_GetInstance == nullptr ||
        WindowBoardView_GetKeyboardBehavior == nullptr || sym_EngChar_OnButtonUp == nullptr ||
        sym_Board_CommitString == nullptr || sym_Board_CommitAppendSymbol == nullptr) {
        log_both("resolve_symbols: core symbols missing");
        return false;
    }

    g_commit_str_off = discover_commit_str_offset(sym_EngChar_OnButtonUp);
    g_behavior_off =
            discover_behavior_offset(reinterpret_cast<void *>(WindowBoardView_GetKeyboardBehavior));
    char buf[160];
    snprintf(buf, sizeof(buf),
             "discovered offsets commitStr=0x%zx behavior=0x%zx (known141=0x%zx)",
             g_commit_str_off, g_behavior_off, kBehaviorOffsetKnown141);
    log_both(buf);
    if (g_behavior_off == 0) {
        log_both("resolve_symbols: cannot decode keyboard behavior offset");
        return false;
    }
    log_both("resolve_symbols OK (ELF dynsym, version-agnostic)");
    return true;
}

int current_input_mode() {
    void *im = InputModel_GetInstance();
    if (im == nullptr || InputModel_GetInputMode == nullptr) {
        return -1;
    }
    return InputModel_GetInputMode(im);
}

int current_keyboard_behavior() {
    void *wb = WindowBoardView_GetInstance();
    if (wb == nullptr || WindowBoardView_GetKeyboardBehavior == nullptr) {
        return -1;
    }
    return WindowBoardView_GetKeyboardBehavior(wb);
}

/**
 * 反汇编已确认 ButtonEnglishChar 仅在 keyboard_behavior==1 时走直接提交；
 * 0 会退回 ButtonChar 词模式。因此兜底复位必须写 1，不能写 0。
 */
void reset_keyboard_behavior(const char *reason) {
    if (g_behavior_off == 0 || WindowBoardView_GetInstance == nullptr ||
        WindowBoardView_GetKeyboardBehavior == nullptr) {
        return;
    }
    void *wb = WindowBoardView_GetInstance();
    if (wb == nullptr) {
        return;
    }
    int before = WindowBoardView_GetKeyboardBehavior(wb);
    int *slot = reinterpret_cast<int *>(reinterpret_cast<char *>(wb) + g_behavior_off);
    // 解出的字段必须与 getter 当前值一致，校验不过绝不写内存。
    if (*slot != before) {
        static std::atomic<int> mismatch{0};
        log_rate("behavior offset verify mismatch", ++mismatch);
        return;
    }
    if (before != kBehaviorNormal) {
        *slot = kBehaviorNormal;
    }
    static std::atomic<int> n{0};
    int c = ++n;
    if (c <= 80 || (c % 20) == 0) {
        char buf[128];
        snprintf(buf, sizeof(buf), "reset keyboard behavior #%d %d->%d reason=%s", c, before,
                 WindowBoardView_GetKeyboardBehavior(wb), reason ? reason : "?");
        log_both(buf);
    }
}

/**
 * 英文键盘 UI 判定：boardType==2 为英文 26 键（日志已验证）；
 * 辅以 GetInputMode。比 Java IsEnglishKeyboard 稳定（后者切语言后会误变 false）。
 */
bool is_english_ui() {
    int board = current_board_type();
    if (board == 2) {
        return true;
    }
    return is_english_mode(current_input_mode());
}

/**
 * 与 ButtonEnglishChar::OnButtonUp 使用同一条判定：当前 InputBox 虚表 +0x40
 * 即 IsPasswordBox。密码框会由原抬键自行 CommitString，模块不得再预提交。
 */
bool is_password_box() {
    if (InputBoxModelManager_GetCurrent != nullptr) {
        void *box = InputBoxModelManager_GetCurrent();
        if (box != nullptr) {
            auto **vtable = *reinterpret_cast<void ***>(box);
            if (vtable != nullptr && vtable[8] != nullptr) {
                auto is_pwd = reinterpret_cast<FnInputBoxIsPassword>(vtable[8]);
                if (is_pwd(box)) {
                    return true;
                }
            }
        }
    }
    if (InputBoxScreenModel_GetInstance == nullptr ||
        InputBoxScreenModel_IsPasswordBox == nullptr) {
        return false;
    }
    void *screen = InputBoxScreenModel_GetInstance();
    return screen != nullptr && InputBoxScreenModel_IsPasswordBox(screen) != 0;
}

/** 丢弃预编辑/词态（不提交）。仅输入界面就绪后调用；InitWindow 阶段会 NPE。 */
void discard_preedit_once(const char *reason) {
    if (!g_input_ready.load()) {
        static std::atomic<int> skip{0};
        int c = ++skip;
        if (c <= 20 || (c % 10) == 0) {
            char buf[128];
            snprintf(buf, sizeof(buf), "skip discard (input not ready) #%d reason=%s", c,
                     reason ? reason : "?");
            log_both(buf);
        }
        return;
    }
    if (orig_Board_FinishPreedit == nullptr || BoardController_GetInstance == nullptr) {
        return;
    }
    void *bc = BoardController_GetInstance();
    if (bc == nullptr) {
        return;
    }
    static thread_local int reenter = 0;
    if (reenter > 0) {
        return;
    }
    bool was_english = is_english_ui();
    ++reenter;
    orig_Board_FinishPreedit(bc, 0, 0);
    // MarkSkip 是 InputModel 的一次性联想闩锁。切回中文后继续写入会吞掉
    // 随后的中文联想请求，因此只允许英文阶段的 discard 设置它。
    if (was_english) {
        mark_skip_associate();
    }
    static std::atomic<int> n{0};
    int c = ++n;
    if (c <= 60 || (c % 10) == 0) {
        char buf[160];
        snprintf(buf, sizeof(buf), "discard preedit #%d reason=%s board=%d mode=%d", c,
                 reason ? reason : "?", current_board_type(), current_input_mode());
        log_both(buf);
    }
    --reenter;
}

bool should_bypass_english_associate() {
    return is_english_ui();
}

void note_assoc_bypass(const char *tag) {
    int n = ++g_assoc_bypass;
    if (n <= 80 || (n % 10) == 0) {
        char buf[160];
        snprintf(buf, sizeof(buf), "DIAG bypass assoc %s #%d mode=%d boardType=%d", tag, n,
                 current_input_mode(), current_board_type());
        log_both(buf);
    }
}

void mark_skip_associate() {
    if (InputModel_MarkSkip == nullptr) {
        return;
    }
    void *im = InputModel_GetInstance();
    if (im != nullptr) {
        InputModel_MarkSkip(im);
    }
}

/** 真正清除 InputModel/Impl 的 typing buffer；候选管理器 Clear 不能替代它。 */
void clear_english_engine_state(const char *reason) {
    if (InputModel_Clear == nullptr || InputModel_GetInstance == nullptr) {
        return;
    }
    void *im = InputModel_GetInstance();
    if (im == nullptr) {
        return;
    }
    static thread_local int reenter = 0;
    if (reenter > 0) {
        return;
    }
    ++reenter;
    InputModel_Clear(im, 0, 0);
    --reenter;
    static std::atomic<int> n{0};
    int c = ++n;
    if (c <= 80 || (c % 20) == 0) {
        char buf[144];
        snprintf(buf, sizeof(buf), "clear ENG InputModel typing #%d reason=%s", c,
                 reason ? reason : "?");
        log_both(buf);
    }
}

struct StdStrView {
    const char *data;
    size_t len;
};

StdStrView parse_std_string(const void *str) {
    StdStrView v{nullptr, 0};
    if (str == nullptr) {
        return v;
    }
    const auto *p = reinterpret_cast<const uint8_t *>(str);
    uint8_t b0 = p[0];
    if ((b0 & 1u) != 0u) {
        // libc++ long: 常见 size@+8 data@+16；兼容 data@+8
        size_t sz = *reinterpret_cast<const size_t *>(p + 8);
        const char *d = *reinterpret_cast<const char *const *>(p + 16);
        if (d == nullptr) {
            d = *reinterpret_cast<const char *const *>(p + 8);
            sz = *reinterpret_cast<const size_t *>(p + 16);
        }
        v.data = d;
        v.len = sz;
        return v;
    }
    v.len = static_cast<size_t>(b0 >> 1);
    v.data = reinterpret_cast<const char *>(p + 1);
    return v;
}

bool is_ascii_alnum(char c) {
    return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
}

/** 英文下拦截「词态缓冲上屏」：非授权提交且含字母数字则丢弃。纯符号放行。 */
bool should_block_english_bulk_commit(const void *str) {
    if (!is_english_ui()) {
        return false;
    }
    if (g_allow_direct_commit.load() > 0) {
        return false;
    }
    StdStrView v = parse_std_string(str);
    if (v.len == 0 || v.data == nullptr) {
        return false;
    }
    // 原始 ButtonEnglishChar::OnButtonUp 通过这条链提交当前单字符；只拦截
    // OIME 残留形成的多字符整词，不能把正常抬键尾链一并吞掉。
    if (v.len == 1) {
        return false;
    }
    bool has_alnum = false;
    size_t n = v.len < 64 ? v.len : 64;
    for (size_t i = 0; i < n; ++i) {
        if (is_ascii_alnum(v.data[i])) {
            has_alnum = true;
            break;
        }
    }
    if (!has_alnum) {
        return false;
    }
    int c = ++g_block_commit;
    char buf[160];
    snprintf(buf, sizeof(buf), "block bulk commit #%d len=%zu head=%.16s", c, v.len, v.data);
    log_both(buf);
    return true;
}

bool commit_button_string(void *button, size_t str_off) {
    if (button == nullptr || str_off == 0) {
        return false;
    }
    void *str = reinterpret_cast<char *>(button) + str_off;
    StdStrView value = parse_std_string(str);
    if (value.data == nullptr || value.len == 0) {
        return false;
    }
    void *bc = BoardController_GetInstance();
    if (bc == nullptr || orig_Board_CommitString == nullptr) {
        return false;
    }
    // 必须在 CommitString 前置位；其内部可能同步触发英文 commit-associate。
    mark_skip_associate();
    g_allow_direct_commit.fetch_add(1);
    orig_Board_CommitString(bc, str);
    g_allow_direct_commit.fetch_sub(1);
    mark_skip_associate();
    if (value.len == 1 && is_ascii_alnum(value.data[0])) {
        g_last_direct_ascii.store(static_cast<unsigned char>(value.data[0]));
        g_last_direct_ascii_at_ms.store(monotonic_ms());
    }
    return true;
}

char ascii_from_keycode(int keycode) {
    int base = keycode & 0xff;
    bool upper = (keycode & 0x1000) != 0;
    if (base >= 'A' && base <= 'Z') {
        return static_cast<char>(upper ? base : base + ('a' - 'A'));
    }
    if (base >= 'a' && base <= 'z') {
        return static_cast<char>(upper ? base - ('a' - 'A') : base);
    }
    return '\0';
}

int64_t monotonic_ms() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000 + ts.tv_nsec / 1000000;
}

bool consume_recent_lower_direct() {
    int64_t at = g_lower_direct_at_ms;
    g_lower_direct_at_ms = 0;
    return at > 0 && monotonic_ms() - at <= 1000;
}

bool commit_ascii_keycode(int keycode, const char *source, bool expect_ui_tail = true) {
    char ch = ascii_from_keycode(keycode);
    if (ch == '\0' || BoardController_GetInstance == nullptr || orig_Board_CommitString == nullptr) {
        return false;
    }
    void *bc = BoardController_GetInstance();
    if (bc == nullptr) {
        return false;
    }
    // libc++ arm64 短字符串：首字节为 len<<1，数据从 +1 开始。
    alignas(16) uint8_t str[32]{};
    str[0] = static_cast<uint8_t>(1u << 1);
    str[1] = static_cast<uint8_t>(ch);
    mark_skip_associate();
    g_allow_direct_commit.fetch_add(1);
    orig_Board_CommitString(bc, str);
    g_allow_direct_commit.fetch_sub(1);
    mark_skip_associate();
    g_last_direct_ascii.store(static_cast<unsigned char>(ch));
    g_last_direct_ascii_at_ms.store(monotonic_ms());
    reset_keyboard_behavior(source);
    clear_english_candidate_state(source);
    // InputModel 兜底通常发生在按键尾链之前，需要消费随后到来的 UI 重复事件；
    // BoardController 已位于 UI 尾链内，不能把标记遗留给下一次物理按键。
    if (expect_ui_tail) {
        g_lower_direct_at_ms = monotonic_ms();
    }
    static std::atomic<int> n{0};
    int c = ++n;
    if (c <= 100 || (c % 20) == 0) {
        char buf[128];
        snprintf(buf, sizeof(buf), "EN keycode fallback direct #%d key=0x%x char=%c via=%s", c,
                 keycode, ch, source ? source : "?");
        log_both(buf);
    }
    return true;
}

/**
 * 1.4.1 在 ButtonChar::OnButtonDown 内就调用 CommitInput 把字母送入英文引擎，
 * 比 OnButtonUp 的直接提交更早。必须在这个源头短路，否则只会隐藏 preedit。
 */
void fake_ButtonChar_CommitInput(void *self, uint64_t point) {
    if (is_english_ui()) {
        static std::atomic<int> n{0};
        log_rate("block ENG ButtonChar::CommitInput preedit source", ++n);
        return;
    }
    if (orig_ButtonChar_CommitInput) {
        orig_ButtonChar_CommitInput(self, point);
    }
}

void fake_OnButtonUp(void *self, uint64_t point) {
    if (orig_OnButtonUp == nullptr) {
        return;
    }
    int mode = current_input_mode();
    int behavior = current_keyboard_behavior();
    int board = current_board_type();
    bool eng = board == 2 || is_english_mode(mode);
    static std::atomic<int> seen{0};
    int n = ++seen;
    if (n <= 120 || (n % 20) == 0) {
        diag_key_snapshot("EngChar", n, mode, behavior, true,
                          eng && behavior == kBehaviorNormal);
    }
    bool already_direct = eng && consume_recent_lower_direct();
    if (already_direct) {
        static std::atomic<int> skipped{0};
        log_rate("EngChar tail after keycode direct", ++skipped);
    }
    if (!eng) {
        orig_OnButtonUp(self, point);
        return;
    }

    /**
     * OnButtonDown 中的 CommitInput 已由源头 Hook 消费，不会进入 OIME。普通文本框
     * 的原始 OnButtonUp 只执行 UI/大小写尾链，所以 behavior=1 需要模块先直提。
     * 密码框例外：1.4.1/1.4.2 原函数在 IsPasswordBox && behavior==1 时会自己
     * CommitString，模块再预提交就会重复上屏。长按 behavior=5 仍不提前提交。
     */
    bool password = is_password_box();
    bool direct = already_direct;
    if (!direct && behavior == kBehaviorNormal && !password) {
        direct = commit_button_string(self, g_commit_str_off);
        static std::atomic<int> direct_count{0};
        if (direct) {
            log_rate("EN direct commit before original tail", ++direct_count);
        } else {
            static std::atomic<int> failed{0};
            log_rate("EN direct commit failed before original tail", ++failed);
        }
    } else if (!direct && behavior == kBehaviorNormal && password) {
        static std::atomic<int> skipped{0};
        log_rate("skip module direct commit in password box", ++skipped);
    }
    orig_OnButtonUp(self, point);
    mark_skip_associate();
    clear_english_candidate_state("EngChar-tail");
}

void fake_ButtonChar_OnButtonUp(void *self, uint64_t point) {
    if (orig_ButtonChar_OnButtonUp == nullptr) {
        return;
    }
    int mode = current_input_mode();
    int behavior = current_keyboard_behavior();
    int board = current_board_type();
    bool eng = board == 2 || is_english_mode(mode);
    static std::atomic<int> seen{0};
    int n = ++seen;
    if (n <= 120 || (n % 20) == 0) {
        diag_key_snapshot("BtnChar", n, mode, behavior, false,
                          eng && behavior == kBehaviorNormal);
    }
    // 基类 OnButtonUp 不负责把普通字符送进 OIME，只维护触摸、气泡和按键状态。
    orig_ButtonChar_OnButtonUp(self, point);
    if (eng) {
        mark_skip_associate();
    }
}

void fake_PushCommit_OnButtonUp(void *self, uint64_t point) {
    if (orig_PushCommit_OnButtonUp == nullptr) {
        return;
    }
    static std::atomic<int> n{0};
    int c = ++n;
    if (c <= 40 || (c % 20) == 0) {
        char buf[128];
        snprintf(buf, sizeof(buf), "PushCommit OnButtonUp#%d mode=%d uiEng=%d", c,
                 current_input_mode(), is_english_ui() ? 1 : 0);
        log_both(buf);
    }
    orig_PushCommit_OnButtonUp(self, point);
    if (is_english_ui()) {
        mark_skip_associate();
    }
}

void fake_ButtonChar_LongPress(void *self, uint64_t point) {
    if (orig_ButtonChar_LongPress == nullptr) {
        return;
    }
    static std::atomic<int> n{0};
    log_rate("BtnChar LongPress", ++n);
    // 原函数会把 keyboard_behavior 设为 5，并由后续 Move/Up 驱动气泡滑选。
    // 在手势结束前不得重置 behavior、隐藏气泡或清候选状态。
    orig_ButtonChar_LongPress(self, point);
}

void fake_English26_Clicked(void *self) {
    bool eng = is_english_ui();
    if (orig_English26_Clicked) {
        // 原逻辑负责在单次 Shift 输入后恢复小写并刷新按键标签；源头 CommitInput
        // 已稳定阻断 OIME，因此不再跳过整条 UI 尾链。
        orig_English26_Clicked(self);
    }
    if (eng) {
        static std::atomic<int> n{0};
        log_rate("English26 clicked tail proceeded", ++n);
        mark_skip_associate();
    }
}

void fake_English26_OnSelectionUpdated(void *self) {
    bool eng = is_english_ui();
    if (orig_English26_OnSelectionUpdated) {
        // 保留布局对光标、Shift 和按键标签的同步；英文联想由 Associate/Candidate
        // 专用 Hook 拦截，不再通过跳过整个布局回调来实现。
        orig_English26_OnSelectionUpdated(self);
    }
    if (eng) {
        static std::atomic<int> n{0};
        log_rate("Eng26 OnSelectionUpdated eng-proceed", ++n);
        mark_skip_associate();
    }
}

/**
 * UI 方法可能通过版本特定的虚表/尾调用绕过 ButtonChar 与 InputModel 的符号入口。
 * BoardController 是两者之间稳定的一层；在这里直接提交 ASCII 字符，确保按键不会
 * 进入英文组词引擎。该调用已位于 UI 尾链内，不设置“等待 UI 重复事件”标记。
 */
void fake_Board_CommitKeycode(void *self, int keycode, const void *point, int flag,
                             uint64_t time, const void *extra) {
    if (is_english_ui() &&
        commit_ascii_keycode(keycode, "BoardController::CommitKeycode", false)) {
        return;
    }
    if (orig_Board_CommitKeycode) {
        orig_Board_CommitKeycode(self, keycode, point, flag, time, extra);
    }
}

void fake_Board_PushCommitKeycode(void *self, int keycode, const void *point, int flag,
                                 uint64_t time, const void *extra) {
    if (is_english_ui() &&
        commit_ascii_keycode(keycode, "BoardController::PushCommitKeycode", false)) {
        return;
    }
    if (orig_Board_PushCommitKeycode) {
        orig_Board_PushCommitKeycode(self, keycode, point, flag, time, extra);
    }
}

/**
 * 最低层按键兜底：任何漏过 UI Hook 的英文 A-Z keycode 都改成单字符 CommitString，
 * 不允许进入 InputModel::Impl::Input/typing 状态。
 */
void fake_InputModel_CommitKeycode(void *self, int keycode, const void *point, int flag,
                                  uint64_t time, const void *extra) {
    if (is_english_ui() && commit_ascii_keycode(keycode, "InputModel::CommitKeycode")) {
        return;
    }
    if (orig_InputModel_CommitKeycode) {
        orig_InputModel_CommitKeycode(self, keycode, point, flag, time, extra);
    }
}

void fake_InputModel_PushCommitKeycode(void *self, int keycode, const void *point, int flag,
                                      uint64_t time, const void *extra, int push_flag) {
    if (is_english_ui() && commit_ascii_keycode(keycode, "InputModel::PushCommitKeycode")) {
        return;
    }
    if (orig_InputModel_PushCommitKeycode) {
        orig_InputModel_PushCommitKeycode(self, keycode, point, flag, time, extra, push_flag);
    }
}

void fake_Impl_Input(void *self, int keycode, const void *point, int flag, uint64_t time,
                     const void *extra, void *callback) {
    if (is_english_ui() && commit_ascii_keycode(keycode, "InputModel::Impl::Input")) {
        return;
    }
    if (orig_Impl_Input) {
        orig_Impl_Input(self, keycode, point, flag, time, extra, callback);
    }
}

void fake_Impl_CommitString(void *self, const void *str, int a, int b, const void *s1,
                            const void *s2, const void *s3, int c) {
    if (should_block_english_bulk_commit(str)) {
        mark_skip_associate();
        clear_english_candidate_state("Impl::CommitString-block");
        return;
    }
    if (orig_Impl_CommitString) {
        orig_Impl_CommitString(self, str, a, b, s1, s2, s3, c);
    }
}

void fake_OnUpdateEnglishPreCommit(void *self) {
    if (is_english_ui()) {
        static std::atomic<int> n{0};
        log_rate("skip OnUpdateEnglish26PreCommit", ++n);
        clear_english_candidate_state("English26PreCommit");
        return;
    }
    if (orig_OnUpdateEnglishPreCommit) {
        orig_OnUpdateEnglishPreCommit(self);
    }
}

void force_candidate_idle(const char *reason) {
    if (CandToolbar_GetInstance == nullptr || Cand_SwitchToIdle == nullptr) {
        return;
    }
    void *center = CandToolbar_GetInstance();
    if (center == nullptr) {
        return;
    }
    Cand_SwitchToIdle(center);
    static std::atomic<int> n{0};
    int c = ++n;
    if (c <= 40 || (c % 10) == 0) {
        char buf[96];
        snprintf(buf, sizeof(buf), "Cand SwitchToIdle #%d reason=%s", c, reason ? reason : "?");
        log_both(buf);
    }
}

void clear_english_candidate_state(const char *reason) {
    if (!is_english_ui()) {
        return;
    }
    static thread_local int reenter = 0;
    if (reenter > 0) {
        return;
    }
    ++reenter;
    if (CandidateRefresh_GetInstance != nullptr && CandidateRefresh_Clear != nullptr) {
        void *manager = CandidateRefresh_GetInstance();
        if (manager != nullptr) {
            CandidateRefresh_Clear(manager);
        }
    }
    if (CorrectionManager_GetInstance != nullptr && CorrectionManager_ClearCorrections != nullptr) {
        void *manager = CorrectionManager_GetInstance();
        if (manager != nullptr) {
            CorrectionManager_ClearCorrections(manager);
        }
    }
    force_candidate_idle(reason);
    static std::atomic<int> n{0};
    int c = ++n;
    if (c <= 80 || (c % 20) == 0) {
        char buf[112];
        snprintf(buf, sizeof(buf), "clear ENG candidate state #%d reason=%s", c,
                 reason ? reason : "?");
        log_both(buf);
    }
    --reenter;
}

/**
 * Java BackspaceSwipeWindow 确认手势后直接进入此 JNI 函数，可能完全绕过
 * ButtonBackspace 与 InputBoxScreenModel 的可 Hook 调用点。action=1 表示上滑清除；
 * 必须在原 JNI 分发读取 IsTyping 前清掉英文镜像，尤其覆盖首次清除后的后续轮次。
 */
void fake_Jni_DoUpClearAction(void *env, void *object, int action, unsigned char flag) {
    bool eng = is_english_ui();
    int typing_before = -1;
    void *im = InputModel_GetInstance ? InputModel_GetInstance() : nullptr;
    if (im != nullptr && InputModel_IsTyping != nullptr) {
        typing_before = InputModel_IsTyping(im);
    }
    if (eng && action == 1) {
        char buf[160];
        snprintf(buf, sizeof(buf), "Jni_DoUpClearAction BEGIN action=%d flag=%u typing=%d", action,
                 static_cast<unsigned int>(flag), typing_before);
        log_both(buf);
        clear_english_engine_state("jni-upclear-before");
        discard_preedit_once("jni-upclear-before");
        clear_english_candidate_state("jni-upclear-before");
        mark_skip_associate();
    }
    int typing_cleared = -1;
    if (im != nullptr && InputModel_IsTyping != nullptr) {
        typing_cleared = InputModel_IsTyping(im);
    }
    if (orig_Jni_DoUpClearAction) {
        orig_Jni_DoUpClearAction(env, object, action, flag);
    }
    if (eng && action == 1) {
        int typing_after = -1;
        im = InputModel_GetInstance ? InputModel_GetInstance() : nullptr;
        if (im != nullptr && InputModel_IsTyping != nullptr) {
            typing_after = InputModel_IsTyping(im);
        }
        char buf[128];
        snprintf(buf, sizeof(buf), "Jni_DoUpClearAction END typing=%d->%d->%d", typing_before,
                 typing_cleared, typing_after);
        log_both(buf);
    }
}

/**
 * InputBoxScreenModel::UpClear 是上滑清除最终且稳定的语义入口。其原始实现先检查
 * InputModel::IsTyping：true 时只清 composition，false 时才调用 ClearTextBeforeCursor。
 * 英文直输已经把字符 commit 到正文，残留 typing 只是不应存在的镜像，因此必须在检查前
 * 同步清除。翻译输入框使用同构的 InputBoxTranslateModel::UpClear，也走同一处理。
 */
void run_input_box_up_clear(void *self, FnVoidSelf original, const char *source) {
    bool eng = is_english_ui();
    int typing_before = -1;
    void *im = InputModel_GetInstance ? InputModel_GetInstance() : nullptr;
    if (im != nullptr && InputModel_IsTyping != nullptr) {
        typing_before = InputModel_IsTyping(im);
    }
    if (eng) {
        char buf[160];
        snprintf(buf, sizeof(buf), "%s BEGIN typing=%d mode=%d board=%d", source, typing_before,
                 current_input_mode(), current_board_type());
        log_both(buf);
        clear_english_engine_state("inputbox-upclear-before");
        discard_preedit_once("inputbox-upclear-before");
        clear_english_candidate_state("inputbox-upclear-before");
        mark_skip_associate();
    }
    int typing_cleared = -1;
    if (im != nullptr && InputModel_IsTyping != nullptr) {
        typing_cleared = InputModel_IsTyping(im);
    }
    if (original) {
        original(self);
    }
    if (eng) {
        int typing_after = -1;
        im = InputModel_GetInstance ? InputModel_GetInstance() : nullptr;
        if (im != nullptr && InputModel_IsTyping != nullptr) {
            typing_after = InputModel_IsTyping(im);
        }
        char buf[160];
        snprintf(buf, sizeof(buf), "%s END typing=%d->%d->%d", source, typing_before,
                 typing_cleared, typing_after);
        log_both(buf);
    }
}

void fake_InputBoxScreen_UpClear(void *self) {
    run_input_box_up_clear(self, orig_InputBoxScreen_UpClear, "InputBoxScreen UpClear");
}

void fake_InputBoxTranslate_UpClear(void *self) {
    run_input_box_up_clear(self, orig_InputBoxTranslate_UpClear, "InputBoxTranslate UpClear");
}

/**
 * 退格键按下是普通退格、长按连续删除和上滑清除共有的最早入口。英文字符已经直接
 * commit，Native 中若仍有 preedit 只能是镜像残留，必须在原始手势状态机读取它之前
 * 清掉；放到 OnButtonUp 或 ShowUpClear 都可能已经被第一次手势消费。
 */
void fake_Backspace_OnButtonDown(void *self, uint64_t point) {
    bool eng = is_english_ui();
    int before = current_keyboard_behavior();
    if (eng) {
        char buf[176];
        snprintf(buf, sizeof(buf),
                 "Backspace OnButtonDown BEGIN behavior=%d point=0x%llx mode=%d board=%d", before,
                 static_cast<unsigned long long>(point), current_input_mode(),
                 current_board_type());
        log_both(buf);
        clear_english_engine_state("backspace-down-before");
        discard_preedit_once("backspace-down-before");
        clear_english_candidate_state("backspace-down-before");
        mark_skip_associate();
    }
    if (orig_Backspace_OnButtonDown) {
        orig_Backspace_OnButtonDown(self, point);
    }
    if (eng) {
        char buf[128];
        snprintf(buf, sizeof(buf), "Backspace OnButtonDown END behavior=%d->%d", before,
                 current_keyboard_behavior());
        log_both(buf);
    }
}

/**
 * 上滑退格的明确触发点。英文直输的字符已经逐个 commit 到编辑器，但豆包输入法内部
 * 仍可能保留一份不可见的 preedit 镜像。原始 ShowUpClear 第一次会优先消费这份镜像，
 * 导致编辑器正文没有被清除；在原逻辑判断前先丢弃镜像，使同一次手势直接进入正文清除。
 * 这里不能 reset keyboard_behavior，该字段仍由当前上滑手势的后续状态机使用。
 */
void fake_Backspace_ShowUpClear(void *self) {
    bool eng = is_english_ui();
    int before = current_keyboard_behavior();
    static std::atomic<int> count{0};
    int n = ++count;
    if (eng) {
        char buf[160];
        snprintf(buf, sizeof(buf),
                 "Backspace ShowUpClear BEGIN #%d behavior=%d mode=%d board=%d", n, before,
                 current_input_mode(), current_board_type());
        log_both(buf);
        clear_english_engine_state("backspace-upclear-before");
        discard_preedit_once("backspace-upclear-before");
        clear_english_candidate_state("backspace-upclear-before");
        mark_skip_associate();
    }
    if (orig_Backspace_ShowUpClear) {
        orig_Backspace_ShowUpClear(self);
    }
    if (eng) {
        char buf[128];
        snprintf(buf, sizeof(buf), "Backspace ShowUpClear END #%d behavior=%d->%d", n, before,
                 current_keyboard_behavior());
        log_both(buf);
    }
}

void fake_Backspace_OnButtonUp(void *self, uint64_t point) {
    bool eng = is_english_ui();
    int before = current_keyboard_behavior();
    if (eng) {
        char buf[160];
        snprintf(buf, sizeof(buf),
                 "Backspace OnButtonUp BEGIN behavior=%d point=0x%llx mode=%d board=%d", before,
                 static_cast<unsigned long long>(point), current_input_mode(),
                 current_board_type());
        log_both(buf);
    }
    if (eng) {
        clear_english_engine_state("backspace-before");
        discard_preedit_once("backspace-before");
    }
    if (orig_Backspace_OnButtonUp) {
        orig_Backspace_OnButtonUp(self, point);
    }
    if (eng) {
        static std::atomic<int> n{0};
        log_rate("Backspace ENG clear-assoc", ++n);
        mark_skip_associate();
        clear_english_candidate_state("backspace");
        reset_keyboard_behavior("backspace");
        char buf[128];
        snprintf(buf, sizeof(buf), "Backspace OnButtonUp END behavior=%d->%d", before,
                 current_keyboard_behavior());
        log_both(buf);
    }
}

void fake_Space_OnButtonUp(void *self, uint64_t point) {
    if (is_english_ui()) {
        // 先丢弃任何异常残留，再让官方逻辑只提交空格。
        clear_english_engine_state("space-before");
        discard_preedit_once("space-before");
        clear_english_candidate_state("space-before");
    }
    if (orig_Space_OnButtonUp) {
        orig_Space_OnButtonUp(self, point);
    }
}

/** 英文候选条刷新入口：点候选无效是因为提交被拦，这里直接不刷新 UI。 */
void fake_Cand_UpdateDisplay(void *self, int a, int b, int c) {
    if (is_english_ui()) {
        static std::atomic<int> n{0};
        log_rate("skip Cand UpdateDisplay", ++n);
        return;
    }
    if (orig_Cand_UpdateDisplay) {
        orig_Cand_UpdateDisplay(self, a, b, c);
    }
}

void fake_Cand_UpdateCandidate(void *self, int a, int b) {
    if (is_english_ui()) {
        static std::atomic<int> n{0};
        log_rate("skip Cand UpdateCandidate", ++n);
        return;
    }
    if (orig_Cand_UpdateCandidate) {
        orig_Cand_UpdateCandidate(self, a, b);
    }
}

void fake_Cand_OnAssociated(void *self, int status, int a, int b) {
    if (is_english_ui()) {
        static std::atomic<int> n{0};
        log_rate("skip Cand OnAssociated", ++n);
        return;
    }
    if (orig_Cand_OnAssociated) {
        orig_Cand_OnAssociated(self, status, a, b);
    }
}

void fake_Cand_UpdateComposition(void *self, int a, int b) {
    if (is_english_ui()) {
        static std::atomic<int> n{0};
        log_rate("skip Cand UpdateComposition", ++n);
        return;
    }
    if (orig_Cand_UpdateComposition) {
        orig_Cand_UpdateComposition(self, a, b);
    }
}

void fake_CandidateRefresh_Notify(void *self, int event) {
    if (is_english_ui()) {
        static std::atomic<int> n{0};
        log_rate("skip CandidateRefresh Notify", ++n);
        return;
    }
    if (orig_CandidateRefresh_Notify) {
        orig_CandidateRefresh_Notify(self, event);
    }
}

void fake_CandidateRefresh_NotifyCommit(void *self) {
    if (is_english_ui()) {
        static std::atomic<int> n{0};
        log_rate("skip CandidateRefresh NotifyCommit", ++n);
        return;
    }
    if (orig_CandidateRefresh_NotifyCommit) {
        orig_CandidateRefresh_NotifyCommit(self);
    }
}

void fake_CandidateContainer_Snapshot(void *self, const void *corrections,
                                      const void *candidates, int a, int b, int c, int d, int e,
                                      int f, int g) {
    if (is_english_ui()) {
        static std::atomic<int> n{0};
        log_rate("block ENG Android candidate snapshot", ++n);
        return;
    }
    if (orig_CandidateContainer_Snapshot) {
        orig_CandidateContainer_Snapshot(self, corrections, candidates, a, b, c, d, e, f, g);
    }
}

void fake_CandidateComposition_Update(void *self, int a, int b) {
    if (is_english_ui()) {
        static std::atomic<int> n{0};
        log_rate("skip CandidateComposition UpdateComp", ++n);
        return;
    }
    if (orig_CandidateComposition_Update) {
        orig_CandidateComposition_Update(self, a, b);
    }
}

void fake_AssociateOuter(void *self, int flag, const void *str) {
    if (should_bypass_english_associate()) {
        note_assoc_bypass("AssociateOuter");
        return;
    }
    if (orig_AssociateOuter) {
        orig_AssociateOuter(self, flag, str);
    }
}

int fake_ImplAssociate1(void *a0, void *a1, void *a2, void *a3, void *a4, void *a5, void *a6,
                        void *a7) {
    if (should_bypass_english_associate()) {
        note_assoc_bypass("ImplAssociate#1");
        return 0;
    }
    return orig_ImplAssociate1 ? orig_ImplAssociate1(a0, a1, a2, a3, a4, a5, a6, a7) : 0;
}

int fake_ImplAssociate2(void *a0, void *a1, void *a2, void *a3, void *a4, void *a5, void *a6,
                        void *a7) {
    if (should_bypass_english_associate()) {
        note_assoc_bypass("ImplAssociate#2");
        return 0;
    }
    return orig_ImplAssociate2 ? orig_ImplAssociate2(a0, a1, a2, a3, a4, a5, a6, a7) : 0;
}

void fake_AssociateSelectText(void *self, const void *selected, const void *nbest,
                              const void *context, int a, int b, const void *before,
                              const void *after) {
    if (should_bypass_english_associate()) {
        note_assoc_bypass("AssociateSelectText");
        return;
    }
    if (orig_AssociateSelectText) {
        orig_AssociateSelectText(self, selected, nbest, context, a, b, before, after);
    }
}

void fake_ImplAssociateSelectText(void *self, const void *selected, const void *nbest,
                                  const void *context, int a, int b, const void *before,
                                  const void *after) {
    if (should_bypass_english_associate()) {
        note_assoc_bypass("Impl::AssociateSelectText");
        return;
    }
    if (orig_ImplAssociateSelectText) {
        orig_ImplAssociateSelectText(self, selected, nbest, context, a, b, before, after);
    }
}

void fake_OnAssociate(void *self, int a, int b, int c) {
    if (should_bypass_english_associate()) {
        note_assoc_bypass("OnAssociate");
        return;
    }
    if (orig_OnAssociate) {
        orig_OnAssociate(self, a, b, c);
    }
}

void fake_BoardAssociate(void *self) {
    if (should_bypass_english_associate()) {
        note_assoc_bypass("BoardAssociate");
        return;
    }
    if (orig_BoardAssociate) {
        orig_BoardAssociate(self);
    }
}

void fake_NotifyUpdateAssociations(void *self) {
    if (should_bypass_english_associate()) {
        note_assoc_bypass("NotifyUpdateAssociations");
        return;
    }
    if (orig_NotifyUpdateAssociations) {
        orig_NotifyUpdateAssociations(self);
    }
}

void fake_Board_FinishPreedit(void *self, int commit, int flag2) {
    static thread_local int reenter = 0;
    if (reenter > 0) {
        if (orig_Board_FinishPreedit) {
            orig_Board_FinishPreedit(self, commit, flag2);
        }
        return;
    }
    // 英文：禁止 commit=true 把残留 composing 冲进编辑器；不主动 ClearInput
    if (is_english_ui() && commit) {
        static std::atomic<int> n{0};
        char buf[96];
        snprintf(buf, sizeof(buf), "FinishPreedit eng discard-commit #%d", ++n);
        log_both(buf);
        ++reenter;
        if (orig_Board_FinishPreedit) {
            orig_Board_FinishPreedit(self, 0, flag2);
        }
        --reenter;
        mark_skip_associate();
        return;
    }
    if (orig_Board_FinishPreedit) {
        orig_Board_FinishPreedit(self, commit, flag2);
    }
}

/** 英文禁止非空预编辑上屏（根治 sticky 缓冲下划线）；空串仍放行以清 UI。 */
void fake_Board_UpdatePreedit(void *self, const void *str) {
    if (is_english_ui()) {
        StdStrView v = parse_std_string(str);
        if (v.len == 0) {
            if (orig_Board_UpdatePreedit) {
                orig_Board_UpdatePreedit(self, str);
            }
            return;
        }
        static std::atomic<int> n{0};
        int c = ++n;
        if (c <= 80 || (c % 10) == 0) {
            char buf[160];
            snprintf(buf, sizeof(buf), "block ENG Board::UpdatePreedit #%d len=%zu head=%.16s", c,
                     v.len, v.data ? v.data : "");
            log_both(buf);
        }
        clear_english_candidate_state("Board::UpdatePreedit-leak");
        return;
    }
    if (orig_Board_UpdatePreedit) {
        orig_Board_UpdatePreedit(self, str);
    }
}

/**
 * 英文词态泄漏出口：正常直提不应到这里；一旦命中就吞掉并清理残留状态。
 * 字母上屏只走 ButtonChar 直提或 InputModel keycode 兜底。
 */
void fake_Callback_UpdatePreedit(void *self, const void *str) {
    if (!is_english_ui()) {
        if (orig_Callback_UpdatePreedit) {
            orig_Callback_UpdatePreedit(self, str);
        }
        return;
    }
    StdStrView v = parse_std_string(str);
    if (v.len == 0) {
        if (orig_Callback_UpdatePreedit) {
            orig_Callback_UpdatePreedit(self, str);
        }
        mark_skip_associate();
        return;
    }
    static std::atomic<int> n{0};
    int c = ++n;
    if (c <= 80 || (c % 10) == 0) {
        char buf[160];
        snprintf(buf, sizeof(buf), "swallow ENG Callback::UpdatePreedit #%d len=%zu head=%.16s", c,
                 v.len, v.data ? v.data : "");
        log_both(buf);
    }
    mark_skip_associate();
    clear_english_candidate_state("Callback::UpdatePreedit-leak");
    /**
     * 正常直提不应再到这里。若版本特定的虚表入口仍把字符送进引擎，就在 Native
     * 回调栈内只救援累计串最后一个 ASCII 字符，并立即真正 Clear InputModel。
     * 这里处于豆包输入法自身的 Native 调用上下文，避免从模块 JNI 调用栈 Clear
     * 导致 FindClass 使用错误 ClassLoader。
     */
    static thread_local int rescue_reenter = 0;
    if (rescue_reenter == 0 && v.len > 0 && v.data != nullptr) {
        char last = v.data[v.len - 1];
        if (is_ascii_alnum(last)) {
            int64_t now = monotonic_ms();
            bool already_direct =
                    g_last_direct_ascii.load() == static_cast<unsigned char>(last) &&
                    now - g_last_direct_ascii_at_ms.load() <= 500;
            ++rescue_reenter;
            if (!already_direct && BoardController_GetInstance != nullptr &&
                orig_Board_CommitString != nullptr) {
                void *bc = BoardController_GetInstance();
                if (bc != nullptr) {
                    alignas(16) uint8_t one[32]{};
                    one[0] = static_cast<uint8_t>(1u << 1);
                    one[1] = static_cast<uint8_t>(last);
                    g_allow_direct_commit.fetch_add(1);
                    orig_Board_CommitString(bc, one);
                    g_allow_direct_commit.fetch_sub(1);
                    g_last_direct_ascii.store(static_cast<unsigned char>(last));
                    g_last_direct_ascii_at_ms.store(now);
                    char buf[144];
                    snprintf(buf, sizeof(buf),
                             "ENG native leak rescue char=%c preeditLen=%zu rescueCount=%d", last,
                             v.len, g_native_leak_rescue_count.load() + 1);
                    log_both(buf);
                }
            } else if (already_direct) {
                char buf[128];
                snprintf(buf, sizeof(buf),
                         "ENG native leak skip duplicate char=%c preeditLen=%zu", last, v.len);
                log_both(buf);
            }
            clear_english_engine_state("Callback::UpdatePreedit-leak");
            g_native_leak_rescue_count.fetch_add(1);
            --rescue_reenter;
        } else {
            clear_english_engine_state("Callback::UpdatePreedit-nonalnum-leak");
            g_native_leak_rescue_count.fetch_add(1);
        }
    }
}

void fake_Board_CommitString(void *self, const void *str) {
    if (should_block_english_bulk_commit(str)) {
        mark_skip_associate();
        return;
    }
    if (orig_Board_CommitString) {
        orig_Board_CommitString(self, str);
    }
}

/** 上滑符号主路径：内部会 GetCompOrg 拼「词态+符号」。英文只提交符号本身。 */
void fake_Board_CommitAppendSymbol(void *self, const void *symbol, int flag) {
    if (is_english_ui()) {
        static std::atomic<int> n{0};
        StdStrView v = parse_std_string(symbol);
        char buf[160];
        snprintf(buf, sizeof(buf), "CommitAppendSymbol ENG symbol-only #%d len=%zu head=%.8s",
                 ++n, v.len, v.data ? v.data : "");
        log_both(buf);
        if (orig_Board_CommitString != nullptr && v.len > 0) {
            g_allow_direct_commit.fetch_add(1);
            orig_Board_CommitString(self, symbol);
            g_allow_direct_commit.fetch_sub(1);
        }
        mark_skip_associate();
        return;
    }
    if (orig_Board_CommitAppendSymbol) {
        orig_Board_CommitAppendSymbol(self, symbol, flag);
    }
}

void fake_Board_CommitSymbol(void *self, const void *symbol, int flag) {
    if (is_english_ui()) {
        static std::atomic<int> n{0};
        StdStrView v = parse_std_string(symbol);
        char buf[128];
        snprintf(buf, sizeof(buf), "CommitSymbol ENG #%d len=%zu head=%.8s", ++n, v.len,
                 v.data ? v.data : "");
        log_both(buf);
        // 走原 CommitSymbol 一般不拼 comp；仍 mark_skip 防联想
        if (orig_Board_CommitSymbol) {
            g_allow_direct_commit.fetch_add(1);
            orig_Board_CommitSymbol(self, symbol, flag);
            g_allow_direct_commit.fetch_sub(1);
        }
        mark_skip_associate();
        return;
    }
    if (orig_Board_CommitSymbol) {
        orig_Board_CommitSymbol(self, symbol, flag);
    }
}

void fake_DoCommit(void *self, const void *str, int flag, const void *a, const void *b,
                   const void *c) {
    if (should_block_english_bulk_commit(str)) {
        mark_skip_associate();
        return;
    }
    if (orig_DoCommit) {
        orig_DoCommit(self, str, flag, a, b, c);
    }
}

void fake_InputModel_SetInputMode(void *self, int mode, int a, int b) {
    int before = current_input_mode();
    if (is_english_mode(before) && !is_english_mode(mode)) {
        clear_english_engine_state("SetInputMode-leave-eng-before");
        discard_preedit_once("SetInputMode-leave-eng-before");
    }
    if (orig_InputModel_SetInputMode) {
        orig_InputModel_SetInputMode(self, mode, a, b);
    }
    int after = current_input_mode();
    char buf[192];
    snprintf(buf, sizeof(buf),
             "DIAG SetInputMode arg=%d before=%d after=%d boardType=%d engArg=%d", mode, before,
             after, current_board_type(), is_english_mode(mode) ? 1 : 0);
    log_both(buf);
    diag_note_mode_change("InputModel::SetInputMode", mode, is_english_mode(mode));
}

void fake_WindowBoard_SetBoardTypeMode(void *self, int mode, int flag) {
    int before = current_board_type();
    if (before == 2 && !is_english_mode(mode)) {
        clear_english_engine_state("SetBoardTypeMode-leave-eng-before");
        discard_preedit_once("SetBoardTypeMode-leave-eng-before");
    }
    if (orig_WindowBoard_SetBoardTypeMode) {
        orig_WindowBoard_SetBoardTypeMode(self, mode, flag);
    }
    int after = current_board_type();
    char buf[192];
    snprintf(buf, sizeof(buf),
             "DIAG SetBoardType(InputMode) arg=%d flag=%d boardTypeNow=%d inputMode=%d engArg=%d",
             mode, flag, after, current_input_mode(), is_english_mode(mode) ? 1 : 0);
    log_both(buf);
    // 注意：此处 arg 未必等于 InputMode 英文=2；只记日志，不用它改行为。
    diag_note_mode_change("WindowBoard::SetBoardType", mode, is_english_mode(mode));
    // 仅 input ready 后、且板型真正切换时清词态；InitWindow 时 g_input_ready=false
    if (before != 2 && after == 2) {
        discard_preedit_once("enter-eng-SetBoardType(InputMode)");
    }
}

void fake_WindowBoard_SetBoardTypeBoard(void *self, int board, int flag) {
    int before = current_board_type();
    if (before == 2 && board != 2) {
        clear_english_engine_state("SetBoardTypeBoard-leave-eng-before");
        discard_preedit_once("SetBoardTypeBoard-leave-eng-before");
    }
    if (orig_WindowBoard_SetBoardTypeBoard) {
        orig_WindowBoard_SetBoardTypeBoard(self, board, flag);
    }
    int after = current_board_type();
    char buf[192];
    snprintf(buf, sizeof(buf),
             "DIAG SetBoardType(InputBoardType) arg=%d flag=%d before=%d after=%d mode=%d", board,
             flag, before, after, current_input_mode());
    log_both(buf);
    diag_note_mode_change("WindowBoard::SetBoardType(Board)", after, after == 2);
    if (before != 2 && after == 2) {
        discard_preedit_once("enter-eng-SetBoardType(Board)");
    }
}

void force_no_keep_composition(const char *reason) {
    if (InputModel_SetKeepComposition == nullptr || InputModel_GetInstance == nullptr) {
        return;
    }
    void *im = InputModel_GetInstance();
    if (im == nullptr) {
        return;
    }
    // 直接调原函数（若已 hook 则走 fake，仍强制 false）
    if (orig_SetKeepComposition) {
        orig_SetKeepComposition(im, 0);
    } else {
        InputModel_SetKeepComposition(im, 0);
    }
    char buf[128];
    snprintf(buf, sizeof(buf), "force KeepComposition=0 reason=%s", reason ? reason : "?");
    log_both(buf);
}

void fake_SetKeepComposition(void *self, int keep) {
    // 英文切换时禁止保留中文 composing（官方 GetKeepCompositionOnEnglishSwitch）
    if (keep && is_english_ui()) {
        static std::atomic<int> n{0};
        char buf[96];
        snprintf(buf, sizeof(buf), "block KeepComposition=1 #%d", ++n);
        log_both(buf);
        keep = 0;
    }
    if (orig_SetKeepComposition) {
        orig_SetKeepComposition(self, keep);
    }
}

void fake_SwitchBoard_OnButtonUp(void *self, uint64_t point) {
    char buf[160];
    snprintf(buf, sizeof(buf), "DIAG SwitchBoard OnButtonUp BEFORE board=%d mode=%d",
             current_board_type(), current_input_mode());
    log_both(buf);
    if (is_english_ui()) {
        clear_english_engine_state("SwitchBoard-before");
        discard_preedit_once("SwitchBoard-before");
    }
    if (orig_SwitchBoard_OnButtonUp) {
        orig_SwitchBoard_OnButtonUp(self, point);
    }
    snprintf(buf, sizeof(buf), "DIAG SwitchBoard OnButtonUp AFTER board=%d mode=%d",
             current_board_type(), current_input_mode());
    log_both(buf);
    if (is_english_ui()) {
        force_no_keep_composition("SwitchBoard");
        discard_preedit_once("SwitchBoard");
        clear_english_candidate_state("SwitchBoard");
    }
}

void fake_SwitchCnEn_OnButtonUp(void *self, uint64_t point) {
    char buf[192];
    snprintf(buf, sizeof(buf),
             "DIAG SwitchCnEn OnButtonUp BEFORE mode=%d boardType=%d behavior=%d",
             current_input_mode(), current_board_type(), current_keyboard_behavior());
    log_both(buf);
    if (is_english_ui()) {
        clear_english_engine_state("SwitchCnEn-before");
        discard_preedit_once("SwitchCnEn-before");
        clear_english_candidate_state("SwitchCnEn-before");
    }
    if (orig_SwitchCnEn_OnButtonUp) {
        orig_SwitchCnEn_OnButtonUp(self, point);
    }
    snprintf(buf, sizeof(buf),
             "DIAG SwitchCnEn OnButtonUp AFTER mode=%d boardType=%d behavior=%d",
             current_input_mode(), current_board_type(), current_keyboard_behavior());
    log_both(buf);
    if (is_english_ui()) {
        force_no_keep_composition("SwitchCnEn");
        // 进入英文后清掉旧中文拼音；离开英文时已在调用原切换逻辑前清完英文词态。
        // 切回中文后不得再 FinishPreedit/MarkSkip，否则会吞掉中文联想。
        discard_preedit_once("SwitchCnEn");
        clear_english_candidate_state("SwitchCnEn");
    }
}

bool hook_addr(const char *name, void *target, void *proxy, void **orig) {
    void *stub = shadowhook_hook_func_addr(target, proxy, orig);
    if (stub == nullptr) {
        char buf[192];
        snprintf(buf, sizeof(buf), "hook FAIL %s errno=%d %s", name, shadowhook_get_errno(),
                 shadowhook_to_errmsg(shadowhook_get_errno()));
        log_both(buf);
        return false;
    }
    char buf[160];
    snprintf(buf, sizeof(buf), "hook OK %s @%p", name, target);
    log_both(buf);
    return true;
}

}  // namespace

extern "C" int noensuggest_install_hooks(void) {
    if (g_ready.load()) {
        return 0;
    }
    bool expected = false;
    if (!g_installing.compare_exchange_strong(expected, true)) {
        return g_ready.load() ? 0 : -1;
    }

    log_both("noensuggest_install_hooks begin (ELF dynsym v30 shadowhook-unique)");

    /**
     * 本模块每个 native 地址只安装一个代理，并在代理内通过 orig_* trampoline
     * 调用原函数，必须使用 unique 模式。
     *
     * 历史版本误用 shared 模式，却没有在所有代理入口调用
     * SHADOWHOOK_STACK_SCOPE/SHADOWHOOK_POP_STACK，也没有用 SHADOWHOOK_CALL_PREV。
     * ShadowHook 会因此保留当前线程的 proxy call state：同一 Hook 首次命中后，
     * 后续调用被当成循环重入并直接绕过全部代理。日志表现为各按键 Hook 通常只出现
     * “#1”，随后大小写字符重新进入 OIME 并持续累计隐藏 preedit。
     *
     * unique 模式与现有 orig_* 调用模型完全一致，也避免几十个代理遗漏栈清理。
     */
    int init_ret = shadowhook_init(SHADOWHOOK_MODE_UNIQUE, false);
    if (init_ret != 0) {
        char buf[96];
        snprintf(buf, sizeof(buf), "shadowhook_init failed ret=%d", init_ret);
        log_both(buf);
        g_installing.store(false);
        return init_ret;
    }

    if (!resolve_symbols()) {
        g_installing.store(false);
        return -2;
    }

    bool ok = true;
    auto do_hook = [&](const char *name, void *target, void *proxy, void **orig, bool required) {
        if (target == nullptr) {
            char buf[128];
            snprintf(buf, sizeof(buf), "%s %s (null target)", required ? "hook FAIL" : "hook SKIP",
                     name);
            log_both(buf);
            if (required) {
                ok = false;
            }
            return;
        }
        if (!hook_addr(name, target, proxy, orig) && required) {
            ok = false;
        }
    };

    do_hook("ButtonEnglishChar::OnButtonUp", sym_EngChar_OnButtonUp,
            reinterpret_cast<void *>(fake_OnButtonUp), reinterpret_cast<void **>(&orig_OnButtonUp),
            true);
    do_hook("ButtonChar::OnButtonUp", sym_BtnChar_OnButtonUp,
            reinterpret_cast<void *>(fake_ButtonChar_OnButtonUp),
            reinterpret_cast<void **>(&orig_ButtonChar_OnButtonUp), true);
    do_hook("ButtonChar::CommitInput", sym_BtnChar_CommitInput,
            reinterpret_cast<void *>(fake_ButtonChar_CommitInput),
            reinterpret_cast<void **>(&orig_ButtonChar_CommitInput), true);
    do_hook("BoardController::CommitKeycode", sym_Board_CommitKeycode,
            reinterpret_cast<void *>(fake_Board_CommitKeycode),
            reinterpret_cast<void **>(&orig_Board_CommitKeycode), false);
    do_hook("BoardController::PushCommitKeycode", sym_Board_PushCommitKeycode,
            reinterpret_cast<void *>(fake_Board_PushCommitKeycode),
            reinterpret_cast<void **>(&orig_Board_PushCommitKeycode), false);
    do_hook("InputModel::CommitKeycode", sym_InputModel_CommitKeycode,
            reinterpret_cast<void *>(fake_InputModel_CommitKeycode),
            reinterpret_cast<void **>(&orig_InputModel_CommitKeycode), true);
    do_hook("InputModel::PushCommitKeycode", sym_InputModel_PushCommitKeycode,
            reinterpret_cast<void *>(fake_InputModel_PushCommitKeycode),
            reinterpret_cast<void **>(&orig_InputModel_PushCommitKeycode), true);
    do_hook("InputModel::Impl::Input", sym_Impl_Input, reinterpret_cast<void *>(fake_Impl_Input),
            reinterpret_cast<void **>(&orig_Impl_Input), true);
    do_hook("InputModel::Impl::CommitString", sym_Impl_CommitString,
            reinterpret_cast<void *>(fake_Impl_CommitString),
            reinterpret_cast<void **>(&orig_Impl_CommitString), true);
    do_hook("InputModel::OnUpdateEnglish26PreCommit", sym_OnUpdateEnglishPreCommit,
            reinterpret_cast<void *>(fake_OnUpdateEnglishPreCommit),
            reinterpret_cast<void **>(&orig_OnUpdateEnglishPreCommit), false);
    do_hook("ButtonEnglishPushCommit::OnButtonUp", sym_PushCommit_OnButtonUp,
            reinterpret_cast<void *>(fake_PushCommit_OnButtonUp),
            reinterpret_cast<void **>(&orig_PushCommit_OnButtonUp), false);
    do_hook("ButtonChar::OnButtonLongPress", sym_BtnChar_LongPress,
            reinterpret_cast<void *>(fake_ButtonChar_LongPress),
            reinterpret_cast<void **>(&orig_ButtonChar_LongPress), false);
    do_hook("English26Layout::OnButtonEnglishCharClicked", sym_English26_Clicked,
            reinterpret_cast<void *>(fake_English26_Clicked),
            reinterpret_cast<void **>(&orig_English26_Clicked), true);
    do_hook("English26Layout::OnSelectionUpdated", sym_English26_OnSelectionUpdated,
            reinterpret_cast<void *>(fake_English26_OnSelectionUpdated),
            reinterpret_cast<void **>(&orig_English26_OnSelectionUpdated), false);
    do_hook("Jni_DoUpClearAction", sym_Jni_DoUpClearAction,
            reinterpret_cast<void *>(fake_Jni_DoUpClearAction),
            reinterpret_cast<void **>(&orig_Jni_DoUpClearAction), false);
    do_hook("InputBoxScreenModel::UpClear", sym_InputBoxScreen_UpClear,
            reinterpret_cast<void *>(fake_InputBoxScreen_UpClear),
            reinterpret_cast<void **>(&orig_InputBoxScreen_UpClear), false);
    do_hook("InputBoxTranslateModel::UpClear", sym_InputBoxTranslate_UpClear,
            reinterpret_cast<void *>(fake_InputBoxTranslate_UpClear),
            reinterpret_cast<void **>(&orig_InputBoxTranslate_UpClear), false);
    do_hook("ButtonBackspace::OnButtonDown", sym_Backspace_OnButtonDown,
            reinterpret_cast<void *>(fake_Backspace_OnButtonDown),
            reinterpret_cast<void **>(&orig_Backspace_OnButtonDown), false);
    do_hook("ButtonBackspace::ShowUpClear", sym_Backspace_ShowUpClear,
            reinterpret_cast<void *>(fake_Backspace_ShowUpClear),
            reinterpret_cast<void **>(&orig_Backspace_ShowUpClear), false);
    do_hook("ButtonBackspace::OnButtonUp", sym_Backspace_OnButtonUp,
            reinterpret_cast<void *>(fake_Backspace_OnButtonUp),
            reinterpret_cast<void **>(&orig_Backspace_OnButtonUp), false);
    do_hook("ButtonSpace::OnButtonUp", sym_Space_OnButtonUp,
            reinterpret_cast<void *>(fake_Space_OnButtonUp),
            reinterpret_cast<void **>(&orig_Space_OnButtonUp), true);
    do_hook("CandidateToolbarCenter::UpdateCandidateDisplay", sym_Cand_UpdateDisplay,
            reinterpret_cast<void *>(fake_Cand_UpdateDisplay),
            reinterpret_cast<void **>(&orig_Cand_UpdateDisplay), false);
    do_hook("CandidateToolbarCenter::UpdateCandidate", sym_Cand_UpdateCandidate,
            reinterpret_cast<void *>(fake_Cand_UpdateCandidate),
            reinterpret_cast<void **>(&orig_Cand_UpdateCandidate), false);
    do_hook("CandidateToolbarCenter::OnAssociated", sym_Cand_OnAssociated,
            reinterpret_cast<void *>(fake_Cand_OnAssociated),
            reinterpret_cast<void **>(&orig_Cand_OnAssociated), false);
    do_hook("CandidateToolbarCenter::UpdateComposition", sym_Cand_UpdateComposition,
            reinterpret_cast<void *>(fake_Cand_UpdateComposition),
            reinterpret_cast<void **>(&orig_Cand_UpdateComposition), false);
    do_hook("CandidateRefreshManager::NotifyRefreshListener", sym_CandidateRefresh_Notify,
            reinterpret_cast<void *>(fake_CandidateRefresh_Notify),
            reinterpret_cast<void **>(&orig_CandidateRefresh_Notify), false);
    do_hook("CandidateRefreshManager::NotifyCommitStringListeners",
            sym_CandidateRefresh_NotifyCommit,
            reinterpret_cast<void *>(fake_CandidateRefresh_NotifyCommit),
            reinterpret_cast<void **>(&orig_CandidateRefresh_NotifyCommit), false);
    do_hook("CandidateContainerCenter::BuildAndPushAndroidSnapshot",
            sym_CandidateContainer_Snapshot,
            reinterpret_cast<void *>(fake_CandidateContainer_Snapshot),
            reinterpret_cast<void **>(&orig_CandidateContainer_Snapshot), true);
    do_hook("CandidateCompositionCenter::UpdateComp", sym_CandidateComposition_Update,
            reinterpret_cast<void *>(fake_CandidateComposition_Update),
            reinterpret_cast<void **>(&orig_CandidateComposition_Update), false);
    do_hook("InputModel::Associate", sym_AssociateOuter,
            reinterpret_cast<void *>(fake_AssociateOuter),
            reinterpret_cast<void **>(&orig_AssociateOuter), true);
    do_hook("InputModel::Impl::Associate#1", sym_ImplAssociate1,
            reinterpret_cast<void *>(fake_ImplAssociate1),
            reinterpret_cast<void **>(&orig_ImplAssociate1), true);
    do_hook("InputModel::Impl::Associate#2", sym_ImplAssociate2,
            reinterpret_cast<void *>(fake_ImplAssociate2),
            reinterpret_cast<void **>(&orig_ImplAssociate2), true);
    do_hook("InputModel::AssociateSelectText", sym_AssociateSelectText,
            reinterpret_cast<void *>(fake_AssociateSelectText),
            reinterpret_cast<void **>(&orig_AssociateSelectText), true);
    do_hook("InputModel::Impl::AssociateSelectText", sym_ImplAssociateSelectText,
            reinterpret_cast<void *>(fake_ImplAssociateSelectText),
            reinterpret_cast<void **>(&orig_ImplAssociateSelectText), true);
    do_hook("InputModel::OnAssociate", sym_OnAssociate, reinterpret_cast<void *>(fake_OnAssociate),
            reinterpret_cast<void **>(&orig_OnAssociate), false);
    do_hook("BoardController::Associate", sym_BoardAssociate,
            reinterpret_cast<void *>(fake_BoardAssociate),
            reinterpret_cast<void **>(&orig_BoardAssociate), false);
    do_hook("KeyboardCallbackImpl::NotifyUpdateAssociations", sym_NotifyUpdateAssociations,
            reinterpret_cast<void *>(fake_NotifyUpdateAssociations),
            reinterpret_cast<void **>(&orig_NotifyUpdateAssociations), false);
    do_hook("BoardController::FinishPreedit", sym_Board_FinishPreedit,
            reinterpret_cast<void *>(fake_Board_FinishPreedit),
            reinterpret_cast<void **>(&orig_Board_FinishPreedit), true);
    do_hook("BoardController::UpdatePreedit", sym_Board_UpdatePreedit,
            reinterpret_cast<void *>(fake_Board_UpdatePreedit),
            reinterpret_cast<void **>(&orig_Board_UpdatePreedit), false);
    do_hook("KeyboardCallbackImpl::UpdatePreedit", sym_Callback_UpdatePreedit,
            reinterpret_cast<void *>(fake_Callback_UpdatePreedit),
            reinterpret_cast<void **>(&orig_Callback_UpdatePreedit), true);
    do_hook("BoardController::CommitString", sym_Board_CommitString,
            reinterpret_cast<void *>(fake_Board_CommitString),
            reinterpret_cast<void **>(&orig_Board_CommitString), true);
    do_hook("BoardController::CommitAppendSymbol", sym_Board_CommitAppendSymbol,
            reinterpret_cast<void *>(fake_Board_CommitAppendSymbol),
            reinterpret_cast<void **>(&orig_Board_CommitAppendSymbol), true);
    do_hook("BoardController::CommitSymbol", sym_Board_CommitSymbol,
            reinterpret_cast<void *>(fake_Board_CommitSymbol),
            reinterpret_cast<void **>(&orig_Board_CommitSymbol), false);
    do_hook("KeyboardCallbackImpl::DoCommit", sym_DoCommit,
            reinterpret_cast<void *>(fake_DoCommit), reinterpret_cast<void **>(&orig_DoCommit),
            true);
    do_hook("InputModel::SetInputMode", sym_InputModel_SetInputMode,
            reinterpret_cast<void *>(fake_InputModel_SetInputMode),
            reinterpret_cast<void **>(&orig_InputModel_SetInputMode), false);
    do_hook("WindowBoardView::SetBoardType(InputMode)", sym_WindowBoard_SetBoardTypeMode,
            reinterpret_cast<void *>(fake_WindowBoard_SetBoardTypeMode),
            reinterpret_cast<void **>(&orig_WindowBoard_SetBoardTypeMode), false);
    do_hook("WindowBoardView::SetBoardType(InputBoardType)", sym_WindowBoard_SetBoardTypeBoard,
            reinterpret_cast<void *>(fake_WindowBoard_SetBoardTypeBoard),
            reinterpret_cast<void **>(&orig_WindowBoard_SetBoardTypeBoard), false);
    do_hook("ButtonSwitchChineseEnglish::OnButtonUp", sym_SwitchCnEn_OnButtonUp,
            reinterpret_cast<void *>(fake_SwitchCnEn_OnButtonUp),
            reinterpret_cast<void **>(&orig_SwitchCnEn_OnButtonUp), false);
    do_hook("ButtonSwitchBoard::OnButtonUp", sym_SwitchBoard_OnButtonUp,
            reinterpret_cast<void *>(fake_SwitchBoard_OnButtonUp),
            reinterpret_cast<void **>(&orig_SwitchBoard_OnButtonUp), false);
    do_hook("InputModel::SetKeepCompositionOnEnglishSwitch", sym_SetKeepComposition,
            reinterpret_cast<void *>(fake_SetKeepComposition),
            reinterpret_cast<void **>(&orig_SetKeepComposition), false);

    {
        char buf[160];
        snprintf(buf, sizeof(buf), "DIAG install snapshot mode=%d boardType=%d",
                 current_input_mode(), current_board_type());
        log_both(buf);
    }

    if (ok && orig_OnButtonUp != nullptr && orig_Board_CommitString != nullptr &&
        orig_Board_CommitAppendSymbol != nullptr) {
        g_ready.store(true);
        log_both("noensuggest_install_hooks SUCCESS (all required gates active)");
        g_installing.store(false);
        return 0;
    }

    log_both("noensuggest_install_hooks FAIL");
    g_installing.store(false);
    return -3;
}

extern "C" int noensuggest_is_ready(void) {
    return g_ready.load() ? 1 : 0;
}

extern "C" void noensuggest_set_logging_enabled(int enabled) {
    g_logging_enabled.store(enabled != 0);
}

extern "C" void noensuggest_clear_english_typing_buffer(void) {
    if (!g_ready.load()) {
        return;
    }
    // Java/Xposed 调用栈中直接 InputModel::Clear 会让 libkeyboard 的 FindClass
    // 使用模块 ClassLoader 并 SIGABRT；这里只做不会回调 KeyboardJni 的清理。
    discard_preedit_once("jni-clear");
    clear_english_candidate_state("jni-clear");
}

extern "C" void noensuggest_mark_input_ready(int ready) {
    bool v = ready != 0;
    g_input_ready.store(v);
    char buf[64];
    snprintf(buf, sizeof(buf), "input_ready=%d", v ? 1 : 0);
    log_both(buf);
}

extern "C" void noensuggest_force_password_box(int enable) {
    if (!g_ready.load()) {
        return;
    }
    if (InputBoxScreenModel_GetInstance == nullptr || InputBoxScreenModel_SetPasswordBox == nullptr) {
        log_both("forcePasswordBox helpers null");
        return;
    }
    void *box = InputBoxScreenModel_GetInstance();
    if (box == nullptr) {
        log_both("forcePasswordBox InputBox null");
        return;
    }
    int before = InputBoxScreenModel_IsPasswordBox ? InputBoxScreenModel_IsPasswordBox(box) : -1;
    InputBoxScreenModel_SetPasswordBox(box, enable ? 1 : 0);
    int after = InputBoxScreenModel_IsPasswordBox ? InputBoxScreenModel_IsPasswordBox(box) : -1;
    char buf[128];
    snprintf(buf, sizeof(buf), "forcePasswordBox enable=%d before=%d after=%d", enable, before,
             after);
    log_both(buf);
}

extern "C" int noensuggest_is_english_ui(void) {
    if (!g_ready.load()) {
        return 0;
    }
    return is_english_ui() ? 1 : 0;
}

extern "C" int noensuggest_get_board_type(void) {
    if (!g_ready.load()) {
        return -1;
    }
    return current_board_type();
}

extern "C" int noensuggest_get_input_mode(void) {
    if (!g_ready.load()) {
        return -1;
    }
    return current_input_mode();
}
