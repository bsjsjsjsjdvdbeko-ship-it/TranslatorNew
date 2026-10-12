package com.sari.subtitle

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SrtExporter {
    suspend fun export(context: Context, cues: List<SubtitleCue>): String? = withContext(Dispatchers.IO) {
        if (cues.isEmpty()) return@withContext null
        val name="SariSubtitle_"+SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(Date())+".srt"
        val content=cues.mapIndexed { i,c -> "${i+1}\n${format(c.startElapsedMs)} --> ${format(c.endElapsedMs)}\n${c.translated.trim()}\n" }.joinToString("\n")
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values=ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME,name); put(MediaStore.Downloads.MIME_TYPE,"application/x-subrip"); put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/SariSubtitle/") }
            val uri=context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values) ?: return@withContext null
            context.contentResolver.openOutputStream(uri)?.use{it.write(content.toByteArray(Charsets.UTF_8))} ?: return@withContext null
            uri.toString()
        } else null
    }
    private fun format(ms:Long):String { val x=ms.coerceAtLeast(0); return String.format(Locale.US,"%02d:%02d:%02d,%03d",x/3600000,(x/60000)%60,(x/1000)%60,x%1000) }
}
