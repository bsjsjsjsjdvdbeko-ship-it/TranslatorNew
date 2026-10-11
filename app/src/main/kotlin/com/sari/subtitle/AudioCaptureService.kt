package com.sari.subtitle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import kotlin.concurrent.thread
import kotlin.math.sqrt

class AudioCaptureService : Service() {
    companion object {
        const val ACTION_START = "com.sari.subtitle.START_CAPTURE"
        const val ACTION_STOP = "com.sari.subtitle.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        private const val CHANNEL_ID = "sari_audio_test"
        private const val NOTIFICATION_ID = 4104
    }

    @Volatile private var running = false
    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private var overlay: TextView? = null
    private var windowManager: WindowManager? = null
    private var captureThread: Thread? = null
    private var lastOverlayUpdate = 0L
    private var nonSilentBlocks = 0L
    private var totalBlocks = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture("تم إيقاف الاختبار.")
            return START_NOT_STICKY
        }
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("اختبار التقاط صوت التشغيل جارٍ"))
        if (running) return START_NOT_STICKY

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        }

        if (resultCode == 0 || resultData == null) {
            stopCapture("لم تصل بيانات إذن التقاط الصوت.")
            return START_NOT_STICKY
        }

        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopCapture("أوقف أندرويد جلسة التقاط الصوت.")
                }
            }, android.os.Handler(mainLooper))
            setupOverlay()
            startAudioCapture()
        } catch (e: Exception) {
            stopCapture("تعذر بدء الالتقاط: ${e.javaClass.simpleName}: ${e.message ?: "لا توجد تفاصيل"}")
        }
        return START_NOT_STICKY
    }

    private fun startAudioCapture() {
        val p = projection ?: run {
            stopCapture("جلسة الالتقاط غير متاحة.")
            return
        }
        val sampleRate = 44100
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val config = android.media.AudioPlaybackCaptureConfiguration.Builder(p)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            stopCapture("لم يستطع أندرويد إعداد مخزن الصوت (code=$minBuffer).")
            return
        }
        val bufferSize = maxOf(minBuffer * 2, sampleRate / 2)
        val audioRecord = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(config)
            .build()
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            stopCapture("فشل تهيئة مسجل صوت التشغيل.")
            return
        }
        recorder = audioRecord
        audioRecord.startRecording()
        if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            stopCapture("لم يبدأ تدفق الصوت.")
            return
        }

        running = true
        updateOverlay("تم بدء الاختبار\nشغّل صوتًا في تطبيق آخر…")
        captureThread = thread(name = "SariAudioCapture", isDaemon = true) {
            val samples = ShortArray(bufferSize / 2)
            var lastStatsAt = System.currentTimeMillis()
            var blocksSinceStats = 0L
            while (running) {
                val count = try {
                    audioRecord.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                } catch (e: Exception) {
                    -1
                }
                if (count > 0) {
                    totalBlocks++
                    blocksSinceStats++
                    var sum = 0.0
                    for (i in 0 until count) {
                        val v = samples[i].toDouble()
                        sum += v * v
                    }
                    val rms = sqrt(sum / count)
                    if (rms > 120.0) nonSilentBlocks++
                    val now = System.currentTimeMillis()
                    if (now - lastStatsAt >= 1000) {
                        val quiet = nonSilentBlocks
                        val total = totalBlocks
                        val text = if (quiet > 0) {
                            "وصلت عينات صوت غير صامتة\n" +
                                "مقاطع نشطة: $quiet من $total\n" +
                                "هذا يثبت وصول صوت، وليس دقة الترجمة."
                        } else {
                            "تدفق الصوت بدأ لكن لم تُكتشف عينات واضحة\n" +
                                "تأكد من تشغيل فيديو بصوت مرتفع.\n" +
                                "قد يمنع التطبيق المصدر التقاط الصوت."
                        }
                        updateOverlay(text)
                        lastStatsAt = now
                        blocksSinceStats = 0
                    }
                } else if (count < 0) {
                    updateOverlay("خطأ أثناء قراءة الصوت (code=$count).")
                    break
                }
            }
            if (running) stopCapture("انتهى تدفق الصوت.")
        }
    }

    private fun setupOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val label = TextView(this).apply {
            text = "Sari Subtitle\nتهيئة اختبار الصوت…"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xDD18212B.toInt())
            setPadding(22, 16, 22, 16)
        }
        // minSdk is 29, so TYPE_APPLICATION_OVERLAY is always available.
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 140
        }
        try {
            windowManager?.addView(label, params)
            overlay = label
        } catch (_: Exception) {
            overlay = null
        }
    }

    @Synchronized
    private fun updateOverlay(message: String) {
        getSharedPreferences("capture_status", MODE_PRIVATE)
            .edit().putString("message", message).apply()
        val label = overlay ?: return
        runOnMain {
            try {
                label.text = "Sari Subtitle — اختبار الصوت\n$message"
            } catch (_: Exception) { }
        }
    }

    private fun runOnMain(action: () -> Unit) {
        android.os.Handler(mainLooper).post(action)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Sari Subtitle audio test", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "إشعار خدمة اختبار التقاط صوت التشغيل" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Sari Subtitle")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(openApp)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Sari Subtitle")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(openApp)
                .setOngoing(true)
                .build()
        }
    }

    private fun stopCapture(message: String) {
        updateOverlay(message)
        running = false
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        val wm = windowManager
        val view = overlay
        if (wm != null && view != null) {
            runOnMain { try { wm.removeView(view) } catch (_: Exception) {} }
        }
        overlay = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        val wm = windowManager
        val view = overlay
        if (wm != null && view != null) {
            try { wm.removeView(view) } catch (_: Exception) {}
        }
        overlay = null
        super.onDestroy()
    }
}
