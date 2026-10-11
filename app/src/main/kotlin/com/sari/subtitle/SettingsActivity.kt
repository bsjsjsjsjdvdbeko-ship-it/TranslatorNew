package com.sari.subtitle

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private lateinit var provider: MaterialAutoCompleteTextView
    private lateinit var speechKey: TextInputEditText
    private lateinit var translationKey: TextInputEditText
    private lateinit var sourceLang: TextInputEditText
    private lateinit var targetLang: TextInputEditText
    private lateinit var speechLang: TextInputEditText
    private lateinit var email: TextInputEditText
    private lateinit var fontSize: Slider
    private lateinit var opacity: Slider
    private lateinit var fontValue: TextView
    private lateinit var opacityValue: TextView
    private lateinit var contextAwareSwitch: MaterialSwitch
    private lateinit var translationMemorySwitch: MaterialSwitch
    private lateinit var glossaryInput: TextInputEditText
    private lateinit var qualitySpinner: MaterialAutoCompleteTextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<MaterialToolbar>(R.id.settingsToolbar).apply {
            setNavigationOnClickListener { finish() }
            setOnMenuItemClickListener { if (it.itemId == R.id.action_save) { saveSettings(); true } else false }
        }
        provider = findViewById(R.id.providerSpinner)
        provider.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, listOf("Groq", "Google Cloud Translation", "MyMemory")))
        provider.setOnClickListener { provider.showDropDown() }
        speechKey = findViewById(R.id.speechKeyInput)
        translationKey = findViewById(R.id.translationKeyInput)
        sourceLang = findViewById(R.id.sourceLanguageInput)
        targetLang = findViewById(R.id.targetLanguageInput)
        speechLang = findViewById(R.id.speechLanguageInput)
        email = findViewById(R.id.emailInput)
        fontSize = findViewById(R.id.fontSizeSeek)
        opacity = findViewById(R.id.opacitySeek)
        fontValue = findViewById(R.id.fontSizeValue)
        opacityValue = findViewById(R.id.opacityValue)
        contextAwareSwitch = findViewById(R.id.contextAwareSwitch)
        translationMemorySwitch = findViewById(R.id.translationMemorySwitch)
        glossaryInput = findViewById(R.id.glossaryInput)
        qualitySpinner = findViewById(R.id.qualitySpinner)
        qualitySpinner.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, listOf("سريع — Fast", "متوازن — Balanced", "بجودة بشرية — Human Quality")))
        qualitySpinner.setOnClickListener { qualitySpinner.showDropDown() }
        fontSize.valueFrom = 12f; fontSize.valueTo = 48f; fontSize.stepSize = 1f
        opacity.valueFrom = 0f; opacity.valueTo = 100f; opacity.stepSize = 1f
        fontSize.addOnChangeListener { _, value, _ -> fontValue.text = "${value.toInt()} sp" }
        opacity.addOnChangeListener { _, value, _ -> opacityValue.text = "${value.toInt()}%" }
        findViewById<MaterialButton>(R.id.getGroqKeyButton).setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys"))) }
        findViewById<MaterialButton>(R.id.saveSettingsButton).setOnClickListener { saveSettings() }
        lifecycleScope.launch {
            val s = SettingsRepository(this@SettingsActivity).load()
            provider.setText(listOf("Groq", "Google Cloud Translation", "MyMemory")[s.provider.coerceIn(0, 2)], false)
            speechKey.setText(s.speechKey); translationKey.setText(s.translationKey)
            sourceLang.setText(s.sourceLanguage); targetLang.setText(s.targetLanguage)
            speechLang.setText(s.speechLanguage); email.setText(s.myMemoryEmail)
            fontSize.value = s.fontSize.coerceIn(12, 48).toFloat(); opacity.value = s.opacity.coerceIn(0, 100).toFloat()
            fontValue.text = "${s.fontSize} sp"; opacityValue.text = "${s.opacity}%"
            contextAwareSwitch.isChecked = s.contextAwareTranslation
            translationMemorySwitch.isChecked = s.translationMemoryEnabled
            glossaryInput.setText(s.glossary)
            qualitySpinner.setText(listOf("سريع — Fast", "متوازن — Balanced", "بجودة بشرية — Human Quality")[s.translationQuality.coerceIn(0, 2)], false)
        }
    }

    private fun saveSettings() {
        lifecycleScope.launch {
            try {
                SettingsRepository(this@SettingsActivity).save(
                    provider = listOf("Groq", "Google Cloud Translation", "MyMemory").indexOf(provider.text.toString()).coerceAtLeast(0),
                    speechKey = speechKey.text?.toString()?.trim().orEmpty(),
                    translationKey = translationKey.text?.toString()?.trim().orEmpty(),
                    source = sourceLang.text?.toString()?.trim().orEmpty().ifBlank { "auto" },
                    target = targetLang.text?.toString()?.trim().orEmpty().ifBlank { "ar" },
                    font = fontSize.value.toInt(), opacity = opacity.value.toInt(),
                    email = email.text?.toString()?.trim().orEmpty(),
                    speechLanguage = speechLang.text?.toString()?.trim().orEmpty().ifBlank { "ar" },
                    contextAwareTranslation = contextAwareSwitch.isChecked,
                    translationMemoryEnabled = translationMemorySwitch.isChecked,
                    glossary = glossaryInput.text?.toString().orEmpty(),
                    translationQuality = listOf("سريع — Fast", "متوازن — Balanced", "بجودة بشرية — Human Quality").indexOf(qualitySpinner.text.toString()).coerceIn(0, 2)
                )
                Toast.makeText(this@SettingsActivity, R.string.settings_saved, Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, e.message ?: getString(R.string.settings_save_failed), Toast.LENGTH_LONG).show()
            }
        }
    }
}
