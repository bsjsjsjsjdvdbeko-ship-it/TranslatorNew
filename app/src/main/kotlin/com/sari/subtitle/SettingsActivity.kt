package com.sari.subtitle

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private lateinit var provider: Spinner
    private lateinit var speechKey: EditText
    private lateinit var translationKey: EditText
    private lateinit var sourceLang: EditText
    private lateinit var targetLang: EditText
    private lateinit var speechLang: EditText
    private lateinit var email: EditText
    private lateinit var fontSize: SeekBar
    private lateinit var opacity: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        fun label(value: String) {
            root.addView(TextView(this).apply { text = value; textSize = 14f; setPadding(0, 12, 0, 4) })
        }
        fun field(hintText: String, secret: Boolean = false): EditText = EditText(this).apply {
            hint = hintText
            if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        label("Provider — مزوّد الترجمة")
        provider = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Groq", "Google Cloud Translation", "MyMemory")
            )
        }
        root.addView(provider)

        label("Groq API Key — مفتاح الصوت")
        speechKey = field("Groq API Key", true)
        root.addView(speechKey)
        root.addView(Button(this).apply {
            text = "Get Groq API Key"
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys"))) }
        })

        label("Translation API Key (optional)")
        translationKey = field("Translation API Key (اختياري)", true)
        root.addView(translationKey)
        label("MyMemory Email (optional)")
        email = field("MyMemory Email (اختياري)")
        root.addView(email)

        label("Speech language")
        speechLang = field("ar أو en أو auto")
        root.addView(speechLang)
        label("Source language")
        sourceLang = field("auto أو en أو ar")
        root.addView(sourceLang)
        label("Target language")
        targetLang = field("ar أو en")
        root.addView(targetLang)

        label("Font size")
        fontSize = SeekBar(this).apply { max = 36; progress = 18 }
        root.addView(fontSize)
        label("Overlay opacity")
        opacity = SeekBar(this).apply { max = 100; progress = 82 }
        root.addView(opacity)

        root.addView(Button(this).apply {
            text = "SAVE SETTINGS"
            setOnClickListener {
                lifecycleScope.launch {
                    try {
                        SettingsRepository(this@SettingsActivity).save(
                            provider = provider.selectedItemPosition,
                            speechKey = speechKey.text.toString().trim(),
                            translationKey = translationKey.text.toString().trim(),
                            source = sourceLang.text.toString().trim().ifBlank { "auto" },
                            target = targetLang.text.toString().trim().ifBlank { "ar" },
                            font = fontSize.progress + 12,
                            opacity = opacity.progress,
                            email = email.text.toString().trim(),
                            speechLanguage = speechLang.text.toString().trim().ifBlank { "ar" }
                        )
                        Toast.makeText(this@SettingsActivity, "تم حفظ الإعدادات", Toast.LENGTH_SHORT).show()
                        finish()
                    } catch (e: Exception) {
                        Toast.makeText(this@SettingsActivity, e.message ?: "تعذر الحفظ", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })

        setContentView(ScrollView(this).apply { addView(root) })
        lifecycleScope.launch {
            val s = SettingsRepository(this@SettingsActivity).load()
            provider.setSelection(s.provider.coerceIn(0, 2))
            speechKey.setText(s.speechKey)
            translationKey.setText(s.translationKey)
            sourceLang.setText(s.sourceLanguage)
            targetLang.setText(s.targetLanguage)
            speechLang.setText(s.speechLanguage)
            email.setText(s.myMemoryEmail)
            fontSize.progress = (s.fontSize - 12).coerceIn(0, 36)
            opacity.progress = s.opacity.coerceIn(0, 100)
        }
    }
}
