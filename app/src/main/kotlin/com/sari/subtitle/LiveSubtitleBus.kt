package com.sari.subtitle

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LiveSubtitle(val id:Long,val original:String,val translated:String,val capturedAtElapsedMs:Long,val estimatedDurationMs:Long=2200L,val latencyMs:Long=0L,val videoPositionMs:Long?=null)
object LiveSubtitleBus{
    @Volatile private var latestCaptureStartMs=Long.MIN_VALUE
    @Volatile private var videoSyncEnabled=false
    @Volatile private var videoClockOffsetMs:Long=0L
    private val _current=MutableStateFlow<LiveSubtitle?>(null);val current:StateFlow<LiveSubtitle?>=_current.asStateFlow()
    fun markLatestCapture(startElapsedMs:Long){if(startElapsedMs>latestCaptureStartMs)latestCaptureStartMs=startElapsedMs}
    fun setVideoSync(enabled:Boolean){videoSyncEnabled=enabled}
    fun updatePlayerPosition(positionMs:Long){if(videoSyncEnabled)videoClockOffsetMs=positionMs-SystemClock.elapsedRealtime()}
    fun publish(original:String,translated:String,capturedAtElapsedMs:Long=SystemClock.elapsedRealtime(),durationMs:Long=2200L){if(translated.isBlank())return;if(capturedAtElapsedMs+1000L<latestCaptureStartMs)return;val now=SystemClock.elapsedRealtime();_current.value=LiveSubtitle(SystemClock.elapsedRealtime(),original,translated,capturedAtElapsedMs,durationMs.coerceIn(900L,6000L),(now-capturedAtElapsedMs).coerceAtLeast(0L),if(videoSyncEnabled)capturedAtElapsedMs+videoClockOffsetMs else null)}
    fun clear(){latestCaptureStartMs=Long.MIN_VALUE;_current.value=null}
}
