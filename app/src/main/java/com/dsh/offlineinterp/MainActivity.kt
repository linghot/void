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
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import com.dsh.offlineinterp.pipeline.ModelStore
import com.dsh.offlineinterp.pipeline.Settings
import com.dsh.offlineinterp.service.InterpreterService

/**
 * 主界面（v0.7 重做）：
 *  - 顶部状态卡实时显示：运行状态 / 识别模型 / 翻译 / 设备温度
 *  - 启动、停止做成主操作按钮，其余按"准备步骤"顺序排列
 *  - 日志固定在最下方，不用来回翻
 *  - 温度每 2 秒刷新一次，发烫时能一眼看到
 */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var tvRunState: TextView
    private lateinit var tvModelState: TextView
    private lateinit var tvMtState: TextView
    private lateinit var tvThermal: TextView
    private lateinit var projectionManager: MediaProjectionManager

    private var pendingMode: String = InterpreterService.MODE_PROBE

    private val statusTicker = object : Runnable {
        override fun run() {
            refreshStatus()
            ui.postDelayed(this, 2_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        log = findViewById(R.id.log)
        logScroll = findViewById(R.id.logScroll)
        tvRunState = findViewById(R.id.tvRunState)
        tvModelState = findViewById(R.id.tvModelState)
        tvMtState = findViewById(R.id.tvMtState)
        tvThermal = findViewById(R.id.tvThermal)
        projectionManager = getSystemService(MediaProjectionManager::class.java)

        InterpreterService.logListener = { line -> append(line) }
        InterpreterService.stateListener = { running -> ui.post { applyRunningState(running) } }

        append("离线同传 v0.7 · 已做发热优化与界面重做")
        append("首次使用顺序：① 权限 → ② 悬浮窗 → ③ 下载/导入模型 → 启动")
        append("字幕可拖动：按住字幕拖到任意位置，松手记住；单击切换详细/精简")
        append("")

        findViewById<Button>(R.id.btnPermissions).setOnClickListener { requestRuntimePermissions() }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener { askOverlayPermission() }
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
        ui.post(statusTicker)
    }

    override fun onPause() {
        ui.removeCallbacks(statusTicker)
        super.onPause()
    }

    // ------------------------------------------------------------ 状态刷新

    private fun currentModel(): ModelStore.Model {
        val id = Settings.asrModelId(this)
        return ModelStore.MODELS.firstOrNull { it.id == id } ?: ModelStore.DEFAULT
    }

    private fun refreshStatus() {
        val model = currentModel()
        val found = ModelStore.resolveFile(this, model)

        setStatus(
            tvModelState,
            found != null,
            if (found != null) "识别模型：已就绪（${model.id}，${ModelStore.sizeMb(found)} MB）"
            else "识别模型：未就绪 —— 点 ③ 下载或 ③b 导入（当前选 ${model.id}）"
        )

        val mtReady = Settings.translatorReady(this)
        setStatus(
            tvMtState,
            mtReady,
            if (mtReady) "翻译：已配置（${Settings.mtModel(this)}）"
            else "翻译：未配置 —— 现在第二行会与第一行相同"
        )

        tvThermal.text = "设备温度：${thermalLabel()} ｜ 线程 ${Settings.threads(this)} ｜ 临时字幕 ${
            if (Settings.partialEnabled(this)) "开" else "关"
        }"
        tvThermal.setTextColor(getColor(thermalColorRes()))
    }

    private fun setStatus(view: TextView, ok: Boolean, text: String) {
        view.text = (if (ok) "✓ " else "✗ ") + text
        view.setTextColor(getColor(if (ok) R.color.ok else R.color.warn))
    }

    private fun thermalStatus(): Int = try {
        getSystemService(PowerManager::class.java).currentThermalStatus
    } catch (_: Throwable) {
        0
    }

    private fun thermalLabel(): String = when (thermalStatus()) {
        PowerManager.THERMAL_STATUS_NONE -> "正常"
        PowerManager.THERMAL_STATUS_LIGHT -> "轻微"
        PowerManager.THERMAL_STATUS_MODERATE -> "偏热（已自动关临时字幕）"
        PowerManager.THERMAL_STATUS_SEVERE -> "较热，建议降低强度"
        PowerManager.THERMAL_STATUS_CRITICAL -> "过热，建议停止同传"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "严重过热"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "系统即将关机保护"
        else -> "未知"
    }

    private fun thermalColorRes(): Int = when (thermalStatus()) {
        PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> R.color.ok
        PowerManager.THERMAL_STATUS_MODERATE -> R.color.warn
        else -> R.color.err
    }

    private fun applyRunningState(running: Boolean) {
        tvRunState.text = if (running) "● 正在捕获并翻译" else "● 未启动"
        tvRunState.setTextColor(getColor(if (running) R.color.ok else R.color.text_secondary))
        findViewById<Button>(R.id.btnStop).isEnabled = running
        findViewById<Button>(R.id.btnProbe).isEnabled = !running
        findViewById<Button>(R.id.btnInterpret).isEnabled = !running
        findViewById<Button>(R.id.btnModel).isEnabled = !running
        findViewById<Button>(R.id.btnImport).isEnabled = !running
        refreshStatus()
        append(if (running) "状态：捕获中" else "状态：已停止")
    }

    // ------------------------------------------------------------ 权限

    private fun requestRuntimePermissions() {
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        requestPermissions(need.toTypedArray(), REQ_PERMISSIONS)
    }

    private fun askOverlayPermission() {
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

    private fun launchCapture(mode: String) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            append("还没有录音权限，请先点 ①")
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

    // ------------------------------------------------------------ 模型

    private fun downloadModel() {
        val model = currentModel()
        val target = ModelStore.file(this, model)
        val button = findViewById<Button>(R.id.btnModel)

        ModelStore.resolveFile(this, model)?.let {
            append("模型已就绪，无需重新下载：${it.absolutePath}")
            return
        }

        append("开始下载 ${model.fileName}（约 ${model.sizeMb} MB）")
        button.isEnabled = false

        Thread({
            var lastMb = -1L
            val ok = ModelStore.download(
                model = model,
                target = target,
                onProgress = { done, total ->
                    val mb = done / (1024 * 1024)
                    if (mb != lastMb) {
                        lastMb = mb
                        val pct = if (total > 0) done * 100 / total else -1
                        append(if (pct >= 0) "下载中 $pct%（$mb/${total / 1024 / 1024} MB）" else "下载中 $mb MB")
                    }
                },
                onLog = { line -> append(line) }
            )
            if (ok) {
                append("模型下载完成 ✅ ${ModelStore.sizeMb(target)} MB")
            } else {
                append("下载失败 ❌ 两个源都不通。改用 ③b：「从文件导入模型」")
                append("  电脑浏览器下载 ${model.fileName}（约 ${model.sizeMb} MB）→ 传到手机 → 点 ③b 选中它")
            }
            ui.post {
                button.isEnabled = true
                refreshStatus()
            }
        }, "model-download").start()
    }

    private fun pickModelFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        append("请选择模型文件（ggml-tiny.bin / ggml-base.bin / ggml-small.bin）")
        startActivityForResult(intent, REQ_IMPORT)
    }

    private fun importModel(uri: Uri) {
        val model = currentModel()
        val target = ModelStore.file(this, model)
        append("开始导入到 ${target.absolutePath}")
        Thread({
            ModelStore.importFrom(this, uri, target) { line -> append(line) }
            ui.post { refreshStatus() }
        }, "model-import").start()
    }

    // ------------------------------------------------------------ 日志

    private fun append(line: String) {
        ui.post {
            log.append("$line\n")
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private companion object {
        const val REQ_PERMISSIONS = 101
        const val REQ_CAPTURE = 102
        const val REQ_IMPORT = 103
    }
}
