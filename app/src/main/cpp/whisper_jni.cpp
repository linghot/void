// JNI 外壳：Kotlin <-> whisper.cpp
//
// 对应 Kotlin 类：com.dsh.offlineinterp.pipeline.WhisperAsrEngine
// 方法名必须与 Kotlin 的 external fun 一一对应，否则 UnsatisfiedLinkError。
//
// 用的都是 whisper.cpp 的稳定 C API（v1.5 ~ v1.7 系列没变过）：
//   whisper_init_from_file_with_params / whisper_full_default_params /
//   whisper_full / whisper_full_n_segments / whisper_full_get_segment_text / whisper_free

#include <jni.h>
#include <string>

#include "whisper.h"

namespace {
    whisper_context *g_ctx = nullptr;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_dsh_offlineinterp_pipeline_WhisperAsrEngine_nativeInit(
        JNIEnv *env, jobject /*thiz*/, jstring modelPath) {

    if (g_ctx != nullptr) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
    }

    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr) return JNI_FALSE;

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;   // 纯 CPU，兼容性优先

    g_ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    return g_ctx != nullptr ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_dsh_offlineinterp_pipeline_WhisperAsrEngine_nativeTranscribe(
        JNIEnv *env, jobject /*thiz*/, jfloatArray samples, jint threads,
        jstring language, jboolean translate) {

    if (g_ctx == nullptr) {
        return env->NewStringUTF("");
    }

    const jsize n = env->GetArrayLength(samples);
    if (n <= 0) {
        return env->NewStringUTF("");
    }

    jfloat *pcm = env->GetFloatArrayElements(samples, nullptr);
    if (pcm == nullptr) {
        return env->NewStringUTF("");
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;   // 交给上游做字幕排版，这里只要纯文本
    params.print_special = false;
    params.translate = (translate == JNI_TRUE);
    params.n_threads = threads > 0 ? threads : 4;
    params.no_timestamps = true;
    params.single_segment = false;

    const char *lang = env->GetStringUTFChars(language, nullptr);
    params.language = lang;            // "auto" 自动检测，或 "zh" / "en" 等

    const int rc = whisper_full(g_ctx, params, pcm, n);

    if (lang != nullptr) env->ReleaseStringUTFChars(language, lang);
    env->ReleaseFloatArrayElements(samples, pcm, JNI_ABORT);

    if (rc != 0) {
        return env->NewStringUTF("");
    }

    std::string text;
    const int segments = whisper_full_n_segments(g_ctx);
    for (int i = 0; i < segments; ++i) {
        const char *piece = whisper_full_get_segment_text(g_ctx, i);
        if (piece != nullptr) text += piece;
    }

    return env->NewStringUTF(text.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_dsh_offlineinterp_pipeline_WhisperAsrEngine_nativeFree(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    if (g_ctx != nullptr) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
    }
}
