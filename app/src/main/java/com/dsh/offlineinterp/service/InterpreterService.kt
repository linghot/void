package com.dsh.offlineinterp.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.dsh.offlineinterp.MainActivity
import com.dsh.offlineinterp.capture.AppAudioCapture
import com.dsh.offlineinterp.capture.AudioCaptureProbe
import com.dsh.offlineinterp.pipeline.AsrEngine
import com.dsh.offlineinterp.pipeline.EnergySegmenter
import com.dsh.offlineinterp.pipeline.ModelStore
import com.dsh.offlineinterp.pipeline.NoopTranslator
import com.dsh.offlineinterp.pipeline.Settings
import com.dsh.offlineinterp.pipeline.StreamingTranscriber
import com.dsh.offlineinterp.pipeline.StubAsrEngine
import com.dsh.offlineinterp.pipeline.SubtitleOverlay
import com.dsh.offlineinterp.pipeline.Translator
import com.dsh.offlineinterp.pipeline.TranslatorFactory
import com.dsh.offlineinterp.pipeline.WhisperAsrEngine
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 前台服务：捕获 → 切句 → 识别 → 翻译 → 悬浮字幕。
 *
 * 识别引擎（[chooseAsr]）：设置页选的模型下载了就上 whisper.cpp，否则占位实现。
 * 翻译引擎（[TranslatorFactory]）：设置页填了 API 就真翻译，否则原文直通。
 * 临时字幕（[StreamingTranscriber]）：设置页可开关，开了就"边说边出"降低感知延迟。
 *
 * 停止有三个入口，都会走 [teardown] 干净收尾：
 *  1. App 界面按钮 → [stop]
 *  2. 通知栏「停止同传」→ ACTION_STOP
 *  3. 系统"停止共享"→ MediaProjection 回调 onStop
 */
class InterpreterService : Service() {

    private val ui = Handler(Looper.getMainLooper())
    private val overlay by lazy { SubtitleOverlay(this) }

    private var asr: AsrEngine = StubAsrEngine()
    private var translator: Translator = NoopTranslator()

    private val segmentQueue = LinkedBlockingQueue<ShortArray>()

    private var projection: MediaProjection? = null
    private var capture: AppAudioCapture? = null
    private var segmenter: EnergySegmenter? = null
    private var streaming: StreamingTranscriber? = null
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        // 停止指令优先处理：不需要前台服务，也不需要授权数据
        if (intent?.action == ACTION_STOP) {
            notifyLog("收到停止指令，正在结束捕获…")
            teardown()
            stopSelf()
            return START_NOT_STICKY
        }

        // ① 先起前台服务 —— 必须在 getMediaProjection() 之前
        startForeground(NOTIFICATION_ID, buildNotification("离线同传运行中"))

        val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_INTERPRET
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val resultData = intent?.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            notifyLog("授权数据无效，无法开始捕获")
            teardown()
            stopSelf()
            return START_NOT_STICKY
        }

        // ② 再取 MediaProjection
        val manager = getSystemService(MediaProjectionManager::class.java)
        val mp = try {
            manager.getMediaProjection(resultCode, resultData)
        } catch (t: Throwable) {
            notifyLog("getMediaProjection 失败：${t.javaClass.simpleName} ${t.message}")
            null
        }

        if (mp == null) {
            notifyLog("getMediaProjection 返回 null")
            teardown()
            stopSelf()
            return START_NOT_STICKY
        }

        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                notifyLog("系统结束了屏幕共享")
                teardown()
                stopSelf()
            }
        }, ui)

        isRunning = true
        stateListener?.invoke(true)

        when (mode) {
            MODE_PROBE -> runProbe(mp)
            else -> runPipeline(mp)
        }

        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- 探针模式

    private fun runProbe(mp: MediaProjection) {
        notifyLog("开始 ${PROBE_SECONDS} 秒探针，请确认目标 App 正在出声…")
        Thread({
            val result = AudioCaptureProbe(mp).run(PROBE_SECONDS) { line -> notifyLog(line) }
            notifyLog("")
            notifyLog("—— 探针结果 ——")
            notifyLog("采样帧数：${result.frames}")
            notifyLog("峰值：${result.peak}   RMS：${"%.1f".format(result.rms)}")
            notifyLog("非静音占比：${"%.2f%%".format(result.nonSilentRatio * 100)}")
            notifyLog("削波占比：${"%.2f%%".format(result.clippedRatio * 100)}")
            notifyLog("判定：${result.verdict}")
            notifyLog("提示：${result.hint}")
            teardown()
            stopSelf()
        }, "m0-probe").start()
    }

    // ---------------------------------------------------------------- 同传模式

    private fun runPipeline(mp: MediaProjection) {
        if (!overlay.canDraw()) {
            notifyLog("提示：没有悬浮窗权限，字幕将不可见")
        }
        overlay.show()

        // 引擎按设置页的配置来选
        asr = chooseAsr()
        translator = TranslatorFactory.create(this)
        val asrReady = asr.prepare()

        notifyLog(
            "识别：${asr.name}（就绪=$asrReady）｜ 翻译：${translator.name} ｜ " +
                "语言：${Settings.asrLanguage(this)} ｜ 临时字幕：${if (Settings.partialEnabled(this)) "开" else "关"}"
        )
        if (!Settings.translatorReady(this)) {
            notifyLog("提示：翻译 API 未配置，字幕第二行会与第一行相同（设置页可填）")
        }

        overlay.setText(
            if (asrReady) {
                "识别：${asr.name}\n翻译：${translator.name}\n正在等待语音…"
            } else {
                "识别模型不可用（请回 App 点第 3 步下载）\n当前为占位识别，不会出真字幕"
            }
        )

        segmenter = EnergySegmenter(onSegment = { segment -> segmentQueue.offer(segment) })
        startWorker()

        capture = AppAudioCapture(
            projection = mp,
            onPcm16k = { pcm ->
                segmenter?.accept(pcm)
                streaming?.accept(pcm)
            },
            onError = { message -> overlay.setText("捕获错误：$message") }
        ).also { it.start() }

        notifyLog("同传已启动。停止方式：① 本页按钮 ② 通知栏「停止同传」 ③ 系统「停止共享」")
    }

    /** 按设置页选的模型挑识别引擎：文件在就用 whisper，否则占位 */
    private fun chooseAsr(): AsrEngine {
        val id = Settings.asrModelId(this)
        val model = ModelStore.MODELS.firstOrNull { it.id == id } ?: ModelStore.DEFAULT
        if (!ModelStore.isReady(this, model)) return StubAsrEngine()
        return WhisperAsrEngine(
            modelPath = ModelStore.file(this, model).absolutePath,
            threads = WHISPER_THREADS,
            language = Settings.asrLanguage(this)
        )
    }

    private fun startWorker() {
        // 边说边出：设了开关才启用（CPU 换取低延迟）
        streaming = if (Settings.partialEnabled(this)) {
            StreamingTranscriber(asr, onPartial = { partial -> overlay.setText("〔识别中〕$partial") })
        } else {
            null
        }

        worker = Thread({
            while (!Thread.currentThread().isInterrupted) {

                // 每 200ms 检查一次是否需要出临时字幕
                streaming?.maybeRunPartial()

                val segment = try {
                    segmentQueue.poll(200, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    null
                } ?: continue

                // 这一句交给最终识别，窗口清空避免重复
                streaming?.clear()

                val startedAt = System.currentTimeMillis()
                val sourceText = try {
                    asr.transcribe(segment)
                } catch (t: Throwable) {
                    "[识别失败 ${t.javaClass.simpleName}]"
                }
                if (sourceText.isBlank()) continue
                val asrCost = System.currentTimeMillis() - startedAt

                val translated = try {
                    translator.translate(sourceText)
                } catch (t: Throwable) {
                    sourceText
                }

                val audioMs = segment.size * 1000 / SAMPLE_RATE
                overlay.setText(
                    "$sourceText\n$translated\n" +
                        "(${audioMs}ms 音频 · 识别 ${asrCost}ms · ${asr.name} · ${translator.name})"
                )
            }
        }, "interp-worker").also { it.start() }
    }

    // ---------------------------------------------------------------- 收尾

    private fun teardown() {
        capture?.stop()
        capture = null

        segmenter?.flush()
        segmenter = null

        streaming = null

        worker?.interrupt()
        worker = null
        segmentQueue.clear()

        overlay.hide()

        try {
            asr.release()
        } catch (_: Throwable) {
        }
        asr = StubAsrEngine()

        val mp = projection
        projection = null
        try {
            mp?.stop()
        } catch (_: Throwable) {
        }

        isRunning = false
        stateListener?.invoke(false)

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
    }

    override fun onDestroy() {
        notifyLog("同传已停止")
        teardown()
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

    @Suppress("DEPRECATION")
    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val openIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val openPending = PendingIntent.getActivity(this, REQ_OPEN, openIntent, flags)

        val stopIntent = Intent(this, InterpreterService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(this, REQ_STOP, stopIntent, flags)

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("离线同传")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openPending)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "停止同传",
                    stopPending
                ).build()
            )
            .build()
    }

    private fun notifyLog(line: String) {
        logListener?.invoke(line)
    }

    companion object {
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_MODE = "extra_mode"

        const val MODE_PROBE = "probe"
        const val MODE_INTERPRET = "interpret"
        const val ACTION_STOP = "com.dsh.offlineinterp.action.STOP"

        private const val CHANNEL_ID = "offline_interp"
        private const val NOTIFICATION_ID = 42
        private const val PROBE_SECONDS = 10
        private const val SAMPLE_RATE = 16_000
        private const val REQ_OPEN = 101
        private const val REQ_STOP = 102

        /** whisper 推理线程数：一般取大核数量，4~6 之间 */
        private const val WHISPER_THREADS = 4

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var logListener: ((String) -> Unit)? = null

        @Volatile
        var stateListener: ((Boolean) -> Unit)? = null

        fun start(context: Context, resultCode: Int, resultData: Intent, mode: String) {
            val intent = Intent(context, InterpreterService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
                .putExtra(EXTRA_MODE, mode)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, InterpreterService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (t: Throwable) {
                isRunning = false
                stateListener?.invoke(false)
            }
        }
    }
}
