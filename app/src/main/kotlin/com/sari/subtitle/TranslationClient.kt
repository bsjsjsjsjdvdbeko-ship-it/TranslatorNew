package com.sari.subtitle

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder

class TranslationClient(private val provider: TranslationProvider, private val apiKey: String, private val email: String = "", private val http: OkHttpClient = WhisperClient.defaultClient()) {
 suspend fun translate(text: String, source: String, target: String): String = withContext(Dispatchers.IO) {
  require(text.isNotBlank()) { "لا يوجد نص لترجمته." }; require(target.isNotBlank()) { "حدد لغة الترجمة الهدف." }
  val request = when(provider) {
   TranslationProvider.GOOGLE -> {
    require(apiKey.isNotBlank()) { "مفتاح Google Cloud Translation غير موجود." }
    val payload=JSONObject().put("q",text).put("target",target).apply { if(source!="auto") put("source",source) }.toString()
    Request.Builder().url("https://translation.googleapis.com/language/translate/v2?key=$apiKey").post(payload.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
   }
   TranslationProvider.MYMEMORY -> {
    val s=if(source=="auto") "en" else source
    val q=URLEncoder.encode(text,"UTF-8"); val pair=URLEncoder.encode("$s|$target","UTF-8")
    val e=if(email.isBlank()) "" else "&de="+URLEncoder.encode(email,"UTF-8")
    Request.Builder().url("https://api.mymemory.translated.net/get?q=$q&langpair=$pair$e").get().build()
   }
  }
  var last:Exception?=null
  repeat(3) { i -> try { http.newCall(request).execute().use { r -> val raw=r.body?.string().orEmpty(); if(!r.isSuccessful) error("HTTP ${r.code}"); val j=JSONObject(raw); val result=if(provider==TranslationProvider.GOOGLE) j.getJSONObject("data").getJSONArray("translations").getJSONObject(0).getString("translatedText") else { if(j.optString("responseStatus")!="200") error("خدمة MyMemory لم تقبل الطلب: ${j.optString("responseDetails")}"); j.getJSONObject("responseData").getString("translatedText") }; return@withContext result } } catch(e:Exception) {last=e; if(i<2) kotlinx.coroutines.delay(500L*(i+1))} }
  throw IllegalStateException("فشلت الترجمة بعد 3 محاولات. تحقق من الإنترنت ومفاتيح الخدمة.",last)
 }
}
enum class TranslationProvider { GOOGLE, MYMEMORY }
