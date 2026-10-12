package com.sari.subtitle

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class TranslationClient(private val provider:TranslationProvider,private val apiKey:String,private val email:String="",private val http:OkHttpClient=WhisperClient.defaultClient()){
    suspend fun translate(text:String,source:String,target:String):String=translateWithContext(text,source,target,TranslationContext(),TranslationQuality.BALANCED)
    suspend fun translateWithContext(text:String,source:String,target:String,context:TranslationContext,quality:TranslationQuality=TranslationQuality.HUMAN):String=withContext(Dispatchers.IO){
        require(text.isNotBlank()){ "لا يوجد نص لترجمته." }; require(target.isNotBlank()){ "حدد لغة الترجمة الهدف." }
        if(provider==TranslationProvider.OPENAI) return@withContext OpenAiClient(apiKey,http).translate(text,source,target,context)
        val request=when(provider){
            TranslationProvider.GROQ->{require(apiKey.isNotBlank()){ "أدخل مفتاح Groq API في الإعدادات." }; val payload=JSONObject().put("model",if(quality==TranslationQuality.HUMAN)"openai/gpt-oss-120b" else "openai/gpt-oss-20b").put("temperature",0.0).put("messages",JSONArray().put(JSONObject().put("role","system").put("content",buildGroqPrompt(source,target,context))).put(JSONObject().put("role","user").put("content",text))).toString(); Request.Builder().url("https://api.groq.com/openai/v1/chat/completions").header("Authorization","Bearer $apiKey").post(payload.toRequestBody("application/json; charset=utf-8".toMediaType())).build()}
            TranslationProvider.GOOGLE->{require(apiKey.isNotBlank()){ "مفتاح Google Cloud Translation غير موجود." }; val payload=JSONObject().put("q",text).put("target",target).apply{if(source!="auto")put("source",source)}.toString(); Request.Builder().url("https://translation.googleapis.com/language/translate/v2?key=${URLEncoder.encode(apiKey,"UTF-8")}").post(payload.toRequestBody("application/json; charset=utf-8".toMediaType())).build()}
            TranslationProvider.MYMEMORY->{val s=if(source=="auto")"en" else source; val q=URLEncoder.encode(text,"UTF-8"); val pair=URLEncoder.encode("$s|$target","UTF-8"); val e=if(email.isBlank())"" else "&de="+URLEncoder.encode(email,"UTF-8"); Request.Builder().url("https://api.mymemory.translated.net/get?q=$q&langpair=$pair$e").get().build()}
            TranslationProvider.OPENAI->error("unreachable")
        }
        execute(request)
    }
    private fun buildGroqPrompt(source:String,target:String,c:TranslationContext)=buildString{append("You are a professional human subtitle translator. Translate dialogue from ${if(source.isBlank()||source=="auto")"the detected source language" else source} to $target. Prioritize meaning, natural spoken dialogue, consistency, emotion and concise subtitle phrasing. Preserve names and terminology. Never invent facts. Return only the translation.\n"); appendContext(c)}
    private fun appendContext(c:TranslationContext)=buildString{if(c.previousTranslations.isNotEmpty()){append("Recent dialogue:\n");c.previousTranslations.takeLast(6).forEach{append("SRC: ${it.source}\nTGT: ${it.translation}\n")}};if(c.glossary.isNotEmpty())append("Glossary:\n${c.glossary.entries.joinToString("\n"){ "${it.key} = ${it.value}"}}\n");if(c.characterNotes.isNotBlank())append("Speaker/name notes: ${c.characterNotes}\n")}
    private suspend fun execute(request:Request):String{var last:Exception?=null;repeat(3){i->try{return http.newCall(request).execute().use{r->val raw=r.body?.string().orEmpty();if(!r.isSuccessful)error("HTTP ${r.code}: ${raw.take(180)}");val result=when(provider){TranslationProvider.GROQ->JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim();TranslationProvider.GOOGLE->JSONObject(raw).getJSONObject("data").getJSONArray("translations").getJSONObject(0).getString("translatedText").trim();TranslationProvider.MYMEMORY->{val j=JSONObject(raw);if(j.optString("responseStatus")!="200")error(j.optString("responseDetails"));j.getJSONObject("responseData").getString("translatedText").trim()};TranslationProvider.OPENAI->""};if(result.isBlank())error("خدمة الترجمة أعادت نصاً فارغاً.");result}}catch(e:Exception){last=e;Log.d("SariTranslation","attempt ${i+1}: ${e.message}");if(i<2)delay(500L*(i+1))}};throw IllegalStateException("فشلت الترجمة بعد 3 محاولات.",last)}
}
data class TranslationContext(val previousSegments:List<String> = emptyList(),val previousTranslations:List<ContextTurn> = emptyList(),val glossary:Map<String,String> = emptyMap(),val characterNotes:String="")
enum class TranslationProvider{GROQ,GOOGLE,MYMEMORY,OPENAI}
enum class TranslationQuality{FAST,BALANCED,HUMAN}
