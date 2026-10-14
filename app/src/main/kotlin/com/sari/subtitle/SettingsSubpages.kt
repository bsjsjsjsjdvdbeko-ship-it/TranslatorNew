package com.sari.subtitle

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

abstract class SettingsSubpageBase : AppCompatActivity() {
    abstract val page: String
    private lateinit var fields: List<android.widget.EditText>
    private lateinit var layouts: List<TextInputLayout?>
    private lateinit var switches: List<MaterialSwitch>
    private lateinit var slider1: Slider
    private lateinit var slider2: Slider
    private lateinit var s: SettingsRepository
    private var current = AppSettings()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            initializeSettingsPage(savedInstanceState)
        } catch (e: Exception) {
            showInitializationError(e)
        }
    }

    private fun initializeSettingsPage(savedInstanceState: Bundle?) {
        val layoutId = resources.getIdentifier("activity_settings_$page", "layout", packageName)
        check(layoutId != 0) { "Layout activity_settings_$page غير موجود" }
        setContentView(layoutId)
        s = SettingsRepository(this)
        findViewById<MaterialToolbar>(R.id.subpageToolbar).apply {
            title = pageTitle()
            setNavigationOnClickListener { finish() }
        }
        val fieldIds = when (page) {
            "keys" -> listOf(R.id.providerSpinner, R.id.groqKeyInput, R.id.translationKeyInput, R.id.myMemoryEmailInput)
            "languages" -> listOf(R.id.speechLanguageInput, R.id.sourceLanguageInput, R.id.targetLanguageInput)
            "appearance" -> listOf(R.id.positionSpinner)
            "quality" -> listOf(R.id.qualitySpinner)
            "context" -> listOf(R.id.glossaryInput, R.id.contextPromptInput)
            "omniroute" -> listOf(R.id.omniRouteBaseUrlInput, R.id.omniRouteKeyInput, R.id.omniRouteSttModelsInput, R.id.omniRouteTranslationModelInput, R.id.omniRouteTranslationModelsInput)
            "orchestration" -> listOf(R.id.benchmarkIntervalInput, R.id.hedgeDelayInput)
            else -> emptyList()
        }
        fields = fieldIds.map { findViewById<android.widget.EditText>(it) }
        layouts = fieldIds.map { id -> runCatching { findViewById<android.view.View>(id)?.parent as? TextInputLayout }.getOrNull() }
        val switchIds = when (page) {
            "context" -> listOf(R.id.contextAwareSwitch, R.id.translationMemorySwitch)
            "orchestration" -> listOf(R.id.smartRoutingSwitch, R.id.qualityRetrySwitch, R.id.compareCandidatesSwitch, R.id.streamingOrchestrationSwitch, R.id.quotaMonitoringSwitch, R.id.providerBenchmarkSwitch, R.id.circuitBreakerSwitch, R.id.adaptiveRoutingSwitch, R.id.hedgedFailoverSwitch)
            else -> emptyList()
        }
        switches = switchIds.map { findViewById(it) }
        slider1 = if (page == "appearance") findViewById(R.id.fontSizeSeek) else Slider(this)
        slider2 = if (page == "appearance") findViewById(R.id.opacitySeek) else Slider(this)
        val saveId = when(page) { "keys" -> R.id.saveKeysButton; "languages" -> R.id.saveLanguagesButton; "appearance" -> R.id.saveAppearanceButton; "quality" -> R.id.saveQualityButton; "context" -> R.id.saveContextButton; "omniroute" -> R.id.saveOmniRouteButton; "orchestration" -> R.id.saveOrchestrationButton; else -> R.id.saveAboutButton }
        val saveButton = runCatching { findViewById<MaterialButton>(saveId) }.getOrNull()
        if (saveButton != null) {
            saveButton.setOnClickListener { if (page == "about") finish() else savePage() }
        } else {
            throw IllegalStateException("زر الحفظ غير موجود في صفحة ${pageTitle()} (ID=$saveId)")
        }
        if (page == "keys") runCatching { findViewById<MaterialButton>(R.id.getGroqKeyButton) }.getOrNull()?.setOnClickListener { open("https://console.groq.com/keys") }
        if (page == "omniroute") runCatching { findViewById<MaterialButton>(R.id.testOmniRouteButton) }.getOrNull()?.setOnClickListener { testOmni() }
        if (page == "orchestration") runCatching { findViewById<MaterialButton>(R.id.providerDashboardButton) }.getOrNull()?.setOnClickListener { runCatching { startActivity(Intent(this, ProviderDashboardActivity::class.java)) }.onFailure { toast("تعذر فتح لوحة مزودي الذكاء الاصطناعي: ${it.message ?: "خطأ غير معروف"}") } }
        configure()
        lifecycleScope.launch { try { current=s.load(); populate() } catch(e:Exception) { toast(e.message ?: getString(R.string.settings_save_failed)) } }
    }
    private fun showInitializationError(error: Exception) {
        val message = error.message?.takeIf { it.isNotBlank() }
            ?: error.javaClass.simpleName
        android.util.Log.e("SettingsSubpageBase", "Failed to initialize settings page: $page", error)
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(android.graphics.Color.WHITE)
        }
        val title = TextView(this).apply {
            text = "تعذر فتح صفحة الإعدادات"
            textSize = 20f
            setTextColor(android.graphics.Color.BLACK)
        }
        val detail = TextView(this).apply {
            text = "حدث خطأ أثناء تهيئة صفحة ${page.ifBlank { "الإعدادات" }}.\n$message"
            textSize = 15f
            setTextColor(android.graphics.Color.DKGRAY)
            setPadding(0, 20, 0, 24)
        }
        val back = MaterialButton(this).apply {
            text = "العودة"
            setOnClickListener { finish() }
        }
        root.addView(title)
        root.addView(detail)
        root.addView(back)
        setContentView(root)
        Toast.makeText(this, "تعذر فتح الصفحة؛ راجع رسالة الخطأ الظاهرة.", Toast.LENGTH_LONG).show()
    }

    private fun pageTitle() = when(page) {
        "keys" -> "مفاتيح API"; "languages" -> "اللغات"; "appearance" -> "المظهر"
        "quality" -> "جودة الترجمة"; "context" -> "الترجمة السياقية"; "omniroute" -> "OmniRoute"
        "orchestration" -> "AI Orchestration"; else -> getString(R.string.about)
    }
    private fun configure() {
        val labels = when(page) {
            "keys" -> listOf("مزوّد الترجمة (Groq / Google Cloud Translation / MyMemory / OmniRoute / OpenAI)","Groq API Key","Translation API Key","MyMemory Email")
            "languages" -> listOf("لغة التعرّف على الكلام (مثل auto أو ar)","لغة النص الأصلي (auto مسموح)","لغة الترجمة (مثل ar)","")
            "quality" -> listOf("الجودة: fast أو balanced أو human","ملاحظة","", "")
            "context" -> listOf("قاموس المصطلحات (مصطلح = ترجمة)","تعليمات السياق الإضافية","","")
            "omniroute" -> listOf("Base URL (HTTPS)","API Key","STT Models مفصولة بفاصلة","Translation Model","نماذج الترجمة البديلة مفصولة بفاصلة")
            "appearance" -> listOf("موضع الترجمة: top أو center أو bottom","","","")
            "about" -> listOf("Sari Subtitle","الإصدار: "+BuildConfig.VERSION_NAME,"ترجمة صوتية فورية مع نافذة عائمة","")
            else -> listOf("","","","")
        }
        fields.forEachIndexed { i, f -> val label = labels.getOrElse(i) { "" }; layouts.getOrNull(i)?.hint = label; f.hint = label }
        if (page == "keys") {
            // The view is looked up only after setContentView() in initializeSettingsPage().
            // Do not rely on fields[0] being castable: bind the concrete layout ID directly.
            val providerSpinner = findViewById<MaterialAutoCompleteTextView>(R.id.providerSpinner)
            val translationProviders = listOf(
                "Groq", "Google Cloud Translation", "MyMemory", "OmniRoute", "OpenAI"
            )
            providerSpinner.setAdapter(
                android.widget.ArrayAdapter(
                    this@SettingsSubpageBase,
                    android.R.layout.simple_dropdown_item_1line,
                    translationProviders
                )
            )
            providerSpinner.keyListener = null
            providerSpinner.inputType = android.text.InputType.TYPE_NULL
            providerSpinner.isFocusable = false
            providerSpinner.isClickable = true
            providerSpinner.setOnClickListener { providerSpinner.showDropDown() }
            providerSpinner.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) providerSpinner.showDropDown()
            }
            layouts.getOrNull(1)?.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            layouts.getOrNull(2)?.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            fields[1].inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            fields[2].inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            fields[3].inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        } else if (page == "omniroute") {
            layouts.getOrNull(1)?.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            fields[1].inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val visibleFields = fields.size
        fields.forEachIndexed { i,f -> layouts.getOrNull(i)?.visibility=if(i<visibleFields) View.VISIBLE else View.GONE }
        switches.forEach { it.visibility=View.GONE }
        findViewById<TextView>(R.id.subpageDescription).text = when(page) {
            "keys" -> "تُحفظ المفاتيح باستخدام التخزين المشفر الحالي. لا تشارك مفاتيحك."
            "languages" -> "استخدم رموز لغات مدعومة من المزود المحدد. Auto Detect يعتمد على دعم المزود."
            "appearance" -> "تؤثر هذه القيم على مظهر الترجمة العائمة عند تطبيقها بواسطة OverlayService."
            "quality" -> "Human Quality لا يضمن جودة بشرية؛ إنه وضع معالجة أعلى كلفة/زمنًا."
            "context" -> "السياق يساعد على اتساق المصطلحات؛ وذاكرة الترجمة تقلل تكرار الطلبات."
            "omniroute" -> "أدخل عنوان بوابة متوافقًا مع OpenAI API. دعم النماذج يختلف حسب البوابة."
            "orchestration" -> "فعّل الميزات المدعومة. تتضمن الخيارات الإضافية التبديل الاحتياطي المتزامن وفترة القياس. لا تُفعّل إلا ما تدعمه إعدادات المشروع."
            else -> "معلومات الإصدار مأخوذة من إعدادات بناء التطبيق."
        }
        if(page=="appearance") {
            findViewById<TextView>(R.id.slider1Label).visibility=View.VISIBLE; slider1.visibility=View.VISIBLE
            findViewById<TextView>(R.id.slider2Label).visibility=View.VISIBLE; slider2.visibility=View.VISIBLE
            slider1.valueTo=48f; slider1.valueFrom=12f; slider1.stepSize=1f
            slider2.valueTo=100f; slider2.valueFrom=0f; slider2.stepSize=1f
            findViewById<TextView>(R.id.slider1Label).text="حجم الخط"
            findViewById<TextView>(R.id.slider2Label).text="الشفافية (%)"
            val preview = findViewById<TextView>(R.id.appearancePreview)
            val fontValue = findViewById<TextView>(R.id.fontSizeValue)
            val opacityValue = findViewById<TextView>(R.id.opacityValue)
            val positionSpinner = fields[0] as MaterialAutoCompleteTextView
            positionSpinner.setAdapter(android.widget.ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, listOf("أعلى", "وسط", "أسفل")))
            positionSpinner.setOnClickListener { positionSpinner.showDropDown() }
            fun updatePreview() {
                preview.textSize = slider1.value
                preview.alpha = slider2.value / 100f
                fontValue.text = "${slider1.value.toInt()} sp"
                opacityValue.text = "${slider2.value.toInt()}%"
                preview.text = "معاينة الترجمة العائمة"
            }
            slider1.addOnChangeListener { _, _, _ -> updatePreview() }
            slider2.addOnChangeListener { _, _, _ -> updatePreview() }
            updatePreview()
        } else { slider1.visibility=View.GONE; slider2.visibility=View.GONE }
        if(page=="context") {
            switches.forEach { it.visibility=View.VISIBLE }
            fields.forEach { it.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE; it.minLines = 3; it.gravity = android.view.Gravity.TOP or android.view.Gravity.START }
        }
        if(page=="quality") {
            val quality = fields[0] as MaterialAutoCompleteTextView
            quality.setAdapter(android.widget.ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, listOf("Fast", "Balanced", "Human Quality")))
            quality.setOnClickListener { quality.showDropDown() }
            findViewById<TextView>(R.id.qualityDescription).text = "Fast: أسرع استجابة. Balanced: توازن بين السرعة والجودة. Human Quality: معالجة أعلى كلفة وزمنًا."
        }
        if(page=="orchestration") {
            val names=listOf("Smart Routing","Quality-aware Retry","Compare Candidates","Streaming Orchestration","Quota Monitoring","Provider Benchmark","Circuit Breaker","Adaptive Routing","Hedged Failover")
            switches.forEachIndexed { i,v -> v.visibility=View.VISIBLE;v.text=names[i] }
            layouts.getOrNull(0)?.hint = "فترة القياس الدوري (بالساعات)"
            layouts.getOrNull(1)?.hint = "تأخير الإرسال الاحتياطي (مللي ثانية)"
            fields[0].inputType = android.text.InputType.TYPE_CLASS_NUMBER
            fields[1].inputType = android.text.InputType.TYPE_CLASS_NUMBER
            switches[8].visibility = View.VISIBLE
            switches[8].text = "Hedged Failover"
        }
    }
    private fun populate() {
        when(page) {
            "keys" -> { fields[0].setText(listOf("Groq","Google Cloud Translation","MyMemory","OmniRoute","OpenAI").getOrElse(current.provider){"Groq"});fields[1].setText(current.speechKey);fields[2].setText(current.translationKey);fields[3].setText(current.myMemoryEmail) }
            "languages" -> { fields[0].setText(current.speechLanguage);fields[1].setText(current.sourceLanguage);fields[2].setText(current.targetLanguage) }
            "quality" -> { fields[0].setText(listOf("Fast","Balanced","Human Quality")[current.translationQuality.coerceIn(0,2)]) }
            "context" -> { fields[0].setText(current.glossary);fields[1].setText(current.contextPrompt);switches[0].isChecked=current.contextAwareTranslation;switches[1].isChecked=current.translationMemoryEnabled }
            "appearance" -> { (fields[0] as MaterialAutoCompleteTextView).setText(when(current.subtitlePosition){"top"->"أعلى";"center"->"وسط";else->"أسفل"},false); slider1.value=current.fontSize.coerceIn(12,48).toFloat();slider2.value=current.opacity.coerceIn(0,100).toFloat() }
            "omniroute" -> { fields[0].setText(current.omniRouteBaseUrl);fields[1].setText(current.omniRouteKey);fields[2].setText(current.omniRouteSttModels);fields[3].setText(current.omniRouteTranslationModel);fields[4].setText(current.omniRouteTranslationModels) }
            "orchestration" -> { fields[0].setText((current.benchmarkIntervalMs / 3_600_000L).toString()); fields[1].setText(current.hedgeDelayMs.toString()); switches[0].isChecked=current.smartRoutingEnabled;switches[1].isChecked=current.qualityRetryEnabled;switches[2].isChecked=current.compareCandidatesEnabled;switches[3].isChecked=current.streamingOrchestrationEnabled;switches[4].isChecked=current.quotaMonitoringEnabled;switches[5].isChecked=current.providerBenchmarkEnabled;switches[6].isChecked=current.circuitBreakerEnabled;switches[7].isChecked=current.adaptiveRoutingEnabled;switches[8].isChecked=current.hedgedFailoverEnabled }
            "about" -> { findViewById<TextView>(R.id.aboutTitle).text=getString(R.string.app_name); findViewById<TextView>(R.id.aboutVersion).text="1.0.0"; findViewById<TextView>(R.id.aboutDescription).text=getString(R.string.about_description) }
        }
    }
    private fun savePage() {
        val v=fields.map { it.text?.toString()?.trim().orEmpty() }
        current=when(page) {
            "keys" -> current.copy(provider=when(v[0]){"Google Cloud Translation"->1;"MyMemory"->2;"OmniRoute"->3;"OpenAI"->4;else->0},speechKey=v[1],translationKey=v[2],myMemoryEmail=v[3])
            "languages" -> current.copy(speechLanguage=v[0].ifBlank{"auto"},sourceLanguage=v[1].ifBlank{"auto"},targetLanguage=v[2].ifBlank{"ar"})
            "quality" -> current.copy(translationQuality=when(v[0].lowercase()){"fast","سريع"->0;"balanced","متوازن"->1;else->2})
            "context" -> current.copy(glossary=v[0],contextPrompt=v[1],contextAwareTranslation=switches[0].isChecked,translationMemoryEnabled=switches[1].isChecked)
            "appearance" -> current.copy(fontSize=slider1.value.toInt(),opacity=slider2.value.toInt(),subtitlePosition=when(v[0].lowercase()){"top","أعلى"->"top";"center","middle","وسط"->"center";else->"bottom"})
            "omniroute" -> current.copy(omniRouteBaseUrl=v[0],omniRouteKey=v[1],omniRouteSttModels=v[2],omniRouteTranslationModel=v[3],omniRouteTranslationModels=v[4])
            "orchestration" -> current.copy(smartRoutingEnabled=switches[0].isChecked,qualityRetryEnabled=switches[1].isChecked,compareCandidatesEnabled=switches[2].isChecked,streamingOrchestrationEnabled=switches[3].isChecked,quotaMonitoringEnabled=switches[4].isChecked,providerBenchmarkEnabled=switches[5].isChecked,circuitBreakerEnabled=switches[6].isChecked,adaptiveRoutingEnabled=switches[7].isChecked,hedgedFailoverEnabled=switches[8].isChecked,benchmarkIntervalMs=(v[0].toLongOrNull()?.coerceIn(1,720) ?: (current.benchmarkIntervalMs/3_600_000L))*3_600_000L,hedgeDelayMs=v[1].toLongOrNull()?.coerceIn(0,2000) ?: current.hedgeDelayMs)
            else -> current
        }
        if(page=="omniroute" && v[0].isNotBlank()) {
            val parsed = runCatching { Uri.parse(v[0]) }.getOrNull()
            if (!android.util.Patterns.WEB_URL.matcher(v[0]).matches() || parsed?.scheme !in listOf("https", "http") || parsed?.host.isNullOrBlank()) { toast("عنوان URL غير صالح؛ أدخل عنوان HTTP(S) كاملًا"); return }
        }
        if(page=="languages") {
            val languagePattern = Regex("(?i)^(auto|[a-z]{2,3}([_-][a-z]{2,4})?)$")
            if (v.take(3).any { it.isBlank() || !languagePattern.matches(it) }) { toast("أدخل رموز لغات صحيحة مثل en وar، واستخدم auto للكشف التلقائي حيث يدعمه المزود."); return }
            if (v[2].equals("auto", true)) { toast("لغة الترجمة الهدف لا يمكن أن تكون auto؛ اختر لغة محددة مثل ar."); return }
        }
        if(page=="keys" && v[0] !in listOf("Groq","Google Cloud Translation","MyMemory","OmniRoute","OpenAI")) { toast("اختر مزوّد ترجمة من القيم المعروفة"); return }
        lifecycleScope.launch { try {
            s.saveSnapshot(current)
            if (page == "appearance") startService(Intent(this@SettingsSubpageBase, OverlayService::class.java).setAction(OverlayService.ACTION_APPLY_SETTINGS))
            toast(getString(R.string.settings_saved)); finish()
        } catch(e:Exception) { toast(e.message ?: getString(R.string.settings_save_failed)) } }
    }
    private fun testOmni() { val u=fields[0].text?.toString()?.trim().orEmpty(); val k=fields[1].text?.toString()?.trim().orEmpty(); if(!u.startsWith("https://")||k.isBlank()){toast("أدخل Base URL آمنًا ومفتاح API");return}; lifecycleScope.launch { try { val r=OmniRouteClient(u,k).healthCheck(); toast(if(r.reachable) "نجح الاتصال؛ ${r.modelCount} نموذجًا، ${r.latencyMs}ms" else "فشل الاتصال: ${r.error}") } catch(e:Exception) { toast("فشل الاتصال: ${e.message}") } } }
    private fun open(url:String) { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch(e:Exception) { toast("تعذر فتح الرابط") } }
    private fun toast(m:String) = Toast.makeText(this,m,Toast.LENGTH_LONG).show()
}
