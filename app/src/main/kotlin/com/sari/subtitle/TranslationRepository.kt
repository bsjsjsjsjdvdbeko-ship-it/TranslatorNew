package com.sari.subtitle
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
class TranslationRepository(private val context:Context){
 private val deduplicator=TranscriptDeduplicator()
 suspend fun translateAudio(audioFile:File):TranslationEntity=withContext(Dispatchers.IO){val s=SettingsRepository(context).load();val speech=WhisperClient(s.speechKey).transcribe(audioFile,s.speechLanguage);if(!deduplicator.shouldTranslate(speech))return@withContext TranslationEntity(sourceText=speech,translatedText="",sourceLanguage=s.sourceLanguage,targetLanguage=s.targetLanguage,createdAt=System.currentTimeMillis());val provider=if(s.provider==0)TranslationProvider.GOOGLE else TranslationProvider.MYMEMORY;val translated=TranslationClient(provider,s.translationKey,s.myMemoryEmail).translate(speech,s.sourceLanguage,s.targetLanguage);val entity=TranslationEntity(speech,translated,s.sourceLanguage,s.targetLanguage,System.currentTimeMillis());AppDatabase.get(context).translationDao().insert(entity);context.startService(android.content.Intent(context,OverlayService::class.java).setAction(OverlayService.ACTION_UPDATE).putExtra(OverlayService.EXTRA_ORIGINAL,speech).putExtra(OverlayService.EXTRA_TRANSLATED,translated));entity}
}
