package com.sari.subtitle

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class TranslationRepository(private val context:Context){
    private val deduplicator=TranscriptDeduplicator()
    private val memory by lazy{AppDatabase.get(context).translationMemoryDao()}
    private val episodeContext by lazy{EpisodeContextStore(context)}

    suspend fun translateAudio(audioFile:File,capturedAtElapsedMs:Long=SystemClock.elapsedRealtime(),durationMs:Long=AudioChunker.CHUNK_MS):TranslationEntity=withContext(Dispatchers.IO){
        val settings=SettingsRepository(context).load()
        val pre=if(settings.contextAwareTranslation)episodeContext.snapshot() else TranslationContext()
        val prompt=buildPrompt(settings,pre)
        val stt=createStt(settings)
        val speechResult=stt.transcribe(audioFile,settings.speechLanguage,prompt)
        if(!speechResult.success) throw IllegalStateException(speechResult.error ?: "فشل تحويل الصوت إلى نص.")
        val speech=speechResult.text.trim()
        val sourceLanguage=if(settings.sourceLanguage=="auto") speechResult.language ?: "auto" else settings.sourceLanguage
        val target=settings.targetLanguage.ifBlank{"ar"}
        if(speech.isBlank()||!deduplicator.shouldTranslate(speech)) return@withContext TranslationEntity(sourceText=speech,translatedText="",sourceLanguage=sourceLanguage,targetLanguage=target,createdAt=System.currentTimeMillis())
        val hash=TextHash.sha256(speech)
        val cached=if(settings.translationMemoryEnabled)memory.find(hash,sourceLanguage,target) else null
        val context=if(settings.contextAwareTranslation)episodeContext.snapshot() else TranslationContext()
        val translated=cached?.translatedText ?: run{
            val quality=when(settings.translationQuality){0->TranslationQuality.FAST;1->TranslationQuality.BALANCED;else->TranslationQuality.HUMAN}
            val provider=when(settings.provider){SettingsRepository.PROVIDER_GROQ->TranslationProvider.GROQ;SettingsRepository.PROVIDER_GOOGLE->TranslationProvider.GOOGLE;SettingsRepository.PROVIDER_OPENAI->TranslationProvider.OPENAI;else->TranslationProvider.MYMEMORY}
            val key=when(provider){TranslationProvider.GROQ->settings.speechKey;TranslationProvider.OPENAI->settings.openAiKey;else->settings.translationKey}
            val result=TranslationClient(provider,key,settings.myMemoryEmail).translateWithContext(speech,sourceLanguage,target,context,quality)
            if(settings.translationMemoryEnabled){memory.insert(TranslationMemoryEntity(sourceHash=hash,sourceText=speech,translatedText=result,sourceLanguage=sourceLanguage,targetLanguage=target,createdAt=System.currentTimeMillis()));memory.trim()}
            result
        }
        episodeContext.addTurn(speech,translated)
        val startOffset=(speechResult.utterances.minOfOrNull{it.start}?.times(1000)?.toLong() ?: speechResult.words.minOfOrNull{it.start}?.times(1000)?.toLong() ?: 0L)
        val endOffset=(speechResult.utterances.maxOfOrNull{it.end}?.times(1000)?.toLong() ?: speechResult.words.maxOfOrNull{it.end}?.times(1000)?.toLong() ?: durationMs.toDouble()).toLong()
        val start=capturedAtElapsedMs+startOffset+settings.calibrationMs
        val end=capturedAtElapsedMs+endOffset+settings.calibrationMs
        val cueDuration=(end-start).coerceIn(900L,6000L)
        LiveSubtitleBus.publish(speech,translated,start,cueDuration)
        runCatching{context.startService(Intent(context,OverlayService::class.java).setAction(OverlayService.ACTION_UPDATE).putExtra(OverlayService.EXTRA_ORIGINAL,speech).putExtra(OverlayService.EXTRA_TRANSLATED,translated).putExtra(OverlayService.EXTRA_DURATION_MS,cueDuration))}
        val entity=TranslationEntity(sourceText=speech,translatedText=translated,sourceLanguage=sourceLanguage,targetLanguage=target,createdAt=System.currentTimeMillis())
        AppDatabase.get(context).translationDao().insert(entity)
        entity
    }

    private fun createStt(settings:AppSettings):SttClient=when(settings.sttProvider){
        SettingsRepository.STT_DEEPGRAM->DeepgramClient(settings.deepgramKey)
        SettingsRepository.STT_VOSK->VoskClient(ModelDownloader(context).modelDirectory())
        else->WhisperClient(settings.speechKey)
    }
    private fun buildPrompt(settings:AppSettings,c:TranslationContext)=buildString{if(settings.contextPrompt.isNotBlank())append(settings.contextPrompt).append("\n");if(c.glossary.isNotEmpty())append("Known terminology: ").append(c.glossary.entries.joinToString(", "){"${it.key}=${it.value}"}).append(". ");if(c.previousSegments.isNotEmpty())append("Recent dialogue for spelling/context: ").append(c.previousSegments.takeLast(4).joinToString(" | "))}.take(3000)
    fun resetEpisodeContext()=episodeContext.clearSession()
    suspend fun clearTranslationMemory()=withContext(Dispatchers.IO){memory.clear()}
}
