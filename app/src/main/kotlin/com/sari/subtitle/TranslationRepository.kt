package com.sari.subtitle

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class TranslationRepository(private val context: Context) {

    companion object {
        private const val TAG = "SariTranslationRepo"
        const val ACTION_TRANSLATION_UPDATED = "com.sari.subtitle.TRANSLATION_UPDATED"
        const val EXTRA_ORIGINAL_TEXT = "original_text"
        const val EXTRA_TRANSLATED_TEXT = "translated_text"
    }

    private val deduplicator = TranscriptDeduplicator()

    suspend fun translateAudio(audioFile: File): TranslationEntity? =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "translateAudio started: ${audioFile.name}")

                val settings = try {
                    SettingsRepository(context).load()
                } catch (e: Exception) {
                    Log.e(TAG, "Unable to load settings", e)
                    return@withContext null
                }

                // 1) Speech-to-text via Groq Whisper
                val whisper = WhisperClient(
                    context = context,
                    apiKeyProvider = { settings.speechKey },
                    languageProvider = { settings.speechLanguage }
                )

                val transcription = whisper.transcribe(audioFile)

                if (!transcription.success || transcription.text.isBlank()) {
                    Log.w(TAG, "Transcription failed: ${transcription.error}")
                    return@withContext null
                }

                val speech = transcription.text.trim()
                Log.d(TAG, "Transcription: ${speech.take(80)}...")

                // 2) Skip duplicates
                if (!deduplicator.shouldTranslate(speech)) {
                    Log.d(TAG, "Duplicate transcript, skipped")
                    return@withContext null
                }

                // 3) Translate
                val providerName = when (settings.provider) {
                    0 -> "google"
                    1 -> "mymemory"
                    else -> "mymemory"
                }

                val translation = TranslationClient(
                    providerProvider = { providerName },
                    googleApiKeyProvider = { settings.translationKey },
                    myMemoryEmailProvider = { settings.myMemoryEmail }
                )

                val translated = try {
                    translation.translate(
                        speech,
                        settings.sourceLanguage,
                        settings.targetLanguage
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Translation failed", e)
                    ""
                }

                if (translated.isBlank()) {
                    Log.w(TAG, "Empty translation result")
                    return@withContext null
                }

                Log.d(TAG, "Translated: ${translated.take(80)}...")

                // 4) Save to database
                val entity = TranslationEntity(
                    sourceText = speech,
                    translatedText = translated,
                    sourceLanguage = settings.sourceLanguage,
                    targetLanguage = settings.targetLanguage,
                    createdAt = System.currentTimeMillis()
                )

                try {
                    AppDatabase.get(context).translationDao().insert(entity)
                    Log.d(TAG, "Saved to history")
                } catch (e: Exception) {
                    Log.e(TAG, "DB insert failed", e)
                }

                // 5) Send broadcast to MainActivity
                try {
                    val intent = Intent(ACTION_TRANSLATION_UPDATED).apply {
                        setPackage(context.packageName)
                        putExtra(EXTRA_ORIGINAL_TEXT, speech)
                        putExtra(EXTRA_TRANSLATED_TEXT, translated)
                    }
                    context.sendBroadcast(intent)
                    Log.d(TAG, "Broadcast sent")
                } catch (e: Exception) {
                    Log.e(TAG, "Broadcast failed", e)
                }

                // 6) Update overlay
                try {
                    OverlayService.show(context, translated)
                    Log.d(TAG, "Overlay updated")
                } catch (e: Exception) {
                    Log.e(TAG, "Overlay update failed", e)
                }

                entity
            } catch (e: Exception) {
                Log.e(TAG, "translateAudio failed", e)
                null
            }
        }
}
