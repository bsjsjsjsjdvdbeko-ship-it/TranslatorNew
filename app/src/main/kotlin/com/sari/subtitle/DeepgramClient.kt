package com.sari.subtitle

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Deepgram Nova file-based STT adapter; WAV is sent as raw audio bytes. */
class DeepgramClient(
    private val apiKey: String,
    private val http: OkHttpClient = defaultClient(),
    private val model: String = "nova-2"
) : SttClient {
    override suspend fun transcribe(audioFile: File, language: String, prompt: String): TranscriptResult = withContext(Dispatchers.IO) {
        val key = apiKey.trim()
        require(key.isNotBlank()) { "أدخل مفتاح Deepgram API في الإعدادات." }
        require(!key.contains('\r') && !key.contains('\n')) { "مفتاح Deepgram يحتوي على محارف غير صالحة؛ أعد حفظه من الإعدادات." }
        val wav = SttDiagnostics.inspectWav(audioFile)
        val selectedModel = if (model.equals("nova-3", ignoreCase = true) || model.equals("nova-3-general", ignoreCase = true)) "nova-3" else "nova-2"
        val selectedLanguage = SttLanguagePolicy.forDeepgram(language)
        Log.d(TAG, "Deepgram STT request model=$selectedModel file=${audioFile.name} sizeBytes=${wav.sizeBytes} wavValid=${wav.valid} sampleRate=${wav.sampleRate} channels=${wav.channels} bits=${wav.bitsPerSample} dataBytes=${wav.dataBytes} language=${selectedLanguage ?: "default"}")
        require(wav.valid) { "ملف WAV غير صالح أو لا يحتوي بيانات صوتية: ${wav.problem ?: "تحقق من الالتقاط"}." }
        val query = buildString {
            append("model=").append(URLEncoder.encode(selectedModel, "UTF-8"))
            append("&smart_format=true&punctuate=true")
            if (selectedLanguage != null) append("&language=").append(URLEncoder.encode(selectedLanguage, "UTF-8"))
        }
        val request = Request.Builder()
            .url("$ENDPOINT?$query")
            .header("Authorization", "Token $key")
            .header("Content-Type", "audio/wav")
            .post(audioFile.asRequestBody("audio/wav".toMediaType()))
            .build()
        val raw = SttRequestExecutor.execute("Deepgram", http, request)
        try {
            val root = JSONObject(raw)
            val channels = root.optJSONObject("results")?.optJSONArray("channels")
            val alternatives = channels?.optJSONObject(0)?.optJSONArray("alternatives")
            val alt = alternatives?.optJSONObject(0)
            val text = alt?.optString("transcript").orEmpty().trim()
            if (text.isBlank()) throw IllegalStateException("أعاد Deepgram استجابة ناجحة لكن النص فارغ؛ قد لا يحتوي المقطع على كلام واضح.")
            Log.i(TAG, "Deepgram STT result=success model=$selectedModel textChars=${text.length}")
            TranscriptResult(text, emptyList())
        } catch (e: Exception) {
            Log.e(TAG, "Deepgram STT result=parse_error responseChars=${raw.length} exception=${e.javaClass.simpleName}", e)
            throw IllegalStateException("نجح اتصال Deepgram لكن تعذر تحليل استجابة تحويل الصوت. افحص إصدار الاستجابة وسجل التشخيص.", e)
        }
    }

    companion object {
        private const val TAG = "SariTranslate"
        const val ENDPOINT = "https://api.deepgram.com/v1/listen"
        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
