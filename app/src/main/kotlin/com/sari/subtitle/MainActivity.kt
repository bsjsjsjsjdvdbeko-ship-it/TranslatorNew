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
    private val notificationPermissionRequest = 4104

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        updateStatus("جاهز. لم يتم تأكيد إمكانية التقاط صوت X حتى الآن.")
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
            text = "المرحلة الثانية: اختبار التقاط صوت التطبيقات"
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 22)
        }

        status = TextView(this).apply {
            textSize = 16f
            setPadding(14, 16, 14, 16)
            setBackgroundColor(0xFFEAF2F8.toInt())
        }

        val overlayButton = makeButton(
            "1) السماح بالنافذة العائمة"
        ) {
            if (Settings.canDrawOverlays(this)) {
                updateStatus("إذن النافذة العائمة مفعّل.")
            } else {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        val captureButton = makeButton(
            "2) تشغيل التقاط صوت التطبيقات"
        ) {
            beginCapturePermissionFlow()
        }

        val stopButton = makeButton(
            "إيقاف التسجيل / الالتقاط"
        ) {
            val stopIntent =
                Intent(this, AudioCaptureService::class.java).apply {
                    action = AudioCaptureService.ACTION_STOP
                }

            startService(stopIntent)
            updateStatus("أُرسل طلب الإيقاف.")
        }

        val notes = TextView(this).apply {
            text = "\nطريقة الاختبار:\n" +
                "• فعّل إذن النافذة العائمة.\n" +
                "• اضغط تشغيل التقاط صوت التطبيقات ووافق على طلبات أندرويد.\n" +
                "• شغّل فيديو بصوت مسموع في تطبيق آخر، ثم راقب النافذة العائمة والإشعار الدائم.\n" +
                "• اضغط إيقاف التسجيل / الالتقاط لإنهاء الخدمة.\n\n" +
                "مهم: هذه المرحلة تلتقط صوت تشغيل التطبيقات المسموح بالتقاطه، ولا تحفظ ملفًا صوتيًا."

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

    private fun makeButton(
        label: String,
        action: () -> Unit
    ): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }

            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 12
            }
        }

    private fun updateStatus(message: String) {
        if (::status.isInitialized) {
            status.text = "الحالة:\n$message"
        }
    }

    private fun beginCapturePermissionFlow() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED &&
            !getPreferences(MODE_PRIVATE).getBoolean(
                "notification_permission_requested",
                false
            )
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                notificationPermissionRequest
            )

            updateStatus(
                "اختر السماح أو الرفض للإشعارات، ثم اضغط تشغيل التقاط الصوت مرة أخرى."
            )
            return
        }

        if (
            checkSelfPermission(
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                audioPermissionRequest
            )

            updateStatus(
                "اسمح بإذن الصوت ثم اضغط تشغيل التقاط الصوت مرة أخرى."
            )
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            updateStatus(
                "فعّل إذن النافذة العائمة أولًا، ثم ارجع واضغط تشغيل التقاط الصوت."
            )

            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        val manager = getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        startActivityForResult(
            manager.createScreenCaptureIntent(),
            projectionRequest
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        when (requestCode) {
            notificationPermissionRequest -> {
                getPreferences(MODE_PRIVATE)
                    .edit()
                    .putBoolean(
                        "notification_permission_requested",
                        true
                    )
                    .apply()

                updateStatus(
                    if (
                        grantResults.firstOrNull() ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        "تم السماح بالإشعارات. اضغط تشغيل التقاط الصوت للمتابعة."
                    } else {
                        "رُفض إذن الإشعارات. يمكن متابعة الاختبار. اضغط تشغيل التقاط الصوت للمتابعة."
                    }
                )
            }

            audioPermissionRequest -> {
                updateStatus(
                    if (
                        grantResults.firstOrNull() ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        "تم السماح بإذن الصوت. اضغط تشغيل التقاط الصوت للمتابعة."
                    } else {
                        "إذن RECORD_AUDIO مطلوب لالتقاط صوت التشغيل."
                    }
                )
            }
        }
    }

    @Deprecated(
        "Uses the platform MediaProjection consent activity for broad Android compatibility."
    )
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != projectionRequest) return

        if (resultCode != RESULT_OK || data == null) {
            updateStatus(
                "لم تتم الموافقة على إذن التقاط الصوت. لم يبدأ الاختبار."
            )
            return
        }

        val serviceIntent =
            Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_START

                putExtra(
                    AudioCaptureService.EXTRA_RESULT_CODE,
                    resultCode
                )

                putExtra(
                    AudioCaptureService.EXTRA_RESULT_DATA,
                    data
                )
            }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        updateStatus(
            "بدأ الاختبار. شغّل صوتًا في تطبيق آخر وانتظر تحديث نافذة الحالة."
        )
    }

    override fun onResume() {
        super.onResume()

        if (::status.isInitialized) {
            val lastStatus = getSharedPreferences(
                "capture_status",
                MODE_PRIVATE
            ).getString("message", null)

            if (lastStatus != null) {
                updateStatus(lastStatus)
            }
        }
    }
}
