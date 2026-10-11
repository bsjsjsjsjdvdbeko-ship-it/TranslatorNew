package com.sari.subtitle

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class OverlayService : Service() {
    private var wm: WindowManager? = null
    private var subtitle: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val hide = Runnable { subtitle?.visibility = android.view.View.GONE }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> { remove(); stopSelf(); return START_NOT_STICKY }
            ACTION_UPDATE -> {
                val original = intent.getStringExtra(EXTRA_ORIGINAL).orEmpty()
                val translated = intent.getStringExtra(EXTRA_TRANSLATED).orEmpty()
                show()
                subtitle?.text = if (translated.isBlank()) original else "$original\n$translated"
                subtitle?.visibility = android.view.View.VISIBLE
                applySettings()
                handler.removeCallbacks(hide)
                handler.postDelayed(hide, 3_000L)
            }
            else -> show()
        }
        return START_NOT_STICKY
    }

    private fun applySettings() {
        serviceScope.launch {
            val settings = try { SettingsRepository(this@OverlayService).load() } catch (_: Exception) { AppSettings() }
            subtitle?.textSize = settings.fontSize.toFloat().coerceIn(12f, 48f)
            val alpha = (settings.opacity.coerceIn(0, 100) * 255 / 100)
            (subtitle?.background as? GradientDrawable)?.setColor(Color.argb(alpha, 21, 21, 21))
            subtitle?.setTextColor(Color.WHITE)
            subtitle?.invalidate()
        }
    }

    private fun show() {
        if (subtitle != null) return
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        subtitle = TextView(this).apply {
            text = "Sari Subtitle — بانتظار الصوت"
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(18, 14, 18, 14)
            background = GradientDrawable().apply { setColor(0xD9151515.toInt()); cornerRadius = 16f }
            setOnTouchListener(object : android.view.View.OnTouchListener {
                var x = 0f; var y = 0f; var startX = 0f; var startY = 0f
                override fun onTouch(v: android.view.View, e: MotionEvent): Boolean {
                    val p = params ?: return false
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> {
                            x = e.rawX; y = e.rawY; startX = p.x.toFloat(); startY = p.y.toFloat()
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            p.x = (startX + e.rawX - x).toInt()
                            p.y = (startY + e.rawY - y).toInt()
                            try { wm?.updateViewLayout(v, p) } catch (_: Exception) { }
                            return true
                        }
                    }
                    return true
                }
            })
        }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 120 }
        try { wm?.addView(subtitle, params) } catch (_: Exception) { subtitle = null }
        applySettings()
    }

    private fun remove() {
        handler.removeCallbacks(hide)
        try { subtitle?.let { wm?.removeView(it) } } catch (_: Exception) { }
        subtitle = null
    }

    override fun onDestroy() {
        remove()
        serviceScope.cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_SHOW = "com.sari.subtitle.SHOW_OVERLAY"
        const val ACTION_HIDE = "com.sari.subtitle.HIDE_OVERLAY"
        const val ACTION_UPDATE = "com.sari.subtitle.UPDATE_OVERLAY"
        const val EXTRA_ORIGINAL = "original"
        const val EXTRA_TRANSLATED = "translated"
    }
}
