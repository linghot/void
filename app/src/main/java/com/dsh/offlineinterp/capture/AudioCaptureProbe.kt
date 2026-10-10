package com.dsh.offlineinterp.capture

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * ★ M0 核心：判断"手机里正在播放的音频，我到底能不能抓到"。
 *
 * 为什么先做这个：
 * 抓播放音频的唯一正统途径是 AudioPlaybackCapture（API 29+），而**能不能抓到取决于
 * 正在播放的那个 App**：它可以声明 android:allowAudioPlaybackCapture="false"，
 * 受 DRM 保护的内容也会被系统屏蔽，通话类音频同样禁止。这些限制代码绕不过去。
 *
 * 所以本探针只回答一个问题：抓 10 秒，有没有声音？
 *   有 → 这条路走得通，可以继续投入 ASR 集成
 *   没有 → 该 App 走不通，必须改道（麦克风 / OCR / 换源），见 docs/音频捕获限制.md
 *
 * 注意：探针只能告诉你"有没有声音"，不能告诉你"为什么没有"。
 * 判为静音时请按文档里的排查清单逐条排除。
 */
class AudioCaptureProbe(private val projection: MediaProjection) {

    enum class Verdict {
        /** 抓到了有效音频，方案可行 */
        CAPTURABLE,

        /** 全程几乎静音：目标 App 禁止捕获 / 内容受保护 / 或我们匹配规则没写全 */
        SILENT_OR_BLOCKED,

        /** 初始化失败或抛异常，通常是权限或 ROM 限制 */
        ERROR
    }

    data class Result(
        val frames: Int,
        val peak: Int,
        val rms: Double,
        val nonSilentRatio: Double,
        val verdict: Verdict,
        val hint: String
    )

    fun run(seconds: Int, onProgress: (String) -> Unit = {}): Result {
        val record: AudioRecord = try {
            buildRecord()
        } catch (t: Throwable) {
            return Result(0, 0, 0.0, 0.0, Verdict.ERROR, "构建 AudioRecord 失败：${describe(t)}")
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return Result(
                0, 0, 0.0, 0.0, Verdict.ERROR,
                "AudioRecord 初始化失败：检查录音权限；部分 ROM 需要先开启录屏授权"
            )
        }

        var frames = 0
        var peak = 0
        var sumSquares = 0.0
        var nonSilentFrames = 0

        try {
            record.startRecording()
            val buffer = ShortArray(FRAME_SAMPLES)
            val totalSamples = CAPTURE_RATE * seconds
            var read = 0
            var lastReportedSecond = -1

            while (read < totalSamples) {
                val n = record.read(buffer, 0, buffer.size)
                if (n <= 0) break
                for (i in 0 until n) {
                    val v = buffer[i].toInt()
                    val a = abs(v)
                    if (a > peak) peak = a
                    sumSquares += v.toDouble() * v
                    if (a > SILENCE_THRESHOLD) nonSilentFrames++
                }
                frames += n
                read += n
                val sec = read / CAPTURE_RATE
                if (sec != lastReportedSecond) {
                    lastReportedSecond = sec
                    onProgress("采集中 ${sec}s/${seconds}s · 当前峰值 $peak")
                }
            }
        } catch (t: Throwable) {
            return Result(frames, peak, 0.0, 0.0, Verdict.ERROR, "读取音频失败：${describe(t)}")
        } finally {
            try {
                record.stop()
            } catch (_: Throwable) {
            }
            record.release()
        }

        if (frames == 0) {
            return Result(0, 0, 0.0, 0.0, Verdict.ERROR, "一帧都没读到：授权可能已失效，或系统立刻回收了捕获会话")
        }

        val rms = sqrt(sumSquares / frames)
        val ratio = nonSilentFrames.toDouble() / frames
        val verdict = if (peak > SILENCE_THRESHOLD && ratio > NON_SILENT_RATIO_MIN) {
            Verdict.CAPTURABLE
        } else {
            Verdict.SILENT_OR_BLOCKED
        }

        return Result(frames, peak, rms, ratio, verdict, hintFor(verdict))
    }

    private fun buildRecord(): AudioRecord {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(CAPTURE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minBuffer = AudioRecord.getMinBufferSize(
            CAPTURE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = max(minBuffer * 2, CAPTURE_RATE) // 至少约 0.5 秒

        return AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(config)
            .build()
    }

    private fun hintFor(verdict: Verdict): String = when (verdict) {
        Verdict.CAPTURABLE ->
            "抓到了有效音频，这条路可行 → 下一步接 ASR（先 whisper.cpp tiny，再换 sherpa-onnx 流式）"
        Verdict.SILENT_OR_BLOCKED ->
            "全程静音。按 docs/音频捕获限制.md 的清单排查：确认在出声 → 换系统音乐 App 复测 → " +
                "排除 DRM → 把 matchingUsage 列全。若别的 App 能抓到而它不能，基本可判定该 App 禁止捕获。"
        Verdict.ERROR ->
            "初始化/读取异常。先确认录音权限，再确认 ROM 是否拦截了音频捕获。"
    }

    private fun describe(t: Throwable): String = "${t.javaClass.simpleName}: ${t.message}"

    companion object {
        /** 采集用 48 kHz：播放捕获路径对 44.1k/48k 支持最稳，之后在管线里降到 16k 给 ASR */
        const val CAPTURE_RATE = 48_000

        /** 每次读 4096 采样（约 85 ms） */
        const val FRAME_SAMPLES = 4096

        /** 判定"有声"的幅度阈值（int16 满量程 32767，这里约 1%） */
        const val SILENCE_THRESHOLD = 300

        /** 非静音采样占比低于此值就认为整体静音 */
        const val NON_SILENT_RATIO_MIN = 0.02
    }
}
