package com.sari.subtitle

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class VideoPlayerActivity:AppCompatActivity(){
    private var player:ExoPlayer?=null;private lateinit var urlInput:EditText;private lateinit var subtitleInput:EditText;private lateinit var drmLicenseInput:EditText;private lateinit var status:TextView;private lateinit var playerView:PlayerView
    private val handler=Handler(Looper.getMainLooper());private var syncEnabled=true;private val sessionCues=mutableListOf<SubtitleCue>()
    private val projectionLauncher=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){result->try{if(result.resultCode==Activity.RESULT_OK&&result.data!=null){val i=Intent(this,AudioCaptureService::class.java).setAction(AudioCaptureService.ACTION_START).putExtra(AudioCaptureService.EXTRA_RESULT_CODE,result.resultCode).putExtra(AudioCaptureService.EXTRA_RESULT_DATA,result.data);ContextCompat.startForegroundService(this,i);ContextCompat.startForegroundService(this,Intent(this,OverlayService::class.java).setAction(OverlayService.ACTION_SHOW));status.text=getString(R.string.player_translation_running)}else status.text=getString(R.string.capture_permission_denied)}catch(e:Exception){status.text=e.message?:"error"}}}
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContentView(R.layout.activity_video_player);urlInput=findViewById(R.id.videoUrlInput);subtitleInput=findViewById(R.id.subtitleUrlInput);drmLicenseInput=findViewById(R.id.drmLicenseUrlInput);status=findViewById(R.id.videoPlayerStatus);playerView=findViewById(R.id.videoPlayerView)
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.playerBack).setNavigationOnClickListener{finish()};findViewById<MaterialButton>(R.id.playUrlButton).setOnClickListener{playOrRouteUrl()};findViewById<MaterialButton>(R.id.loadSubtitleButton).setOnClickListener{playOrRouteUrl()};findViewById<MaterialButton>(R.id.startPlayerTranslationButton).setOnClickListener{requestTranslation()};findViewById<MaterialButton>(R.id.openSourceButton).setOnClickListener{openSource()};findViewById<MaterialButton>(R.id.syncVideoButton).setOnClickListener{syncEnabled=!syncEnabled;LiveSubtitleBus.setVideoSync(syncEnabled);status.text=if(syncEnabled)getString(R.string.sync_enabled) else getString(R.string.sync_disabled)};findViewById<MaterialButton>(R.id.calibrateButton).setOnClickListener{calibrate()};findViewById<MaterialButton>(R.id.exportSrtButton).setOnClickListener{exportSrt()}
        urlInput.setText(intent.getStringExtra(EXTRA_URL).orEmpty());createPlayer();LiveSubtitleBus.setVideoSync(true)
        lifecycleScope.launch{LiveSubtitleBus.current.collect{item->val view=findViewById<TextView>(R.id.liveSubtitleText);handler.removeCallbacksAndMessages(null);if(item==null||item.translated.isBlank()){view.visibility=android.view.View.GONE}else{if(syncEnabled&&item.videoPositionMs!=null){val now=player?.currentPosition?:0L;val delta=item.videoPositionMs-now; if(kotlin.math.abs(delta)>1200L){view.visibility=android.view.View.GONE;handler.postDelayed({if(syncEnabled){view.text=item.translated;view.visibility=android.view.View.VISIBLE}},delta.coerceAtLeast(0L))}else{view.text=item.translated;view.visibility=android.view.View.VISIBLE}}else{view.text=item.translated;view.visibility=android.view.View.VISIBLE};sessionCues.add(SubtitleCue(item.original,item.translated,item.videoPositionMs ?: item.capturedAtElapsedMs,(item.videoPositionMs ?: item.capturedAtElapsedMs)+item.estimatedDurationMs));status.text="Live subtitle • ${item.latencyMs} ms";handler.postDelayed({view.visibility=android.view.View.GONE},item.estimatedDurationMs.coerceAtLeast(900L))}}}
        handler.post(object:Runnable{override fun run(){player?.let{if(syncEnabled)LiveSubtitleBus.updatePlayerPosition(it.currentPosition)};handler.postDelayed(this,100L)}})
        if(urlInput.text.isNotBlank())playOrRouteUrl()
    }
    private fun createPlayer(){player=ExoPlayer.Builder(this).build().also{exo->playerView.player=exo;exo.addListener(object:Player.Listener{override fun onPlaybackStateChanged(state:Int){if(state==Player.STATE_READY){LiveSubtitleBus.updatePlayerPosition(exo.currentPosition);status.text=getString(R.string.player_ready)}}override fun onPositionDiscontinuity(reason:Player.PositionInfo,oldPosition:Player.PositionInfo){LiveSubtitleBus.updatePlayerPosition(exo.currentPosition)}})}}
    private fun playOrRouteUrl(){try{val raw=urlInput.text.toString().trim();val resolved=VideoPlatformResolver.resolve(raw)?:run{urlInput.error=getString(R.string.player_invalid_url);return};if(!resolved.isDirectMedia){status.text=getString(R.string.player_platform_page,VideoPlatformResolver.displayName(resolved.platform));startActivity(Intent(this,BrowserActivity::class.java).putExtra(BrowserActivity.EXTRA_URL,resolved.uri.toString()));return};val b=MediaItem.Builder().setUri(resolved.uri).setMimeType(VideoPlatformResolver.mimeTypeFor(resolved));subtitleConfiguration()?.let{b.setSubtitleConfigurations(listOf(it))};drmLicenseUriOrNull()?.let{b.setDrmConfiguration(MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID).setLicenseUri(it).setMultiSession(false).build());status.text=getString(R.string.player_drm_configured)};player?.setMediaItem(b.build());player?.prepare();player?.playWhenReady=true;if(drmLicenseInput.text.isNullOrBlank())status.text=getString(R.string.player_loading)}catch(e:Exception){status.text=getString(R.string.player_error,e.message?:"unknown")}}
    private fun subtitleConfiguration():MediaItem.SubtitleConfiguration?{val raw=subtitleInput.text.toString().trim();if(raw.isBlank())return null;val uri=VideoPlatformResolver.resolve(raw)?.uri?:return null;val ext=uri.path.orEmpty().substringAfterLast('.',"").lowercase();val mime=when(ext){"vtt"->MimeTypes.TEXT_VTT;"srt"->MimeTypes.APPLICATION_SUBRIP;else->return null};return MediaItem.SubtitleConfiguration.Builder(uri).setMimeType(mime).setLanguage("ar").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()}
    private fun drmLicenseUriOrNull():Uri?{val raw=drmLicenseInput.text.toString().trim();if(raw.isBlank())return null;val uri=runCatching{Uri.parse(raw)}.getOrNull();if(uri?.scheme?.lowercase()!="https"||uri.host.isNullOrBlank()){drmLicenseInput.error=getString(R.string.player_drm_https_required);return null};return uri}
    private fun requestTranslation(){if(player?.currentMediaItem==null){Toast.makeText(this,R.string.player_load_first,Toast.LENGTH_LONG).show();return};val manager=getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager;projectionLauncher.launch(manager.createScreenCaptureIntent())}
    private fun calibrate(){try{val options=arrayOf("-1.5 s","-1.0 s","-0.5 s","0 s","+0.5 s","+1.0 s","+1.5 s");com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle(R.string.calibrate_title).setItems(options){_,which->val ms=(-1500L+which*500L);lifecycleScope.launch{val s=SettingsRepository(this@VideoPlayerActivity).load();SettingsRepository(this@VideoPlayerActivity).save(s.provider,s.speechKey,s.translationKey,s.sourceLanguage,s.targetLanguage,s.fontSize,s.opacity,s.myMemoryEmail,s.speechLanguage,s.contextAwareTranslation,s.translationMemoryEnabled,s.glossary,s.translationQuality,s.sttProvider,s.deepgramKey,s.openAiKey,s.contextPrompt,s.subtitlePosition,ms);status.text=getString(R.string.calibration_set,options[which])}}.show()}catch(e:Exception){status.text=e.message?:"error"}}
    private fun exportSrt(){lifecycleScope.launch{try{val path=SrtExporter.export(this@VideoPlayerActivity,sessionCues.distinctBy{it.startElapsedMs}) ;Toast.makeText(this@VideoPlayerActivity,if(path!=null)getString(R.string.srt_exported) else getString(R.string.srt_export_failed),Toast.LENGTH_LONG).show()}catch(e:Exception){Toast.makeText(this@VideoPlayerActivity,e.message?:getString(R.string.srt_export_failed),Toast.LENGTH_LONG).show()}}}
    private fun openSource(){val raw=urlInput.text.toString().trim();if(VideoPlatformResolver.resolve(raw)!=null)startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(raw)))}
    override fun onDestroy(){handler.removeCallbacksAndMessages(null);runCatching{ContextCompat.startService(this,Intent(this,AudioCaptureService::class.java).setAction(AudioCaptureService.ACTION_STOP))};runCatching{exportSrt()};playerView.player=null;player?.release();player=null;super.onDestroy()}
    companion object{const val EXTRA_URL="video_url"}
}
