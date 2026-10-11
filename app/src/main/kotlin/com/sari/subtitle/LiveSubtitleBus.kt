package com.sari.subtitle

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-process subtitle state. Timestamps use SystemClock.elapsedRealtime(). */
data class LiveSubtitle(
    val id: Long,
    val original: String,
    val translated: String,
    val capturedAtElapsedMs: Long,
    val estimatedDurationMs: Long = 2000L
)

object LiveSubtitleBus {
    private val _current = MutableStateFlow<LiveSubtitle?>(null)
    val current: StateFlow<LiveSubtitle?> = _current.asStateFlow()

    fun publish(
        original: String,
        translated: String,
        capturedAtElapsedMs: Long = android.os.SystemClock.elapsedRealtime(),
        durationMs: Long = 2000L
    ) {
        if (translated.isBlank()) return
        _current.value = LiveSubtitle(
            id = capturedAtElapsedMs,
            original = original,
            translated = translated,
            capturedAtElapsedMs = capturedAtElapsedMs,
            estimatedDurationMs = durationMs.coerceAtLeast(500L)
        )
    }

    fun clear() { _current.value = null }
}
