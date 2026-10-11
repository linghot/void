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
import android.provider.Settings as AndroidSettings
import android.widget.Button
import android.widget.TextView
import com.dsh.offlineinterp.pipeline.ModelStore
import com.dsh.offlineinterp.pipeline.Settings
import com.dsh.offlineinterp.service.InterpreterService

/**
 * 入口界面。6 步 + 设置页。
 *
 * v0.5 重点：把"模型没就绪"这件事变得**不可能被误解**——
 * 下载支持国内镜像、支持从文件导入、界面直接列出查找过的路径。
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

        InterpreterService.logListener = { line -> append(line) }
        InterpreterService.stateListener = { running -> ui.post { applyRunningState(running) } }

        append("离线同传 · v0.5（模型下载加国内镜像 + 支持从文件导入）")
        append("顺序：1 权限 → 2 悬浮窗 → 3 下载/导入模型 → 4 探针 → 5 启动 → 6 停止")
        append("")

        findViewById<Button>(R.id.btnPermissions).setOnClickListener { requestRuntimePermissions() }

        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            if (AndroidSettings.canDrawOverlays(this)) {
                append("悬浮窗权限：已具备")
            } else {
                append("请在系统页面里允许「显示在其他应用上层」")
                startActivity(
                    Intent(
                        AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<Button>(R.id.btnModel).setOnClickListener { downloadModel() }

        findViewById<Button>(R.id.btnImport).setOnClickListener { pickModelFile() }

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

    override fun onResume() {
        super.onResume()
        refreshConfigSummary()
    }

    private fun currentModel(): ModelStore.Model {
        val id = Settings.asrModelId(this)
        return ModelStore.MODELS.firstOrNull { it.id == id } ?: ModelStore.DEFAULT
    }

    private fun refreshConfigSummary() {
        val model = currentModel()
        val found = ModelStore.resolveFile(this, model)
        val modelState = if (found != null) {
            "已就绪（${ModelStore.sizeMb(found)} MB）→ ${found.absolutePath}"
        } else {
            "未就绪 —— 点第 3 步下载，或点「从文件导入模型」"
        }
        val mtState = if (Settings.translatorReady(this)) {
            "已配置（${Settings.mtModel(this)}）"
        } else {
            "未配置 —— 当前原文直通，去 ⚙ 设置 填"
        }
        append("当前配置：识别=${model.id} ｜ $modelState")
        append("　　　　　翻译=$mtState ｜ 语言=${Settings.asrLanguage(this)} ｜ 临时字幕=${if (Settings.partialEnabled(this)) "开" else "关"}")
        if (found == null) {
            append("会依次在这些位置找模型 ${model.fileName}：")
            ModelStore.candidates(this, model).forEach { append("  · ${it.absolutePath}") }
        }
    }

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

        if (requestCode == REQ_IMPORT) {
            val uri = data?.data
            if (resultCode != RESULT_OK || uri == null) {
                append("已取消选择文件")
                return
            }
            importModel(uri)
            return
        }

        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            append("授权被取消 —— 捕获必须要用户手动同意录屏")
            return
        }
        append("已获得录屏授权，交给前台服务执行（模式=$pendingMode）")
        InterpreterService.start(this, resultCode, data, pendingMode)
    }

    // ------------------------------------------------------------ 模型：下载 / 导入

    private fun downloadModel() {
        val model = currentModel()
        val target = ModelStore.file(this, model)
        val button = findViewById<Button>(R.id.btnModel)

        if (ModelStore.resolveFile(this, model) != null) {
            append("模型已就绪，不用重新下载：${ModelStore.resolveFile(this, model)?.absolutePath}")
            return
        }

        append("开始下载 ${model.fileName}（约 ${model.sizeMb} MB）")
        button.isEnabled = false

        Thread({
            var lastReportedMb = -1L
            val ok = ModelStore.download(
                model = model,
                target = target,
                onProgress = { done, total ->
                    val doneMb = done / (1024 * 1024)
                    if (doneMb != lastReportedMb) {
                        lastReportedMb = doneMb
                        val pct = if (total > 0) done * 100 / total else -1
                        append(
                            if (pct >= 0) "下载中 $pct%（${doneMb}/${total / 1024 / 1024} MB）"
                            else "下载中 ${doneMb} MB"
                        )
                    }
                },
                onLog = { line -> append(line) }
            )

            if (ok) {
                append("模型下载完成 ✅ ${ModelStore.sizeMb(target)} MB")
                append("现在点第 5 步，识别会自动切到 whisper.cpp（离线）")
            } else {
                append("下载失败 ❌ 两个源都不通。请改用「从文件导入模型」：")
                append("  1) 电脑浏览器下载 ${model.fileName}（约 ${model.sizeMb} MB）")
                append("  2) 用微信/数据线传到手机")
                append("  3) 回本页点「从文件导入模型」选中它")
            }
            ui.post { button.isEnabled = true }
        }, "model-download").start()
    }

    private fun pickModelFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        append("请选择模型文件（通常叫 ggml-tiny.bin / ggml-base.bin / ggml-small.bin）")
        startActivityForResult(intent, REQ_IMPORT)
    }

    private fun importModel(uri: Uri) {
        val model = currentModel()
        val target = ModelStore.file(this, model)
        append("开始导入到 ${target.absolutePath}")
        Thread({
            ModelStore.importFrom(this, uri, target) { line -> append(line) }
            append("导入流程结束，可回主界面看「当前配置」确认是否就绪")
        }, "model-import").start()
    }

    // ------------------------------------------------------------ 日志

    private fun append(line: String) {
        ui.post { log.append("$line\n") }
    }

    private companion object {
        const val REQ_PERMISSIONS = 101
        const val REQ_CAPTURE = 102
        const val REQ_IMPORT = 103
    }
}
