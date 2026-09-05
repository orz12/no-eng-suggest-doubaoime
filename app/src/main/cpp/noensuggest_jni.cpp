#include <jni.h>

#include "noensuggest_hooks.h"

static jint nativeInstall(JNIEnv *, jclass) {
    return noensuggest_install_hooks();
}

static jboolean nativeIsReady(JNIEnv *, jclass) {
    return noensuggest_is_ready() ? JNI_TRUE : JNI_FALSE;
}

static void nativeSetLoggingEnabled(JNIEnv *, jclass, jboolean enabled) {
    noensuggest_set_logging_enabled(enabled ? 1 : 0);
}

static void nativeClearEnglishTypingBuffer(JNIEnv *, jclass) {
    noensuggest_clear_english_typing_buffer();
}

static void nativeMarkInputReady(JNIEnv *, jclass, jboolean ready) {
    noensuggest_mark_input_ready(ready ? 1 : 0);
}

static void nativeForcePasswordBox(JNIEnv *, jclass, jboolean enable) {
    noensuggest_force_password_box(enable ? 1 : 0);
}

static jboolean nativeIsEnglishUi(JNIEnv *, jclass) {
    return noensuggest_is_english_ui() ? JNI_TRUE : JNI_FALSE;
}

static jboolean nativeIsTranslateActive(JNIEnv *, jclass) {
    return noensuggest_is_translate_active() ? JNI_TRUE : JNI_FALSE;
}

static jboolean nativeShouldApplyEnglishDirect(JNIEnv *, jclass) {
    return noensuggest_should_apply_english_direct() ? JNI_TRUE : JNI_FALSE;
}

static jint nativeGetBoardType(JNIEnv *, jclass) {
    return noensuggest_get_board_type();
}

static jint nativeGetInputMode(JNIEnv *, jclass) {
    return noensuggest_get_input_mode();
}



static jint nativeHookOkCount(JNIEnv *, jclass) {
    return noensuggest_hook_ok_count();
}

static jint nativeHookTotalCount(JNIEnv *, jclass) {
    return noensuggest_hook_total_count();
}

static jint nativeHookFailCount(JNIEnv *, jclass) {
    return noensuggest_hook_fail_count();
}

static jstring nativeHookFailNames(JNIEnv *env, jclass) {
    const char *names = noensuggest_hook_fail_names();
    return env->NewStringUTF(names != nullptr ? names : "");
}

static jlong nativeBehaviorOffset(JNIEnv *, jclass) {
    return static_cast<jlong>(noensuggest_behavior_offset());
}


static jstring nativeHookSkipNames(JNIEnv *env, jclass) {
    const char *names = noensuggest_hook_skip_names();
    return env->NewStringUTF(names != nullptr ? names : "");
}

static jstring nativeHookStatusMap(JNIEnv *env, jclass) {
    const char *map = noensuggest_hook_status_map();
    return env->NewStringUTF(map != nullptr ? map : "");
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        return JNI_ERR;
    }
    jclass cls = env->FindClass("com/doubao/ime/noensuggest/NativeBridge");
    if (cls == nullptr) {
        env->ExceptionClear();
        return JNI_VERSION_1_6;
    }
    const JNINativeMethod methods[] = {
            {"nativeInstall", "()I", reinterpret_cast<void *>(nativeInstall)},
            {"nativeIsReady", "()Z", reinterpret_cast<void *>(nativeIsReady)},
            {"nativeSetLoggingEnabled", "(Z)V",
             reinterpret_cast<void *>(nativeSetLoggingEnabled)},
            {"nativeClearEnglishTypingBuffer", "()V",
             reinterpret_cast<void *>(nativeClearEnglishTypingBuffer)},
            {"nativeMarkInputReady", "(Z)V", reinterpret_cast<void *>(nativeMarkInputReady)},
            {"nativeForcePasswordBox", "(Z)V", reinterpret_cast<void *>(nativeForcePasswordBox)},
            {"nativeIsEnglishUi", "()Z", reinterpret_cast<void *>(nativeIsEnglishUi)},
            {"nativeIsTranslateActive", "()Z",
             reinterpret_cast<void *>(nativeIsTranslateActive)},
            {"nativeShouldApplyEnglishDirect", "()Z",
             reinterpret_cast<void *>(nativeShouldApplyEnglishDirect)},
            {"nativeGetBoardType", "()I", reinterpret_cast<void *>(nativeGetBoardType)},
                        {"nativeGetInputMode", "()I", reinterpret_cast<void *>(nativeGetInputMode)},
            {"nativeHookOkCount", "()I", reinterpret_cast<void *>(nativeHookOkCount)},
            {"nativeHookTotalCount", "()I", reinterpret_cast<void *>(nativeHookTotalCount)},
            {"nativeHookFailCount", "()I", reinterpret_cast<void *>(nativeHookFailCount)},
            {"nativeHookFailNames", "()Ljava/lang/String;",
             reinterpret_cast<void *>(nativeHookFailNames)},
            {"nativeBehaviorOffset", "()J", reinterpret_cast<void *>(nativeBehaviorOffset)},
            {"nativeHookSkipNames", "()Ljava/lang/String;",
             reinterpret_cast<void *>(nativeHookSkipNames)},
            {"nativeHookStatusMap", "()Ljava/lang/String;",
             reinterpret_cast<void *>(nativeHookStatusMap)},
    };
    if (env->RegisterNatives(cls, methods,
                             static_cast<jint>(sizeof(methods) / sizeof(methods[0]))) != 0) {
        env->ExceptionClear();
    }
    env->DeleteLocalRef(cls);
    return JNI_VERSION_1_6;
}
