package com.sari.subtitle

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Deepgram Nova file-based STT adapter. */
class DeepgramClient(
    private val apiKey: String,
    private val http: OkHttpClient = defaultClient(),
    private val model: String = "nova-2"
) : SttClient {
    override suspend fun transcribe(audioFile: File, language: String, prompt: String): TranscriptResult = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "أدخل مفتاح Deepgram API في الإعدادات." }
        val wav = SttDiagnostics.inspectWav(audioFile)
        Log.d(TAG, "Deepgram request model=$model file=${audioFile.name} size=${wav.sizeBytes} wavValid=${wav.valid} sampleRate=${wav.sampleRate} channels=${wav.channels} bits=${wav.bitsPerSample} dataBytes=${wav.dataBytes}")
        require(wav.valid) { "ملف WAV غير صالح أو لا يحتوي بيانات صوتية: ${wav.problem ?: "تحقق من الالتقاط"}." }
        val selectedModel = if (model.equals("nova-3", ignoreCase = true)) "nova-3" else "nova-2"
        val params = buildString {
            append("model=").append(java.net.URLEncoder.encode(selectedModel, "UTF-8"))
            append("&smart_format=true&punctuate=true")
            if (language.isNotBlank() && language != "auto") {
                append("&language=").append(java.net.URLEncoder.encode(language, "UTF-8"))
            }
        }
        val request = Request.Builder()
            .url("https://api.deepgram.com/v1/listen?$params")
            .header("Authorization", "Token $apiKey")
            .header("Content-Type", "audio/wav")
            .post(audioFile.asRequestBody("audio/wav".toMediaType()))
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.e(TAG, "Deepgram HTTP ${response.code}: ${raw.take(400)}")
                    throw SttHttpException(response.code, raw, "Deepgram")
                }
                Log.d(TAG, "Deepgram HTTP ${response.code}; responseBytes=${raw.length}")
                val root = JSONObject(raw)
                val alt = root.optJSONObject("results")?.optJSONArray("channels")?.optJSONObject(0)?.optJSONArray("alternatives")?.optJSONObject(0)
                val text = alt?.optString("transcript").orEmpty().trim()
                if (text.isBlank()) error("Deepgram أعاد نصاً فارغاً؛ تحقق من أن المقطع يحتوي صوتاً واضحاً.")
                TranscriptResult(text, emptyList())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Deepgram request failed: ${SttDiagnostics.messageFor(e)}", e)
            throw IllegalStateException(SttDiagnostics.messageFor(e), e)
        }
    }

    companion object {
        private const val TAG = "SariTranslate"
        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
