package com.sari.subtitle

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, AudioCaptureService::class.java)
                .setAction(AudioCaptureService.ACTION_START)
                .putExtra(AudioCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(AudioCaptureService.EXTRA_RESULT_DATA, result.data)
            ContextCompat.startForegroundService(this, serviceIntent)
            status.text = "Audio capture started."
        } else {
            status.text = "Audio capture permission was not granted."
            Toast.makeText(this, "يلزم السماح بالتقاط صوت الجهاز.", Toast.LENGTH_LONG).show()
        }
    }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (requiredPermissionsGranted()) continueStartCapture() else {
            status.text = "Microphone/audio and notification permissions are required."
            Toast.makeText(this, "امنح أذونات الصوت والإشعارات أولاً.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 40, 32, 24) }
        layout.addView(TextView(this).apply { text = "Sari Subtitle"; textSize = 28f })
        status = TextView(this).apply {
            text = "Configure Groq and a translation provider in Settings."
            textSize = 15f
            setPadding(0, 16, 0, 24)
        }
        layout.addView(status)
        layout.addView(Button(this).apply {
            text = getString(R.string.start_overlay)
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    startService(Intent(this@MainActivity, OverlayService::class.java).setAction(OverlayService.ACTION_SHOW))
                    status.text = "Floating subtitle panel is visible."
                }
            }
        })
        layout.addView(Button(this).apply {
            text = getString(R.string.stop_overlay)
            setOnClickListener {
                startService(Intent(this@MainActivity, OverlayService::class.java).setAction(OverlayService.ACTION_HIDE))
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
                    val historyText = lines.joinToString("\n\n") { "${it.sourceText}\n→ ${it.translatedText}" }
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Recent translations")
                        .setMessage(historyText.ifBlank { "No translations saved yet." })
                        .setPositiveButton("Export TXT") { _, _ -> exportHistory(historyText) }
                        .setNegativeButton("Close", null).show()
                }
            }
        })
        layout.addView(Button(this).apply {
            text = "START CAPTURE"
            setOnClickListener { requestCaptureStart() }
        })
        layout.addView(Button(this).apply {
            text = "STOP CAPTURE"
            setOnClickListener {
                startService(Intent(this@MainActivity, AudioCaptureService::class.java).setAction(AudioCaptureService.ACTION_STOP))
                status.text = "Stopping audio capture…"
            }
        })
        setContentView(layout)
    }

    private fun requestCaptureStart() {
        val missing = buildList {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
        else continueStartCapture()
    }

    private fun requiredPermissionsGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    private fun continueStartCapture() {
        if (!Settings.canDrawOverlays(this)) {
            status.text = "Grant overlay permission, then press START CAPTURE again."
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun exportHistory(content: String) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "text/plain"; putExtra(Intent.EXTRA_TITLE, "sari-translations.txt")
        }
        exportContent = content
        @Suppress("DEPRECATION")
        startActivityForResult(intent, 41)
    }

    @Deprecated("Uses document picker for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 41 && resultCode == RESULT_OK) data?.data?.let { uri ->
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(exportContent) }
        }
    }

    companion object { private var exportContent: String = "" }
}
