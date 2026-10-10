package com.dsh.offlineinterp.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.dsh.offlineinterp.capture.AppAudioCapture
import com.dsh.offlineinterp.capture.AudioCaptureProbe
import com.dsh.offlineinterp.pipeline.AsrEngine
import com.dsh.offlineinterp.pipeline.EnergySegmenter
import com.dsh.offlineinterp.pipeline.NoopTranslator
import com.dsh.offlineinterp.pipeline.StubAsrEngine
import com.dsh.offlineinterp.pipeline.SubtitleOverlay
import com.dsh.offlineinterp.pipeline.Translator
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 前台服务：捕获 → 切句 → 识别 → 翻译 → 悬浮字幕，全在这里跑。
 *
 * **为什么必须放在服务里**（这是 Android 14 / API 34 的硬要求）：
 * 使用 MediaProjection 前，必须先启动一个 foregroundServiceType="mediaProjection"
 * 的前台服务并调用 startForeground()，然后才能 getMediaProjection()，
 * 否则会抛 SecurityException。所以授权结果是从 Activity 传进来、在这里消费的。
 *
 * 两种模式：
 *  - [MODE_PROBE]：只跑 10 秒捕获探针，回答"这个 App 的音频抓不抓得到"
 *  - [MODE_INTERPRET]：跑完整管线（当前 ASR/翻译是占位实现，用于验证链路）
 */
class InterpreterService : Service() {

    private val ui = Handler(Looper.getMainLooper())
    private val overlay by lazy { SubtitleOverlay(this) }

    // 换成 OpenAiCompatTranslator(baseUrl, apiKey, model) 就能走你自己的 API
    private val translator: Translator = NoopTranslator()

    // 换成 WhisperAsrEngine / SherpaOnnxAsrEngine 即可接入真模型
    private val asr: AsrEngine = StubAsrEngine()

    private val segmentQueue = LinkedBlockingQueue<ShortArray>()

    private var projection: MediaProjection? = null
    private var capture: AppAudioCapture? = null
    private var segmenter: EnergySegmenter? = null
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ① 先起前台服务 —— 必须在 getMediaProjection() 之前
        startForeground(NOTIFICATION_ID, buildNotification("离线同传运行中"))

        val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_INTERPRET
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val resultData = intent?.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            notifyProbe("授权数据无效，无法开始捕获")
            stopSelf()
            return START_NOT_STICKY
        }

        // ② 再取 MediaProjection
        val manager = getSystemService(MediaProjectionManager::class.java)
        val mp = try {
            manager.getMediaProjection(resultCode, resultData)
        } catch (t: Throwable) {
            notifyProbe("getMediaProjection 失败：${t.javaClass.simpleName} ${t.message}")
            null
        }

        if (mp == null) {
            notifyProbe("getMediaProjection 返回 null")
            stopSelf()
            return START_NOT_STICKY
        }

        projection = mp
        // API 34+ 要求注册回调，否则复用会话时会抛异常
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, ui)

        when (mode) {
            MODE_PROBE -> runProbe(mp)
            else -> runPipeline(mp)
        }

        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- 探针模式

    private fun runProbe(mp: MediaProjection) {
        notifyProbe("开始 10 秒探针，请确认目标 App 正在出声…")
        Thread({
            val result = AudioCaptureProbe(mp).run(PROBE_SECONDS) { line -> notifyProbe(line) }
            notifyProbe("")
            notifyProbe("—— 探针结果 ——")
            notifyProbe("采样帧数：${result.frames}")
            notifyProbe("峰值：${result.peak}   RMS：${"%.1f".format(result.rms)}")
            notifyProbe("非静音占比：${"%.2f%%".format(result.nonSilentRatio * 100)}")
            notifyProbe("判定：${result.verdict}")
            notifyProbe("提示：${result.hint}")
            try {
                mp.stop()
            } catch (_: Throwable) {
            }
            stopSelf()
        }, "m0-probe").start()
    }

    // ---------------------------------------------------------------- 同传模式

    private fun runPipeline(mp: MediaProjection) {
        if (!overlay.canDraw()) {
            notifyProbe("提示：没有悬浮窗权限，字幕将不可见（只会在通知里跑）")
        }
        overlay.show()
        overlay.setText("正在准备…")

        if (!asr.prepare()) {
            overlay.setText("ASR 不可用，降级为占位识别")
        }

        segmenter = EnergySegmenter(onSegment = { segment -> segmentQueue.offer(segment) })
        startWorker()

        capture = AppAudioCapture(
            projection = mp,
            onPcm16k = { pcm -> segmenter?.accept(pcm) },
            onError = { message -> overlay.setText("捕获错误：$message") }
        ).also { it.start() }

        overlay.setText("已开始捕获，等待语音…（当前 ASR=占位，翻译=占位）")
    }

    private fun startWorker() {
        worker = Thread({
            while (!Thread.currentThread().isInterrupted) {
                val segment = try {
                    segmentQueue.poll(1, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    null
                } ?: continue

                val sourceText = try {
                    asr.transcribe(segment)
                } catch (t: Throwable) {
                    "[识别失败 ${t.javaClass.simpleName}]"
                }
                if (sourceText.isBlank()) continue

                val translated = try {
                    translator.translate(sourceText)
                } catch (t: Throwable) {
                    sourceText
                }

                val ms = segment.size * 1000 / SAMPLE_RATE
                overlay.setText("$sourceText\n$translated\n(${ms}ms · asr=${asr.name} · mt=${translator.name})")
            }
        }, "interp-worker").also { it.start() }
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onDestroy() {
        capture?.stop()
        capture = null
        segmenter?.flush()
        segmenter = null
        worker?.interrupt()
        worker = null
        segmentQueue.clear()
        overlay.hide()
        asr.release()
        try {
            projection?.stop()
        } catch (_: Throwable) {
        }
        projection = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 通知

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "离线同传",
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = "音频捕获与字幕翻译的前台服务"
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("离线同传")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

    private fun notifyProbe(line: String) {
        probeListener?.invoke(line)
    }

    companion object {
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_MODE = "extra_mode"

        /** 只跑捕获探针 */
        const val MODE_PROBE = "probe"

        /** 跑完整同传管线 */
        const val MODE_INTERPRET = "interpret"

        private const val CHANNEL_ID = "offline_interp"
        private const val NOTIFICATION_ID = 42
        private const val PROBE_SECONDS = 10
        private const val SAMPLE_RATE = 16_000

        /** 探针进度回传通道（骨架简化：Activity 设进来，服务往里推） */
        @Volatile
        var probeListener: ((String) -> Unit)? = null

        fun start(context: Context, resultCode: Int, resultData: Intent, mode: String) {
            val intent = Intent(context, InterpreterService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
                .putExtra(EXTRA_MODE, mode)
            context.startForegroundService(intent)
        }
    }
}
