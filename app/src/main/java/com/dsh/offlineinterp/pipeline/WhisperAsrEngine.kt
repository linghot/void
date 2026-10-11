package com.dsh.offlineinterp.pipeline

/**
 * 真识别引擎：whisper.cpp（完全离线，纯 CPU）。
 *
 * 依赖两样东西，缺一样 [prepare] 就返回 false，管线会自动退回占位实现：
 *   1. 原生库 libwhisper_jni.so —— 由 app/src/main/cpp/ 编译产生
 *   2. 模型文件（如 ggml-base.bin）—— 用 [ModelStore] 下载/导入
 *
 * v0.6 起区分两种用法：
 *   [transcribe]        最终识别：整句，允许模型切多段
 *   [transcribePartial] 临时识别：短窗口，强制单段（防止反复吐同一句）
 *
 * 防重复的关键在原生层（no_context / temperature_inc / suppress_blank），见 whisper_jni.cpp。
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

    override fun transcribe(pcm16k: ShortArray): String = run(pcm16k, singleSegment = false)

    override fun transcribePartial(pcm16k: ShortArray): String = run(pcm16k, singleSegment = true)

    private fun run(pcm16k: ShortArray, singleSegment: Boolean): String {
        if (!loaded || pcm16k.isEmpty()) return ""
        // whisper 要的是 -1.0 ~ 1.0 的 float
        val f = FloatArray(pcm16k.size) { pcm16k[it] / 32768.0f }
        return try {
            nativeTranscribe(f, threads, language, false, singleSegment).trim()
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
        translate: Boolean,
        singleSegment: Boolean
    ): String

    private external fun nativeFree()
}
