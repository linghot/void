package com.dsh.offlineinterp

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import com.dsh.offlineinterp.pipeline.ModelStore
import com.dsh.offlineinterp.service.InterpreterService

/**
 * 入口界面。6 步：权限 → 悬浮窗 → 下载模型 → 探针自检 → 启动同传 → **停止**。
 *
 * 设计要点：
 * 1. 探针与正式管线都交给 [InterpreterService] 执行 —— Android 14 起，
 *    使用 MediaProjection 必须先在 mediaProjection 类型的前台服务里 startForeground()，
 *    再调用 getMediaProjection()。
 * 2. 停止有三个入口（本页按钮 / 通知栏按钮 / 系统"停止共享"），
 *    状态由 [InterpreterService.stateListener] 回传，按钮会自动启用禁用。
 * 3. 刻意不引入 AndroidX（平台 Activity + startActivityForResult），保持零依赖。
 */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var log: TextView
    private lateinit var projectionManager: MediaProjectionManager

    private var pendingMode: String = InterpreterService.MODE_PROBE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        log = findViewById(R.id.log)
        projectionManager = getSystemService(MediaProjectionManager::class.java)

        // 服务 → 界面的两条通道
        InterpreterService.logListener = { line -> append(line) }
        InterpreterService.stateListener = { running -> ui.post { applyRunningState(running) } }

        append("离线同传 · v0.3（已修复：无法停止捕获）")
        append("顺序：1 权限 → 2 悬浮窗 → 3 下载模型 → 4 探针 → 5 启动 → 6 停止")
        append("停止有三种方式：① 本页第 6 步  ② 通知栏「停止同传」  ③ 系统「停止共享」")
        append("")

        val model = ModelStore.DEFAULT
        if (ModelStore.isReady(this, model)) {
            append("识别模型：已就绪（${model.fileName}，${ModelStore.sizeMb(this, model)} MB）")
        } else {
            append("识别模型：未下载 —— 不下载的话只能跑占位识别")
        }
        append("模型目录：${ModelStore.dir(this).absolutePath}")
        append("")

        findViewById<Button>(R.id.btnPermissions).setOnClickListener { requestRuntimePermissions() }

        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                append("悬浮窗权限：已具备")
            } else {
                append("请在系统页面里允许「显示在其他应用上层」")
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        findViewById<Button>(R.id.btnModel).setOnClickListener { downloadModel() }

        findViewById<Button>(R.id.btnProbe).setOnClickListener {
            launchCapture(InterpreterService.MODE_PROBE)
        }

        findViewById<Button>(R.id.btnInterpret).setOnClickListener {
            launchCapture(InterpreterService.MODE_INTERPRET)
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            append("已发送停止指令…")
            InterpreterService.stop(this)
        }

        applyRunningState(InterpreterService.isRunning)
    }

    /** 按运行状态启用/禁用按钮，避免重复点击 */
    private fun applyRunningState(running: Boolean) {
        findViewById<Button>(R.id.btnStop).isEnabled = running
        findViewById<Button>(R.id.btnProbe).isEnabled = !running
        findViewById<Button>(R.id.btnInterpret).isEnabled = !running
        append(if (running) "状态：捕获中" else "状态：已停止")
    }

    // ------------------------------------------------------------ 权限与授权

    private fun requestRuntimePermissions() {
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        requestPermissions(need.toTypedArray(), REQ_PERMISSIONS)
    }

    /** 申请录屏授权；授权结果在 onActivityResult 里交给服务处理 */
    private fun launchCapture(mode: String) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            append("还没有录音权限，请先点第 1 步")
            requestRuntimePermissions()
            return
        }
        pendingMode = mode
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            append("授权被取消 —— 捕获必须要用户手动同意录屏")
            return
        }
        append("已获得录屏授权，交给前台服务执行（模式=$pendingMode）")
        InterpreterService.start(this, resultCode, data, pendingMode)
    }

    // ------------------------------------------------------------ 模型下载

    private fun downloadModel() {
        val model = ModelStore.DEFAULT
        val target = ModelStore.file(this, model)
        val button = findViewById<Button>(R.id.btnModel)

        if (ModelStore.isReady(this, model)) {
            append("模型已就绪：${model.fileName}（${ModelStore.sizeMb(this, model)} MB）→ ${target.absolutePath}")
            return
        }

        append("开始下载 ${model.fileName}（约 ${model.sizeMb} MB）…")
        append("地址：${model.url}")
        button.isEnabled = false

        Thread({
            var lastReportedMb = -1L
            val ok = ModelStore.download(model, target) { done, total ->
                if (done < 0) {
                    append("下载失败：服务器返回异常状态码")
                    return@download
                }
                val doneMb = done / (1024 * 1024)
                if (doneMb != lastReportedMb) {
                    lastReportedMb = doneMb
                    val pct = if (total > 0) done * 100 / total else -1
                    append(
                        if (pct >= 0) "下载中 $pct%（${doneMb}MB / ${total / 1024 / 1024}MB）"
                        else "下载中 ${doneMb}MB"
                    )
                }
            }
            if (ok) {
                append("模型下载完成 ✅ 大小 ${ModelStore.sizeMb(this, model)} MB")
                append("现在点第 5 步，识别会自动切到 whisper.cpp（离线）")
            } else {
                append("模型下载失败 ❌ 检查网络后重试；也可以手动把 ${model.fileName} 放到上面那个目录")
            }
            ui.post { button.isEnabled = true }
        }, "model-download").start()
    }

    // ------------------------------------------------------------ 日志

    private fun append(line: String) {
        ui.post { log.append("$line\n") }
    }

    private companion object {
        const val REQ_PERMISSIONS = 101
        const val REQ_CAPTURE = 102
    }
}
