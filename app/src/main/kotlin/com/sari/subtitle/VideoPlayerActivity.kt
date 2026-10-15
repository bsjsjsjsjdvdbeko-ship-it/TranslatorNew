package com.sari.subtitle

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.android.material.button.MaterialButton

@OptIn(UnstableApi::class)
class VideoPlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private var selectedMediaUri: Uri? = null
    private var currentMediaUri: Uri? = null
    private lateinit var urlInput: EditText
    private lateinit var subtitleInput: EditText
    private lateinit var drmLicenseInput: EditText
    private lateinit var status: TextView
    private lateinit var playerView: PlayerView
    private val subtitleHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val mediaPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            selectedMediaUri = uri
            urlInput.setText(uri.toString())
            playMediaUri(uri)
        }
    }

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(this, AudioCaptureService::class.java)
                .setAction(AudioCaptureService.ACTION_START)
                .putExtra(AudioCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(AudioCaptureService.EXTRA_RESULT_DATA, result.data)
            ContextCompat.startForegroundService(this, intent)
            ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_SHOW))
            status.text = getString(R.string.player_translation_running)
        } else status.text = getString(R.string.capture_permission_denied)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)
        urlInput = findViewById(R.id.videoUrlInput)
        subtitleInput = findViewById(R.id.subtitleUrlInput)
        drmLicenseInput = findViewById(R.id.drmLicenseUrlInput)
        status = findViewById(R.id.videoPlayerStatus)
        playerView = findViewById(R.id.videoPlayerView)
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.playerBack)
            .setNavigationOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.playUrlButton).setOnClickListener { playOrRouteUrl() }
        findViewById<MaterialButton>(R.id.loadSubtitleButton).setOnClickListener { reloadWithSubtitle() }
        findViewById<MaterialButton>(R.id.importMediaButton).setOnClickListener { mediaPicker.launch(arrayOf("video/*", "audio/*", "application/octet-stream")) }
        findViewById<MaterialButton>(R.id.generateSrtButton).setOnClickListener { generateSubtitles() }
        findViewById<MaterialButton>(R.id.startPlayerTranslationButton).setOnClickListener { requestTranslation() }
        findViewById<MaterialButton>(R.id.openSourceButton).setOnClickListener { openSource() }
        val fileUri = intent.getStringExtra(EXTRA_URI)
        if (fileUri != null && fileUri.isNotBlank()) {
            urlInput.setText(fileUri)
        } else {
            urlInput.setText(intent.getStringExtra(EXTRA_URL).orEmpty())
        }
        createPlayer()
        lifecycleScope.launch {
            LiveSubtitleBus.current.collect { item ->
                val view = findViewById<TextView>(R.id.liveSubtitleText)
                subtitleHandler.removeCallbacksAndMessages(null)
                if (item == null || item.translated.isBlank()) {
                    view.visibility = android.view.View.GONE
                } else {
                    view.text = item.translated
                    view.visibility = android.view.View.VISIBLE
                    status.text = "Live subtitle • ${item.latencyMs} ms pipeline latency"
                    subtitleHandler.postDelayed({ view.visibility = android.view.View.GONE }, item.estimatedDurationMs)
                }
            }
        }
        if (urlInput.text.isNotBlank()) playOrRouteUrl()
    }

    private fun createPlayer() {
        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    val drmConfigured = player?.currentMediaItem?.localConfiguration?.drmConfiguration != null
                    status.text = if (drmConfigured) {
                        getString(R.string.player_drm_error, error.errorCodeName)
                    } else {
                        getString(R.string.player_error, error.errorCodeName)
                    }
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) status.text = getString(R.string.player_ready)
                }
            })
        }
    }

    private fun playOrRouteUrl() {
        val raw = urlInput.text.toString().trim()
        val parsed = runCatching { Uri.parse(raw) }.getOrNull()
        if (parsed != null && parsed.scheme?.lowercase() in setOf("content", "file")) {
            selectedMediaUri = parsed
            playMediaUri(parsed)
            return
        }
        selectedMediaUri = null
        val resolved = VideoPlatformResolver.resolve(raw)
        if (resolved == null) {
            urlInput.error = getString(R.string.player_invalid_url)
            return
        }
        if (!resolved.isDirectMedia) {
            currentMediaUri = null
            status.text = getString(R.string.player_platform_page, VideoPlatformResolver.displayName(resolved.platform))
            startActivity(Intent(this, BrowserActivity::class.java).putExtra(BrowserActivity.EXTRA_URL, resolved.uri.toString()))
            return
        }
        playMediaUri(resolved.uri)
    }

    private fun playMediaUri(mediaUri: Uri, subtitleUri: Uri? = subtitleUriFromField()) {
        currentMediaUri = mediaUri
        val previousPosition = player?.currentPosition ?: 0L
        val wasPlaying = player?.isPlaying ?: true
        val mediaItemBuilder = MediaItem.Builder().setUri(mediaUri)
        val extension = mediaUri.lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase()
        val mime = when (extension) {
            "mp4" -> "video/mp4"; "m4v" -> "video/x-m4v"; "webm" -> "video/webm"
            "m3u8" -> "application/x-mpegURL"; "mpd" -> "application/dash+xml"
            "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "wav" -> "audio/wav"; "ogg" -> "audio/ogg"
            else -> contentResolver.getType(mediaUri)
        }
        if (mime != null) mediaItemBuilder.setMimeType(mime)
        val subtitle = subtitleUri?.let { subtitleConfiguration(it) }
        if (subtitle != null) mediaItemBuilder.setSubtitleConfigurations(listOf(subtitle))
        val drmLicense = drmLicenseUriOrNull()
        if (drmLicense != null) {
            mediaItemBuilder.setDrmConfiguration(
                MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                    .setLicenseUri(drmLicense).setMultiSession(false).build()
            )
        }
        try {
            player?.setMediaItem(mediaItemBuilder.build())
            player?.prepare()
            if (previousPosition > 0L) player?.seekTo(previousPosition)
            player?.playWhenReady = wasPlaying
            status.text = when {
                drmLicense != null -> getString(R.string.player_drm_configured)
                subtitle != null -> "تم تحميل الفيديو وملف الترجمة."
                else -> getString(R.string.player_loading)
            }
        } catch (e: Exception) {
            status.text = getString(R.string.player_error, e.message ?: "unknown")
        }
    }

    private fun reloadWithSubtitle() {
        val media = currentMediaUri ?: selectedMediaUri
        if (media == null) { playOrRouteUrl(); return }
        val sub = subtitleUriFromField()
        if (sub == null) { status.text = "أدخل رابط SRT/VTT أو أنشئ ملف ترجمة أولاً."; return }
        playMediaUri(media, sub)
    }

    private fun subtitleUriFromField(): Uri? {
        val raw = subtitleInput.text.toString().trim()
        if (raw.isBlank()) return null
        val parsed = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        if (parsed.scheme?.lowercase() in setOf("content", "file")) return parsed
        return VideoPlatformResolver.resolve(raw)?.uri
    }

    private fun generateSubtitles() {
        val media = selectedMediaUri ?: currentMediaUri ?: run {
            status.text = "اختر ملف فيديو/صوت، أو أدخل رابط وسائط مباشر يدعم الوصول إلى مسار الصوت."
            return
        }
        findViewById<MaterialButton>(R.id.generateSrtButton).isEnabled = false
        lifecycleScope.launch {
            try {
                val srtUri = LocalSubtitleProcessor(this@VideoPlayerActivity).prepare(media) { message ->
                    runOnUiThread { status.text = message }
                }
                subtitleInput.setText(srtUri)
                playMediaUri(media, Uri.parse(srtUri))
                status.text = "اكتملت الترجمة وحُفظ ملف SRT في Downloads/SariSubtitle."
                Toast.makeText(this@VideoPlayerActivity, "تم إنشاء ملف الترجمة", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                status.text = "تعذرت معالجة الوسائط: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                findViewById<MaterialButton>(R.id.generateSrtButton).isEnabled = true
            }
        }
    }

    private fun subtitleConfiguration(uri: Uri): MediaItem.SubtitleConfiguration? {
        val ext = uri.lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase()
        val mime = when {
            ext == "vtt" -> MimeTypes.TEXT_VTT
            ext == "srt" || uri.scheme.equals("content", true) -> MimeTypes.APPLICATION_SUBRIP
            else -> return null
        }
        return MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mime)
            .setLanguage("ar")
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
    }

    private fun drmLicenseUriOrNull(): Uri? {
        val raw = drmLicenseInput.text.toString().trim()
        if (raw.isBlank()) return null
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
        if (uri?.scheme?.lowercase() != "https" || uri.host.isNullOrBlank()) {
            drmLicenseInput.error = getString(R.string.player_drm_https_required)
            return null
        }
        return uri
    }

    private fun requestTranslation() {
        if (player?.currentMediaItem == null) {
            Toast.makeText(this, R.string.player_load_first, Toast.LENGTH_LONG).show()
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun openSource() {
        val raw = urlInput.text.toString().trim()
        if (VideoPlatformResolver.resolve(raw) != null)
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(raw)))
    }

    override fun onDestroy() {
        subtitleHandler.removeCallbacksAndMessages(null)
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object { const val EXTRA_URL = "video_url"; const val EXTRA_URI = "EXTRA_URI" }
}
