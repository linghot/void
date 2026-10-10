package com.dsh.offlineinterp.pipeline

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * 悬浮字幕窗：贴在屏幕下方的双语字幕。
 *
 * 需要 SYSTEM_ALERT_WINDOW（"显示在其他应用上层"）权限，
 * 由用户在系统设置里手动授予，代码不能代劳。
 *
 * 所有窗口操作都 post 到主线程执行，调用方（工作线程）可以放心直接调。
 */
class SubtitleOverlay(private val context: Context) {

    private val ui = Handler(Looper.getMainLooper())
    private val windowManager: WindowManager = context.getSystemService(WindowManager::class.java)

    @Volatile
    private var view: TextView? = null

    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    fun show() {
        if (!canDraw()) return
        ui.post {
            if (view != null) return@post
            val textView = TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = 16f
                setBackgroundColor(0x99000000.toInt())
                setPadding(28, 18, 28, 18)
                gravity = Gravity.CENTER
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM
                y = 200
            }
            try {
                windowManager.addView(textView, params)
                view = textView
            } catch (_: Throwable) {
                // 权限被撤销或 ROM 拒绝时静默失败，不打断捕获
                view = null
            }
        }
    }

    fun setText(text: String) {
        ui.post { view?.text = text }
    }

    fun hide() {
        ui.post {
            view?.let {
                try {
                    windowManager.removeView(it)
                } catch (_: Throwable) {
                }
            }
            view = null
        }
    }
}
