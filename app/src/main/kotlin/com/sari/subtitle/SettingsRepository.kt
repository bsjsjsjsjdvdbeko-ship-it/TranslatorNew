package com.sari.subtitle

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppSettings(
    val provider: Int = 0, val speechKey: String = "", val translationKey: String = "",
    val sourceLanguage: String = "auto", val targetLanguage: String = "ar",
    val fontSize: Int = 30, val opacity: Int = 82
)

class SettingsRepository(context: Context) {
    private val prefs = try {
        val master = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(context, "sari_secure_settings", master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    } catch (_: Exception) {
        // Fail closed for secrets: no unencrypted fallback is used.
        null
    }

    suspend fun save(provider: Int, speechKey: String, translationKey: String,
                     source: String, target: String, font: Int, opacity: Int) = withContext(Dispatchers.IO) {
        val p = prefs ?: throw IllegalStateException("Encrypted settings are unavailable on this device.")
        p.edit().putInt("provider", provider).putString("speechKey", speechKey)
            .putString("translationKey", translationKey).putString("source", source)
            .putString("target", target).putInt("font", font).putInt("opacity", opacity).apply()
    }

    suspend fun load(): AppSettings = withContext(Dispatchers.IO) {
        val p = prefs ?: return@withContext AppSettings()
        AppSettings(p.getInt("provider", 0), p.getString("speechKey", "").orEmpty(),
            p.getString("translationKey", "").orEmpty(), p.getString("source", "auto").orEmpty(),
            p.getString("target", "ar").orEmpty(), p.getInt("font", 30), p.getInt("opacity", 82))
    }
}
