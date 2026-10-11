package com.sari.subtitle

import android.content.Context
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
import java.io.IOException
import java.util.concurrent.TimeUnit

class WhisperClient(
    private val context: Context,
    private val apiKeyProvider: () -> String,
    private val languageProvider: () -> String = { "auto" }
) {

    companion object {
        private const val TAG = "SariWhisperClient"
        private const val ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"
        private const val MODEL = "whisper-large-v3-turbo"
        private const val MAX_ATTEMPTS = 3
        private const val TIMEOUT_SECONDS = 30L
    }

    data class TranscriptionResult(
        val text: String,
        val language: String? = null,
        val confidence: Double? = null,
        val success: Boolean = true,
        val error: String? = null
    )

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun transcribe(audioFile: File): TranscriptionResult = withContext(Dispatchers.IO) {
        try {
            if (!audioFile.exists() || !audioFile.isFile) {
                Log.e(TAG, "Audio file does not exist")
                return@withContext failure("ملف الصوت غير موجود")
            }
            if (audioFile.length() <= 44L) {
                Log.w(TAG, "Audio file is empty or too short")
                return@withContext failure("ملف الصوت فارغ")
            }

            val apiKey = try { apiKeyProvider().trim() } catch (e: Exception) {
                Log.e(TAG, "Unable to read Groq API key", e)
                return@withContext failure("تعذر قراءة مفتاح Groq")
            }

            if (apiKey.isBlank()) {
                Log.e(TAG, "Groq API key is missing")
                return@withContext failure("أدخل مفتاح Groq API في الإعدادات")
            }

            val language = normalizeLanguage(
                try { languageProvider() } catch (e: Exception) { "auto" }
            )

            Log.d(TAG, "Transcription started: model=$MODEL, language=$language, bytes=${audioFile.length()}")

            var lastError = "فشل التفريغ الصوتي"

            for (attempt in 1..MAX_ATTEMPTS) {
                try {
                    Log.d(TAG, "Request attempt $attempt/$MAX_ATTEMPTS")
                    val result = executeRequest(audioFile, apiKey, language)
                    if (result.success) {
                        Log.d(TAG, "Transcription succeeded; characters=${result.text.length}")
                        return@withContext result
                    }
                    lastError = result.error ?: lastError
                    if (lastError.contains("401") || lastError.contains("403") || lastError.contains("400")) {
                        Log.e(TAG, "Non-retryable API error: $lastError")
                        return@withContext result
                    }
                } catch (e: IOException) {
                    lastError = e.message ?: "خطأ في الاتصال"
                    Log.w(TAG, "Network error on attempt $attempt", e)
                } catch (e: Exception) {
                    lastError = e.message ?: "خطأ غير متوقع"
                    Log.e(TAG, "Request attempt failed", e)
                }
                if (attempt < MAX_ATTEMPTS) {
                    val backoffMillis = 1000L * (1L shl (attempt - 1))
                    Log.d(TAG, "Retrying after ${backoffMillis}ms")
                    delay(backoffMillis)
                }
            }
            Log.e(TAG, "All transcription attempts failed: $lastError")
            failure(lastError)
        } catch (e: Exception) {
            Log.e(TAG, "Transcription operation failed", e)
            failure(e.message ?: "حدث خطأ أثناء التفريغ الصوتي")
        }
    }

    private fun executeRequest(audioFile: File, apiKey: String, language: String): TranscriptionResult {
        val wavMediaType = "audio/wav".toMediaType()
        val fileBody = audioFile.asRequestBody(wavMediaType)
        val multipartBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, fileBody)
            .addFormDataPart("model", MODEL)
            .addFormDataPart("temperature", "0")
            .addFormDataPart("response_format", "json")
        if (language != "auto") {
            multipartBuilder.addFormDataPart("language", language)
        }
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .post(multipartBuilder.build())
            .build()
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = try {
                    val json = JSONObject(responseBody)
                    json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                        ?: responseBody.take(500)
                } catch (_: Exception) { responseBody.take(500) }
                Log.e(TAG, "Groq HTTP ${response.code}: $message")
                return TranscriptionResult(text = "", success = false, error = "Groq HTTP ${response.code}: $message")
            }
            val json = try { JSONObject(responseBody) } catch (e: Exception) {
                Log.e(TAG, "Invalid JSON response", e)
                return failure("استجابة Groq غير صالحة")
            }
            val text = json.optString("text", "").trim()
            if (text.isBlank()) {
                Log.w(TAG, "Groq returned empty transcription")
                return TranscriptionResult(text = "", success = false, error = "لم يتم التعرف على كلام في المقطع")
            }
            return TranscriptionResult(
                text = text,
                language = json.optString("language").takeIf { it.isNotBlank() },
                confidence = null,
                success = true
            )
        }
    }

    private fun normalizeLanguage(language: String): String {
        return when (language.trim().lowercase()) {
            "ar", "arabic" -> "ar"
            "en", "english" -> "en"
            "auto", "", "detect" -> "auto"
            else -> "auto"
        }
    }

    private fun failure(message: String): TranscriptionResult {
        return TranscriptionResult(text = "", success = false, error = message)
    }

    suspend fun testApiKey(apiKey: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) return@withContext false
            val request = Request.Builder()
                .url("https://api.groq.com/openai/v1/models")
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val valid = response.isSuccessful
                Log.d(TAG, "API key test result: $valid")
                valid
            }
        } catch (e: Exception) {
            Log.e(TAG, "API key validation failed", e)
            false
        }
    }
}
