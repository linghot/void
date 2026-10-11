package com.dsh.offlineinterp.pipeline

/**
 * 真识别引擎：whisper.cpp（完全离线，纯 CPU）。
 *
 * 依赖两样东西，缺一样 [prepare] 就返回 false，管线会自动退回占位实现：
 *   1. 原生库 libwhisper_jni.so —— 由 app/src/main/cpp/ 编译产生，见 CMakeLists.txt
 *   2. 模型文件（如 ggml-base.bin）—— 用 [ModelStore] 下载或手动放进手机
 *
 * 参数说明：
 *   threads  —— 线程数，一般取 CPU 大核数量（4~6）。多了反而抢资源。
 *   language —— "auto" 自动检测；已知语言时写死（如 "zh"）能更快更稳。
 *
 * 注意：whisper 不是流式模型，必须由 Segmenter 切好句子整段送来。
 * 因此端到端延迟 ≈ 静音判定等待 + 推理时间。想要更低延迟请看
 * docs/M2-接入识别模型.md 里的 sherpa-onnx 流式方案。
 */
class WhisperAsrEngine(
    private val modelPath: String,
    private val threads: Int = 4,
    private val language: String = "auto"
) : AsrEngine {

    override val name: String = "whisper.cpp(${modelPath.substringAfterLast('/')})"

    @Volatile
    private var loaded = false

    override fun prepare(): Boolean {
        if (loaded) return true
        loaded = try {
            System.loadLibrary("whisper_jni")
            nativeInit(modelPath)
        } catch (t: Throwable) {
            // 原生库没编进来、或模型文件不可读，都会走到这里
            false
        }
        return loaded
    }

    override fun transcribe(pcm16k: ShortArray): String {
        if (!loaded || pcm16k.isEmpty()) return ""
        // whisper 要的是 -1.0 ~ 1.0 的 float，这里做一次转换
        val f = FloatArray(pcm16k.size) { pcm16k[it] / 32768.0f }
        return try {
            nativeTranscribe(f, threads, language, false).trim()
        } catch (t: Throwable) {
            ""
        }
    }

    override fun release() {
        if (!loaded) return
        try {
            nativeFree()
        } catch (_: Throwable) {
        }
        loaded = false
    }

    private external fun nativeInit(modelPath: String): Boolean

    private external fun nativeTranscribe(
        samples: FloatArray,
        threads: Int,
        language: String,
        translate: Boolean
    ): String

    private external fun nativeFree()
}
