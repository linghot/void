package com.dsh.offlineinterp.pipeline

import kotlin.math.abs

/**
 * 「边说边出」：把最近几秒音频反复送进识别引擎，先显示临时字幕，
 * 等切句器判定句子结束再给最终结果。
 *
 * 为什么这么做：whisper 不是流式模型，必须等一句话说完才能开始识别，
 * 所以"等到句子结束 + 整句推理"天然有 2~3 秒延迟。滑窗把这些推理**提前**
 * 到说话过程中做，感知延迟就降到 1 秒级。
 *
 * 代价：CPU 占用上升（每 [minIntervalMs] 跑一次）。所以：
 *  - 手机偏慢时建议改用 tiny 模型（设置页可选）
 *  - 也可以在设置页关掉"临时字幕"，回到最省电的模式
 *
 * 纯 Kotlin 实现，复用现有 [AsrEngine]，不新增任何原生代码。
 */
class StreamingTranscriber(
    private val asr: AsrEngine,
    private val sampleRate: Int = 16_000,
    /** 滚动窗口长度：太短丢上下文，太长拖慢推理（4 秒是功耗与延迟的折中） */
    private val windowMs: Int = 4_000,
    /** 两次临时识别的最小间隔，防止 CPU 打满（2 秒起步，降发热） */
    private val minIntervalMs: Long = 2_000,
    /** 窗口里至少要有这么多音频才值得跑一次 */
    private val minAudioMs: Int = 500,
    private val onPartial: (String) -> Unit
) {

    private val lock = Any()
    private val capacity = sampleRate * windowMs / 1000
    private val buffer = ShortArray(capacity)

    private var filled = 0
    private var dirty = false
    private var lastRunAt = 0L
    private var lastText = ""

    /** 采集线程调用：把新音频并进窗口 */
    fun accept(pcm: ShortArray) {
        if (pcm.isEmpty()) return
        synchronized(lock) {
            var src = pcm
            if (src.size >= capacity) {
                src = src.copyOfRange(src.size - capacity, src.size)
            }
            if (filled + src.size > capacity) {
                val drop = filled + src.size - capacity
                System.arraycopy(buffer, drop, buffer, 0, filled - drop)
                filled -= drop
            }
            System.arraycopy(src, 0, buffer, filled, src.size)
            filled += src.size
            dirty = true
        }
    }

    /** 句子已经交给"最终识别"后调用，清空窗口避免重复 */
    fun clear() {
        synchronized(lock) {
            filled = 0
            dirty = false
            lastText = ""
        }
    }

    /** 工作线程按需调用；满足条件才真跑一次识别 */
    fun maybeRunPartial(now: Long = System.currentTimeMillis()) {
        val snapshot: ShortArray
        synchronized(lock) {
            if (!dirty) return
            if (now - lastRunAt < minIntervalMs) return
            if (filled < sampleRate * minAudioMs / 1000) return
            if (!hasSpeech()) return
            snapshot = buffer.copyOf(filled)
            dirty = false
            lastRunAt = now
        }

        val text = try {
            asr.transcribePartial(snapshot).trim()
        } catch (_: Throwable) {
            ""
        }
        if (text.isEmpty()) return

        // 再兜一层：结果和上次一模一样就不刷屏（whisper 偶尔会重复吐同一句）
        synchronized(lock) {
            if (text == lastText) return
            lastText = text
        }
        onPartial(text)
    }

    /** 粗略的能量判断，避免对纯静音/纯音乐跑推理 */
    private fun hasSpeech(): Boolean {
        var loud = 0
        for (i in 0 until filled) {
            if (abs(buffer[i].toInt()) > SPEECH_THRESHOLD) loud++
        }
        return filled > 0 && loud.toDouble() / filled > SPEECH_RATIO_MIN
    }

    private companion object {
        const val SPEECH_THRESHOLD = 300
        const val SPEECH_RATIO_MIN = 0.08
    }
}
