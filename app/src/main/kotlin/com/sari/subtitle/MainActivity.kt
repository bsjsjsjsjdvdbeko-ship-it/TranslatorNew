package com.sari.subtitle

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.content.pm.PackageManager
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private val projectionRequest = 4102
    private val audioPermissionRequest = 4103

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        updateStatus("جاهز للاختبار. لم يتم اختبار التقاط صوت X بعد.")
        requestAudioPermissionIfNeeded()
    }

    private fun buildUi() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 16)
        }
        val title = TextView(this).apply {
            text = "Sari Subtitle"
            textSize = 30f
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "المرحلة الأولى: اختبار التقاط صوت التطبيقات"
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 22)
        }
        status = TextView(this).apply {
            textSize = 16f
            setPadding(14, 16, 14, 16)
            setBackgroundColor(0xFFEAF2F8.toInt())
        }
        val overlayButton = makeButton("1) السماح بالنافذة العائمة") {
            if (Settings.canDrawOverlays(this)) {
                updateStatus("إذن النافذة العائمة مفعّل.")
            } else {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
            }
        }
        val captureButton = makeButton("2) طلب إذن التقاط صوت التشغيل") {
            beginCapturePermissionFlow()
        }
        val stopButton = makeButton("إيقاف الاختبار") {
            stopService(Intent(this, AudioCaptureService::class.java))
            updateStatus("تم طلب إيقاف اختبار الصوت.")
        }
        val notes = TextView(this).apply {
            text = "\nطريقة الاختبار:\n" +
                "• فعّل إذن النافذة العائمة.\n" +
                "• اضغط طلب إذن التقاط صوت التشغيل ووافق على نافذة أندرويد.\n" +
                "• شغّل فيديو بصوت مسموع في تطبيق آخر، ثم راقب حالة النافذة العائمة.\n\n" +
                "مهم: نافذة موافقة أندرويد لا تعني أن X يسمح بالتقاط صوته. بعض التطبيقات تمنع الالتقاط؛ لذلك سنسجل النتيجة الفعلية ولا نفترض النجاح."
            textSize = 15f
            setPadding(0, 18, 0, 0)
        }
        content.addView(title)
        content.addView(subtitle)
        content.addView(status)
        content.addView(overlayButton)
        content.addView(captureButton)
        content.addView(stopButton)
        content.addView(notes)
        scroll.addView(content)
        page.addView(scroll)
        setContentView(page)
    }

    private fun makeButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }
        }

    private fun updateStatus(message: String) {
        if (::status.isInitialized) status.text = "الحالة:\n$message"
    }

    private fun requestAudioPermissionIfNeeded() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), audioPermissionRequest)
        }
    }

    private fun beginCapturePermissionFlow() {
        if (!Settings.canDrawOverlays(this)) {
            updateStatus("فعّل إذن الظهور فوق التطبيقات أولًا، ثم ارجع واضغط الزر مرة أخرى.")
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestAudioPermissionIfNeeded()
            updateStatus("امنح إذن الصوت ثم اضغط الزر مرة أخرى.")
            return
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), projectionRequest)
    }

    @Deprecated("Uses the platform MediaProjection consent activity for broad Android compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != projectionRequest) return
        if (resultCode != RESULT_OK || data == null) {
            updateStatus("لم تتم الموافقة على إذن التقاط الصوت. لم يبدأ الاختبار.")
            return
        }
        val serviceIntent = Intent(this, AudioCaptureService::class.java).apply {
            action = AudioCaptureService.ACTION_START
            putExtra(AudioCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(AudioCaptureService.EXTRA_RESULT_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
        else startService(serviceIntent)
        updateStatus("بدأ الاختبار. شغّل صوتًا في تطبيق آخر وانتظر تحديث نافذة الحالة.")
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) {
            val lastStatus = getSharedPreferences("capture_status", MODE_PRIVATE)
                .getString("message", null)
            if (lastStatus != null) updateStatus(lastStatus)
        }
    }
}
