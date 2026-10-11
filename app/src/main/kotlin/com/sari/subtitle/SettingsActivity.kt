package com.sari.subtitle

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class SettingsActivity:AppCompatActivity(){
 private lateinit var provider:Spinner; private lateinit var speechKey:EditText; private lateinit var translationKey:EditText; private lateinit var sourceLang:EditText; private lateinit var targetLang:EditText; private lateinit var speechLang:EditText; private lateinit var email:EditText; private lateinit var fontSize:SeekBar; private lateinit var opacity:SeekBar
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState); val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24)}; fun label(t:String){root.addView(TextView(this).apply{text=t;textSize=14f})}; fun field(h:String,secret:Boolean=false)=EditText(this).apply{hint=h;if(secret)inputType=129};
  label("محرك الترجمة"); provider=Spinner(this).apply{adapter=ArrayAdapter(this@SettingsActivity,android.R.layout.simple_spinner_dropdown_item,listOf("Google Cloud Translation (مفتاح مطلوب)","MyMemory (بدون مفتاح)").also{})};root.addView(provider)
  speechKey=field("Groq API Key",true);label("مفتاح Groq للصوت");root.addView(speechKey)
  root.addView(Button(this).apply{text="إنشاء مفتاح Groq";setOnClickListener{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://console.groq.com/keys")))}})
  translationKey=field("Google Cloud API Key (اختياري عند اختيار MyMemory)",true);label("مفتاح Google Cloud");root.addView(translationKey)
  email=field("MyMemory Email (اختياري لرفع الحد)");label("البريد لخدمة MyMemory");root.addView(email)
  speechLang=field("لغة الصوت: ar أو en أو auto");label("لغة التعرّف الصوتي");root.addView(speechLang)
  sourceLang=field("لغة النص المصدر: auto أو en");label("لغة المصدر للترجمة");root.addView(sourceLang)
  targetLang=field("لغة الهدف: ar أو en");label("لغة الترجمة الهدف");root.addView(targetLang)
  label("حجم الخط");fontSize=SeekBar(this).apply{max=36;progress=18};root.addView(fontSize);label("شفافية الخلفية");opacity=SeekBar(this).apply{max=100;progress=82};root.addView(opacity)
  root.addView(Button(this).apply{text="حفظ الإعدادات";setOnClickListener{lifecycleScope.launch{try{SettingsRepository(this@SettingsActivity).save(provider.selectedItemPosition,speechKey.text.toString().trim(),translationKey.text.toString().trim(),sourceLang.text.toString().ifBlank{"auto"}.trim(),targetLang.text.toString().ifBlank{"ar"}.trim(),fontSize.progress+12,opacity.progress,email.text.toString().trim(),speechLang.text.toString().ifBlank{"ar"}.trim());Toast.makeText(this@SettingsActivity,"تم حفظ الإعدادات",Toast.LENGTH_SHORT).show();finish()}catch(e:Exception){Toast.makeText(this@SettingsActivity,e.message?:"تعذر الحفظ",Toast.LENGTH_LONG).show()}}}})
  setContentView(ScrollView(this).apply{addView(root)});lifecycleScope.launch{val s=SettingsRepository(this@SettingsActivity).load();provider.setSelection(s.provider.coerceIn(0,1));speechKey.setText(s.speechKey);translationKey.setText(s.translationKey);sourceLang.setText(s.sourceLanguage);targetLang.setText(s.targetLanguage);speechLang.setText(s.speechLanguage);email.setText(s.myMemoryEmail);fontSize.progress=(s.fontSize-12).coerceIn(0,36);opacity.progress=s.opacity.coerceIn(0,100)}
 }
}
