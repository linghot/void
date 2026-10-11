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
 * 主界面（v0.9）：
 *  - **权限自动申请**：打开就自动弹录音/通知权限；悬浮窗权限自动跳授权页。
 *    不再让用户去找按钮（缺权限时点状态栏那行也能补）。
 *  - **探针已移除**：M0 验证完成，那段代码退役了。
 *  - 状态栏每 2 秒刷新，✗ 的那一行点一下即可自动修复。
 */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var tvRunState: TextView
    private lateinit var tvPermState: TextView
    private lateinit var tvModelState: TextView
    private lateinit var tvMtState: TextView
    private lateinit var tvThermal: TextView
    private lateinit var projectionManager: MediaProjectionManager

    private var autoPrompted = false

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
        tvPermState = findViewById(R.id.tvPermState)
        tvModelState = findViewById(R.id.tvModelState)
        tvMtState = findViewById(R.id.tvMtState)
        tvThermal = findViewById(R.id.tvThermal)
        projectionManager = getSystemService(MediaProjectionManager::class.java)

        InterpreterService.logListener = { line -> append(line) }
        InterpreterService.stateListener = { running -> ui.post { applyRunningState(running) } }

        append("离线同传 v0.9 · 已移除探针（M0 验证完成），权限改为自动申请")
        append("用法：授权 → 下载/导入模型 → 点「启动同传」→ 打开你要看的视频")
        append("字幕可拖动；单击字幕切换详细/精简")
        append("")

        // 状态栏每一行都可点：✗ 的那行点一下就能修
        tvRunState.setOnClickListener {
            if (InterpreterService.isRunning) {
                InterpreterService.stop(this)
            } else {
                launchCapture()
            }
        }
        tvPermState.setOnClickListener { autoFixPermissions(force = true) }
        tvModelState.setOnClickListener { downloadModel() }
        tvMtState.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<Button>(R.id.btnModel).setOnClickListener { downloadModel() }
        findViewById<Button>(R.id.btnImport).setOnClickListener { pickModelFile() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnInterpret).setOnClickListener { launchCapture() }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            append("已发送停止指令…")
            InterpreterService.stop(this)
        }

        applyRunningState(InterpreterService.isRunning)
    }

    override fun onResume() {
        super.onResume()
        ui.post(statusTicker)
        // 打开应用就自动把缺的权限要一遍（每次启动只自动要一次，避免烦人）
        if (!autoPrompted) {
            autoPrompted = true
            autoFixPermissions(force = false)
        }
    }

    override fun onPause() {
        ui.removeCallbacks(statusTicker)
        super.onPause()
    }

    // ------------------------------------------------------------ 权限（自动）

    /**
     * 自动补权限：
     *  - 录音 / 通知：系统框，直接弹
     *  - 悬浮窗：只能跳到设置页（安卓不允许程序自己授予）
     * [force] = 用户手动点了状态栏那行，这时即已具备也重新检查一遍
     */
    private fun autoFixPermissions(force: Boolean) {
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }

        if (missing.isNotEmpty()) {
            append("自动申请权限：${missing.size} 项（录音 / 通知）")
            requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
            return
        }

        if (!AndroidSettings.canDrawOverlays(this)) {
            if (force || !overlayPrompted) {
                overlayPrompted = true
                askOverlayPermission()
            }
        } else if (force) {
            append("权限检查：已全部具备 ✓")
        }
    }

    private var overlayPrompted = false

    private fun askOverlayPermission() {
        append("请允许「显示在其他应用上层」，这是悬浮字幕必需的")
        startActivity(
            Intent(
                AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return
        // 录音/通知处理完，接着引导悬浮窗
        if (!AndroidSettings.canDrawOverlays(this) && !overlayPrompted) {
            overlayPrompted = true
            ui.postDelayed({ askOverlayPermission() }, 400)
        }
        refreshStatus()
    }

    // ------------------------------------------------------------ 状态刷新

    private fun currentModel(): ModelStore.Model {
        val id = Settings.asrModelId(this)
        return ModelStore.MODELS.firstOrNull { it.id == id } ?: ModelStore.DEFAULT
    }

    private fun refreshStatus() {
        val model = currentModel()
        val found = ModelStore.resolveFile(this, model)

        val recordOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notifyOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val overlayOk = AndroidSettings.canDrawOverlays(this)
        setStatus(
            tvPermState,
            recordOk && overlayOk,
            "权限：录音${mark(recordOk)} 悬浮窗${mark(overlayOk)} 通知${mark(notifyOk)}（点这行可重新申请）"
        )

        setStatus(
            tvModelState,
            found != null,
            if (found != null) "识别模型：已就绪（${model.id}，${ModelStore.sizeMb(found)} MB）（点这行可换/下载）"
            else "识别模型：未就绪（当前选 ${model.id}）—— 点这行下载"
        )

        val mtReady = Settings.translatorReady(this)
        setStatus(
            tvMtState,
            mtReady,
            if (mtReady) "翻译：已配置（${Settings.mtModel(this)}）（点这行去设置）"
            else "翻译：未配置，第二行会与第一行相同 —— 点这行去填"
        )

        tvThermal.text = "设备温度：${thermalLabel()} ｜ 线程 ${Settings.threads(this)} ｜ 临时字幕 ${
            if (Settings.partialEnabled(this)) "开" else "关"
        }"
        tvThermal.setTextColor(getColor(thermalColorRes()))
    }

    private fun mark(ok: Boolean): String = if (ok) "✓" else "✗"

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
        tvRunState.text = if (running) "● 正在捕获并翻译（点这行=停止）" else "● 未启动（点这行=启动）"
        tvRunState.setTextColor(getColor(if (running) R.color.ok else R.color.primary))
        findViewById<Button>(R.id.btnStop).isEnabled = running
        findViewById<Button>(R.id.btnInterpret).isEnabled = !running
        findViewById<Button>(R.id.btnModel).isEnabled = !running
        findViewById<Button>(R.id.btnImport).isEnabled = !running
        refreshStatus()
        append(if (running) "状态：捕获中" else "状态：已停止")
    }

    // ------------------------------------------------------------ 启动捕获

    private fun launchCapture() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            append("还缺录音权限，正在为你申请…")
            autoFixPermissions(force = true)
            return
        }
        if (!AndroidSettings.canDrawOverlays(this)) {
            append("还缺悬浮窗权限，否则看不到字幕，正在为你打开授权页…")
            askOverlayPermission()
            return
        }
        if (ModelStore.resolveFile(this, currentModel()) == null) {
            append("提示：识别模型还没就绪，启动后只会显示占位文字。建议先回上一步下载模型。")
        }
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
        append("已获得录屏授权，开始捕获")
        InterpreterService.start(this, resultCode, data)
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
                append("下载失败 ❌ 两个源都不通。改用「从文件导入模型」：")
                append("  电脑浏览器下载 ${model.fileName}（约 ${model.sizeMb} MB）→ 传到手机 → 点导入按钮选中它")
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
