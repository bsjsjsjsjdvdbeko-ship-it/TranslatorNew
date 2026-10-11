package com.sari.subtitle

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class AudioCaptureService : Service() {

    companion object {
        private const val TAG = "SariAudioCapture"
        const val ACTION_START = "com.sari.subtitle.action.START_CAPTURE"
        const val ACTION_STOP = "com.sari.subtitle.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        private const val NOTIFICATION_CHANNEL_ID = "sari_audio_capture"
        private const val NOTIFICATION_ID = 7101
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_MASK = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            try {
                val intent = Intent(context, AudioCaptureService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_RESULT_CODE, resultCode)
                    putExtra(EXTRA_RESULT_DATA, resultData)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.d(TAG, "Capture start requested")
            } catch (e: Exception) {
                Log.e(TAG, "Unable to start capture service", e)
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, AudioCaptureService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
                Log.d(TAG, "Capture stop requested")
            } catch (e: Exception) {
                Log.e(TAG, "Unable to request capture stop", e)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val isCapturing = AtomicBoolean(false)
    private val isDestroyed = AtomicBoolean(false)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var audioChunker: AudioChunker? = null

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
            audioChunker = AudioChunker(applicationContext) { wavFile ->
                handleChunkReady(wavFile)
            }
            Log.d(TAG, "Service created with chunker callback")
        } catch (e: Exception) {
            Log.e(TAG, "Service initialization failed", e)
        }
    }

    private fun handleChunkReady(wavFile: java.io.File) {
        serviceScope.launch {
            try {
                Log.d(TAG, "Chunk ready, translating: ${wavFile.name} (${wavFile.length()} bytes)")
                val repository = TranslationRepository(applicationContext)
                repository.translateAudio(wavFile)
                Log.d(TAG, "Chunk translation completed")
            } catch (e: Exception) {
                Log.e(TAG, "Chunk translation failed", e)
            } finally {
                try {
                    if (wavFile.exists()) {
                        val deleted = wavFile.delete()
                        Log.d(TAG, "Chunk cleanup: deleted=$deleted")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Chunk delete failed", e)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_STOP -> {
                    Log.d(TAG, "Stop action received")
                    handler.post {
                        try {
                            stopCapture()
                            stopSelfResult(startId)
                        } catch (e: Exception) {
                            Log.e(TAG, "Stop action failed", e)
                            stopSelf()
                        }
                    }
                }
                ACTION_START -> {
                    Log.d(TAG, "Start action received")

                    // Must call startForeground within 5 seconds on Android 14+
                    try {
                        startCaptureForeground()
                        Log.d(TAG, "Foreground started early")
                    } catch (e: Exception) {
                        Log.e(TAG, "Early foreground failed", e)
                        stopSelfResult(startId)
                        return START_NOT_STICKY
                    }

                    val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                    val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(EXTRA_RESULT_DATA)
                    }
                    if (resultCode != Activity.RESULT_OK || resultData == null) {
                        Log.e(TAG, "Missing or invalid MediaProjection result")
                        stopSelfResult(startId)
                        return START_NOT_STICKY
                    }
                    handler.post {
                        try {
                            startCapture(resultCode, resultData)
                        } catch (e: Exception) {
                            Log.e(TAG, "Capture start failed", e)
                            stopCapture()
                            stopSelf()
                        }
                    }
                }
                else -> {
                    Log.w(TAG, "Unknown service action")
                    stopSelfResult(startId)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand failed", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        try {
            if (isDestroyed.get()) return
            if (isCapturing.get()) {
                Log.d(TAG, "Capture already active")
                return
            }
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            if (projectionManager == null) {
                Log.e(TAG, "MediaProjectionManager unavailable")
                return
            }
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
            if (projection == null) {
                Log.e(TAG, "MediaProjection creation failed")
                stopSelf()
                return
            }
            mediaProjection = projection
            projectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped by system/user")
                    handler.post {
                        try {
                            stopCapture()
                            stopSelf()
                        } catch (e: Exception) {
                            Log.e(TAG, "Projection stop handling failed", e)
                        }
                    }
                }
            }
            projection.registerCallback(projectionCallback!!, handler)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                Log.e(TAG, "Playback capture requires Android 10+")
                stopCapture()
                stopSelf()
                return
            }
            val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, AUDIO_ENCODING)
            if (minBufferSize <= 0) {
                Log.e(TAG, "Invalid buffer size: $minBufferSize")
                stopCapture()
                stopSelf()
                return
            }
            val bufferSize = maxOf(minBufferSize * 2, SAMPLE_RATE)
            val recorder = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AUDIO_ENCODING)
                        .setChannelMask(CHANNEL_MASK)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(playbackConfig)
                .build()
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed")
                recorder.release()
                stopCapture()
                stopSelf()
                return
            }
            audioRecord = recorder
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "AudioRecord not recording")
                stopCapture()
                stopSelf()
                return
            }
            isCapturing.set(true)
            startAudioReader(recorder, bufferSize)
            Log.d(TAG, "Audio capture started at ${SAMPLE_RATE}Hz mono")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security error", e)
            stopCapture()
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Unable to init capture", e)
            stopCapture()
            stopSelf()
        }
    }

    private fun startCaptureForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startAudioReader(recorder: AudioRecord, bufferSize: Int) {
        val chunker = audioChunker
        if (chunker == null) {
            Log.e(TAG, "AudioChunker unavailable")
            stopCapture()
            stopSelf()
            return
        }
        val buffer = ByteArray(bufferSize)
        audioThread = Thread({
            Log.d(TAG, "Audio reader started")
            while (isCapturing.get() && !Thread.currentThread().isInterrupted) {
                try {
                    val bytesRead = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (bytesRead > 0) {
                        val audioBytes = buffer.copyOf(bytesRead)
                        chunker.appendAudioData(audioBytes)
                    } else if (bytesRead < 0) {
                        Log.e(TAG, "AudioRecord read error: $bytesRead")
                        break
                    }
                } catch (e: Exception) {
                    if (isCapturing.get()) {
                        Log.e(TAG, "Audio read failed", e)
                    }
                    break
                }
            }
            Log.d(TAG, "Audio reader ended")
        }, "SariAudioReader")
        audioThread?.start()
    }

    private fun stopCapture() {
        try {
            Log.d(TAG, "Stopping audio capture")
            isCapturing.set(false)
            try {
                audioRecord?.let { recorder ->
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord stop failed", e)
            }
            try {
                audioThread?.interrupt()
                audioThread?.join(500)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                Log.w(TAG, "Audio thread cleanup failed", e)
            }
            audioThread = null
            try {
                audioRecord?.release()
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord release failed", e)
            }
            audioRecord = null
            try {
                projectionCallback?.let { mediaProjection?.unregisterCallback(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Callback cleanup failed", e)
            }
            projectionCallback = null
            try {
                mediaProjection?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "MediaProjection stop failed", e)
            }
            mediaProjection = null
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Foreground removal failed", e)
            }
            Log.d(TAG, "Audio capture stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Capture cleanup failed", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Sari Subtitle Audio Capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "إشعار أثناء التقاط الصوت للترجمة"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = launchIntent?.let { launch ->
            android.app.PendingIntent.getActivity(
                this, 0, launch,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        android.app.PendingIntent.FLAG_IMMUTABLE
                    } else 0
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder
            .setContentTitle("Sari Subtitle")
            .setContentText("جارٍ التقاط الصوت للترجمة")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .apply { if (pendingIntent != null) setContentIntent(pendingIntent) }
        return builder.build()
    }

    override fun onDestroy() {
        try {
            isDestroyed.set(true)
            handler.removeCallbacksAndMessages(null)
            stopCapture()
            audioChunker?.close()
            serviceScope.cancel()
            Log.d(TAG, "Service destroyed")
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy failed", e)
        } finally {
            super.onDestroy()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
