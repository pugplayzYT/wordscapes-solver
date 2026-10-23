package com.pugplayz.wordscapesauto

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONObject

/**
 * Diagnostic viewer for the actual /api/chat POST bodies and raw HTTP replies.
 * Launch from the floating DEBUG button after five STOP taps.
 *
 * The screenshot displayed here is decoded from the SAME image data sent
 * to Gemma (not a separate later screen capture).
 */
class AiDebugActivity:Activity() {
    private lateinit var picker:Spinner
    private lateinit var details:TextView
    private lateinit var request:TextView
    private lateinit var response:TextView
    private lateinit var screenshot:ImageView
    private lateinit var empty:TextView
    private var entries:List<AiDebugLog.Entry> = emptyList()
    private var selected:AiDebugLog.Entry? = null
    private var displayedBitmap:Bitmap? = null

    private fun dp(value:Int):Int =
        (resources.displayMetrics.density*value+.5f).toInt()

    private fun label(value:String,size:Float=14f):TextView =
        TextView(this).apply{
            text=value
            textSize=size
            setTextColor(Color.rgb(226,236,250))
            setPadding(dp(8),dp(9),dp(8),dp(9))
            setTextIsSelectable(true)
        }

    private fun uiButton(title:String,onClick:()->Unit):Button =
        Button(this).apply{
            text=title
            textSize=12f
            isAllCaps=false
            setOnClickListener{onClick()}
        }

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val root=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(14,23,39))
            setPadding(dp(10),dp(15),dp(10),dp(6))
        }
        root.addView(label("AI DEBUG · EXACT OLLAMA TRAFFIC",20f))
        root.addView(label(
            "Recorded locally, including screenshots and full JSON request " +
            "bodies. Copying requests also copies image base64. " +
            "Don't share logs that show private screen content.",12f))

        val tools=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        tools.addView(uiButton("REFRESH"){refresh()},LinearLayout.LayoutParams(0,dp(45),1f))
        tools.addView(uiButton("COPY ALL TEXT"){
            copy("All AI debug conversations (text only)",AiDebugLog.readableAll(this))
        },LinearLayout.LayoutParams(0,dp(45),1.2f))
        tools.addView(uiButton("CLEAR"){
            AlertDialog.Builder(this).setTitle("Clear AI debug logs?")
                .setMessage("Delete all saved request and response traces from this app?")
                .setNegativeButton("CANCEL",null)
                .setPositiveButton("DELETE"){_,_->
                    AiDebugLog.clear(this)
                    refresh()
                }.show()
        },LinearLayout.LayoutParams(0,dp(45),1f))
        root.addView(tools)

        picker=Spinner(this)
        root.addView(picker,LinearLayout.LayoutParams(-1,dp(55)))
        empty=label("No AI requests recorded yet. Press AUTO, then open DEBUG.",14f)
        root.addView(empty)

        val actionRow=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
        }
        actionRow.addView(uiButton("COPY PROMPT"){selected?.let{
            copy("Gemma prompt",AiDebugLog.prompt(this,it))
        }},LinearLayout.LayoutParams(0,dp(49),1f))
        actionRow.addView(uiButton("COPY RESPONSE"){selected?.let{
            copy("Raw Ollama response",AiDebugLog.response(this,it))
        }},LinearLayout.LayoutParams(0,dp(49),1f))
        root.addView(actionRow)
        val rawRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        rawRow.addView(uiButton("COPY EXACT REQUEST + IMAGE"){selected?.let{
            copy("Exact Ollama JSON request",AiDebugLog.request(this,it))
        }},LinearLayout.LayoutParams(0,dp(49),1f))
        rawRow.addView(uiButton("COPY EXCHANGE"){selected?.let{
            copy("Ollama request/response text",AiDebugLog.readable(this,it))
        }},LinearLayout.LayoutParams(0,dp(49),1f))
        root.addView(rawRow)

        val scroll=ScrollView(this).apply{isFillViewport=false}
        val detailColumn=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        details=label("",12f)
        detailColumn.addView(details)
        detailColumn.addView(label("EXACT SCREENSHOT SENT TO GEMMA",16f))
        screenshot=ImageView(this).apply{
            adjustViewBounds=true
            scaleType=ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.rgb(0,0,0))
        }
        detailColumn.addView(screenshot,LinearLayout.LayoutParams(-1,dp(300)))
        detailColumn.addView(label("SENT PROMPT",16f))
        request=label("",13f)
        detailColumn.addView(request)
        detailColumn.addView(label("RAW MODEL RESPONSE · JSON + METADATA",16f))
        response=label("",13f)
        detailColumn.addView(response)
        scroll.addView(detailColumn)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(uiButton("CLOSE DEBUG"){finish()},LinearLayout.LayoutParams(-1,dp(44)))

        picker.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent:android.widget.AdapterView<*>?,view:View?,
                position:Int,id:Long
            ){
                selected=entries.getOrNull(position)
                render()
            }
            override fun onNothingSelected(parent:android.widget.AdapterView<*>?){
                selected=null
                render()
            }
        }
        setContentView(root)
        refresh()
    }

    private fun refresh(){
        val lastId=selected?.id
        entries=AiDebugLog.entries(this)
        val titles=if(entries.isEmpty())listOf("No AI requests yet")
            else entries.map{it.title(this)}
        picker.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,titles)
        val position=entries.indexOfFirst{it.id==lastId}
        picker.setSelection(if(position>=0)position else 0)
        selected=entries.getOrNull(if(position>=0)position else 0)
        render()
    }

    private fun render(){
        displayedBitmap?.let {
            screenshot.setImageDrawable(null)
            if(!it.isRecycled)it.recycle()
        }
        displayedBitmap=null
        val item=selected
        if(item==null){
            empty.visibility=View.VISIBLE
            details.text=""
            request.text=""
            response.text=""
            return
        }
        empty.visibility=View.GONE
        val meta=AiDebugLog.info(this,item)
        val req=runCatching{JSONObject(AiDebugLog.request(this,item))}
            .getOrDefault(JSONObject())
        details.text=buildString {
            appendLine("Time: ${item.title(this@AiDebugActivity)}")
            appendLine("Endpoint: ${meta.optString("url","")}")
            appendLine("Model: ${req.optString("model","")}")
            appendLine("Options: ${req.optJSONObject("options")?.toString() ?: "{}"}")
            appendLine("Exact request bytes are available with COPY EXACT REQUEST.")
        }
        request.text=AiDebugLog.prompt(this,item)
        response.text=buildString {
            val failure=AiDebugLog.failure(this@AiDebugActivity,item)
            if(failure.isNotBlank())appendLine("ERROR: $failure\n")
            append(AiDebugLog.response(this@AiDebugActivity,item))
        }
        displayedBitmap=AiDebugLog.screenshot(this,item)
        screenshot.setImageBitmap(displayedBitmap)
    }

    private fun copy(label:String,content:String){
        val clipboard=getSystemService(Context.CLIPBOARD_SERVICE)
            as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label,content))
        Toast.makeText(this,"Copied to clipboard",Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        screenshot.setImageDrawable(null)
        displayedBitmap?.let{if(!it.isRecycled)it.recycle()}
        WordscapesService.instance?.setDebugViewing(false)
        super.onDestroy()
    }
}
