package com.sari.subtitle

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppSettings(
    val provider: Int = SettingsRepository.PROVIDER_MYMEMORY,
    val speechKey: String = "",
    val translationKey: String = "",
    val sourceLanguage: String = "auto",
    val targetLanguage: String = "ar",
    val fontSize: Int = 30,
    val opacity: Int = 82,
    val myMemoryEmail: String = "",
    val speechLanguage: String = "auto",
    val contextAwareTranslation: Boolean = true,
    val translationMemoryEnabled: Boolean = true,
    val glossary: String = "",
    val translationQuality: Int = SettingsRepository.QUALITY_HUMAN
)

class SettingsRepository(context: Context) {
    private val prefs = try {
        val master = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "sari_secure_settings", master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) { null }

    suspend fun save(
        provider: Int,
        speechKey: String,
        translationKey: String,
        source: String,
        target: String,
        font: Int,
        opacity: Int,
        email: String,
        speechLanguage: String,
        contextAwareTranslation: Boolean = true,
        translationMemoryEnabled: Boolean = true,
        glossary: String = "",
        translationQuality: Int = QUALITY_HUMAN
    ) = withContext(Dispatchers.IO) {
        val p = prefs ?: throw IllegalStateException("تعذر تفعيل التخزين المشفر على هذا الجهاز.")
        p.edit()
            .putInt("provider", provider.coerceIn(PROVIDER_GROQ, PROVIDER_MYMEMORY))
            .putBoolean("providerSchemaV2", true)
            .putString("speechKey", speechKey)
            .putString("translationKey", translationKey)
            .putString("source", source)
            .putString("target", target)
            .putInt("font", font.coerceIn(12, 48))
            .putInt("opacity", opacity.coerceIn(0, 100))
            .putString("email", email)
            .putString("speechLanguage", speechLanguage)
            .putBoolean("contextAwareTranslation", contextAwareTranslation)
            .putBoolean("translationMemoryEnabled", translationMemoryEnabled)
            .putString("glossary", glossary)
            .putInt("translationQuality", translationQuality.coerceIn(QUALITY_FAST, QUALITY_HUMAN))
            .apply()
    }

    suspend fun load(): AppSettings = withContext(Dispatchers.IO) {
        val p = prefs ?: return@withContext AppSettings()
        val storedProvider = p.getInt("provider", PROVIDER_MYMEMORY)
        // Stage 4 stored 0=Google and 1=MyMemory. Migrate once to Stage 5's 0=Groq, 1=Google, 2=MyMemory.
        val provider = if (p.getBoolean("providerSchemaV2", false)) storedProvider.coerceIn(0, 2) else when (storedProvider) {
            0 -> PROVIDER_GOOGLE
            else -> PROVIDER_MYMEMORY
        }
        AppSettings(
            provider = provider,
            speechKey = p.getString("speechKey", "").orEmpty(),
            translationKey = p.getString("translationKey", "").orEmpty(),
            sourceLanguage = p.getString("source", "auto").orEmpty(),
            targetLanguage = p.getString("target", "ar").orEmpty(),
            fontSize = p.getInt("font", 30),
            opacity = p.getInt("opacity", 82),
            myMemoryEmail = p.getString("email", "").orEmpty(),
            speechLanguage = p.getString("speechLanguage", "ar").orEmpty(),
            contextAwareTranslation = p.getBoolean("contextAwareTranslation", true),
            translationMemoryEnabled = p.getBoolean("translationMemoryEnabled", true),
            glossary = p.getString("glossary", "").orEmpty(),
            translationQuality = p.getInt("translationQuality", QUALITY_HUMAN).coerceIn(QUALITY_FAST, QUALITY_HUMAN)
        )
    }

    companion object {
        const val PROVIDER_GROQ = 0
        const val PROVIDER_GOOGLE = 1
        const val PROVIDER_MYMEMORY = 2
        const val QUALITY_FAST = 0
        const val QUALITY_BALANCED = 1
        const val QUALITY_HUMAN = 2
    }
}
