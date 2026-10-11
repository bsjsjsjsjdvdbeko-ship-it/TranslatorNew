package com.sari.subtitle

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

class OverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var subtitle: TextView? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE) { removeOverlay(); stopSelf(); return START_NOT_STICKY }
        showOverlay()
        return START_STICKY
    }

    private fun showOverlay() {
        if (subtitle != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        subtitle = TextView(this).apply {
            text = "Sari Subtitle — waiting for speech…"
            setTextColor(Color.WHITE); textSize = 18f
            setBackgroundColor(0xCC151515.toInt())
            setPadding(20, 14, 20, 14)
        }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                   else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP; y = 100 }
        try { windowManager?.addView(subtitle, params) } catch (_: Exception) { subtitle = null }
    }

    private fun removeOverlay() {
        try { subtitle?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        subtitle = null
    }
    override fun onDestroy() { removeOverlay(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val ACTION_SHOW = "com.sari.subtitle.SHOW_OVERLAY"
        const val ACTION_HIDE = "com.sari.subtitle.HIDE_OVERLAY"
    }
}
