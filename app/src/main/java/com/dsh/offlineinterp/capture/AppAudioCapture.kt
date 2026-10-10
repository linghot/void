package com.dsh.offlineinterp.capture

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import kotlin.math.max

/**
 * 持续抓取「手机内播放的音频」，输出 16 kHz 单声道 PCM16（ASR 的标准输入格式）。
 *
 * 采集端用 48 kHz（播放捕获路径对 44.1k/48k 支持最稳），
 * 再做 3:1 抽取降到 16 kHz。抽取前做了 3 点平均，属于最简抗混叠。
 *
 * 生产环境建议改进：
 *  - 换成正经的重采样（如 libsamplerate / speex_resampler），抽出式平均会有混叠
 *  - 用 addMatchingUid 精准匹配目标 App，避免把系统提示音也收进来
 */
class AppAudioCapture(
    private val projection: MediaProjection,
    private val onPcm16k: (ShortArray) -> Unit,
    private val onError: (String) -> Unit
) {

    @Volatile
    private var running = false

    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "app-audio-capture").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        val record = try {
            buildRecord()
        } catch (t: Throwable) {
            onError("构建 AudioRecord 失败：${t.javaClass.simpleName} ${t.message}")
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            onError("AudioRecord 初始化失败（检查录音权限 / ROM 限制）")
            return
        }

        try {
            record.startRecording()
            val buffer = ShortArray(FRAME_SAMPLES)
            while (running && !Thread.currentThread().isInterrupted) {
                val n = record.read(buffer, 0, buffer.size)
                if (n <= 0) {
                    onError("AudioRecord.read 返回 $n，捕获可能已被系统中断")
                    break
                }
                onPcm16k(downsampleTo16k(buffer, n))
            }
        } catch (t: Throwable) {
            onError("读取音频异常：${t.javaClass.simpleName} ${t.message}")
        } finally {
            try {
                record.stop()
            } catch (_: Throwable) {
            }
            record.release()
        }
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
        // 取 4 倍最小缓冲，降低丢帧概率
        val bufferSize = max(minBuffer * 4, CAPTURE_RATE)

        return AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(config)
            .build()
    }

    /** 48 kHz → 16 kHz，3:1 抽取 + 3 点平均 */
    private fun downsampleTo16k(src: ShortArray, count: Int): ShortArray {
        val outLength = count / DECIMATION
        val out = ShortArray(outLength)
        for (i in 0 until outLength) {
            val base = i * DECIMATION
            val sum = src[base].toInt() + src[base + 1].toInt() + src[base + 2].toInt()
            out[i] = (sum / DECIMATION).toShort()
        }
        return out
    }

    companion object {
        const val CAPTURE_RATE = 48_000
        const val DECIMATION = 3
        const val FRAME_SAMPLES = 4096
    }
}
