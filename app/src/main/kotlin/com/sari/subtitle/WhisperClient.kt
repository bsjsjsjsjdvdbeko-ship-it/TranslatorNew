package com.sari.subtitle

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class WhisperClient(private val apiKey: String, private val http: OkHttpClient = defaultClient()) : SttClient {
    override suspend fun transcribe(file: File, language: String, contextPrompt: String): SttResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext SttResult("", success = false, error = "أدخل مفتاح Groq API من الإعدادات.")
        if (!file.exists() || file.length() == 0L) return@withContext SttResult("", success = false, error = "الملف الصوتي فارغ.")
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-large-v3-turbo")
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("temperature", "0")
            .apply { if (contextPrompt.isNotBlank()) addFormDataPart("prompt", contextPrompt.take(3_000)) }
            .addFormDataPart("timestamp_granularities[]", "segment")
            .apply { if (language.isNotBlank() && language != "auto") addFormDataPart("language", language) }
            .addFormDataPart("file", file.name, file.asRequestBody("audio/wav".toMediaType())).build()
        val request = Request.Builder().url("https://api.groq.com/openai/v1/audio/transcriptions").header("Authorization", "Bearer $apiKey").post(body).build()
        executeWithRetry(request)
    }

    suspend fun detectLanguage(file: File): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || !file.exists()) return@withContext null
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-large-v3-turbo")
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("temperature", "0")
            .addFormDataPart("file", file.name, file.asRequestBody("audio/wav".toMediaType())).build()
        val request = Request.Builder().url("https://api.groq.com/openai/v1/audio/transcriptions").header("Authorization", "Bearer $apiKey").post(body).build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                JSONObject(response.body?.string().orEmpty()).optString("language").ifBlank { null }
            }
        } catch (e: Exception) { Log.d(TAG, "Language detection failed: ${e.message}"); null }
    }

    private suspend fun executeWithRetry(request: Request): SttResult {
        var last: Exception? = null
        repeat(3) { i ->
            try {
                http.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) error("Groq HTTP ${response.code}: ${raw.take(180)}")
                    return parse(raw)
                }
            } catch (e: Exception) {
                last = e; Log.d(TAG, "Groq STT attempt ${i + 1} failed: ${e.message}")
                if (i < 2) delay(500L * (i + 1))
            }
        }
        return SttResult("", success = false, error = "فشل Groq STT بعد 3 محاولات: ${last?.message}")
    }

    private fun parse(raw: String): SttResult = try {
        val json = JSONObject(raw)
        val text = json.optString("text").trim()
        val segments = mutableListOf<Utterance>()
        json.optJSONArray("segments")?.let { a -> for (i in 0 until a.length()) { val s=a.optJSONObject(i) ?: continue; val t=s.optString("text").trim(); val st=s.optDouble("start", Double.NaN); val en=s.optDouble("end", Double.NaN); if(t.isNotBlank()&&st.isFinite()&&en.isFinite()) segments += Utterance(t,st,en) } }
        SttResult(text, json.optString("language").ifBlank { null }, utterances = segments, success = true)
    } catch (e: Exception) { SttResult("", success = false, error = "تعذر تحليل Groq: ${e.message}") }

    companion object { private const val TAG="SariWhisper"; fun defaultClient()=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(90,TimeUnit.SECONDS).callTimeout(120,TimeUnit.SECONDS).build() }
}
