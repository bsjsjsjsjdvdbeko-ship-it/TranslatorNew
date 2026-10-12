package com.sari.subtitle

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.widget.FrameLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class OverlayService:Service(){
    private var wm:WindowManager?=null;private var panel:FrameLayout?=null;private var text:TextView?=null;private var params:WindowManager.LayoutParams?=null
    private val handler=Handler(Looper.getMainLooper());private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate);private val hide=Runnable{fade(false)}
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{try{when(intent?.action){ACTION_HIDE->{remove();stopSelf()};ACTION_UPDATE->{val t=intent.getStringExtra(EXTRA_TRANSLATED).orEmpty().trim();if(t.isNotBlank()){show();text?.text=t;applySettings();fade(true);handler.removeCallbacks(hide);handler.postDelayed(hide,intent.getLongExtra(EXTRA_DURATION_MS,2200L).coerceIn(900L,6000L))}}else->show()}}catch(e:Exception){android.util.Log.d("SariOverlay","${e.message}")};return START_NOT_STICKY}
    private fun show(){if(panel!=null)return;wm=getSystemService(WINDOW_SERVICE) as WindowManager;val d=resources.displayMetrics.density;val root=FrameLayout(this);val tv=TextView(this).apply{textSize=20f;gravity=Gravity.CENTER;textAlignment=View.TEXT_ALIGNMENT_CENTER;textDirection=View.TEXT_DIRECTION_ANY_RTL;setTextColor(Color.WHITE);setTypeface(Typeface.create("sans-serif",Typeface.BOLD));maxLines=2;includeFontPadding=true;setShadowLayer(4f,0f,2f,Color.BLACK);setPadding((8*d).toInt(),2, (8*d).toInt(),2)};root.addView(tv,FrameLayout.LayoutParams((resources.displayMetrics.widthPixels*.92f).toInt(),WindowManager.LayoutParams.WRAP_CONTENT,Gravity.CENTER));root.setBackgroundColor(Color.TRANSPARENT);panel=root;text=tv
        val type=if(Build.VERSION.SDK_INT>=26)WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        params=WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.CENTER_HORIZONTAL}
        runCatching{wm?.addView(root,params)}.onFailure{panel=null;text=null}
        applySettings()
    }
    private fun applySettings(){scope.launch{val s=runCatching{SettingsRepository(this@OverlayService).load()}.getOrDefault(AppSettings());text?.textSize=s.fontSize.toFloat().coerceIn(12f,48f);text?.alpha=(s.opacity/100f).coerceIn(.1f,1f);val lp=text?.layoutParams as? FrameLayout.LayoutParams?:return@launch;lp.gravity=when(s.subtitlePosition){SettingsRepository.POS_TOP->Gravity.TOP or Gravity.CENTER_HORIZONTAL;SettingsRepository.POS_CENTER->Gravity.CENTER;else->Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL};lp.bottomMargin=if(s.subtitlePosition==SettingsRepository.POS_BOTTOM)(80*resources.displayMetrics.density).toInt() else 0;lp.topMargin=if(s.subtitlePosition==SettingsRepository.POS_TOP)(48*resources.displayMetrics.density).toInt() else 0;text?.layoutParams=lp}}
    private fun fade(show:Boolean){val v=panel?:return;if(show){v.visibility=View.VISIBLE;v.startAnimation(AlphaAnimation(0f,1f).apply{duration=180}}else v.startAnimation(AlphaAnimation(1f,0f).apply{duration=220;setAnimationListener(object:android.view.animation.Animation.AnimationListener{override fun onAnimationStart(a:android.view.animation.Animation?){ };override fun onAnimationRepeat(a:android.view.animation.Animation?){ };override fun onAnimationEnd(a:android.view.animation.Animation?){v.visibility=View.GONE}})} )}
    private fun remove(){handler.removeCallbacksAndMessages(null);runCatching{panel?.let{wm?.removeView(it)}};panel=null;text=null}
    override fun onDestroy(){remove();scope.cancel();super.onDestroy()};override fun onBind(intent:Intent?):IBinder?=null
    companion object{const val ACTION_SHOW="com.sari.subtitle.SHOW_OVERLAY";const val ACTION_HIDE="com.sari.subtitle.HIDE_OVERLAY";const val ACTION_UPDATE="com.sari.subtitle.UPDATE_OVERLAY";const val EXTRA_ORIGINAL="original";const val EXTRA_TRANSLATED="translated";const val EXTRA_DURATION_MS="duration_ms"}
}
