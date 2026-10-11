package com.sari.subtitle

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import android.widget.FrameLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class OverlayService : Service() {
    private var wm: WindowManager? = null
    private var panel: FrameLayout? = null
    private var originalText: TextView? = null
    private var translatedText: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val hide = Runnable { panel?.visibility = View.GONE }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> { remove(); stopSelf(); return START_NOT_STICKY }
            ACTION_UPDATE -> {
                val original = intent.getStringExtra(EXTRA_ORIGINAL).orEmpty()
                val translated = intent.getStringExtra(EXTRA_TRANSLATED).orEmpty()
                show()
                originalText?.text = original
                translatedText?.text = translated
                translatedText?.visibility = if (translated.isBlank()) View.GONE else View.VISIBLE
                panel?.visibility = View.VISIBLE
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
            originalText?.textSize = settings.fontSize.toFloat().coerceIn(12f, 48f)
            translatedText?.textSize = (settings.fontSize + 1).toFloat().coerceIn(13f, 49f)
            val bg = GradientDrawable().apply {
                val base = ContextCompat.getColor(this@OverlayService, R.color.surface)
                val alpha = (settings.opacity.coerceIn(0, 100) * 255 / 100)
                setColor((alpha shl 24) or (base and 0x00FFFFFF))
                cornerRadius = resources.getDimension(R.dimen.overlay_corner_radius)
                setStroke(resources.getDimensionPixelSize(R.dimen.overlay_stroke_width), ContextCompat.getColor(this@OverlayService, R.color.outline))
            }
            panel?.background = bg
        }
    }

    private fun show() {
        if (panel != null) return
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(resources.getDimensionPixelSize(R.dimen.overlay_padding), resources.getDimensionPixelSize(R.dimen.overlay_padding_small), resources.getDimensionPixelSize(R.dimen.overlay_padding), resources.getDimensionPixelSize(R.dimen.overlay_padding))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val brand = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@OverlayService, R.color.primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, WindowManager.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = ImageButton(this).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ContextCompat.getColorStateList(this@OverlayService, R.color.text_secondary)
            background = null
            contentDescription = getString(R.string.stop_overlay)
            setPadding(0, 0, 0, 0)
            setOnClickListener { panel?.visibility = View.GONE; handler.removeCallbacks(hide) }
        }
        header.addView(brand)
        header.addView(close, LinearLayout.LayoutParams(resources.getDimensionPixelSize(R.dimen.overlay_close_size), resources.getDimensionPixelSize(R.dimen.overlay_close_size)))
        content.addView(header)
        originalText = TextView(this).apply {
            text = getString(R.string.waiting_for_audio)
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@OverlayService, R.color.text_secondary))
            setPadding(0, resources.getDimensionPixelSize(R.dimen.overlay_text_spacing), 0, 0)
        }
        translatedText = TextView(this).apply {
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@OverlayService, R.color.text_primary))
            setPadding(0, resources.getDimensionPixelSize(R.dimen.overlay_text_spacing), 0, 0)
        }
        content.addView(originalText)
        content.addView(translatedText)
        panel = FrameLayout(this).apply {
            val backgroundDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = resources.getDimension(R.dimen.overlay_corner_radius)
                setColor(ContextCompat.getColor(this@OverlayService, R.color.surface))
                setStroke(
                    resources.getDimensionPixelSize(R.dimen.overlay_stroke_width),
                    ContextCompat.getColor(this@OverlayService, R.color.outline)
                )
            }
            background = backgroundDrawable
            elevation = resources.getDimension(R.dimen.overlay_elevation)
            setPadding(
                resources.getDimensionPixelSize(R.dimen.overlay_text_spacing),
                resources.getDimensionPixelSize(R.dimen.overlay_text_spacing),
                resources.getDimensionPixelSize(R.dimen.overlay_text_spacing),
                resources.getDimensionPixelSize(R.dimen.overlay_text_spacing)
            )
            addView(content)
            setOnTouchListener(object : View.OnTouchListener {
                var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    val p = params ?: return false
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = p.x; startY = p.y; return true }
                        MotionEvent.ACTION_MOVE -> { p.x = startX + (event.rawX - downX).toInt(); p.y = startY + (event.rawY - downY).toInt(); try { wm?.updateViewLayout(v, p) } catch (_: Exception) {}; return true }
                    }
                    return true
                }
            })
        }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = resources.getDimensionPixelSize(R.dimen.overlay_initial_y) }
        try { wm?.addView(panel, params) } catch (_: Exception) { panel = null }
        applySettings()
    }

    private fun remove() {
        handler.removeCallbacks(hide)
        try { panel?.let { wm?.removeView(it) } } catch (_: Exception) { }
        panel = null; originalText = null; translatedText = null
    }

    override fun onDestroy() { remove(); serviceScope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_SHOW = "com.sari.subtitle.SHOW_OVERLAY"
        const val ACTION_HIDE = "com.sari.subtitle.HIDE_OVERLAY"
        const val ACTION_UPDATE = "com.sari.subtitle.UPDATE_OVERLAY"
        const val EXTRA_ORIGINAL = "original"
        const val EXTRA_TRANSLATED = "translated"
    }
}
