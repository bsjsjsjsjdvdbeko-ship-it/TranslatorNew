package com.sari.subtitle

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppSettings(
    val provider: Int = SettingsRepository.PROVIDER_MYMEMORY,
    val speechKey: String = "",
    val translationKey: String = "",
    val sourceLanguage: String = "auto",
    val targetLanguage: String = "ar",
    val fontSize: Int = 30,
    val opacity: Int = 82,
    val myMemoryEmail: String = "",
    val speechLanguage: String = "auto",
    val contextAwareTranslation: Boolean = true,
    val translationMemoryEnabled: Boolean = true,
    val glossary: String = "",
    val translationQuality: Int = SettingsRepository.QUALITY_HUMAN,
    val sttProvider: Int = SettingsRepository.STT_GROQ,
    val deepgramKey: String = "",
    val openAiKey: String = "",
    val contextPrompt: String = "",
    val subtitlePosition: Int = SettingsRepository.POS_BOTTOM,
    val calibrationMs: Long = 0L
)

class SettingsRepository(context: Context) {
    private val prefs = try {
        val master=MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(context,"sari_secure_settings",master,EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    } catch (_:Exception){null}

    suspend fun save(provider:Int,speechKey:String,translationKey:String,source:String,target:String,font:Int,opacity:Int,email:String,speechLanguage:String,contextAwareTranslation:Boolean=true,translationMemoryEnabled:Boolean=true,glossary:String="",translationQuality:Int=QUALITY_HUMAN,sttProvider:Int=STT_GROQ,deepgramKey:String="",openAiKey:String="",contextPrompt:String="",subtitlePosition:Int=POS_BOTTOM,calibrationMs:Long=0L)=withContext(Dispatchers.IO){
        val p=prefs ?: throw IllegalStateException("تعذر تفعيل التخزين المشفر على هذا الجهاز.")
        p.edit().putInt("provider",provider.coerceIn(PROVIDER_GROQ,PROVIDER_OPENAI)).putBoolean("providerSchemaV3",true)
            .putString("speechKey",speechKey).putString("translationKey",translationKey).putString("source",source).putString("target",target)
            .putInt("font",font.coerceIn(12,48)).putInt("opacity",opacity.coerceIn(0,100)).putString("email",email).putString("speechLanguage",speechLanguage)
            .putBoolean("contextAwareTranslation",contextAwareTranslation).putBoolean("translationMemoryEnabled",translationMemoryEnabled).putString("glossary",glossary)
            .putInt("translationQuality",translationQuality.coerceIn(QUALITY_FAST,QUALITY_HUMAN)).putInt("sttProvider",sttProvider.coerceIn(STT_GROQ,STT_VOSK))
            .putString("deepgramKey",deepgramKey).putString("openAiKey",openAiKey).putString("contextPrompt",contextPrompt.take(4000))
            .putInt("subtitlePosition",subtitlePosition.coerceIn(POS_TOP,POS_BOTTOM)).putLong("calibrationMs",calibrationMs.coerceIn(-10000L,10000L)).apply()
    }

    suspend fun load():AppSettings=withContext(Dispatchers.IO){
        val p=prefs ?: return@withContext AppSettings()
        val stored=p.getInt("provider",PROVIDER_MYMEMORY)
        val provider=if(p.getBoolean("providerSchemaV3",false)) stored.coerceIn(0,3) else if(p.getBoolean("providerSchemaV2",false)) stored.coerceIn(0,2) else if(stored==0) PROVIDER_GOOGLE else PROVIDER_MYMEMORY
        AppSettings(provider=provider,speechKey=p.getString("speechKey","").orEmpty(),translationKey=p.getString("translationKey","").orEmpty(),sourceLanguage=p.getString("source","auto").orEmpty(),targetLanguage=p.getString("target","ar").orEmpty(),fontSize=p.getInt("font",30),opacity=p.getInt("opacity",82),myMemoryEmail=p.getString("email","").orEmpty(),speechLanguage=p.getString("speechLanguage","auto").orEmpty(),contextAwareTranslation=p.getBoolean("contextAwareTranslation",true),translationMemoryEnabled=p.getBoolean("translationMemoryEnabled",true),glossary=p.getString("glossary","").orEmpty(),translationQuality=p.getInt("translationQuality",QUALITY_HUMAN).coerceIn(0,2),sttProvider=p.getInt("sttProvider",STT_GROQ).coerceIn(0,2),deepgramKey=p.getString("deepgramKey","").orEmpty(),openAiKey=p.getString("openAiKey","").orEmpty(),contextPrompt=p.getString("contextPrompt","").orEmpty(),subtitlePosition=p.getInt("subtitlePosition",POS_BOTTOM).coerceIn(0,2),calibrationMs=p.getLong("calibrationMs",0L))
    }
    companion object{
        const val PROVIDER_GROQ=0; const val PROVIDER_GOOGLE=1; const val PROVIDER_MYMEMORY=2; const val PROVIDER_OPENAI=3
        const val STT_GROQ=0; const val STT_DEEPGRAM=1; const val STT_VOSK=2
        const val QUALITY_FAST=0; const val QUALITY_BALANCED=1; const val QUALITY_HUMAN=2
        const val POS_TOP=0; const val POS_CENTER=1; const val POS_BOTTOM=2
    }
}
