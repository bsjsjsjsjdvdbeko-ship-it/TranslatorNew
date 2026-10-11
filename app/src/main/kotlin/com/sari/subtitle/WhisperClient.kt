package com.sari.subtitle

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class WhisperClient(private val apiKey: String, private val http: OkHttpClient = defaultClient()) {
    suspend fun transcribe(audioFile: File, language: String? = null): String = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "OpenAI API key is missing." }
        require(audioFile.exists() && audioFile.length() > 0) { "Audio file is empty." }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("response_format", "json")
            .apply { if (!language.isNullOrBlank() && language != "auto") addFormDataPart("language", language) }
            .addFormDataPart("file", audioFile.name,
                audioFile.asRequestBody("application/octet-stream".toMediaType())).build()
        val request = Request.Builder().url("https://api.openai.com/v1/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey").post(body).build()
        retryRequest(request) { JSONObject(it).optString("text").also { text ->
            if (text.isBlank()) throw IllegalStateException("Whisper returned no text.")
        } }
    }

    private suspend fun <T> retryRequest(request: Request, parse: (String) -> T): T {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                http.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) throw IllegalStateException("Whisper HTTP ${response.code}: ${text.take(300)}")
                    return@retryRequest parse(text)
                }
            } catch (e: Exception) {
                last = e
                if (attempt < 2) kotlinx.coroutines.delay(500L * (attempt + 1))
            }
        }
        throw last ?: IllegalStateException("Whisper request failed.")
    }

    companion object {
        fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS).build()
    }
}
