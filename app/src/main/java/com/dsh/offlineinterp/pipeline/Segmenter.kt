package com.dsh.offlineinterp.pipeline

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 断句器（端点检测）。决定"这一句说完了没有"，直接决定延迟与翻译质量。
 *
 * 本实现是**能量阈值法**：够用、零依赖，先把管线跑通。
 * 缺点是对背景音乐/噪声敏感 —— 视频/直播里通常一直有 BGM，会导致：
 *   - 句子切不干净（BGM 一直被认为是"有声"）
 *   - 或反过来，安静段落被切开
 *
 * 生产环境应换成 Silero VAD（ONNX，约 2 MB），接口保持不变：
 *   只要把 [EnergySegmenter] 换成基于 ONNX 的实现，onSegment 回调语义一致即可。
 */
class EnergySegmenter(
    private val sampleRate: Int = 16_000,
    /** 一帧 20 ms，是 VAD 的常规粒度 */
    private val frameMs: Int = 20,
    /** 判定"有声"的幅度阈值（int16 满量程 32767） */
    private val silenceThreshold: Int = 300,
    /** 一段话至少这么长才值得送去做识别 */
    private val minSpeechMs: Int = 300,
    /** 静音持续这么久就认为这句话说完了 */
    private val hangoverMs: Int = 500,
    /** 单句最长限制，防止一直不出句 */
    private val maxSegmentMs: Int = 8_000,
    private val onSegment: (ShortArray) -> Unit
) {

    private val frameSamples = sampleRate * frameMs / 1000

    private val current = ArrayList<Short>(sampleRate * 2)
    private var leftover = ShortArray(0)

    private var inSpeech = false
    private var speechMs = 0
    private var silenceRunMs = 0

    @Synchronized
    fun accept(pcm: ShortArray) {
        val src = if (leftover.isEmpty()) {
            pcm
        } else {
            ShortArray(leftover.size + pcm.size).also {
                leftover.copyInto(it)
                pcm.copyInto(it, leftover.size)
            }
        }

        var offset = 0
        while (offset + frameSamples <= src.size) {
            val speech = isSpeechFrame(src, offset)
            if (!inSpeech) {
                if (speech) {
                    // 检测到开口：从头开始攒这一句
                    inSpeech = true
                    speechMs = 0
                    silenceRunMs = 0
                    current.clear()
                    appendFrame(src, offset)
                    speechMs += frameMs
                }
                // 未开口的静音段直接丢弃，避免把大段空白送进 ASR
            } else {
                appendFrame(src, offset)
                if (speech) {
                    speechMs += frameMs
                    silenceRunMs = 0
                } else {
                    silenceRunMs += frameMs
                }
                if (silenceRunMs >= hangoverMs || speechMs >= maxSegmentMs) {
                    emit()
                }
            }
            offset += frameSamples
        }

        leftover = if (offset < src.size) src.copyOfRange(offset, src.size) else ShortArray(0)
    }

    /** 停止捕获时调用，把最后半句话也吐出去 */
    @Synchronized
    fun flush() {
        if (inSpeech) emit()
        leftover = ShortArray(0)
    }

    private fun emit() {
        if (speechMs >= minSpeechMs && current.isNotEmpty()) {
            onSegment(current.toShortArray())
        }
        inSpeech = false
        speechMs = 0
        silenceRunMs = 0
        current.clear()
    }

    private fun appendFrame(src: ShortArray, offset: Int) {
        for (i in 0 until frameSamples) {
            current.add(src[offset + i])
        }
    }

    private fun isSpeechFrame(src: ShortArray, offset: Int): Boolean {
        var sumSquares = 0.0
        var peak = 0
        for (i in 0 until frameSamples) {
            val v = src[offset + i].toInt()
            sumSquares += v.toDouble() * v
            val a = abs(v)
            if (a > peak) peak = a
        }
        val rms = sqrt(sumSquares / frameSamples)
        // 峰值与 RMS 同时达标才算有声，减少尖峰误判
        return peak > silenceThreshold && rms > silenceThreshold / 2.0
    }
}
