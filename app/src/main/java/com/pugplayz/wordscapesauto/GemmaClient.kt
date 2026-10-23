package com.pugplayz.wordscapesauto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.*

/**
 * The phone is the controller. A locally hosted Ollama vision model does
 * semantic screenshot reading ONLY; it never receives Android privileges.
 *
 * For USB:
 *   adb reverse tcp:11434 tcp:11434
 *   URL on phone: http://127.0.0.1:11434
 *
 * All calls run on worker threads and return to their caller asynchronously.
 */
class GemmaClient(private val context: Context) {
    data class Scene(
        val state: String, val action: String, val point: Spot?,
        val wheelBounds: RectF?, val confidence: Double, val explanation: String
    )
    data class Wheel(
        val letters: String, val points: List<Spot>,
        val center: Spot, val radius: Float,
        val confidence: Double, val screenshotCrop: Rect
    )
    companion object {
        const val PREF="wordscapes.gemma"
        const val DEFAULT_URL="http://127.0.0.1:11434"
        const val DEFAULT_MODEL="gemma4:12b"
        fun enabled(ctx: Context): Boolean = ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE)
            .getBoolean("enabled",true)
    }

    private val prefs get() = context.getSharedPreferences(PREF,Context.MODE_PRIVATE)

    private fun settings(): Pair<String,String> {
        val url=prefs.getString("url",DEFAULT_URL)!!.trim().trimEnd('/')
        val model=prefs.getString("model",DEFAULT_MODEL)!!.trim()
        if(!url.startsWith("http://") && !url.startsWith("https://"))
            error("Ollama URL must start with http:// or https://")
        if(model.isBlank()) error("Ollama model is empty")
        return url to model
    }

    fun test(callback:(String)->Unit) {
        Thread {
            val msg=try {
                val (url,model)=settings()
                val conn=URL("$url/api/tags").openConnection() as HttpURLConnection
                conn.connectTimeout=6000
                conn.readTimeout=12000
                try {
                    if(conn.responseCode!=200) error("HTTP ${conn.responseCode}")
                    val json=JSONObject(conn.inputStream.bufferedReader().use{it.readText()})
                    val names=json.optJSONArray("models") ?: JSONArray()
                    val models=(0 until names.length()).map {
                        names.optJSONObject(it)?.optString("name") ?: ""
                    }
                    if(models.any{it==model||it.substringBefore(':')==model.substringBefore(':')})
                        "Connected; $model is installed."
                    else "Connected, but model $model was not found. Install with: ollama pull $model"
                } finally { conn.disconnect() }
            } catch(e:Exception){"Ollama connection failed: ${e.message ?: "network error"}"}
            callback(msg)
        }.start()
    }

    private fun encode(image:Bitmap,maxSide:Int):String {
        val maxOriginal=max(image.width,image.height)
        val ratio=min(1f,maxSide.toFloat()/maxOriginal.coerceAtLeast(1))
        val scaled=if(ratio<.999f)
            Bitmap.createScaledBitmap(image,max(1,(image.width*ratio).roundToInt()),
                max(1,(image.height*ratio).roundToInt()),true)
            else image
        try {
            val bytes=ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG,90,bytes)
            return Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP)
        } finally {if(scaled!==image) scaled.recycle()}
    }

    /**
     * Gemma 4 supports a separate thinking channel. Ollama may return HTTP
     * 200 with message.content="" when thinking consumes num_predict.
     * Explicitly disable thinking for the short machine-readable answers.
     *
     * Do not feed an empty string to JSONObject: report the Ollama metadata
     * and retry ONE time with a larger generation budget.
     */
    private fun ask(image:Bitmap,question:String,maxSide:Int):JSONObject {
        val (server,model)=settings()
        val encoded=encode(image,maxSide)
        var reason="Gemma did not return usable JSON"
        for(attempt in 0..1) {
            val request=JSONObject()
                .put("model",model)
                .put("stream",false)
                .put("think",false)
                .put("keep_alive","30m")
                .put("format","json")
                .put("options",JSONObject()
                    .put("temperature",0)
                    .put("num_predict",if(attempt==0)1100 else 1800))
                .put("messages",JSONArray().put(JSONObject()
                    .put("role","user")
                    .put("content",if(attempt==0)question else
                        question+"\\nIMPORTANT: Reply with exactly one valid JSON object. " +
                        "No reasoning, no explanation, no Markdown.")
                    .put("images",JSONArray().put(encoded))))
            val conn=URL("$server/api/chat").openConnection() as HttpURLConnection
            conn.requestMethod="POST"
            conn.setRequestProperty("Content-Type","application/json")
            conn.connectTimeout=9000
            // Running a 12B model partly on CPU can be very slow.
            conn.readTimeout=180000
            conn.doOutput=true
            try {
                conn.outputStream.use {
                    it.write(request.toString().toByteArray(Charsets.UTF_8))
                }
                val status=conn.responseCode
                val body=(if(status in 200..299)conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use{it.readText()} ?: ""
                if(status !in 200..299)
                    error("Ollama HTTP $status: ${body.take(200)}")
                if(body.isBlank()) {
                    reason="Ollama returned an empty HTTP response"
                    android.util.Log.w("WordscapesAuto",reason)
                    continue
                }
                val outer=try {JSONObject(body)}catch(e:org.json.JSONException) {
                    error("Ollama HTTP response is not JSON: ${e.message}")
                }
                val reply=outer.optJSONObject("message")
                val raw=reply?.optString("content","") ?: ""
                val doneReason=outer.optString("done_reason","unknown")
                val evalCount=outer.optInt("eval_count",-1)
                val thinkingChars=reply?.optString("thinking","")?.length ?: 0
                if(raw.isBlank()) {
                    reason="Ollama returned empty model content " +
                        "(done=$doneReason, tokens=$evalCount, thinking=$thinkingChars)"
                    android.util.Log.w("WordscapesAuto",reason)
                    continue
                }
                val trimmed=raw.trim()
                val noFence=trimmed.removePrefix("```json").removePrefix("```")
                    .removeSuffix("```").trim()
                // Some model versions surround the answer with commentary.
                // Only accept a single JSON object; never treat prose as a
                // successful letter recognition.
                val candidate=if(noFence.startsWith("{") && noFence.endsWith("}"))
                    noFence
                else {
                    val start=noFence.indexOf('{')
                    val end=noFence.lastIndexOf('}')
                    if(start>=0 && end>start) noFence.substring(start,end+1)
                    else ""
                }
                if(candidate.isEmpty()) {
                    reason="Gemma returned text instead of a JSON object " +
                        "(done=$doneReason, tokens=$evalCount)"
                    android.util.Log.w("WordscapesAuto",reason)
                    continue
                }
                try {
                    return JSONObject(candidate)
                } catch(e:org.json.JSONException) {
                    reason="Gemma returned invalid JSON: ${e.message?.take(90)} " +
                        "(done=$doneReason, tokens=$evalCount)"
                    android.util.Log.w("WordscapesAuto",reason)
                }
            } finally {
                conn.disconnect()
            }
        }
        error("$reason after 2 attempts")
    }

    /**
     * Optional dictionary-gap recovery. Text-only Gemma suggestions are not
     * assumed to be dictionary truth: constrain every candidate to the
     * letters actually visible in the confirmed wheel.
     *
     * This method is invoked only when no full-length word is found locally.
     */
    /**
     * Word suggestions come from Gemma alone. The full screenshot lets it
     * inspect crossword slot lengths, not just blindly enumerate every
     * permutation. No bundled dictionary or Datamuse results are consulted.
     *
     * Model suggestions are not inherently trustworthy. WordEngine.isValid
     * only validates tile multiplicities and lengths, NOT dictionary status.
     */
    fun suggestWords(
        letters:String,
        board:Bitmap,
        excluded:Set<String> = emptySet(),
        callback:(Result<List<String>>)->Unit
    ) {
        Thread {
            val result=runCatching {
                val cleaned=letters.trim().uppercase(Locale.US)
                require(cleaned.length in 3..12 && cleaned.all {it in 'A'..'Z'}) {
                    "Invalid wheel letters"
                }
                val previous=excluded.map { it.uppercase(Locale.US) }.toSet()
                val avoid=previous.take(50).joinToString(", ").ifBlank { "none" }
                val prompt="""
                    You are a skilled human player solving the Wordscapes
                    puzzle in this IMAGE. The letter tiles have already
                    been reliably read and confirmed: $cleaned.

                    Inspect the crossword grid ABOVE the letter wheel:
                    count blank cells and estimate required WORD LENGTHS.
                    Your job is to propose only the most likely REAL,
                    NORMAL, COMMON English answers to fill that board.

                    Return valid JSON only: {"words":["FIRST","SECOND"]}.

                    Requirements:
                    - Rank answers in best-first PLAY ORDER, not alphabetically.
                    - Choose obvious, frequent English words matching the
                      visible crossword slot lengths FIRST.
                    - Consider obvious full-length anagrams when plausible.
                    - Include common shorter slot words, especially 4+ letters.
                    - 3-letter words only if common and plausibly needed.
                    - NEVER produce 1- or 2-letter words.
                    - Each word uses only the available tiles $cleaned, each
                      occurrence at most once per word (duplicates matter).
                    - Exclude rare forms, archaic words, proper names,
                      abbreviations, nonsense, and made-up words.
                    - Do not brute-force all permutations or bonus words.
                    - At most 32 DISTINCT suggestions. Fewer is fine.
                    - Already attempted words; do NOT suggest: $avoid.
                    - Answer ONLY in JSON, with a 'words' array of strings.
                """.trimIndent()
                val json=ask(board,prompt,1400)
                val array=json.optJSONArray("words") ?:error("Gemma JSON has no words array")
                val output=LinkedHashSet<String>()
                for(i in 0 until array.length()) {
                    val candidate=array.optString(i,"").trim().uppercase(Locale.US)
                    if(candidate !in previous &&
                        WordEngine.isValid(candidate,cleaned))
                        output.add(candidate)
                    if(output.size >= 32)break
                }
                if(output.isEmpty())error("Gemma gave no usable 3+ letter words")
                output.toList() // Preserve Gemma's common-first ordering.
            }
            callback(result)
        }.start()
    }

    data class RoyalMove(
        val scene:String,
        val action:String,
        val point1:Spot?,
        val point2:Spot?,
        val confidence:Double,
        val description:String,
        val signature:String
    )

    /**
     * Single-step Royal Match planner, invoked AFTER every previous gesture.
     * Screenshot coordinates are normalized to 0..1000, then converted to
     * native Android screen coordinates.
     * No purchased boosters / money / lives may be spent intentionally.
     */
    fun planRoyalMatch(
        bitmap:Bitmap,
        previousMove:String,
        previousResult:String,
        callback:(Result<RoyalMove>)->Unit
    ) {
        Thread {
            val result=runCatching {
                val prompt="""
                    You are playing Royal Match, an Android MATCH-3 puzzle.
                    You see one current FULL-SCREEN screenshot. Return ONLY
                    one JSON object specifying EXACTLY ONE next action.
                    The phone (not you) will execute it and show you the
                    result in the next screenshot.

                    Prior executed action: $previousMove.
                    Observed result: $previousResult.
                    If a previous move produced no visible board change,
                    DO NOT repeat it. Re-examine the current board carefully.

                    Output JSON FORMAT (all fields mandatory):
                    {"scene":"menu","action":"tap",
                     "x1":500,"y1":760,"x2":500,"y2":760,
                     "confidence":0.90,"description":"Level button",
                     "board":{"left":0,"top":0,"right":0,"bottom":0,
                              "rows":0,"cols":0},
                     "from_row":-1,"from_col":-1,
                     "to_row":-1,"to_col":-1}

                    Valid scene: menu | prelevel | board | victory | failed |
                                 ad | unknown.
                    Valid action: tap | swipe | wait.
                    x1,y1,x2,y2 are integers 0 to 1000, relative to the
                    WHOLE screenshot including borders, x rightwards and
                    y downwards. Put the center of what must be tapped.

                    MENU: locate the actual bright LEVEL button and tap it.
                    PRELEVEL: locate the PLAY button and tap it, without
                    selecting paid booster options.
                    VICTORY: tap a visible FREE Continue, Next, or Level
                    button (not a paid offer, multiplier, purchase, or claim).
                    FAILED: tap a visible ordinary FREE Retry/Try Again if
                    available. NEVER spend coins, buy extra moves, watch an
                    optional rewarded video, or purchase anything. When no
                    free retry is clearly shown, action=wait.
                    AD: click ONLY clearly visible current Close/X/Skip;
                    never click install, open, rewarded, or purchase buttons.
                    If no close control is visible, action=wait.
                    UNKNOWN or dialog with costs/consent/uncertainty: wait.

                    BOARD: carefully read the match-3 grid, obstacles,
                    TARGETS top-left and remaining MOVES top-right.
                    Choose the single BEST next move to advance targets.
                    Swap only horizontally/vertically adjacent SWAPPABLE
                    ordinary colored tiles to make a legal match of 3+.
                    Prefer matches of 4+, creating Rockets, TNT, Propellers,
                    or Light Balls. Prefer combining free IN-BOARD power-ups,
                    using a free in-board power-up if clearly appropriate.
                    DO NOT touch boosters outside the board, purchase
                    buttons, coins, extra moves, or a Settings menu.
                    For MARBLE obstacles, an in-board power-up is necessary
                    to crack the Marble before a normal match clears Plaster.
                    A move into a blocked cell is invalid.
                    A swipe action must contain the centers of its two
                    adjacent cells in x1/y1 and x2/y2.

                    In BOARD, also report the OUTER rectangle of the
                    visible match-3 tile grid as board.left/top/right/bottom
                    in normalized 0..1000 units, and the number of rows
                    and columns if you can confidently count them.
                    Give 0 for rows/cols and -1 for row/col indices when
                    unsure. When certain, from_row/from_col and to_row/to_col
                    are ZERO-BASED integer indices into the board grid.
                    For a TAP of an already free IN-BOARD power-up, use
                    action=tap and x1/y1 at its center.

                    Only choose a move when you can SEE its cells.
                    Return action=wait and confidence low if uncertain.
                    Return confidence 0..1 and a short reason.
                    No Markdown. Only JSON.
                """.trimIndent()
                val json=ask(bitmap,prompt,1700)
                val scene=json.optString("scene","unknown").trim()
                    .lowercase(Locale.US)
                val action=json.optString("action","wait").trim()
                    .lowercase(Locale.US)
                if(scene !in setOf("menu","prelevel","board","victory",
                        "failed","ad","unknown"))
                    error("Unknown AI scene $scene")
                if(action !in setOf("tap","swipe","wait"))
                    error("Unsupported AI action $action")
                val confidence=json.optDouble("confidence",0.0)
                    .let {if(it.isFinite())it.coerceIn(0.0,1.0) else 0.0}
                val label=json.optString("description","").take(115)
                val x1=json.optDouble("x1",Double.NaN)
                val y1=json.optDouble("y1",Double.NaN)
                val x2=json.optDouble("x2",Double.NaN)
                val y2=json.optDouble("y2",Double.NaN)
                fun pt(x:Double,y:Double):Spot? =
                    if(x.isFinite() && y.isFinite() &&
                        x in 0.0..1000.0 && y in 0.0..1000.0)
                        Spot(pct(x,bitmap.width),pct(y,bitmap.height))
                    else null
                var p1=pt(x1,y1)
                var p2=pt(x2,y2)

                if(scene=="board" && action=="swipe") {
                    val board=json.optJSONObject("board")
                    val left=board?.optDouble("left",Double.NaN) ?:Double.NaN
                    val top=board?.optDouble("top",Double.NaN) ?:Double.NaN
                    val right=board?.optDouble("right",Double.NaN) ?:Double.NaN
                    val bottom=board?.optDouble("bottom",Double.NaN) ?:Double.NaN
                    val rows=board?.optInt("rows",0) ?:0
                    val cols=board?.optInt("cols",0) ?:0
                    val fr=json.optInt("from_row",-1)
                    val fc=json.optInt("from_col",-1)
                    val tr=json.optInt("to_row",-1)
                    val tc=json.optInt("to_col",-1)
                    val gridValid=rows in 3..14 && cols in 3..14 &&
                        left.isFinite() && top.isFinite() &&
                        right.isFinite() && bottom.isFinite() &&
                        left>=0 && top>=0 && right<=1000 && bottom<=1000 &&
                        right-left>=160 && bottom-top>=160 &&
                        fr in 0 until rows && tr in 0 until rows &&
                        fc in 0 until cols && tc in 0 until cols &&
                        kotlin.math.abs(fr-tr)+kotlin.math.abs(fc-tc)==1
                    if(gridValid) {
                        // Grid-cell centers are typically more reliable
                        // than a VLM's unconstrained pixel guesses.
                        fun gridPoint(row:Int,col:Int)=Spot(
                            pct(left+(col+.5)*(right-left)/cols,bitmap.width),
                            pct(top+(row+.5)*(bottom-top)/rows,bitmap.height))
                        p1=gridPoint(fr,fc)
                        p2=gridPoint(tr,tc)
                    } else {
                        // When AI cannot count cells, use only a clearly
                        // adjacent-looking swipe. No random long drags.
                        val a=p1 ?:error("Missing board swap start")
                        val b=p2 ?:error("Missing board swap end")
                        val distance=hypot(a.x-b.x,a.y-b.y)
                        val screenMin=min(bitmap.width,bitmap.height).toFloat()
                        if(distance !in screenMin*.025f..screenMin*.22f)
                            error("Board swipe not between nearby cells")
                        val dx=kotlin.math.abs(a.x-b.x)
                        val dy=kotlin.math.abs(a.y-b.y)
                        if(min(dx,dy)>max(dx,dy)*.45f)
                            error("Board swipe must be horizontal or vertical")
                    }
                }
                if(action!="wait" && p1==null)error("AI did not locate action")
                if(action=="swipe" && p2==null)error("AI missing swipe end")
                if(scene!="board" && action=="swipe")
                    error("Can only swipe in live board")
                if(action=="tap") {
                    val low=label.lowercase(Locale.US)
                    if(setOf("buy","purchase","coins","shop","offer",
                            "watch ad","extra moves","boosters").any {
                            low.contains(it) })
                        error("AI action could spend game currency")
                    if(scene=="board") {
                        // Board taps are allowed only for a clearly
                        // identified free IN-BOARD special power-up.
                        if(!listOf("rocket","tnt","propeller","light ball",
                                "power-up").any{low.contains(it)})
                            error("Board tap lacks free power-up confirmation")
                        val board=json.optJSONObject("board")
                        val l=board?.optDouble("left",Double.NaN) ?:Double.NaN
                        val t=board?.optDouble("top",Double.NaN) ?:Double.NaN
                        val r=board?.optDouble("right",Double.NaN) ?:Double.NaN
                        val b=board?.optDouble("bottom",Double.NaN) ?:Double.NaN
                        if(!l.isFinite() || !t.isFinite() ||
                            !r.isFinite() || !b.isFinite() ||
                            x1 !in l..r || y1 !in t..b)
                            error("Free power-up must be inside visible board")
                    }
                    if(scene=="failed" &&
                        !low.contains("retry") && !low.contains("try again"))
                        error("Failure screen tap is not a free retry")
                    if(scene=="ad" && !listOf("close","skip","x").any {
                            low.contains(it) })
                        error("Ad tap is not visibly Close/Skip")
                    if(scene=="prelevel" &&
                        !low.contains("play") && !low.contains("start"))
                        error("Prelevel tap is not Play")
                    if(scene=="menu" && !low.contains("level"))
                        error("Menu tap is not Level")
                    if(scene=="victory" && !listOf("continue","next",
                            "level","close").any{low.contains(it)})
                        error("Victory tap is not Next/Continue")
                }
                val sig=if(action=="wait")"wait" else
                    "$scene:$action:${p1?.x?.toInt()},${p1?.y?.toInt()}-" +
                    "${p2?.x?.toInt()},${p2?.y?.toInt()}"
                RoyalMove(scene,action,p1,p2,confidence,label,sig)
            }
            callback(result)
        }.start()
    }

    private fun pct(x:Double,max:Int):Float=(x/1000.0*max).toFloat()

    fun inspect(bitmap:Bitmap,callback:(Result<Scene>)->Unit) {
        Thread {
            val result=runCatching {
                val prompt="""
                    You are the visual screen reader for a Wordscapes puzzle controller.
                    Examine this ONE screenshot. Return only one JSON object:
                    {"state":"menu|puzzle|victory|ad|unknown",
                    "action":"level|skip|close_ad|none",
                    "x":0,"y":0,"left":0,"top":0,"right":0,"bottom":0,
                    "confidence":0.0,"explanation":""}
                    All x,y,left,top,right,bottom are coordinates from 0 to 1000,
                    with 0,0 at TOP LEFT and 1000,1000 at BOTTOM RIGHT.
                    The screenshot's aspect ratio must be respected.
                    MENU: if a clear main menu Level number button is visible,
                    state=menu, action=level, x/y point at its center.
                    PUZZLE: if a circular arrangement of large single letters is
                    visible, state=puzzle, action=none, return a tight bounding
                    rectangle around those wheel letters (left/top/right/bottom).
                    VICTORY: if a completed level shows Tap to Skip, Continue,
                    or Next, state=victory, action=skip and x/y at the label.
                    AD: if an advert covers the game, state=ad. Use close_ad ONLY
                    if a visible, currently actionable X, Close, or Skip ad button
                    is clearly shown; its x/y must be centered inside the actual
                    close/skip control. Otherwise return action=none and wait.
                    NEVER click an install, purchase, rewarded-video, claim,
                    download, or advertisement engagement button.
                    UNKNOWN: action=none. Do not invent a clickable button.
                    confidence is between 0 and 1. No Markdown.
                """.trimIndent()
                val json=ask(bitmap,prompt,1400)
                val state=json.optString("state","unknown").lowercase(Locale.US)
                val action=json.optString("action","none").lowercase(Locale.US)
                val x=json.optDouble("x",Double.NaN)
                val y=json.optDouble("y",Double.NaN)
                val point=if(x.isFinite()&&y.isFinite()&&x in 0.0..1000.0&&y in 0.0..1000.0)
                    Spot(pct(x,bitmap.width),pct(y,bitmap.height)) else null
                val left=json.optDouble("left",Double.NaN)
                val top=json.optDouble("top",Double.NaN)
                val right=json.optDouble("right",Double.NaN)
                val bottom=json.optDouble("bottom",Double.NaN)
                val bounds=if(listOf(left,top,right,bottom).all{it.isFinite()} &&
                    left>=0 && top>=0 && right<=1000 && bottom<=1000 &&
                    right-left>=60 && bottom-top>=40)
                    RectF(pct(left,bitmap.width),pct(top,bitmap.height),
                        pct(right,bitmap.width),pct(bottom,bitmap.height))
                    else null
                Scene(state,action,point,bounds,
                    json.optDouble("confidence",0.0).coerceIn(0.0,1.0),
                    json.optString("explanation","").take(150))
            }
            callback(result)
        }.start()
    }

    /**
     * A second high-resolution wheel crop lets Gemma read giant letters,
     * without having to infer character positions from a full-screen image.
     * Coordinates returned are local to that crop (0..1000) and then mapped
     * precisely back to the original screenshot.
     */
    fun readWheel(full:Bitmap,box:RectF,callback:(Result<Wheel>)->Unit) {
        Thread {
            val outcome=runCatching {
                val margin=min(box.width(),box.height())*.14f
                val bounds=Rect(
                    floor(box.left-margin).toInt().coerceIn(0,full.width-1),
                    floor(box.top-margin).toInt().coerceIn(0,full.height-1),
                    ceil(box.right+margin).toInt().coerceIn(1,full.width),
                    ceil(box.bottom+margin).toInt().coerceIn(1,full.height)
                )
                if(bounds.width()<80||bounds.height()<80)error("Wheel crop is too small")
                val crop=Bitmap.createBitmap(full,bounds.left,bounds.top,
                    bounds.width(),bounds.height())
                val json=try {
                    ask(crop,"""
                        This cropped image contains the Wordscapes circular LETTER WHEEL.
                        Return only JSON with format:
                        {"letters":[{"c":"E","x":500,"y":120},
                                    {"c":"P","x":800,"y":420}],
                         "confidence":0.0}
                        Read EACH letter exactly ONCE. Read clockwise starting at
                        the TOPMOST letter, including repeated letters. An O is
                        alphabetic O, not digit zero. NEVER solve the word.
                        Each x/y MUST mark the physical CENTER of its actual
                        letter tile/glyph. Coordinates are 0-1000 relative to
                        THIS CROPPED IMAGE, not the original screenshot.
                        There are between 3 and 12 letters. Don't invent missing
                        letters or positions. Return only valid JSON, no prose.
                    """.trimIndent(),1024)
                } finally {crop.recycle()}
                val array=json.optJSONArray("letters") ?:error("Missing letters array")
                if(array.length() !in 3..12)error("Model found ${array.length()} letters")
                val points=mutableListOf<Spot>()
                val letters=StringBuilder()
                for(i in 0 until array.length()) {
                    val item=array.optJSONObject(i) ?:error("Invalid letter item")
                    val label=item.optString("c","").trim().uppercase(Locale.US)
                        .replace('0','O')
                    if(label.length!=1||label[0] !in 'A'..'Z')
                        error("Invalid glyph at index $i")
                    val x=item.optDouble("x",Double.NaN)
                    val y=item.optDouble("y",Double.NaN)
                    if(!x.isFinite()||!y.isFinite()||
                        x !in 20.0..980.0||y !in 20.0..980.0)
                        error("Missing letter coordinates at index $i")
                    points.add(Spot(bounds.left+pct(x,bounds.width()),
                        bounds.top+pct(y,bounds.height())))
                    letters.append(label)
                }
                val center=Spot(points.map{it.x}.average().toFloat(),
                    points.map{it.y}.average().toFloat())
                val radii=points.map{hypot(it.x-center.x,it.y-center.y)}
                val radius=radii.average().toFloat()
                if(radius<17f)error("Letter centers too close together")
                if(radii.any{it !in radius*.55f..radius*1.50f})
                    error("Letter positions do not form a coherent ring")
                val minSeparation=radius*.24f
                for(i in points.indices)for(j in i+1 until points.size)
                    if(hypot(points[i].x-points[j].x,points[i].y-points[j].y)
                        <minSeparation)error("Duplicated letter location")
                // Check the reported clockwise order, in case the model
                // returned its reading order instead of angular order.
                val paired=letters.indices.map{i->letters[i] to points[i]}
                val sorted=paired.sortedBy {
                    (atan2(it.second.y-center.y,it.second.x-center.x)+2*PI+PI/2)%(2*PI)
                }
                Wheel(sorted.map{it.first}.joinToString(""),
                    sorted.map{it.second},center,radius,
                    json.optDouble("confidence",0.0).coerceIn(0.0,1.0),bounds)
            }
            callback(outcome)
        }.start()
    }
}
