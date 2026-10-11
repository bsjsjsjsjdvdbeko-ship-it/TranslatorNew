package com.sari.subtitle

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class TranslationRepository(private val context: Context) {
    private val deduplicator = TranscriptDeduplicator()

    suspend fun translateAudio(audioFile: File): TranslationEntity = withContext(Dispatchers.IO) {
        val settings = SettingsRepository(context).load()
        val speech = WhisperClient(settings.speechKey).transcribe(audioFile, settings.speechLanguage)
        if (!deduplicator.shouldTranslate(speech)) {
            return@withContext TranslationEntity(
                sourceText = speech, translatedText = "", sourceLanguage = settings.sourceLanguage,
                targetLanguage = settings.targetLanguage, createdAt = System.currentTimeMillis()
            )
        }
        val provider = when (settings.provider) {
            SettingsRepository.PROVIDER_GROQ -> TranslationProvider.GROQ
            SettingsRepository.PROVIDER_GOOGLE -> TranslationProvider.GOOGLE
            else -> TranslationProvider.MYMEMORY
        }
        val translationApiKey = if (provider == TranslationProvider.GROQ) settings.speechKey else settings.translationKey
        val translated = TranslationClient(provider, translationApiKey, settings.myMemoryEmail)
            .translate(speech, settings.sourceLanguage, settings.targetLanguage)
        val entity = TranslationEntity(
            sourceText = speech, translatedText = translated, sourceLanguage = settings.sourceLanguage,
            targetLanguage = settings.targetLanguage, createdAt = System.currentTimeMillis()
        )
        AppDatabase.get(context).translationDao().insert(entity)
        context.startService(Intent(context, OverlayService::class.java)
            .setAction(OverlayService.ACTION_UPDATE)
            .putExtra(OverlayService.EXTRA_ORIGINAL, speech)
            .putExtra(OverlayService.EXTRA_TRANSLATED, translated))
        entity
    }
}
