package com.sari.subtitle

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Groq-hosted Whisper Large V3 Turbo transcription client. */
class WhisperClient(private val apiKey: String, private val http: OkHttpClient = defaultClient()) {
    suspend fun transcribe(audioFile: File, language: String = "ar"): String = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "أدخل مفتاح Groq API من الإعدادات." }
        require(audioFile.exists() && audioFile.length() > 0) { "الملف الصوتي فارغ." }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-large-v3-turbo")
            .addFormDataPart("response_format", "json")
            .apply { if (language.isNotBlank() && language != "auto") addFormDataPart("language", language) }
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("application/octet-stream".toMediaType())).build()
        val request = Request.Builder().url("https://api.groq.com/openai/v1/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey").post(body).build()
        retry(request) { raw -> JSONObject(raw).optString("text").trim().also { if (it.isBlank()) error("لم يُرجع Groq نصاً صوتياً.") } }
    }
    private suspend fun <T> retry(request: Request, parse: (String) -> T): T {
        var last: Exception? = null
        repeat(3) { i -> try { http.newCall(request).execute().use { r -> val raw=r.body?.string().orEmpty(); if(!r.isSuccessful) error("فشل Groq (${r.code}): ${raw.take(180)}"); return parse(raw) } }
            catch(e: Exception) { last=e; if(i<2) kotlinx.coroutines.delay(500L*(i+1)) } }
        throw IllegalStateException("تعذّر الاتصال بخدمة تحويل الصوت بعد 3 محاولات. تحقق من الإنترنت والمفتاح.", last)
    }
    companion object { fun defaultClient() = OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(90,TimeUnit.SECONDS).callTimeout(120,TimeUnit.SECONDS).build() }
}
