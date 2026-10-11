package com.sari.subtitle

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class TranslationRepository(private val context: Context) {
    private val deduplicator = TranscriptDeduplicator()
    private val memory by lazy { AppDatabase.get(context).translationMemoryDao() }
    private val episodeContext by lazy { EpisodeContextStore(context) }

    suspend fun translateAudio(audioFile: File, capturedAtElapsedMs: Long = android.os.SystemClock.elapsedRealtime(), durationMs: Long = AudioChunker.CHUNK_MS): TranslationEntity = withContext(Dispatchers.IO) {
        val settings = SettingsRepository(context).load()
        val whisperResult = WhisperClient(settings.speechKey).transcribeDetailed(audioFile, settings.speechLanguage)
        val speech = whisperResult.text.trim()
        if (speech.isBlank() || !deduplicator.shouldTranslate(speech)) {
            return@withContext TranslationEntity(
                sourceText = speech, translatedText = "", sourceLanguage = settings.sourceLanguage,
                targetLanguage = settings.targetLanguage, createdAt = System.currentTimeMillis()
            )
        }

        episodeContext.setGlossaryFromText(settings.glossary)
        val sourceLanguage = settings.sourceLanguage.ifBlank { "auto" }
        val targetLanguage = settings.targetLanguage.ifBlank { "ar" }
        val hash = TextHash.sha256(speech)
        val cached = if (settings.translationMemoryEnabled) memory.find(hash, sourceLanguage, targetLanguage) else null

        val translated = cached?.translatedText ?: run {
            val provider = when (settings.provider) {
                SettingsRepository.PROVIDER_GROQ -> TranslationProvider.GROQ
                SettingsRepository.PROVIDER_GOOGLE -> TranslationProvider.GOOGLE
                else -> TranslationProvider.MYMEMORY
            }
            val translationApiKey = if (provider == TranslationProvider.GROQ) settings.speechKey else settings.translationKey
            val contextPrompt = if (settings.contextAwareTranslation) episodeContext.snapshot() else TranslationContext()
            val quality = when (settings.translationQuality) {
                SettingsRepository.QUALITY_FAST -> TranslationQuality.FAST
                SettingsRepository.QUALITY_BALANCED -> TranslationQuality.BALANCED
                else -> TranslationQuality.HUMAN
            }
            val result = TranslationClient(provider, translationApiKey, settings.myMemoryEmail)
                .translateWithContext(speech, sourceLanguage, targetLanguage, contextPrompt, quality)
            if (settings.translationMemoryEnabled) {
                memory.insert(TranslationMemoryEntity(
                    sourceHash = hash, sourceText = speech, translatedText = result,
                    sourceLanguage = sourceLanguage, targetLanguage = targetLanguage,
                    createdAt = System.currentTimeMillis()
                ))
                memory.trim()
            }
            result
        }

        episodeContext.addTurn(speech, translated)
        val entity = TranslationEntity(
            sourceText = speech,
            translatedText = translated,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            createdAt = System.currentTimeMillis()
        )
        LiveSubtitleBus.publish(
            speech,
            translated,
            capturedAtElapsedMs,
            durationMs
        )
        AppDatabase.get(context).translationDao().insert(entity)
        context.startService(Intent(context, OverlayService::class.java)
            .setAction(OverlayService.ACTION_UPDATE)
            .putExtra(OverlayService.EXTRA_ORIGINAL, speech)
            .putExtra(OverlayService.EXTRA_TRANSLATED, translated))
        entity
    }

    fun resetEpisodeContext() = episodeContext.clearSession()

    suspend fun clearTranslationMemory() = withContext(Dispatchers.IO) { memory.clear() }
}
