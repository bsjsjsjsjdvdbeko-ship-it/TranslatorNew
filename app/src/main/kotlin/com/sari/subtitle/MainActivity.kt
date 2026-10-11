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
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var latest: TextView
    private lateinit var captureFab: FloatingActionButton
    private lateinit var overlaySwitch: MaterialSwitch
    private lateinit var statusDot: View
    private var capturing = false

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, AudioCaptureService::class.java)
                .setAction(AudioCaptureService.ACTION_START)
                .putExtra(AudioCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(AudioCaptureService.EXTRA_RESULT_DATA, result.data)
            ContextCompat.startForegroundService(this, serviceIntent)
            setCaptureUi(true)
        } else {
            status.text = getString(R.string.capture_permission_denied)
            Toast.makeText(this, R.string.capture_permission_denied, Toast.LENGTH_LONG).show()
        }
    }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (requiredPermissionsGranted()) continueStartCapture() else {
            status.text = getString(R.string.permissions_required)
            Toast.makeText(this, R.string.permissions_required, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.captureStatus)
        latest = findViewById(R.id.latestTranslation)
        captureFab = findViewById(R.id.captureFab)
        overlaySwitch = findViewById(R.id.overlaySwitch)
        statusDot = findViewById(R.id.statusDot)
        findViewById<View>(R.id.settingsShortcut).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<View>(R.id.historyShortcut).setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        captureFab.setOnClickListener {
            it.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).withEndAction { it.animate().scaleX(1f).scaleY(1f).setDuration(110).start() }.start()
            if (capturing) stopCapture() else requestCaptureStart()
        }
        overlaySwitch.setOnCheckedChangeListener { _, checked ->
            if (checked && !Settings.canDrawOverlays(this)) {
                overlaySwitch.isChecked = false
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                return@setOnCheckedChangeListener
            }
            startService(Intent(this, OverlayService::class.java).setAction(if (checked) OverlayService.ACTION_SHOW else OverlayService.ACTION_HIDE))
        }
        findViewById<BottomNavigationView>(R.id.bottomNavigation).setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> true
                R.id.nav_history -> { startActivity(Intent(this, HistoryActivity::class.java)); true }
                R.id.nav_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
                else -> false
            }
        }
        setCaptureUi(false)
        lifecycleScope.launch {
            AppDatabase.get(this@MainActivity).translationDao().observeLatest().collect { items ->
                latest.text = items.firstOrNull()?.translatedText?.takeIf { it.isNotBlank() } ?: getString(R.string.no_translation_yet)
            }
        }
    }

    private fun setCaptureUi(active: Boolean) {
        capturing = active
        status.text = getString(if (active) R.string.capture_active else R.string.capture_stopped)
        captureFab.setImageResource(if (active) R.drawable.ic_stop else R.drawable.ic_play)
        captureFab.contentDescription = getString(if (active) R.string.stop_capture else R.string.start_capture)
        captureFab.backgroundTintList = ContextCompat.getColorStateList(this, if (active) R.color.error else R.color.accent)
        statusDot.backgroundTintList = ContextCompat.getColorStateList(this, if (active) R.color.accent else R.color.text_secondary)
    }

    private fun requestCaptureStart() {
        val missing = buildList {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray()) else continueStartCapture()
    }

    private fun requiredPermissionsGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    private fun continueStartCapture() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun stopCapture() {
        startService(Intent(this, AudioCaptureService::class.java).setAction(AudioCaptureService.ACTION_STOP))
        setCaptureUi(false)
    }

    override fun onResume() {
        super.onResume()
        // Overlay visibility is user-controlled; subtitle updates can auto-hide after silence.
    }
}
