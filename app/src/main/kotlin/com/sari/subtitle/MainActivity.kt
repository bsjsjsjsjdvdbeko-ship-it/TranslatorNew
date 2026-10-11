package com.sari.subtitle

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 24)
        }
        layout.addView(TextView(this).apply {
            text = "Sari Subtitle"; textSize = 28f
        })
        status = TextView(this).apply {
            text = "Configure a speech and translation provider in Settings."
            textSize = 15f
            setPadding(0, 16, 0, 24)
        }
        layout.addView(status)
        layout.addView(Button(this).apply {
            text = getString(R.string.start_overlay)
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")))
                } else {
                    startService(Intent(this@MainActivity, OverlayService::class.java)
                        .setAction(OverlayService.ACTION_SHOW))
                    status.text = "Floating subtitle panel is visible. Start translation from a configured workflow."
                }
            }
        })
        layout.addView(Button(this).apply {
            text = getString(R.string.stop_overlay)
            setOnClickListener {
                stopService(Intent(this@MainActivity, OverlayService::class.java))
                status.text = "Floating panel hidden."
            }
        })
        layout.addView(Button(this).apply {
            text = getString(R.string.settings)
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        })
        layout.addView(Button(this).apply {
            text = "Translation history"
            setOnClickListener {
                lifecycleScope.launch {
                    val lines = AppDatabase.get(this@MainActivity).translationDao().latest(50)
                    val text = lines.joinToString("\n\n") { "${it.sourceText}\n→ ${it.translatedText}" }
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Recent translations")
                        .setMessage(text.ifBlank { "No translations saved yet." })
                        .setPositiveButton("Export TXT") { _, _ -> exportHistory(text) }
                        .setNegativeButton("Close", null).show()
                }
            }
        })
        setContentView(layout)
    }

    private fun exportHistory(content: String) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "sari-translations.txt")
        }
        exportContent = content
        @Suppress("DEPRECATION")
        startActivityForResult(intent, 41)
    }

    @Deprecated("Uses document picker for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 41 && resultCode == RESULT_OK) {
            data?.data?.let { uri -> contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(exportContent) } }
        }
    }

    companion object { private var exportContent: String = "" }
}
