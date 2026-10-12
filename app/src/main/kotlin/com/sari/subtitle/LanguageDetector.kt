package com.sari.subtitle

import java.io.File

class LanguageDetector(private val context: android.content.Context) {
    suspend fun detect(firstAudio: File, groqKey: String): String? {
        if (groqKey.isBlank()) return null
        return WhisperClient(groqKey).detectLanguage(firstAudio)
    }
}
