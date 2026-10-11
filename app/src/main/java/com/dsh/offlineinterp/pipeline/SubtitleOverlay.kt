package com.dsh.offlineinterp.pipeline

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import com.dsh.offlineinterp.R
import kotlin.math.abs

/**
 * 悬浮字幕窗。
 *
 * v0.7 起：
 *  - **可以拖动**：按住字幕拖到任意位置，松手自动记住（存本机）。
 *  - **单击切换详细 / 精简**：精简只留一行译文，不挡画面。
 *  - **字号可调**：设置页里选（10~30sp）。
 *  - 拖到屏幕外了？设置页有「重置字幕位置」。
 *
 * 需要 SYSTEM_ALERT_WINDOW（"显示在其他应用上层"）权限，只能由用户手动授予。
 */
class SubtitleOverlay(private val context: Context) {

    private val ui = Handler(Looper.getMainLooper())
    private val windowManager: WindowManager = context.getSystemService(WindowManager::class.java)

    @Volatile
    private var view: TextView? = null

    @Volatile
    private var lastText: String = ""

    fun canDraw(): Boolean = AndroidSettings.canDrawOverlays(context)

    fun show() {
        if (!canDraw()) return
        ui.post {
            if (view != null) return@post

            val textView = TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = Settings.overlayFontSp(context).toFloat()
                setPadding(28, 18, 28, 18)
                gravity = Gravity.CENTER
                maxWidth = (context.resources.displayMetrics.widthPixels * 0.92f).toInt()
                setBackgroundResource(R.drawable.bg_overlay)
                text = "…"
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // 可触摸（才能拖），但不要焦点（不影响下面的视频操作）
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = Settings.overlayX(context)
                y = Settings.overlayY(context)
            }

            attachDrag(textView, params)

            try {
                windowManager.addView(textView, params)
                view = textView
                render(lastText.ifEmpty { "…" })
            } catch (_: Throwable) {
                // 权限被撤销或 ROM 拒绝时静默失败，不打断捕获
                view = null
            }
        }
    }

    /** 拖动 + 单击切换显示模式 */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(textView: TextView, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var downRawX = 0f
        var downRawY = 0f
        var dragged = false

        textView.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    dragged = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > DRAG_SLOP || abs(dy) > DRAG_SLOP) dragged = true
                    params.x = startX + dx
                    params.y = startY + dy
                    try {
                        windowManager.updateViewLayout(v, params)
                    } catch (_: Throwable) {
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (dragged) {
                        Settings.saveOverlayPosition(context, params.x, params.y)
                    } else {
                        Settings.toggleOverlayDetail(context)
                        render(lastText)
                    }
                    true
                }

                else -> false
            }
        }
    }

    /** 更新内容 */
    fun setText(text: String) {
        lastText = text
        ui.post { render(text) }
    }

    private fun render(text: String) {
        val tv = view ?: return
        if (Settings.overlayDetail(context)) {
            tv.text = text
            return
        }
        // 精简模式：优先显示第二行（译文），没有就显示第一行
        val lines = text.split('\n').filter { it.isNotBlank() }
        tv.text = when {
            lines.size >= 2 && !lines[1].startsWith("(") && !lines[1].startsWith("#") -> lines[1]
            lines.isNotEmpty() -> lines[0]
            else -> text
        }
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

    private companion object {
        /** 位移超过这个像素才算拖动，否则算点击 */
        const val DRAG_SLOP = 12
    }
}
