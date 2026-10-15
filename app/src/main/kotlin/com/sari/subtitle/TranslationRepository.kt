package com.sari.subtitle

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class TranslationRepository(private val context: Context) {
    private val deduplicator = TranscriptDeduplicator()
    private val memory by lazy { AppDatabase.get(context).translationMemoryDao() }
    private val episodeContext by lazy { EpisodeContextStore(context) }

    suspend fun translateAudio(audioFile: File, capturedAtElapsedMs: Long = android.os.SystemClock.elapsedRealtime(), durationMs: Long = AudioChunker.CHUNK_MS): TranslationEntity = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== Start ===")
        Log.d(TAG, "captured durationMs=$durationMs capturedAtElapsedMs=$capturedAtElapsedMs")
        Log.d(TAG, "audioFile.exists=${audioFile.exists()}")
        Log.d(TAG, "audioFile.size=${audioFile.length()}")
        Log.d(TAG, "audioFile.absolutePath=${audioFile.absolutePath}")
        SttDiagnostics.logWav(audioFile, "TranslationRepository input")
        val settings = SettingsRepository(context).load()
        Log.d(TAG, "settings.provider=${settings.provider}")
        Log.d(TAG, "settings.sttProvider=${settings.sttProvider}")
        Log.d(TAG, "settings.speechLanguage=${settings.speechLanguage}")
        Log.d(TAG, "speechKey.length=${settings.speechKey.length}")
        Log.d(TAG, "deepgramKey.length=${settings.deepgramKey.length}")
        Log.d(TAG, "omniRouteKey.length=${settings.omniRouteKey.length}")

        val preContext = if (settings.contextAwareTranslation) episodeContext.snapshot() else TranslationContext()
        val whisperPrompt = buildWhisperPrompt(settings, preContext)
        val configuredSttProvider = SttProviderPolicy.normalize(settings.sttProvider)
        val whisperResult = try { if (settings.provider == SettingsRepository.PROVIDER_OMNIROUTE) {
            Log.i(TAG, "STT route=OmniRoute; configured local STT provider is ignored while translation provider is OmniRoute")
            if (settings.smartRoutingEnabled || settings.adaptiveRoutingEnabled) {
                AiOrchestrator(context).transcribe(
                    audioFile = audioFile,
                    models = settings.omniRouteSttModels.split(',').map { it.trim() },
                    language = settings.speechLanguage,
                    prompt = whisperPrompt,
                    durationMs = durationMs,
                    quality = when (settings.translationQuality) {
                        SettingsRepository.QUALITY_FAST -> TranslationQuality.FAST
                        SettingsRepository.QUALITY_BALANCED -> TranslationQuality.BALANCED
                        else -> TranslationQuality.HUMAN
                    }
                ).result
            } else {
                OmniRouteClient(settings.omniRouteBaseUrl, settings.omniRouteKey).transcribe(
                    audioFile = audioFile,
                    models = settings.omniRouteSttModels.split(',').map { it.trim() },
                    language = settings.speechLanguage,
                    prompt = whisperPrompt
                )
            }
        } else {
            when (configuredSttProvider) {
                "deepgram-nova3" -> {
                    Log.i(TAG, "STT route=Deepgram Nova-3")
                    DeepgramClient(settings.deepgramKey, model = "nova-3")
                        .transcribe(audioFile, settings.speechLanguage, whisperPrompt)
                }
                "deepgram-nova2" -> {
                    Log.i(TAG, "STT route=Deepgram Nova-2")
                    DeepgramClient(settings.deepgramKey, model = "nova-2")
                        .transcribe(audioFile, settings.speechLanguage, whisperPrompt)
                }
                "groq" -> {
                    Log.i(TAG, "STT route=Groq Whisper")
                    WhisperClient(settings.speechKey).transcribeDetailed(audioFile, settings.speechLanguage, whisperPrompt)
                }
                "vosk" -> {
                    Log.i(TAG, "STT route=Vosk")
                    val modelsRoot = File(context.filesDir, "vosk-models")
                    val modelDir = modelsRoot.listFiles()?.firstOrNull {
                        it.isDirectory && (it.name.startsWith("vosk-model-ar-mgb2") || File(it, "am").exists())
                    } ?: error("نموذج Vosk العربي غير موجود. افتح إعدادات STT وحمّل النموذج أولاً.")
                    VoskClient(modelDir).transcribe(audioFile, settings.speechLanguage, whisperPrompt)
                }
                else -> error("مزود STT غير معروف: '${settings.sttProvider}'. افتح إعدادات STT واختر Deepgram Nova-3 أو Groq Whisper ثم احفظ.")
            }
        } } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "STT failed; route=${if (settings.provider == SettingsRepository.PROVIDER_OMNIROUTE) "OmniRoute" else configuredSttProvider}; ${SttDiagnostics.messageFor(e)}", e)
            throw IllegalStateException(SttDiagnostics.messageFor(e), e)
        }
        Log.d(TAG, "STT success; textChars=${whisperResult.text.length}; segments=${whisperResult.segments.size}")
        val speech = whisperResult.text.trim()
        val sourceLanguage = settings.sourceLanguage.ifBlank { "auto" }
        val targetLanguage = settings.targetLanguage.ifBlank { "ar" }
        // Very short one-character detections (for example "N") are commonly noise or a
        // chunk-boundary artifact. Do not translate/publish them as subtitles.
        val onlyOneLetter = speech.matches(Regex("(?iu)^[\\p{L}]$"))
        val punctuationOnly = speech.isNotEmpty() && speech.none { it.isLetterOrDigit() }
        if (speech.isBlank() || onlyOneLetter || punctuationOnly || !deduplicator.shouldTranslate(speech)) {
            return@withContext TranslationEntity(
                sourceText = speech, translatedText = "", sourceLanguage = settings.sourceLanguage,
                targetLanguage = settings.targetLanguage, createdAt = System.currentTimeMillis()
            )
        }


        val hash = TextHash.sha256(speech)
        val cached = if (settings.translationMemoryEnabled) memory.find(hash, sourceLanguage, targetLanguage) else null

        val translated = cached?.translatedText ?: run {
            val contextPrompt = if (settings.contextAwareTranslation) episodeContext.snapshot() else TranslationContext()
            val quality = when (settings.translationQuality) {
                SettingsRepository.QUALITY_FAST -> TranslationQuality.FAST
                SettingsRepository.QUALITY_BALANCED -> TranslationQuality.BALANCED
                else -> TranslationQuality.HUMAN
            }
            val result = if (settings.provider == SettingsRepository.PROVIDER_OMNIROUTE) {
                if (settings.smartRoutingEnabled || settings.adaptiveRoutingEnabled) {
                    val features = AudioFeatureAnalyzer.analyze(audioFile)
                    val seconds = (durationMs.coerceAtLeast(1L) / 1000.0)
                    val wordsPerSecond = speech.split(Regex("\\s+")).count { it.isNotBlank() }.toDouble() / seconds
                    AiOrchestrator(context).translate(
                        text = speech,
                        source = sourceLanguage,
                        target = targetLanguage,
                        models = settings.omniRouteTranslationModels.split(',').map { it.trim() },
                        contextPrompt = contextPrompt,
                        quality = quality,
                        speechProfile = SpeechProfile(wordsPerSecond, features.noiseLevel, features)
                    ).text
                } else {
                    translateOmniRouteWithFallback(
                        settings = settings,
                        text = speech,
                        source = sourceLanguage,
                        target = targetLanguage,
                        context = contextPrompt,
                        quality = quality
                    )
                }
            } else {
                val provider = when (settings.provider) {
                    SettingsRepository.PROVIDER_GROQ -> TranslationProvider.GROQ
                    SettingsRepository.PROVIDER_GOOGLE -> TranslationProvider.GOOGLE
                    SettingsRepository.PROVIDER_OPENAI -> TranslationProvider.OPENAI
                    else -> TranslationProvider.MYMEMORY
                }
                val translationApiKey = when (provider) { TranslationProvider.GROQ -> settings.speechKey; TranslationProvider.OPENAI -> settings.openAiKey.ifBlank { settings.translationKey }; else -> settings.translationKey }
                TranslationClient(provider, translationApiKey, settings.myMemoryEmail)
                    .translateWithContext(speech, sourceLanguage, targetLanguage, contextPrompt, quality)
            }
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
        val segmentStart = whisperResult.segments.minOfOrNull { it.startMs }?.toLong() ?: 0L
        val segmentEnd = whisperResult.segments.maxOfOrNull { it.endMs }?.toLong() ?: durationMs
        val cueDuration = (segmentEnd - segmentStart).coerceIn(900L, 6_000L)
        val cueStart = capturedAtElapsedMs + segmentStart
        LiveSubtitleBus.publish(speech, translated, cueStart, cueDuration)
        context.startService(Intent(context, OverlayService::class.java)
            .setAction(OverlayService.ACTION_UPDATE)
            .putExtra(OverlayService.EXTRA_ORIGINAL, speech)
            .putExtra(OverlayService.EXTRA_TRANSLATED, translated)
            .putExtra(OverlayService.EXTRA_DURATION_MS, cueDuration))
        AppDatabase.get(context).translationDao().insert(entity)
        entity
    }


    /**
     * Even with adaptive routing disabled, try configured translation models in order.
     * This keeps the simple/manual mode resilient without enabling background benchmarks.
     */
    private suspend fun translateOmniRouteWithFallback(
        settings: AppSettings,
        text: String,
        source: String,
        target: String,
        context: TranslationContext,
        quality: TranslationQuality
    ): String {
        val candidates = omniRouteTranslationCandidates(
            settings.omniRouteTranslationModel,
            settings.omniRouteTranslationModels
        )
        val client = OmniRouteClient(settings.omniRouteBaseUrl, settings.omniRouteKey)
        var lastFailure: Throwable? = null
        candidates.forEachIndexed { index, model ->
            try {
                return client.translate(
                    text = text,
                    source = source,
                    target = target,
                    model = model,
                    context = context,
                    quality = quality
                )
            } catch (failure: Exception) {
                lastFailure = failure
                if (index < candidates.lastIndex) kotlinx.coroutines.delay(250L * (index + 1))
            }
        }
        throw IllegalStateException(
            "فشلت ترجمة OmniRoute عبر جميع النماذج المحددة: ${candidates.joinToString()}",
            lastFailure
        )
    }

    private fun buildWhisperPrompt(settings: AppSettings, context: TranslationContext): String {
        val glossary = (context.glossary.entries.map { "${it.key}=${it.value}" } + settings.glossary.lines().map { it.trim() }.filter { it.isNotBlank() }).joinToString(", ")
        val recent = context.previousSegments.takeLast(4).joinToString(" | ")
        return buildString {
            if (settings.translationQuality == SettingsRepository.QUALITY_HUMAN) append("[HIGH_ACCURACY] ")
            append("Transcribe spoken dialogue accurately. Preserve names, technical terms, places, and recurring words exactly when heard. ")
            if (glossary.isNotBlank()) append("Known terminology: ").append(glossary).append(". ")
            if (settings.contextPrompt.isNotBlank()) append("Additional translation context: ").append(settings.contextPrompt.take(1000)).append(". ")
            if (recent.isNotBlank()) append("Recent context for spelling only: ").append(recent)
        }.take(3_000)
    }

    fun resetEpisodeContext() = episodeContext.clearSession()

    suspend fun clearTranslationMemory() = withContext(Dispatchers.IO) { memory.clear() }
    companion object { private const val TAG = "SariSTTRoute" }
}
