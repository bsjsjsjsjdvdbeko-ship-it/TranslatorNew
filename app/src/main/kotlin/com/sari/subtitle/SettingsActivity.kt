package com.sari.subtitle

import android.os.Bundle
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
    private lateinit var fontSize: SeekBar
    private lateinit var opacity: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        fun label(text: String) { root.addView(TextView(this).apply { this.text = text; textSize = 14f }) }
        provider = Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Google Cloud Translation", "DeepL", "OpenAI"))
        }
        label("Translation provider"); root.addView(provider)
        speechKey = EditText(this).apply { hint = "OpenAI API key (Whisper + OpenAI translation)"; inputType = 129 }
        label("Speech/API key"); root.addView(speechKey)
        translationKey = EditText(this).apply { hint = "Translation API key (if separate)"; inputType = 129 }
        label("Translation API key"); root.addView(translationKey)
        sourceLang = EditText(this).apply { hint = "Source language (e.g. auto or en)" }
        label("Source language"); root.addView(sourceLang)
        targetLang = EditText(this).apply { hint = "Target language (e.g. ar)"; setText("ar") }
        label("Target language"); root.addView(targetLang)
        label("Subtitle font size"); fontSize = SeekBar(this).apply { max = 36; progress = 18 }; root.addView(fontSize)
        label("Overlay opacity"); opacity = SeekBar(this).apply { max = 100; progress = 82 }; root.addView(opacity)
        root.addView(Button(this).apply {
            text = "Save settings"
            setOnClickListener {
                lifecycleScope.launch {
                    SettingsRepository(this@SettingsActivity).save(
                        provider.selectedItemPosition, speechKey.text.toString(),
                        translationKey.text.toString(), sourceLang.text.toString().ifBlank { "auto" },
                        targetLang.text.toString().ifBlank { "ar" }, fontSize.progress + 12, opacity.progress
                    )
                    Toast.makeText(this@SettingsActivity, "Settings saved", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        })
        setContentView(ScrollView(this).apply { addView(root) })
        lifecycleScope.launch {
            val s = SettingsRepository(this@SettingsActivity).load()
            provider.setSelection(s.provider)
            speechKey.setText(s.speechKey); translationKey.setText(s.translationKey)
            sourceLang.setText(s.sourceLanguage); targetLang.setText(s.targetLanguage)
            fontSize.progress = (s.fontSize - 12).coerceIn(0, 36); opacity.progress = s.opacity
        }
    }
}
