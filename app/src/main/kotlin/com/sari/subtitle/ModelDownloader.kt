package com.sari.subtitle

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class ModelDownloader(private val context: Context) {
    suspend fun downloadArabic(onProgress: (Int) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val root=File(context.filesDir,"vosk")
        val model=File(root,"vosk-model-ar-mgb2-0.4")
        if(File(model,"am/final.mdl").exists() || File(model,"conf/mfcc.conf").exists()) return@withContext model
        root.mkdirs(); val zip=File(root,"arabic.zip")
        val url=URL(ARABIC_URL); val conn=url.openConnection() as HttpURLConnection
        conn.connectTimeout=20_000; conn.readTimeout=60_000
        try {
            val total=conn.contentLengthLong
            conn.inputStream.use { input -> zip.outputStream().use { out -> val b=ByteArray(32*1024); var read=0L; while(true){val n=input.read(b); if(n<0)break; out.write(b,0,n); read+=n; if(total>0) onProgress(((read*100)/total).toInt().coerceIn(0,100))} } }
            ZipInputStream(zip.inputStream().buffered()).use { zin ->
                var entry=zin.nextEntry
                while(entry!=null){ val safe=entry.name.replace("\\","/"); if(safe.contains("..")) {entry=zin.nextEntry; continue}; val out=File(root,safe); if(entry.isDirectory) out.mkdirs() else {out.parentFile?.mkdirs(); out.outputStream().use{zin.copyTo(it)}}; zin.closeEntry(); entry=zin.nextEntry }
            }
            val extracted=File(root,"vosk-model-ar-mgb2-0.4"); if(!extracted.exists()) error("لم يتم استخراج نموذج Vosk بشكل صحيح.")
            zip.delete(); onProgress(100); extracted
        } catch(e:Exception){ Log.d(TAG,"Model download failed: ${e.message}"); zip.delete(); throw e } finally { conn.disconnect() }
    }
    fun modelDirectory(): File=File(context.filesDir,"vosk/vosk-model-ar-mgb2-0.4")
    companion object { const val ARABIC_URL="https://alphacephei.com/vosk/models/vosk-model-ar-mgb2-0.4.zip"; private const val TAG="SariModel" }
}
