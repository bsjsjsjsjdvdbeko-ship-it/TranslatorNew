package com.sari.subtitle

import android.app.*
import android.content.Intent
import android.media.AudioRecord
import android.os.IBinder
import android.os.Build
import android.util.Log

/**
 * VAD-capable microphone capture scaffold. Android playback capture requires a user-approved
 * MediaProjection token; it is deliberately not falsely represented as active here.
 */
class AudioCaptureService : Service() {
    @Volatile private var running = false
    private var recorder: AudioRecord? = null

    override fun onCreate() {
        super.onCreate()
        val channelId = "sari_capture"
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channelId, "Audio capture", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Sari Subtitle").setContentText("Audio capture service")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).build()
        startForeground(2, notification)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Audio source initialization is intentionally permission/token-gated.
        // Call startMicrophoneCapture() only after RECORD_AUDIO permission is granted.
        return START_NOT_STICKY
    }

    fun isSpeech(samples: ShortArray, count: Int, threshold: Double = 700.0): Boolean {
        if (count <= 0) return false
        var sum = 0.0
        for (i in 0 until count) { val v = samples[i].toDouble(); sum += v * v }
        val rms = kotlin.math.sqrt(sum / count)
        return rms >= threshold
    }

    private fun startMicrophoneCapture() {
        running = true
        // A production recorder loop should stream speech segments into TranslationRepository.
        Log.i("SariSubtitle", "VAD ready; capture requires runtime permission.")
    }
    override fun onDestroy() { running = false; recorder?.release(); recorder = null; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
