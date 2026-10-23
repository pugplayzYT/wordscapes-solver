package com.pugplayz.wordscapesauto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exact Ollama HTTP traces, kept solely in private app storage.
 *
 * A request is recorded BEFORE it is sent, including the same JPEG base64
 * image bytes supplied to /api/chat. Each retry is a separate entry.
 * The RAW response body is stored verbatim (including error responses).
 * Saving a diagnostic must never prevent the game's normal AI request.
 *
 * Only the latest 40 exchanges are retained to bound disk usage. They are
 * never uploaded anywhere except the Ollama call the user configured.
 */
object AiDebugLog {
    private val lock = Any()
    private val sequence = AtomicInteger(0)
    private const val KEEP = 40

    data class Entry(val id: String, val whenMs: Long) {
        fun title(context: Context):String {
            val meta=metadata(context,this)
            val status=meta.optString("status","pending")
            val date=SimpleDateFormat("HH:mm:ss",Locale.getDefault())
                .format(Date(whenMs))
            return "$date · ${meta.optString("model","AI")} · $status"
        }
    }

    private fun directory(ctx:Context):File =
        File(ctx.filesDir,"ai_debug_traces").apply{mkdirs()}

    private fun path(ctx:Context,id:String,kind:String):File =
        File(directory(ctx),"$id.$kind")

    private fun metadata(ctx:Context,entry:Entry):JSONObject =
        runCatching{JSONObject(path(ctx,entry.id,"meta.json").readText())}
            .getOrDefault(JSONObject())

    fun begin(ctx:Context,url:String,json:String):String? = try {
        synchronized(lock) {
            val id="${System.currentTimeMillis()}-${sequence.getAndIncrement()}"
            val model=runCatching{JSONObject(json).optString("model","unknown")}
                .getOrDefault("unknown")
            path(ctx,id,"request.json").writeText(json)
            path(ctx,id,"meta.json").writeText(
                JSONObject()
                    .put("timestamp",System.currentTimeMillis())
                    .put("url",url)
                    .put("model",model)
                    .put("status","pending")
                    .toString())
            trim(ctx)
            id
        }
    }catch(e:Exception){
        Log.w("WordscapesAuto","Could not store AI debug request",e)
        null
    }

    fun complete(ctx:Context,id:String?,http:Int,raw:String) {
        if(id==null)return
        try{
            synchronized(lock) {
                path(ctx,id,"response.txt").writeText(raw)
                updateStatus(ctx,id,"HTTP $http")
            }
        }catch(e:Exception) {
            Log.w("WordscapesAuto","Could not store AI debug response",e)
        }
    }

    fun failed(ctx:Context,id:String?,problem:String) {
        if(id==null)return
        try{
            synchronized(lock){
                path(ctx,id,"error.txt").writeText(problem)
                updateStatus(ctx,id,"ERROR")
            }
        }catch(e:Exception){
            Log.w("WordscapesAuto","Could not store AI debug error",e)
        }
    }

    private fun updateStatus(ctx:Context,id:String,state:String) {
        val file=path(ctx,id,"meta.json")
        val json=runCatching{JSONObject(file.readText())}
            .getOrDefault(JSONObject())
        file.writeText(json.put("status",state).toString())
    }

    private fun trim(ctx:Context) {
        val ids=directory(ctx).listFiles()
            ?.filter{it.name.endsWith(".request.json")}
            ?.map{it.name.removeSuffix(".request.json")}
            ?.sortedDescending() ?:return
        for(id in ids.drop(KEEP)) {
            for(kind in listOf("request.json","response.txt","error.txt","meta.json"))
                path(ctx,id,kind).delete()
        }
    }

    fun entries(ctx:Context):List<Entry> = try {
        synchronized(lock) {
            directory(ctx).listFiles()?.filter{
                it.name.endsWith(".request.json")
            }?.map {
                val id=it.name.removeSuffix(".request.json")
                val timestamp=id.substringBefore('-').toLongOrNull() ?:0L
                Entry(id,timestamp)
            }?.sortedWith(compareByDescending<Entry>{it.whenMs}
                .thenByDescending{it.id}) ?:emptyList()
        }
    }catch(_:Exception){emptyList()}

    fun request(ctx:Context,entry:Entry):String =
        runCatching{path(ctx,entry.id,"request.json").readText()}
            .getOrDefault("(request missing)")
    fun response(ctx:Context,entry:Entry):String =
        runCatching{path(ctx,entry.id,"response.txt").readText()}
            .getOrDefault("(response pending or unavailable)")
    fun failure(ctx:Context,entry:Entry):String =
        runCatching{path(ctx,entry.id,"error.txt").readText()}.getOrDefault("")
    fun info(ctx:Context,entry:Entry):JSONObject=metadata(ctx,entry)

    fun prompt(ctx:Context,entry:Entry):String {
        return runCatching {
            val json=JSONObject(request(ctx,entry))
            val messages=json.optJSONArray("messages") ?:return@runCatching ""
            (0 until messages.length()).joinToString("\n\n") {i->
                val m=messages.optJSONObject(i)
                "${m?.optString("role","unknown")}: ${m?.optString("content","")}"
            }
        }.getOrDefault("")
    }

    /** Displays the exact JPEG image that was base64-encoded in the POST. */
    fun screenshot(ctx:Context,entry:Entry):Bitmap? {
        return try {
        val json=JSONObject(request(ctx,entry))
        val messages=json.optJSONArray("messages") ?:return null
        val last=messages.optJSONObject(messages.length()-1) ?:return null
        val images=last.optJSONArray("images") ?:return null
        if(images.length()==0)return null
        val bytes=Base64.decode(images.optString(0),Base64.DEFAULT)
        val opts=BitmapFactory.Options().apply{inSampleSize=2}
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,opts)
    }catch(e:Exception){
        Log.w("WordscapesAuto","Could not open AI debug screenshot",e)
        null
    }catch(_:OutOfMemoryError){null}
    }

    /** Text-only overview deliberately does not duplicate giant image base64. */
    fun readable(ctx:Context,entry:Entry):String {
        val meta=info(ctx,entry)
        return buildString {
            appendLine("=== ${entry.title(ctx)} ===")
            appendLine("Endpoint: ${meta.optString("url","")}")
            appendLine("HTTP status: ${meta.optString("status","")}")
            appendLine("SENT PROMPT:")
            appendLine(prompt(ctx,entry))
            appendLine("RECEIVED RAW RESPONSE:")
            appendLine(response(ctx,entry))
            val error=failure(ctx,entry)
            if(error.isNotBlank()){
                appendLine("ERROR:")
                appendLine(error)
            }
            appendLine()
        }
    }

    fun readableAll(ctx:Context):String =
        entries(ctx).asReversed().joinToString("\n"){readable(ctx,it)}

    fun clear(ctx:Context) {
        synchronized(lock) {
            directory(ctx).listFiles()?.forEach{it.delete()}
        }
    }
}
