package com.sari.subtitle

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean

class OverlayService : Service() {

    companion object {
        private const val TAG = "SariOverlayService"
        const val ACTION_SHOW = "com.sari.subtitle.SHOW_OVERLAY"
        const val ACTION_HIDE = "com.sari.subtitle.HIDE_OVERLAY"
        private const val EXTRA_TEXT = "overlay_text"
        private const val HIDE_DELAY_MS = 3000L

        @Volatile
        private var running = false

        fun show(context: Context, text: String) {
            try {
                val intent = Intent(context, OverlayService::class.java).apply {
                    action = ACTION_SHOW
                    putExtra(EXTRA_TEXT, text)
                }
                context.startService(intent)
                Log.d(TAG, "Show request submitted")
            } catch (e: Exception) {
                Log.e(TAG, "Unable to request overlay display", e)
            }
        }

        fun hide(context: Context) {
            try {
                val intent = Intent(context, OverlayService::class.java).apply {
                    action = ACTION_HIDE
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to request overlay hide", e)
            }
        }

        fun isRunning(): Boolean = running
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val destroyed = AtomicBoolean(false)

    private var windowManager: WindowManager? = null
    private var rootView: FrameLayout? = null
    private var translationView: TextView? = null
    private var closeView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var attached = false

    private val hideRunnable = Runnable {
        safeUiOperation("auto hide") {
            removeOverlay()
            Log.d(TAG, "Overlay hidden after timeout")
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            Log.d(TAG, "Service created")
        } catch (e: Exception) {
            Log.e(TAG, "Service initialization failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_HIDE -> {
                    mainHandler.post {
                        safeUiOperation("hide action") {
                            removeOverlay()
                            stopSelfResult(startId)
                        }
                    }
                }
                ACTION_SHOW, null -> {
                    val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty()
                    mainHandler.post {
                        safeUiOperation("show action") {
                            if (text.isNotBlank()) {
                                displayTranslation(text)
                            } else {
                                Log.d(TAG, "Empty translation ignored")
                            }
                        }
                    }
                }
                else -> Log.w(TAG, "Unknown action: ${intent.action}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand failed", e)
        }
        return START_NOT_STICKY
    }

    private fun displayTranslation(text: String) {
        try {
            if (destroyed.get()) return
            if (!hasOverlayPermission()) {
                Log.w(TAG, "Overlay permission not granted")
                showToastSafely("يرجى السماح بالظهور فوق التطبيقات الأخرى")
                return
            }
            val manager = windowManager ?: return
            if (rootView == null || !attached) {
                createOverlay()
            }
            val translation = translationView ?: return
            translation.text = text
            rootView?.visibility = View.VISIBLE
            mainHandler.removeCallbacks(hideRunnable)
            mainHandler.postDelayed(hideRunnable, HIDE_DELAY_MS)
            Log.d(TAG, "Translation displayed")
        } catch (e: Exception) {
            Log.e(TAG, "Unable to display translation", e)
            removeOverlaySafely()
        }
    }

    private fun createOverlay() {
        try {
            val manager = windowManager ?: return
            removeOverlay()

            val root = FrameLayout(this).apply {
                background = createBackground()
                setPadding(dp(14), dp(10), dp(10), dp(10))
                elevation = dp(8).toFloat()
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            }

            val textView = TextView(this).apply {
                text = ""
                setTextColor(Color.WHITE)
                textSize = 18f
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 5
                setPadding(0, 0, dp(8), 0)
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            }

            val closeButton = TextView(this).apply {
                text = "\u00D7"
                setTextColor(Color.WHITE)
                textSize = 25f
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(100, 255, 255, 255))
                }
                setOnClickListener {
                    safeUiOperation("close button") {
                        mainHandler.removeCallbacks(hideRunnable)
                        removeOverlay()
                        stopSelf()
                    }
                }
            }

            val closeParams = FrameLayout.LayoutParams(dp(34), dp(34), Gravity.END or Gravity.CENTER_VERTICAL)
            val textParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginEnd = dp(38)
            }

            root.addView(textView, textParams)
            root.addView(closeButton, closeParams)

            val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = 0
                y = dp(100)
            }

            installDragListener(root, params, manager)
            manager.addView(root, params)

            rootView = root
            translationView = textView
            closeView = closeButton
            layoutParams = params
            attached = true
            running = true
            Log.d(TAG, "Overlay created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Overlay creation failed", e)
            removeOverlaySafely()
        }
    }

    private fun createBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(Color.argb(220, 25, 28, 35))
            setStroke(dp(1), Color.argb(180, 110, 170, 255))
        }
    }

    private fun installDragListener(view: View, params: WindowManager.LayoutParams, manager: WindowManager) {
        try {
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var dragging = false

            view.setOnTouchListener { touchedView, event ->
                try {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            dragging = false
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.rawX - initialTouchX
                            val dy = event.rawY - initialTouchY
                            if (kotlin.math.abs(dx) > dp(4) || kotlin.math.abs(dy) > dp(4)) {
                                dragging = true
                            }
                            if (dragging && attached) {
                                params.x = initialX + dx.toInt()
                                params.y = initialY + dy.toInt()
                                try {
                                    manager.updateViewLayout(touchedView, params)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Overlay drag update failed", e)
                                }
                            }
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging
                        else -> false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Touch handling failed", e)
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to install drag listener", e)
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.provider.Settings.canDrawOverlays(this)
            } else {
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Permission check failed", e)
            false
        }
    }

    private fun removeOverlay() {
        try {
            mainHandler.removeCallbacks(hideRunnable)
            val view = rootView
            val manager = windowManager
            if (view != null && attached && manager != null) {
                try {
                    manager.removeView(view)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Overlay was already detached", e)
                } catch (e: Exception) {
                    Log.e(TAG, "Window removal failed", e)
                }
            }
            rootView = null
            translationView = null
            closeView = null
            layoutParams = null
            attached = false
            running = false
            Log.d(TAG, "Overlay removed")
        } catch (e: Exception) {
            Log.e(TAG, "removeOverlay failed", e)
        }
    }

    private fun removeOverlaySafely() {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                removeOverlay()
            } else {
                mainHandler.post {
                    safeUiOperation("remove overlay") {
                        removeOverlay()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Safe overlay removal failed", e)
        }
    }

    private inline fun safeUiOperation(operation: String, block: () -> Unit) {
        try {
            if (!destroyed.get()) block()
        } catch (e: Exception) {
            Log.e(TAG, "UI operation failed: $operation", e)
        }
    }

    private fun showToastSafely(message: String) {
        try {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Toast display failed", e)
        }
    }

    private fun dp(value: Int): Int {
        return try {
            (value * resources.displayMetrics.density + 0.5f).toInt()
        } catch (e: Exception) {
            value
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            mainHandler.post {
                safeUiOperation("task removed") {
                    removeOverlay()
                    stopSelf()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onTaskRemoved failed", e)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        try {
            destroyed.set(true)
            mainHandler.removeCallbacksAndMessages(null)
            val view = rootView
            val manager = windowManager
            if (view != null && attached && manager != null) {
                try {
                    manager.removeView(view)
                } catch (e: Exception) {
                    Log.w(TAG, "Final overlay removal failed", e)
                }
            }
            rootView = null
            translationView = null
            closeView = null
            layoutParams = null
            attached = false
            running = false
            Log.d(TAG, "Service destroyed")
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy failed", e)
        } finally {
            super.onDestroy()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
