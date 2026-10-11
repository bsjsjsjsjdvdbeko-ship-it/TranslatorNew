package com.sari.subtitle

import java.io.File
import kotlin.math.max

/** Timing/deduplication primitives for PCM audio pipelines. Chunks are 3s with 500ms overlap. */
object AudioChunker {
 const val CHUNK_MS=3000L; const val OVERLAP_MS=500L; const val STEP_MS=CHUNK_MS-OVERLAP_MS
 fun chunkCount(durationMs:Long):Int=if(durationMs<=0) 0 else max(1, kotlin.math.ceil((durationMs-OVERLAP_MS).toDouble()/STEP_MS).toInt())
 fun startMs(index:Int):Long=index*STEP_MS
}
class TranscriptDeduplicator(private val similarityThreshold:Double=0.82){
 private var previous=""
 @Synchronized fun shouldTranslate(candidate:String):Boolean { val cleaned=candidate.lowercase().replace(Regex("[^\\p{L}\\p{N} ]")," ").replace(Regex("\\s+")," ").trim(); if(cleaned.isBlank())return false; val old=previous; if(old.isNotBlank() && similarity(old,cleaned)>=similarityThreshold)return false; previous=cleaned; return true }
 private fun similarity(a:String,b:String):Double { if(a==b)return 1.0; val aa=a.split(" ").filter{it.isNotBlank()}.toSet(); val bb=b.split(" ").filter{it.isNotBlank()}.toSet(); if(aa.isEmpty()||bb.isEmpty())return 0.0; return 2.0*aa.intersect(bb).size/(aa.size+bb.size) }
}
