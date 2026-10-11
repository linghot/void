// JNI 外壳：Kotlin <-> whisper.cpp
//
// 对应 Kotlin 类：com.dsh.offlineinterp.pipeline.WhisperAsrEngine
// 方法名必须与 Kotlin 的 external fun 一一对应，否则 UnsatisfiedLinkError。
//
// v0.6 关键修复（"卡在一句话上"的根因）：
//   1. no_context = true —— 默认是 false，会把上一次的输出当上下文喂回去。
//      我们的用法是"滑动窗口 + 反复重跑重叠音频"，带着上下文必然被自己的输出
//      带偏，最终锁死在反复吐同一句。
//   2. temperature_inc = 0.2 —— 允许温度回退，遇到重复/低置信时能逃出来。
//   3. single_segment 参数 —— 短窗口临时识别只出一段，避免碎片化重复。
//   4. suppress_blank —— no_context 打开后需要它，否则首段容易被判为空。

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
        jstring language, jboolean translate, jboolean singleSegment) {

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

    // ---- 防重复三件套 ----
    params.no_context = true;          // 每次独立解码，不带上次输出
    params.suppress_blank = true;      // 配合 no_context，避免首段被判空
    params.temperature_inc = 0.2f;     // 允许温度回退，遇重复能逃出

    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;   // 交给上游做字幕排版
    params.print_special = false;
    params.translate = (translate == JNI_TRUE);
    params.n_threads = threads > 0 ? threads : 4;
    params.no_timestamps = true;
    params.single_segment = (singleSegment == JNI_TRUE);

    const char *lang = env->GetStringUTFChars(language, nullptr);
    params.language = lang;            // "auto" 自动检测，或 "zh" / "ja" 等

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
