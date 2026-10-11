package com.sari.subtitle

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.android.material.button.MaterialButton

/** Plays a compatible direct media URL once; subtitle processing captures eligible playback audio. */
class VideoPlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var urlInput: EditText
    private lateinit var status: TextView
    private lateinit var playerView: PlayerView

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(this, AudioCaptureService::class.java)
                .setAction(AudioCaptureService.ACTION_START)
                .putExtra(AudioCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(AudioCaptureService.EXTRA_RESULT_DATA, result.data)
            ContextCompat.startForegroundService(this, intent)
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_SHOW))
            status.text = getString(R.string.player_translation_running)
        } else {
            status.text = getString(R.string.capture_permission_denied)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)
        urlInput = findViewById(R.id.videoUrlInput)
        status = findViewById(R.id.videoPlayerStatus)
        playerView = findViewById(R.id.videoPlayerView)
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.playerBack).setNavigationOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.playUrlButton).setOnClickListener { playUrl() }
        findViewById<MaterialButton>(R.id.startPlayerTranslationButton).setOnClickListener { requestTranslation() }
        urlInput.setText(intent.getStringExtra(EXTRA_URL).orEmpty())
        createPlayer()
        if (urlInput.text.isNotBlank()) playUrl()
    }

    private fun createPlayer() {
        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    status.text = getString(R.string.player_error, error.errorCodeName)
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) status.text = getString(R.string.player_ready)
                }
            })
        }
    }

    private fun playUrl() {
        val raw = urlInput.text.toString().trim()
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
        if (uri == null || uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) {
            urlInput.error = getString(R.string.player_invalid_url)
            return
        }
        try {
            player?.setMediaItem(MediaItem.fromUri(uri))
            player?.prepare()
            player?.playWhenReady = true
            status.text = getString(R.string.player_loading)
        } catch (e: Exception) {
            status.text = getString(R.string.player_error, e.message ?: "unknown")
        }
    }

    private fun requestTranslation() {
        if (player?.currentMediaItem == null) {
            Toast.makeText(this, R.string.player_load_first, Toast.LENGTH_LONG).show()
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    override fun onDestroy() {
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object { const val EXTRA_URL = "video_url" }
}
