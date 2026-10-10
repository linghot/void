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
import com.dsh.offlineinterp.service.InterpreterService

/**
 * M0 入口界面。
 *
 * 设计要点：
 * 1. 探针与正式管线都交给 [InterpreterService] 执行 —— 因为 Android 14 起，
 *    使用 MediaProjection 必须先在 mediaProjection 类型的前台服务里 startForeground()，
 *    再调用 getMediaProjection()。放在 Activity 里做会在新系统上抛 SecurityException。
 * 2. 本类刻意不引入 AndroidX（用平台 Activity + startActivityForResult），
 *    以便在没有网络的环境里也能构建。
 */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var log: TextView
    private lateinit var projectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        log = findViewById(R.id.log)
        projectionManager = getSystemService(MediaProjectionManager::class.java)

        // 让服务里的探针进度能回到这个界面（骨架用的简易通道）
        InterpreterService.probeListener = { line -> append(line) }

        append("离线同传 · M0 捕获探针")
        append("顺序：授权 → 悬浮窗 → 让目标 App 出声 → 开始探针")
        append("若探针全程 RMS≈0 而目标 App 明明在响，说明该 App 禁止被捕获。")
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

        findViewById<Button>(R.id.btnProbe).setOnClickListener {
            launchCapture(InterpreterService.MODE_PROBE)
        }

        findViewById<Button>(R.id.btnInterpret).setOnClickListener {
            launchCapture(InterpreterService.MODE_INTERPRET)
        }
    }

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

    private var pendingMode: String = InterpreterService.MODE_PROBE

    // 平台 Activity 的旧式回调。骨架阶段故意不用 AndroidX 的 ActivityResultContracts。
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

    private fun append(line: String) {
        ui.post { log.append("$line\n") }
    }

    private companion object {
        const val REQ_PERMISSIONS = 101
        const val REQ_CAPTURE = 102
    }
}
