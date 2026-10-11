package com.sari.subtitle

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import org.json.JSONObject

class TranslationClient(
    private val providerProvider: () -> String = { "mymemory" },
    private val googleApiKeyProvider: () -> String = { "" },
    private val myMemoryEmailProvider: () -> String = { "" }
) {

    companion object {
        private const val TAG = "SariTranslationClient"
        private const val MYMEMORY_ENDPOINT = "https://api.mymemory.translated.net/get"
        private const val GOOGLE_ENDPOINT = "https://translation.googleapis.com/language/translate/v2"
        private const val MAX_CACHE_ENTRIES = 500
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    private val translationCache = ConcurrentHashMap<String, String>()

    suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String
    ): String = withContext(Dispatchers.IO) {
        try {
            val input = text.trim()
            if (input.isBlank()) {
                Log.d(TAG, "Empty text; translation skipped")
                return@withContext ""
            }

            val source = normalizeLanguage(sourceLang)
            val target = normalizeLanguage(targetLang)

            if (target == "auto") {
                Log.e(TAG, "Target language cannot be auto")
                return@withContext input
            }

            if (source != "auto" && source == target) {
                Log.d(TAG, "Source and target languages match")
                return@withContext input
            }

            val provider = normalizeProvider(
                try { providerProvider() } catch (e: Exception) { "mymemory" }
            )

            val cacheKey = "$provider|$source|$target|$input"
            translationCache[cacheKey]?.let { cached ->
                Log.d(TAG, "Translation cache hit")
                return@withContext cached
            }

            Log.d(TAG, "Translating: provider=$provider, source=$source, target=$target")

            val translated = when (provider) {
                "google" -> {
                    val key = try { googleApiKeyProvider().trim() } catch (e: Exception) { "" }
                    if (key.isBlank()) {
                        Log.w(TAG, "Google selected without key; using MyMemory")
                        translateWithMyMemory(input, source, target)
                    } else {
                        translateWithGoogle(input, source, target, key)
                    }
                }
                "mymemory" -> translateWithMyMemory(input, source, target)
                else -> translateWithMyMemory(input, source, target)
            }

            if (translated.isBlank()) {
                Log.w(TAG, "Translation returned empty result")
                return@withContext input
            }

            if (translationCache.size >= MAX_CACHE_ENTRIES) {
                translationCache.clear()
                Log.d(TAG, "Translation cache cleared")
            }
            translationCache[cacheKey] = translated
            Log.d(TAG, "Translation succeeded: ${translated.length} characters")
            translated
        } catch (e: Exception) {
            Log.e(TAG, "Translation failed", e)
            text
        }
    }

    private fun translateWithMyMemory(text: String, source: String, target: String): String {
        try {
            val email = try { myMemoryEmailProvider().trim() } catch (e: Exception) { "" }
            val languagePair = "${sourceForApi(source)}|${targetForApi(target)}"
            val urlBuilder = MYMEMORY_ENDPOINT.toHttpUrl().newBuilder()
                .addQueryParameter("q", text)
                .addQueryParameter("langpair", languagePair)
            if (email.isNotBlank()) {
                urlBuilder.addQueryParameter("de", email)
            }
            val request = Request.Builder()
                .url(urlBuilder.build())
                .header("Accept", "application/json")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.e(TAG, "MyMemory HTTP error: ${response.code}")
                    throw IOException("MyMemory HTTP ${response.code}")
                }
                val json = JSONObject(body)
                val responseStatus = json.optInt("responseStatus", 200)
                if (responseStatus != 200) {
                    val details = json.optString("responseDetails", "MyMemory translation error")
                    Log.e(TAG, "MyMemory API error: $details")
                    throw IOException(details)
                }
                val translated = json.optJSONObject("responseData")
                    ?.optString("translatedText")
                    ?.takeIf { it.isNotBlank() }
                    ?: throw IOException("MyMemory returned no translated text")
                Log.d(TAG, "MyMemory translation succeeded")
                return decodeBasicHtmlEntities(translated)
            }
        } catch (e: Exception) {
            Log.e(TAG, "MyMemory request failed", e)
            throw e
        }
    }

    private fun translateWithGoogle(text: String, source: String, target: String, apiKey: String): String {
        try {
            val url = GOOGLE_ENDPOINT.toHttpUrl().newBuilder()
                .addQueryParameter("key", apiKey)
                .build()
            val jsonBody = JSONObject().apply {
                put("q", text)
                put("target", target)
                if (source != "auto") put("source", source)
                put("format", "text")
            }
            val requestBody = jsonBody.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .post(requestBody)
                .build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.e(TAG, "Google HTTP error: ${response.code}")
                    throw IOException("Google HTTP ${response.code}")
                }
                val json = JSONObject(body)
                val translated = json.optJSONObject("data")
                    ?.optJSONArray("translations")
                    ?.optJSONObject(0)
                    ?.optString("translatedText")
                    ?.takeIf { it.isNotBlank() }
                    ?: throw IOException("Google returned no translated text")
                Log.d(TAG, "Google translation succeeded")
                return decodeBasicHtmlEntities(translated)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Google translation failed", e)
            throw e
        }
    }

    private fun normalizeProvider(provider: String): String {
        return when (provider.trim().lowercase(Locale.ROOT)) {
            "google", "google cloud", "googlecloud" -> "google"
            "mymemory", "my memory", "free" -> "mymemory"
            else -> "mymemory"
        }
    }

    private fun normalizeLanguage(language: String): String {
        return when (language.trim().lowercase(Locale.ROOT)) {
            "", "auto", "detect" -> "auto"
            "arabic", "ar-sa", "ar-eg" -> "ar"
            "english", "en-us", "en-gb" -> "en"
            else -> language.trim().lowercase(Locale.ROOT).substringBefore('-')
        }
    }

    private fun sourceForApi(language: String): String {
        return if (language == "auto") "autodetect" else language
    }

    private fun targetForApi(language: String): String {
        return if (language == "auto") "en" else language
    }

    private fun decodeBasicHtmlEntities(value: String): String {
        return try {
            value
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&#x27;", "'")
                .replace("&nbsp;", " ")
        } catch (e: Exception) {
            Log.e(TAG, "HTML entity decoding failed", e)
            value
        }
    }

    fun clearCache() {
        try {
            translationCache.clear()
            Log.d(TAG, "Translation cache cleared manually")
        } catch (e: Exception) {
            Log.e(TAG, "Unable to clear translation cache", e)
        }
    }

    fun getCacheSize(): Int {
        return try { translationCache.size } catch (e: Exception) { 0 }
    }
}
