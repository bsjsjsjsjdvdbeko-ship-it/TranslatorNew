package com.sari.subtitle

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class TranslationClient(
    private val provider: TranslationProvider,
    private val apiKey: String,
    private val http: OkHttpClient = WhisperClient.defaultClient()
) {
    suspend fun translate(text: String, source: String, target: String): String = withContext(Dispatchers.IO) {
        require(text.isNotBlank()) { "Text is empty." }
        require(apiKey.isNotBlank()) { "Translation API key is missing." }
        val url: String
        val body: String
        val request = when (provider) {
            TranslationProvider.GOOGLE -> {
                url = "https://translation.googleapis.com/language/translate/v2"
                body = JSONObject().put("q", text).put("source", if (source == "auto") "auto" else source)
                    .put("target", target).toString()
                Request.Builder().url("$url?key=$apiKey")
                    .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            }
            TranslationProvider.DEEPL -> {
                url = "https://api-free.deepl.com/v2/translate"
                body = JSONObject().put("text", org.json.JSONArray().put(text))
                    .put("source_lang", source.uppercase().takeUnless { source == "auto" })
                    .put("target_lang", target.uppercase()).toString()
                val reqBody = body.toRequestBody("application/json; charset=utf-8".toMediaType())
                Request.Builder().url(url).header("Authorization", "DeepL-Auth-Key $apiKey").post(reqBody).build()
            }
            TranslationProvider.OPENAI -> {
                url = "https://api.openai.com/v1/chat/completions"
                body = JSONObject().put("model", "gpt-4o-mini")
                    .put("messages", org.json.JSONArray().put(JSONObject().put("role", "system")
                        .put("content", "Translate the user's text into $target. Return only the translation."))
                        .put(JSONObject().put("role", "user").put("content", text))).toString()
                Request.Builder().url(url).header("Authorization", "Bearer $apiKey")
                    .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            }
        }
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                http.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) throw IllegalStateException("Translation HTTP ${response.code}: ${raw.take(300)}")
                    return@withContext parseResponse(provider, raw)
                }
            } catch (e: Exception) {
                last = e
                if (attempt < 2) kotlinx.coroutines.delay(500L * (attempt + 1))
            }
        }
        throw last ?: IllegalStateException("Translation failed.")
    }

    private fun parseResponse(provider: TranslationProvider, raw: String): String {
        val json = JSONObject(raw)
        return when (provider) {
            TranslationProvider.GOOGLE -> json.getJSONObject("data").getJSONArray("translations")
                .getJSONObject(0).getString("translatedText")
            TranslationProvider.DEEPL -> json.getJSONArray("translations").getJSONObject(0).getString("text")
            TranslationProvider.OPENAI -> json.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
        }
    }
}

enum class TranslationProvider { GOOGLE, DEEPL, OPENAI }
