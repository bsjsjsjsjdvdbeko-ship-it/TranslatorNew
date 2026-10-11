package com.sari.subtitle

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class TranslationRepository(private val context: Context) {
    suspend fun translateAudio(audioFile: File): TranslationEntity = withContext(Dispatchers.IO) {
        val settings = SettingsRepository(context).load()
        val provider = TranslationProvider.entries.getOrElse(settings.provider) { TranslationProvider.GOOGLE }
        val sourceText = WhisperClient(settings.speechKey).transcribe(audioFile, settings.sourceLanguage)
        val translationKey = settings.translationKey.ifBlank {
            if (provider == TranslationProvider.OPENAI) settings.speechKey else ""
        }
        val translated = TranslationClient(provider, translationKey).translate(
            sourceText, settings.sourceLanguage, settings.targetLanguage)
        val entity = TranslationEntity(
            sourceText = sourceText, translatedText = translated,
            sourceLanguage = settings.sourceLanguage, targetLanguage = settings.targetLanguage,
            createdAt = System.currentTimeMillis()
        )
        AppDatabase.get(context).translationDao().insert(entity)
        entity
    }
}
