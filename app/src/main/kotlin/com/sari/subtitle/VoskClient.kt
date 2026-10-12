package com.sari.subtitle

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileInputStream

class VoskClient(private val modelDir: File) : SttClient {
    override suspend fun transcribe(file: File, language: String, contextPrompt: String): SttResult = withContext(Dispatchers.IO) {
        if (!modelDir.exists()) return@withContext SttResult("", success=false, error="نموذج Vosk غير محمّل بعد.")
        try {
            val model = Model(modelDir.absolutePath)
            val recognizer = Recognizer(model, 16_000f)
            FileInputStream(file).use { input ->
                val header = ByteArray(44); input.read(header)
                val buffer = ByteArray(8192)
                while (true) { val n=input.read(buffer); if(n<=0) break; recognizer.acceptWaveForm(buffer,0,n) }
            }
            val finalJson = JSONObject(recognizer.finalResult())
            recognizer.close(); model.close()
            val text=finalJson.optString("text").trim()
            SttResult(text, language.ifBlank { "ar" }, confidence=null, success=text.isNotBlank())
        } catch (e: Exception) { Log.d(TAG,"Vosk failed: ${e.message}"); SttResult("",success=false,error="فشل Vosk: ${e.message}") }
    }
    companion object { private const val TAG="SariVosk" }
}
