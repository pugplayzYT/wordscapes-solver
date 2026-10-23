package com.pugplayz.wordscapesauto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Path
import android.hardware.HardwareBuffer
import android.os.Handler
import android.os.Looper
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.os.Build
import android.widget.*
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.*

/** Explicit, user-triggered gameplay automation. No ad interaction. */
class WordscapesService : AccessibilityService() {
    companion object { var instance: WordscapesService? = null; private set }

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val ocr = LetterReader()
    private val wheelVision = WheelVision()
    private val sceneReader = SceneReader()
    private val gemma by lazy { GemmaClient(this) }
    private var gemmaMode = false
    private var gemmaIdleCount = 0
    private var gemmaFailures = 0
    private var gemmaLastAction: GemmaClient.Scene? = null
    private var gemmaActionFrames = 0
    private var gemmaLastDispatched = ""
    private var gemmaRepeatTaps = 0
    // Display curtain: physically sleeping Android's display would interrupt
    // screenshots/touches. This visual curtain stays non-touchable so the
    // accessibility automation can still interact with the game underneath.
    private val curtainPrefs get() =
        getSharedPreferences("wordscapes.curtain", android.content.Context.MODE_PRIVATE)
    private var curtainOn = false
    private var curtainMask: View? = null
    private var curtainWake: TextView? = null
    private var autoPilot = false
    private var autoRetries = 0
    private var autoUiRetries = 0
    private var lastUiAction: SceneReader.Action? = null
    private var stableUiFrames = 0
    private lateinit var topBar: LinearLayout
    private lateinit var autoControl: Button
    private val overlayStore get() = getSharedPreferences("wordscapes.overlay", android.content.Context.MODE_PRIVATE)
    private lateinit var status: TextView
    private var reviewPanel: LinearLayout? = null
    private var isRunning = false
    private var generation = 0
    private var pending = ""
    private var pendingPoints: List<Spot> = emptyList()
    private var pendingCalibration: Calibration? = null
    private var pendingWheel: WheelResult? = null
    private var scanPreview: Bitmap? = null
    private var scanPreviewOrigin: Spot? = null
    private var correctedCount: Int? = null
    private var glyphSamples: List<GlyphMemory.Sample?> = emptyList()
    private var uncertainSlots: List<Int> = emptyList()
    private var solveWords: List<String> = emptyList()
    private var aiCandidateWords: List<String> = emptyList()
    private var solveIndex = 0
    private var actualLetters = ""
    private var activePoints: List<Spot> = emptyList()
    private var baselineImage: Bitmap? = null
    private var activeCalibration: Calibration? = null
    private var activeWheel: WheelResult? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        WordEngine.load(this)
        if (!overlayStore.getBoolean("hidden", false)) buildBar()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* Intentionally unused. */ }
    override fun onInterrupt() { stopAutomation("Accessibility interrupted") }
    override fun onDestroy() {
        stopAutomation("Service disabled")
        removeReview()
        if (::topBar.isInitialized && topBar.isAttachedToWindow) try { wm.removeView(topBar) } catch (_: Exception) {}
        baselineImage?.recycle(); baselineImage = null
        scanPreview?.recycle(); scanPreview = null
        ocr.close()
        wheelVision.close()
        sceneReader.close()
        instance = null
        super.onDestroy()
    }

    private fun overlayParams(w: Int, h: Int, focusable: Boolean, y: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
                (if (autoPilot) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0),
            android.graphics.PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                this.y = y
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }

    /**
     * Two-row responsive toolbar. Native Buttons have large intrinsic insets:
     * never put five Buttons and the long solver status on the same row.
     *
     * Row 1: equal-width primary actions.
     * Row 2: separate CAL / HIDE actions and an independently sized status.
     */
    private fun buildBar() {
        if (::topBar.isInitialized && topBar.isAttachedToWindow) return
        val background = android.graphics.Color.rgb(17, 40, 71)
        val blue = android.graphics.Color.rgb(40, 112, 185)
        val danger = android.graphics.Color.rgb(161, 47, 58)
        val neutral = android.graphics.Color.rgb(67, 86, 108)

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), dp(5), dp(5), dp(5))
            setBackgroundColor(android.graphics.Color.argb(246, 17, 40, 71))
        }

        fun toolbarButton(label: String, color: Int, height: Int, action: () -> Unit): Button =
            Button(this).apply {
                text = label
                textSize = 13f
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setSingleLine(true)
                setPadding(dp(4), 0, dp(4), 0)
                backgroundTintList = android.content.res.ColorStateList.valueOf(color)
                setTextColor(android.graphics.Color.WHITE)
                setOnClickListener { action() }
                layoutParams = LinearLayout.LayoutParams(0, dp(height), 1f).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                }
            }

        val mainRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        autoControl = toolbarButton("AUTO", blue, 44) {
            if (autoPilot) stopAutomation("AUTO stopped")
            else startAutomation()
        }
        mainRow.addView(autoControl)
        mainRow.addView(toolbarButton("DARK", neutral, 44) {
            setCurtainPreference(!curtainOn)
        })
        mainRow.addView(toolbarButton("SCAN", blue, 44) { scan() })
        mainRow.addView(toolbarButton("STOP", danger, 44) {
            stopAutomation("Stopped by user")
        })
        topBar.addView(mainRow, LinearLayout.LayoutParams(-1, dp(44)))

        val utilityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, 0)
        }
        val cal = toolbarButton("CAL", neutral, 38) { calibrate() }
        val hide = toolbarButton("HIDE", neutral, 38) { hideControls() }
        // Fixed, comfortable widths for the two utility buttons; the status
        // takes the rest. All controls fit on narrow phone screens.
        utilityRow.addView(cal, LinearLayout.LayoutParams(dp(70), dp(38)).apply {
            setMargins(dp(2), 0, dp(2), 0)
        })
        utilityRow.addView(hide, LinearLayout.LayoutParams(dp(74), dp(38)).apply {
            setMargins(dp(2), 0, dp(2), 0)
        })
        status = TextView(this).apply {
            text = "Ready"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 12f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), 0, dp(3), 0)
            contentDescription = "Solver status: Ready"
        }
        utilityRow.addView(status, LinearLayout.LayoutParams(0, dp(38), 1f))
        topBar.addView(utilityRow, LinearLayout.LayoutParams(-1, dp(41)))

        val screenWidth = resources.displayMetrics.widthPixels
        val barWidth = min(screenWidth - dp(16), dp(560)).coerceAtLeast(dp(210))
        wm.addView(topBar, overlayParams(barWidth, -2, false, dp(16)))
        updateAutoControl()
    }

    private fun updateAutoControl() {
        if (!::autoControl.isInitialized) return
        autoControl.text = if (autoPilot) (if(gemmaMode) "AI ON" else "AUTO ON") else "AUTO"
        autoControl.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (autoPilot) android.graphics.Color.rgb(23, 142, 106)
            else android.graphics.Color.rgb(40, 112, 185)
        )
    }

    /** HIDE removes ALL floating views, cancels the solve queue, and stays hidden until SHOW in the app. */
    private fun hideControls() {
        stopAutomation("Controls hidden")
        removeReview()
        overlayStore.edit().putBoolean("hidden", true).apply()
        if (::topBar.isInitialized && topBar.isAttachedToWindow) {
            try { wm.removeView(topBar) } catch (_: Exception) {}
        }
    }

    fun areControlsVisible(): Boolean =
        ::topBar.isInitialized && topBar.isAttachedToWindow

    /** Called from MainActivity's SHOW CONTROLS button. No accessibility restart needed. */
    fun showControls(): Boolean {
        if (!::wm.isInitialized) return false
        overlayStore.edit().putBoolean("hidden", false).apply()
        if (!areControlsVisible()) buildBar()
        message("Ready")
        return true
    }

    private fun message(text: String) {
        if (::status.isInitialized) {
            status.text = text
            status.contentDescription = "Solver status: $text"
        }
    }

    private fun keepAwake(enabled: Boolean) {
        if (!::topBar.isInitialized || !topBar.isAttachedToWindow) return
        try {
            val lp = topBar.layoutParams as WindowManager.LayoutParams
            if (enabled) lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            else lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
            wm.updateViewLayout(topBar, lp)
        } catch (_: Exception) {}
    }

    /**
     * OLED-friendly curtain. This is NOT the Android power-button screen-off
     * state: display capture and touch dispatch require the display awake.
     *
     * A black, NOT_TOUCHABLE accessibility overlay obscures the gameplay
     * visually while letting synthetic gestures reach the actual game. The
     * small WAKE affordance is separately touchable by the user.
     */
    fun setCurtainPreference(enabled:Boolean) {
        if(enabled && Build.VERSION.SDK_INT<34) {
            curtainPrefs.edit().putBoolean("auto_dark",false).apply()
            message("No-flash DARK requires Android 14+")
            return
        }
        curtainPrefs.edit().putBoolean("auto_dark",enabled).apply()
        if(enabled && !autoPilot) {
            message("Start AUTO first, then tap DARK")
            return
        }
        setCurtain(enabled)
        message(if(enabled) "Screen curtain on • top-right WAKE to reveal"
                else "Screen visible • AUTO unchanged")
    }

    private fun setCurtain(enabled:Boolean) {
        if(!enabled) {
            curtainOn=false
            val wake=curtainWake
            curtainWake=null
            if(wake!=null && wake.isAttachedToWindow)
                try{wm.removeView(wake)}catch(_:Exception){}
            val mask=curtainMask
            curtainMask=null
            if(mask!=null && mask.isAttachedToWindow)
                try{wm.removeView(mask)}catch(_:Exception){}
            return
        }
        if(!autoPilot || !::wm.isInitialized)return
        if(curtainOn && curtainMask?.isAttachedToWindow==true)return
        curtainOn=true
        try {
            val black=View(this).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val maskParams=WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                android.graphics.PixelFormat.OPAQUE
            ).apply {
                gravity=Gravity.TOP or Gravity.START
                screenBrightness=0.0f
                layoutInDisplayCutoutMode=
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            curtainMask=black
            wm.addView(black,maskParams)
            // Only this compact top-right tab receives a human tap; the
            // full-screen curtain lets every other gesture pass through.
            val wake=TextView(this).apply {
                text="WAKE"
                textSize=11f
                gravity=Gravity.CENTER
                setTextColor(android.graphics.Color.rgb(100,100,100))
                setBackgroundColor(android.graphics.Color.rgb(12,12,12))
                contentDescription="Reveal game while keeping AUTO running"
                setOnClickListener {
                    setCurtainPreference(false)
                    Toast.makeText(this@WordscapesService,
                        "Screen revealed; AUTO continues",
                        Toast.LENGTH_SHORT).show()
                }
            }
            val wakeParams=WindowManager.LayoutParams(
                dp(65),dp(38),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity=Gravity.TOP or Gravity.END
                y=dp(34)
                x=dp(12)
                screenBrightness=0.0f
            }
            curtainWake=wake
            wm.addView(wake,wakeParams)
        } catch(e:Exception) {
            curtainOn=false
            curtainWake?.let { if(it.isAttachedToWindow)
                try{wm.removeView(it)}catch(_:Exception){} }
            curtainMask?.let { if(it.isAttachedToWindow)
                try{wm.removeView(it)}catch(_:Exception){} }
            curtainWake=null
            curtainMask=null
            message("Cannot darken display: ${e.message?.take(45)}")
        }
    }

    private fun stopAutomation(reason: String) {
        setCurtain(false)
        autoPilot = false
        gemmaMode = false
        gemmaIdleCount = 0
        gemmaFailures = 0
        gemmaLastAction = null
        gemmaActionFrames = 0
        gemmaLastDispatched = ""
        gemmaRepeatTaps = 0
        autoRetries = 0
        autoUiRetries = 0
        lastUiAction = null
        stableUiFrames = 0
        keepAwake(false)
        updateAutoControl()
        stop(reason)
    }

    private fun startAutomation() {
        if (GemmaClient.enabled(this)) {
            removeReview()
            stop("Starting Gemma vision…")
            autoPilot = true
            gemmaMode = true
            gemmaIdleCount = 0
            gemmaFailures = 0
            gemmaLastAction = null
            gemmaActionFrames = 0
            gemmaLastDispatched = ""
            gemmaRepeatTaps = 0
            keepAwake(true)
            updateAutoControl()
            if(curtainPrefs.getBoolean("auto_dark",false))setCurtain(true)
            gemmaInspect()
            return
        }
        if (!Settings.hasCalibration(this)) {
            message("Set CAL circle first")
            return
        }
        removeReview()
        stop("Starting unattended mode…")
        autoPilot = true
        autoRetries = 0
        autoUiRetries = 0
        stableUiFrames = 0
        lastUiAction = null
        keepAwake(true)
        updateAutoControl()
        if(curtainPrefs.getBoolean("auto_dark",false))setCurtain(true)
        inspectScene(true)
    }

    /**
     * AI state machine: each screenshot is sent to the user's Ollama model.
     * Gemma classifies the whole screen, then we crop the wheel and perform a
     * second image query for precise letter locations. The phone controls taps.
     */
    private fun gemmaInspect(oneShot: Boolean = false) {
        if (!oneShot && (!autoPilot || !gemmaMode)) return
        val ticket = generation
        message("Gemma: reading screen…")
        capture { screenshot ->
            if (ticket != generation || (!oneShot && (!autoPilot || !gemmaMode))) {
                screenshot?.recycle()
                return@capture
            }
            if (screenshot == null) {
                gemmaError("Screenshot unavailable",oneShot)
                return@capture
            }
            gemma.inspect(screenshot) { remote ->
                handler.post {
                    if (ticket != generation || (!oneShot && (!autoPilot || !gemmaMode))) {
                        screenshot.recycle()
                        return@post
                    }
                    val scene = remote.getOrNull()
                    if(scene == null) {
                        screenshot.recycle()
                        gemmaError(remote.exceptionOrNull()?.message ?: "Ollama failed",oneShot)
                        return@post
                    }
                    message("Gemma: ${scene.state} / ${scene.action}")
                    if(gemmaLastDispatched.isNotEmpty() &&
                        !gemmaLastDispatched.startsWith("${scene.state}/")) {
                        gemmaRepeatTaps=0
                        gemmaLastDispatched=""
                    }
                    when(scene.state) {
                        "puzzle" -> {
                            gemmaLastAction = null
                            gemmaActionFrames = 0
                            val saved = Settings.load(this,screenshot.width,screenshot.height)
                            val bounds = when {
                                scene.wheelBounds != null && scene.confidence >= .35 ->
                                    scene.wheelBounds
                                saved != null -> android.graphics.RectF(
                                    saved.center.x-saved.radius,saved.center.y-saved.radius,
                                    saved.center.x+saved.radius,saved.center.y+saved.radius
                                )
                                else -> null
                            }
                            if(bounds == null) {
                                screenshot.recycle()
                                gemmaError("Gemma could not locate the wheel",oneShot)
                            } else {
                                gemmaReadWheel(screenshot,bounds,ticket,oneShot)
                            }
                        }
                        "menu","victory","ad" -> {
                            if(oneShot) {
                                screenshot.recycle()
                                message("AI sees ${scene.state}. Tap AUTO to navigate.")
                            } else {
                                val targetKind=when(scene.state) {
                                    "menu" -> SceneReader.Kind.LEVEL_BUTTON
                                    "victory" -> SceneReader.Kind.TAP_TO_SKIP
                                    else -> SceneReader.Kind.AD_CLOSE
                                }
                                // Independent, fast OCR grounds the Level
                                // button at a real text rectangle instead
                                // of trusting Gemma's approximate x/y.
                                sceneReader.inspect(screenshot,targetKind) { anchor ->
                                    handler.post {
                                        val frameWidth=screenshot.width
                                        val frameHeight=screenshot.height
                                        screenshot.recycle()
                                        if(ticket==generation && autoPilot && gemmaMode) {
                                            gemmaNavigate(scene,anchor,frameWidth,frameHeight)
                                        }
                                    }
                                }
                            }
                        }
                        else -> {
                            screenshot.recycle()
                            if(oneShot) message("AI: unknown screen; open a puzzle")
                            else gemmaWait("unknown screen")
                        }
                    }
                }
            }
        }
    }

    private fun gemmaReadWheel(bitmap:Bitmap,bounds:android.graphics.RectF,
                               ticket:Int,oneShot:Boolean) {
        message("Gemma: reading enlarged wheel…")
        gemma.readWheel(bitmap,bounds) { first ->
            handler.post {
                if(ticket != generation || (!oneShot && (!autoPilot || !gemmaMode))) {
                    bitmap.recycle()
                    return@post
                }
                val initial=first.getOrNull()
                if(initial==null) {
                    bitmap.recycle()
                    gemmaError(first.exceptionOrNull()?.message ?: "Wheel reading failed",oneShot)
                    return@post
                }
                if(oneShot) {
                    // SCAN is intended for diagnosis. Show the very first
                    // returned recognition and marker locations for inspection.
                    gemmaUseWheel(bitmap,initial,oneShot)
                    return@post
                }
                // Don't spend the user's coins/time on single-pass invented
                // letters or screen coordinates. Re-read the same enlarged
                // crop independently and compare letter-by-letter plus touches.
                message("Gemma: confirming ${initial.letters}…")
                gemma.readWheel(bitmap,bounds) { second ->
                    handler.post {
                        if(ticket != generation || !autoPilot || !gemmaMode) {
                            bitmap.recycle()
                            return@post
                        }
                        val again=second.getOrNull()
                        if(again==null || again.letters!=initial.letters ||
                            again.points.size!=initial.points.size) {
                            bitmap.recycle()
                            gemmaError("AI wheel readings disagree",false)
                            return@post
                        }
                        val tolerance=max(14f,initial.radius*.32f)
                        if(initial.points.indices.any {
                            hypot(initial.points[it].x-again.points[it].x,
                                  initial.points[it].y-again.points[it].y)>tolerance
                        }) {
                            bitmap.recycle()
                            gemmaError("AI touch positions disagree",false)
                            return@post
                        }
                        val averaged=initial.points.indices.map {
                            Spot((initial.points[it].x+again.points[it].x)/2f,
                                 (initial.points[it].y+again.points[it].y)/2f)
                        }
                        val center=Spot(averaged.map{it.x}.average().toFloat(),
                            averaged.map{it.y}.average().toFloat())
                        val rad=averaged.map{hypot(it.x-center.x,it.y-center.y)}.average().toFloat()
                        val confirmed=GemmaClient.Wheel(initial.letters,averaged,
                            center,rad,max(initial.confidence,again.confidence),
                            initial.screenshotCrop)
                        gemmaUseWheel(bitmap,confirmed,false)
                    }
                }
            }
        }
    }

    private fun gemmaUseWheel(bitmap:Bitmap,wheel:GemmaClient.Wheel,
                              oneShot:Boolean) {
        val letters=wheel.letters
        val points=wheel.points
        val cal=Calibration(wheel.center,wheel.radius*1.38f)
        pendingCalibration=cal
        pendingPoints=points
        pendingWheel=WheelResult(points,wheel.confidence.toFloat(),
            letters.length,wheel.center,wheel.radius)
        pending=letters
        glyphSamples=emptyList()
        uncertainSlots=emptyList()
        aiCandidateWords=emptyList()
        scanPreview?.recycle()
        scanPreview=Bitmap.createBitmap(bitmap,
            wheel.screenshotCrop.left,wheel.screenshotCrop.top,
            wheel.screenshotCrop.width(),wheel.screenshotCrop.height())
        scanPreviewOrigin=Spot(wheel.screenshotCrop.left.toFloat(),
            wheel.screenshotCrop.top.toFloat())
        gemmaFailures=0
        gemmaIdleCount=0
        if(oneShot) {
            bitmap.recycle()
            message("Gemma: $letters • inspect detected touch points")
            showReview()
            return
        }

        // Do not consult the offline dictionary or Datamuse at all in
        // Gemma mode. Let the vision model inspect the crossword board
        // and rank likely common answers, not random permutations.
        // The bitmap must stay alive until the worker has encoded it.
        val ticket=generation
        message("Gemma: $letters • finding common grid answers…")
        gemma.suggestWords(letters,bitmap) { outcome ->
            handler.post {
                bitmap.recycle()
                if(ticket!=generation || !autoPilot || !gemmaMode)
                    return@post
                val suggested=outcome.getOrNull().orEmpty()
                    .filter {WordEngine.isValid(it,letters)}
                    .distinct().take(32)
                if(suggested.isEmpty()) {
                    android.util.Log.w("WordscapesAuto",
                        "AI common-word list unavailable",
                        outcome.exceptionOrNull())
                    stopAutomation("AI paused: no common words for $letters")
                    if(reviewPanel==null)showReview()
                    return@post
                }
                aiCandidateWords=suggested
                message("$letters • ${suggested.size} likely AI answers")
                startSolving(letters)
            }
        }
    }

    /**
     * A screen classification of MENU/LEVEL is not itself a valid tap.
     * If ML Kit sees the literal Level button, trust its glyph bounding
     * box, which is significantly more precise than an LLM point estimate.
     * Otherwise use Gemma's coordinate only after two stable readings.
     * Ads ALWAYS require a visible close affordance plus strong evidence.
     */
    private fun gemmaNavigate(scene:GemmaClient.Scene,
                              ocr:SceneReader.Action?,
                              screenWidth:Int,screenHeight:Int) {
        if(!autoPilot || !gemmaMode)return
        val desired=when(scene.state) {
            "menu" -> "level"
            "victory" -> "skip"
            "ad" -> "close_ad"
            else -> "none"
        }
        if(scene.action!=desired) {
            gemmaWait("AI: ${scene.state}, no ${desired} control")
            return
        }
        val expected=when(scene.state) {
            "menu" -> SceneReader.Kind.LEVEL_BUTTON
            "victory" -> SceneReader.Kind.TAP_TO_SKIP
            else -> SceneReader.Kind.AD_CLOSE
        }
        val verifiedOCR=ocr?.takeIf{it.kind==expected}
        val target=verifiedOCR?.let{Spot(it.x,it.y)} ?: scene.point
        if(target==null) {
            gemmaWait("AI sees $desired but returned NO x/y; OCR found none")
            return
        }
        // Hard coordinate rejection guards against malformed JSON (e.g.
        // x=0.7 rather than 700 in the normalized 0-1000 format).
        if(target.x !in screenWidth*.04f..screenWidth*.96f ||
           target.y !in screenHeight*.11f..screenHeight*.96f) {
            gemmaWait("AI target off screen: ${target.x.toInt()},${target.y.toInt()}")
            return
        }
        // Ad closes must be actual observed X/Skip buttons; a hallucinated
        // model coordinate is insufficient for unattended navigation.
        if(scene.state=="ad" && verifiedOCR==null) {
            gemmaWait("Ad present: waiting for visible Close/Skip label")
            return
        }
        // Independent OCR + Gemma screen agreement is strong evidence for
        // menu/victory; don't demand an arbitrary 0.79 LLM self-confidence.
        val supported=verifiedOCR!=null || scene.confidence>=.52
        if(!supported) {
            gemmaWait("Gemma $desired confidence ${(scene.confidence*100).toInt()}%")
            return
        }
        val previous=gemmaLastAction
        val tolerance=if(verifiedOCR!=null) dp(110) else dp(90)
        val steady=previous?.state==scene.state &&
            previous.action==scene.action &&
            previous.point!=null &&
            hypot(previous.point.x-target.x,previous.point.y-target.y)<tolerance
        if(steady) gemmaActionFrames++ else {
            gemmaActionFrames=1
            // Save the chosen OCR-anchored screen position for next-frame
            // comparison, not Gemma's potentially volatile coordinate.
            gemmaLastAction=scene.copy(point=target)
        }
        // Two independent full-screen reads for ad navigation, even when
        // the OCR label is present. For ordinary Level buttons the ML Kit
        // evidence itself is independent from the Gemma classification,
        // so a single model frame is sufficient.
        val checksNeeded=if(verifiedOCR!=null && scene.state!="ad")1 else 2
        if(gemmaActionFrames<checksNeeded) {
            message("AI $desired @ ${target.x.toInt()},${target.y.toInt()} • verifying")
            handler.postDelayed({if(autoPilot&&gemmaMode)gemmaInspect()},850)
            return
        }
        val signature="${scene.state}/$desired"
        if(gemmaLastDispatched==signature)gemmaRepeatTaps++
        else {gemmaLastDispatched=signature;gemmaRepeatTaps=1}
        if(gemmaRepeatTaps>3) {
            stopAutomation("AI paused: $desired tap did not change screen")
            return
        }
        gemmaActionFrames=0
        gemmaLastAction=null
        gemmaIdleCount=0
        message("AI tapping $desired: ${target.x.toInt()},${target.y.toInt()}")
        android.util.Log.i("WordscapesAuto",
            "Gemma $signature tapping (${target.x.toInt()},${target.y.toInt()}) " +
            "model confidence=${scene.confidence}, OCR=${verifiedOCR?.text}")
        tapAt(target.x,target.y) { good ->
            if(!autoPilot || !gemmaMode)return@tapAt
            if(!good) {
                stopAutomation("AI tap gesture was rejected by Android")
                return@tapAt
            }
            handler.postDelayed({
                if(autoPilot && gemmaMode)gemmaInspect()
            },if(scene.state=="ad")2100 else 1800)
        }
    }

    private fun gemmaWait(why:String) {
        if(!autoPilot || !gemmaMode)return
        gemmaLastAction=null
        gemmaActionFrames=0
        gemmaIdleCount++
        message("Gemma: $why")
        if(gemmaIdleCount >= 90) {
            stopAutomation("AI paused: screen stayed unresolved")
        } else {
            handler.postDelayed({if(autoPilot&&gemmaMode)gemmaInspect()},2600)
        }
    }

    private fun gemmaError(reason:String,oneShot:Boolean) {
        if(oneShot) {
            message("Gemma: ${reason.take(80)}")
            return
        }
        if(!autoPilot||!gemmaMode)return
        gemmaFailures++
        message("Gemma retry $gemmaFailures/4: ${reason.take(60)}")
        if(gemmaFailures>=4) {
            stopAutomation("AI paused: ${reason.take(65)}")
            if(pendingPoints.isNotEmpty()&&reviewPanel==null)showReview()
        } else {
            handler.postDelayed({if(autoPilot&&gemmaMode)gemmaInspect()},2300)
        }
    }

    private fun autoRetry(reason: String) {
        if (!autoPilot) return
        if (++autoRetries >= 5) {
            stopAutomation("AUTO paused: $reason")
            if (pendingPoints.isNotEmpty() && reviewPanel == null) showReview()
            return
        }
        message("AUTO retry $autoRetries/5: $reason")
        handler.postDelayed({ if (autoPilot) scan() }, 1800)
    }

    /**
     * This is a state-machine transition, not a blind tap timer. Only accept
     * visually recognized Level or Tap to Skip text in two matching frames.
     * Unknown screens, including advertisements, are NEVER tapped.
     */
    private fun inspectScene(atStart: Boolean = false) {
        if (!autoPilot) return
        val ticket = generation
        capture { bitmap ->
            if (!autoPilot || ticket != generation) {
                bitmap?.recycle()
                return@capture
            }
            if (bitmap == null) {
                handler.postDelayed({ if (autoPilot) inspectScene(atStart) }, 3000)
                return@capture
            }
            sceneReader.inspect(bitmap) { action ->
                bitmap.recycle()
                if (!autoPilot || ticket != generation) return@inspect
                if (action != null && lastUiAction?.kind == action.kind &&
                    hypot(lastUiAction!!.x-action.x,lastUiAction!!.y-action.y) < dp(65)) {
                    stableUiFrames++
                } else {
                    lastUiAction = action
                    stableUiFrames = if (action == null) 0 else 1
                }
                if (action != null && stableUiFrames >= 2) {
                    lastUiAction = null
                    stableUiFrames = 0
                    autoUiRetries = 0
                    message("AUTO: ${action.kind}…")
                    tapAt(action.x,action.y) { ok ->
                        if (!autoPilot) return@tapAt
                        if (!ok) {
                            stopAutomation("AUTO tap failed")
                            return@tapAt
                        }
                        if (action.kind == SceneReader.Kind.TAP_TO_SKIP) {
                            handler.postDelayed({ if (autoPilot) inspectScene(false) }, 1500)
                        } else {
                            autoRetries = 0
                            handler.postDelayed({ if (autoPilot) scan() }, 1500)
                        }
                    }
                } else if (action != null) {
                    handler.postDelayed({ if (autoPilot) inspectScene(atStart) }, 750)
                } else if (atStart) {
                    // AUTO was started with an active puzzle instead of the menu.
                    autoRetries = 0
                    scan()
                } else {
                    autoUiRetries++
                    message("AUTO: waiting for Level / Tap to Skip")
                    // Watch unknown screens safely, but stop after 15 minutes to
                    // avoid running without control after the game gets stuck.
                    if (autoUiRetries >= 300) {
                        stopAutomation("AUTO paused: menu not found")
                    } else {
                        handler.postDelayed({ if (autoPilot) inspectScene(false) }, 3000)
                    }
                }
            }
        }
    }

    private fun tapAt(x: Float, y: Float, done:(Boolean)->Unit) {
        val path = Path().apply { moveTo(x,y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path,0L,90L)).build()
        if (!dispatchGesture(gesture,object:GestureResultCallback() {
                override fun onCompleted(gestureDescription:GestureDescription) { done(true) }
                override fun onCancelled(gestureDescription:GestureDescription) { done(false) }
            },handler)) done(false)
    }

    private fun stop(why: String) {
        generation++
        isRunning = false
        message(why)
    }

    /**
     * NO FLASH CAPTURE on Android 14+: takeScreenshotOfWindow captures the
     * real foreground app even UNDER an accessibility overlay. Keep the
     * curtain visible throughout; do not briefly hide it for every frame.
     *
     * ScreenshotOfWindow returns a bitmap of the target window, which may
     * differ from display dimensions. Composite its pixels back into the
     * original full-screen coordinate space before giving it to Gemma,
     * because AI points must line up with touchscreen gesture coordinates.
     */
    private fun capture(callback:(Bitmap?)->Unit) {
        val dark=curtainOn && curtainMask?.isAttachedToWindow==true
        if(dark && Build.VERSION.SDK_INT<34) {
            stopAutomation("DARK requires Android 14+ window capture")
            callback(null)
            return
        }
        val target=if(dark) {
            // Access active application window, NOT our overlay's window.
            windows.filter {it.type==AccessibilityWindowInfo.TYPE_APPLICATION}
                .filter {it.root?.packageName?.toString()!=packageName}
                .sortedWith(compareByDescending<AccessibilityWindowInfo> {
                    it.isActive || it.isFocused
                }.thenByDescending {it.layer})
                .firstOrNull()
        } else null
        if(dark && target==null) {
            android.util.Log.w("WordscapesAuto",
                "No foreground app window for dark screenshot")
            stopAutomation("DARK paused: no capture window")
            callback(null)
            return
        }

        val bounds=Rect()
        target?.getBoundsInScreen(bounds)
        val callbackApi=object:TakeScreenshotCallback {
            override fun onSuccess(result:ScreenshotResult) {
                var copied:Bitmap?=null
                try {
                    val buffer:HardwareBuffer=result.hardwareBuffer
                    try {
                        val wrapped=Bitmap.wrapHardwareBuffer(buffer,result.colorSpace)
                        try {
                            copied=wrapped?.copy(Bitmap.Config.ARGB_8888,false)
                        }finally {
                            wrapped?.recycle()
                        }
                    }finally {
                        buffer.close()
                    }
                    if(dark && copied!=null) {
                        val screenshot=copied!!
                        val size=android.graphics.Point()
                        @Suppress("DEPRECATION")
                        wm.defaultDisplay.getRealSize(size)
                        val fullWidth=size.x.coerceAtLeast(1)
                        val fullHeight=size.y.coerceAtLeast(1)
                        if(bounds.width()<60||bounds.height()<60)
                            error("Window position unavailable")
                        // Some OEMs return the full display image from this
                        // API; do not scale it twice in that case.
                        if(screenshot.width!=fullWidth ||
                           screenshot.height!=fullHeight) {
                            val padded=Bitmap.createBitmap(
                                fullWidth,fullHeight,Bitmap.Config.ARGB_8888)
                            val canvas=Canvas(padded)
                            canvas.drawColor(android.graphics.Color.BLACK)
                            canvas.drawBitmap(screenshot,null,
                                RectF(bounds.left.toFloat(),bounds.top.toFloat(),
                                    bounds.right.toFloat(),bounds.bottom.toFloat()),
                                android.graphics.Paint(3))
                            copied=padded
                            screenshot.recycle()
                        }
                    }
                    callback(copied)
                } catch(e:Exception) {
                    copied?.recycle()
                    android.util.Log.e("WordscapesAuto",
                        "Window capture or mapping failed",e)
                    if(dark)stopAutomation("DARK paused: ${e.message?.take(35)}")
                    callback(null)
                }
            }
            override fun onFailure(errorCode:Int) {
                android.util.Log.w("WordscapesAuto",
                    "Screenshot failed, code=$errorCode, dark=$dark")
                if(dark) {
                    // NEVER flash the game to retry: pause and reveal only
                    // after we know window-only capture is unavailable.
                    stopAutomation("DARK cannot capture game (code $errorCode)")
                } else {
                    message("Screenshot error $errorCode — retry")
                }
                callback(null)
            }
        }
        try {
            if(dark) {
                takeScreenshotOfWindow(target!!.id,mainExecutor,callbackApi)
            } else {
                takeScreenshot(android.view.Display.DEFAULT_DISPLAY,
                    mainExecutor,callbackApi)
            }
        } catch(e:Exception) {
            android.util.Log.e("WordscapesAuto","Capture request threw",e)
            if(dark)stopAutomation("DARK cannot capture game")
            callback(null)
        }
    }

    private fun calibrate() {
        stopAutomation("Capturing calibration")
        removeReview()
        // No ad or navigation button interaction. User must have puzzle visible.
        handler.postDelayed({
            capture { bitmap ->
                if (bitmap == null) return@capture
                try {
                    FileOutputStream(File(cacheDir, "calibration_capture.png")).use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    val intent = Intent(this, CalibrationActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    message("Calibration open")
                } catch (e: Exception) { message("Capture failed: ${e.message}") }
                bitmap.recycle()
            }
        }, 220)
    }

    /** A new level always begins in AUTO mode. Manual corrections are for one puzzle only. */
    private fun scan(manualCount: Int? = null) {
        if(GemmaClient.enabled(this)) {
            if(autoPilot && gemmaMode) {
                message("AI handles scanning automatically")
                return
            }
            gemmaInspect(oneShot = true)
            return
        }
        stop("Scanning wheel…")
        val ticket = generation
        correctedCount = manualCount
        pending = ""
        glyphSamples = emptyList()
        uncertainSlots = emptyList()
        pendingPoints = emptyList()
        pendingCalibration = null
        pendingWheel = null
        removeReview()
        scanPreview?.recycle(); scanPreview = null
        scanPreviewOrigin = null
        if (!Settings.hasCalibration(this)) { message("Set center + search radius with CAL once"); return }
        handler.postDelayed({
            if (ticket != generation) return@postDelayed
            capture { bitmap ->
                if (ticket != generation) { bitmap?.recycle(); return@capture }
                if (bitmap == null) { autoRetry("Screenshot unavailable"); return@capture }
                val cal = Settings.load(this, bitmap.width, bitmap.height)
                if (cal == null) {
                    bitmap.recycle()
                    message("Search circle invalid — tap CAL")
                    if (autoPilot) stopAutomation("CAL invalid")
                    return@capture
                }
                message("Locating actual letters…")
                wheelVision.locate(bitmap, cal, manualCount) { reading ->
                    handler.post {
                        if (ticket != generation) {
                            bitmap.recycle()
                        } else if (reading == null) {
                            bitmap.recycle()
                            message("No reliable letter ring found — RESCAN / CAL")
                            autoRetry("ring not found")
                        } else {
                            val result = reading.wheel
                            pendingPoints = result.points
                            pendingCalibration = cal
                            pendingWheel = result
                            message(if(reading.recoveredSlots==result.inferredCount)
                                "Ring fallback: checking ${result.inferredCount} positions…"
                                else if(reading.recoveredSlots>0)
                                "Checking ${result.inferredCount-reading.recoveredSlots} visible + ${reading.recoveredSlots} estimated…"
                                else "Reading ${result.inferredCount} letters…")
                            ocr.recognize(this, bitmap, result.points, result.radius,
                                reading.hints) { letters ->
                                if (ticket == generation) {
                                    pending = letters.text
                                    glyphSamples = letters.samples
                                    uncertainSlots = letters.uncertainPositions
                                    val autoRun = getSharedPreferences("wordscapes.ocr", MODE_PRIVATE)
                                        .getBoolean("auto_run", true)
                                    // Don't auto-swipe unless the positions are
                                    // strongly grounded AND OCR inputs agree.
                                    if ((autoRun || autoPilot) && letters.allConfident && result.confidence >= .80f) {
                                        message("Verified $pending — solving…")
                                        startSolving(pending)
                                    } else {
                                        // Real screenshot with actual detected touch markers:
                                        // user can spot misplaced points without guessing.
                                        val x=(cal.center.x-cal.radius).toInt().coerceIn(0,bitmap.width-1)
                                        val y=(cal.center.y-cal.radius).toInt().coerceIn(0,bitmap.height-1)
                                        val w=(cal.radius*2).toInt().coerceAtMost(bitmap.width-x).coerceAtLeast(1)
                                        val h=(cal.radius*2).toInt().coerceAtMost(bitmap.height-y).coerceAtLeast(1)
                                        scanPreview=Bitmap.createBitmap(bitmap,x,y,w,h)
                                        scanPreviewOrigin=Spot(x.toFloat(),y.toFloat())
                                        message("${uncertainSlots.size} uncertain • recovered: ${reading.recoveredSlots} • OCR: ${reading.identified}/${result.inferredCount}")
                                        if (autoPilot) autoRetry("ambiguous OCR")
                                        else showReview()
                                    }
                                }
                                bitmap.recycle()
                            }
                        }
                    }
                }
            }
        }, 300)
    }

    private fun showReview() {
        removeReview()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(9))
            setBackgroundColor(android.graphics.Color.argb(250, 241, 247, 255))
        }
        panel.addView(TextView(this).apply {
            text = "${pendingPoints.size} letters • ${uncertainSlots.size} uncertain • correct if necessary:"
            textSize = 14f
        })
        val lettersField = EditText(this).apply {
            setSingleLine(true)
            setText(pending)
            textSize = 22f
            setSelectAllOnFocus(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        panel.addView(lettersField, LinearLayout.LayoutParams(-1, dp(54)))
        // Review the newly detected positions without visiting calibration.
        panel.addView(RingKeyView(pendingPoints, pendingWheel, pending),
            LinearLayout.LayoutParams(-1, dp(230)))
        panel.addView(TextView(this).apply {
            text = "Wrong NUMBER of letters? Use −/+ (rescans this level). Wrong LETTER? Edit above."
            textSize = 12f
        })
        val countRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun countButton(label: String, action: () -> Unit) {
            countRow.addView(Button(this).apply { text = label; setOnClickListener { action() } },
                LinearLayout.LayoutParams(0, dp(46), 1f))
        }
        countButton("− COUNT") { scan((pendingPoints.size - 1).coerceIn(WheelDetector.MIN_LETTERS, WheelDetector.MAX_LETTERS)) }
        countButton("AUTO") { scan() }
        countButton("+ COUNT") { scan((pendingPoints.size + 1).coerceIn(WheelDetector.MIN_LETTERS, WheelDetector.MAX_LETTERS)) }
        panel.addView(countRow)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply { text = "RUN"; setOnClickListener {
            val corrected = lettersField.text.toString().trim().uppercase(Locale.US)
            if (corrected.length != pendingPoints.size || corrected.any { it !in 'A'..'Z' }) {
                Toast.makeText(this@WordscapesService, "Enter exactly ${pendingPoints.size} letters A-Z", Toast.LENGTH_LONG).show()
            } else {
                // These letters are confirmed by the user: teach their actual shapes.
                GlyphMemory.learn(this@WordscapesService, glyphSamples, corrected)
                removeReview()
                startSolving(corrected)
            }
        } }, LinearLayout.LayoutParams(0, dp(52), 1f))
        row.addView(Button(this).apply { text = "RESCAN"; setOnClickListener { scan() } }, LinearLayout.LayoutParams(0, dp(52), 1f))
        row.addView(Button(this).apply { text = "CLOSE"; setOnClickListener { removeReview() } }, LinearLayout.LayoutParams(0, dp(52), 1f))
        panel.addView(row)
        reviewPanel = panel
        wm.addView(panel, overlayParams((resources.displayMetrics.widthPixels * .94f).toInt(), -2, true, dp(135)))
    }

    private fun removeReview() {
        val panel = reviewPanel ?: return
        try { wm.removeView(panel) } catch (_: Exception) {}
        reviewPanel = null
    }

    private fun startSolving(letters: String) {
        val cal=pendingCalibration ?:run {message("No scanned wheel");return}
        actualLetters=letters
        if(autoPilot) {autoRetries=0;autoUiRetries=0}
        activePoints=pendingPoints.toList()
        activeCalibration=cal
        activeWheel=pendingWheel

        // Gemma mode is completely AI-driven for WORD CHOICE. The
        // dictionary and Datamuse are for the optional legacy OCR mode.
        solveWords=if(gemmaMode) {
            aiCandidateWords.filter {WordEngine.isValid(it,letters)}
                .distinct().take(32)
        } else {
            WordEngine.solve(letters)
        }
        aiCandidateWords=emptyList()
        solveIndex=0
        if(solveWords.isEmpty()) {
            message("No words to try for $letters")
            if(autoPilot) stopAutomation("AI paused: no proposed words")
            return
        }
        isRunning=true
        generation++
        val ticket=generation
        message("${solveWords.size} ${if(gemmaMode)"AI" else "dictionary"} candidates")
        baselineImage?.recycle()
        baselineImage=null
        if(!gemmaMode) {
            // Optional network dictionary is legacy-only. It does NOT
            // inject long lists of obscure words into Gemma's rankings.
            Thread {
                val extras=DatamuseClient.supplementalWords(letters)
                handler.post {
                    if(ticket==generation && isRunning) {
                        solveWords=(solveWords+extras).distinct().take(170)
                    }
                }
            }.start()
        }
        capture { bitmap ->
            if(ticket!=generation){bitmap?.recycle();return@capture}
            if(bitmap==null){
                stop("Cannot capture puzzle baseline")
                if(autoPilot)stopAutomation("AUTO paused: screenshot failed")
                return@capture
            }
            baselineImage=bitmap
            handler.postDelayed({nextWord(ticket)},100)
        }
    }

    private fun nextWord(ticket: Int) {
        if (ticket != generation || !isRunning) return
        if (solveIndex >= solveWords.size) {
            stop("Words exhausted. Review OCR or letter positions.")
            if (autoPilot) {
                // Could already be on the completion screen. Check rather than
                // immediately restarting from the wrong state.
                handler.postDelayed({ if(autoPilot) if(gemmaMode) gemmaInspect() else inspectScene(false) },700)
            } else showReview()
            return
        }
        val word = solveWords[solveIndex++]
        val pathPoints = WordEngine.path(word, actualLetters, activePoints)
        if (pathPoints == null || pathPoints.size < 3) {
            handler.post { nextWord(ticket) }; return
        }
        message("${solveIndex}/${solveWords.size}: $word")
        swipe(pathPoints) { completed ->
            if (ticket != generation || !isRunning) return@swipe
            if (!completed) { stop("Gesture failed: check service"); return@swipe }
            handler.postDelayed({ verifyAfterWord(ticket) }, 720)
        }
    }

    private fun swipe(points: List<Spot>, callback: (Boolean) -> Unit) {
        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (p in points.drop(1)) lineTo(p.x, p.y)
        }
        val duration = (165 + 110 * (points.size - 1)).toLong().coerceAtMost(1150)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, duration)).build()
        if (!dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) { callback(true) }
            override fun onCancelled(gestureDescription: GestureDescription) { callback(false) }
        }, handler)) callback(false)
    }

    private fun verifyAfterWord(ticket: Int) {
        if (ticket != generation || !isRunning) return
        val cal = activeCalibration ?: return
        capture { bitmap ->
            if (ticket != generation) { bitmap?.recycle(); return@capture }
            if (bitmap == null) { handler.postDelayed({ nextWord(ticket) }, 650); return@capture }
            val maybeGone = wheelGone(bitmap, cal)
            bitmap.recycle()
            if (!maybeGone) handler.postDelayed({ nextWord(ticket) }, 180)
            else {
                // Confirm two screenshots to avoid treating one animation as a level finish.
                handler.postDelayed({
                    if (ticket != generation || !isRunning) return@postDelayed
                    capture { second ->
                        if (ticket != generation) { second?.recycle(); return@capture }
                        if (second == null) { handler.postDelayed({ nextWord(ticket) }, 500); return@capture }
                        val gone = wheelGone(second, cal)
                        second.recycle()
                        if (gone) {
                            stop("Level complete")
                            if (autoPilot)
                                handler.postDelayed({ if(autoPilot) if(gemmaMode) gemmaInspect() else inspectScene(false) }, 1400)
                            else message("Level complete — advance manually")
                        }
                        else handler.postDelayed({ nextWord(ticket) }, 250)
                    }
                }, 550)
            }
        }
    }

    private fun wheelGone(bitmap: Bitmap, cal: Calibration): Boolean {
        // Compare actual previously detected glyph patches rather than asking
        // the old brightness-peak detector to guess an imaginary ring.
        val original = baselineImage ?: return false
        val wheel = activeWheel ?: return false
        val changed = meanDiff(original,bitmap,wheel.center ?: cal.center,
            wheel.radius.takeIf { it>0f } ?: cal.radius*.70f)
        if (changed < 23f || activePoints.isEmpty()) return false
        var disappeared = 0
        val patch=max(5,(wheel.radius*.09f).toInt())
        for(p in activePoints) {
            var delta=0L;var pixels=0
            for(y in -patch..patch step 3)for(x in -patch..patch step 3) {
                val xx=(p.x+x).roundToInt();val yy=(p.y+y).roundToInt()
                if(xx !in 0 until bitmap.width || yy !in 0 until bitmap.height ||
                   xx !in 0 until original.width || yy !in 0 until original.height)continue
                val a=original.getPixel(xx,yy);val b=bitmap.getPixel(xx,yy)
                delta+=abs(android.graphics.Color.red(a)-android.graphics.Color.red(b))
                delta+=abs(android.graphics.Color.green(a)-android.graphics.Color.green(b))
                delta+=abs(android.graphics.Color.blue(a)-android.graphics.Color.blue(b))
                pixels+=3
            }
            if(pixels>0 && delta.toFloat()/pixels>24f)disappeared++
        }
        return disappeared >= ceil(activePoints.size*.70f).toInt()
    }

    private fun meanDiff(a: Bitmap, b: Bitmap, center: Spot, r: Float): Float {
        if (a.width != b.width || a.height != b.height) return 100f
        var total = 0L; var count = 0L
        val step = max(6, (r / 14).toInt())
        for (y in (center.y - r).toInt()..(center.y + r).toInt() step step)
            for (x in (center.x - r).toInt()..(center.x + r).toInt() step step) {
                if (x !in 0 until b.width || y !in 0 until b.height) continue
                if (hypot((x-center.x).toDouble(), (y-center.y).toDouble()) > r * 1.15f) continue
                val p = a.getPixel(x, y); val q = b.getPixel(x, y)
                total += abs(android.graphics.Color.red(p) - android.graphics.Color.red(q))
                total += abs(android.graphics.Color.green(p) - android.graphics.Color.green(q))
                total += abs(android.graphics.Color.blue(p) - android.graphics.Color.blue(q))
                count += 3
            }
        return if (count > 0) total.toFloat() / count else 0f
    }

    /** Miniature clockwise letter-position key: number and OCR guess per position. */
    private inner class RingKeyView(
        private val positions: List<Spot>,
        private val wheel: WheelResult?,
        private val letters: String
    ) : View(this@WordscapesService) {
        private val dot = android.graphics.Paint(3).apply { color = android.graphics.Color.rgb(24, 96, 147) }
        private val ink = android.graphics.Paint(3).apply {
            color = android.graphics.Color.WHITE
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = dp(12).toFloat()
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val image=scanPreview
            val origin=scanPreviewOrigin
            if(image!=null&&origin!=null&&!image.isRecycled) {
                val scale=min(width.toFloat()/image.width,height.toFloat()/image.height)
                val left=(width-image.width*scale)/2f
                val top=(height-image.height*scale)/2f
                canvas.drawBitmap(image,null,android.graphics.RectF(left,top,
                    left+image.width*scale,top+image.height*scale),android.graphics.Paint(3))
                positions.forEachIndexed { i, spot ->
                    val x=left+(spot.x-origin.x)*scale
                    val y=top+(spot.y-origin.y)*scale
                    canvas.drawCircle(x,y,dp(17).toFloat(),dot)
                    canvas.drawText("${i+1}:${letters.getOrNull(i) ?: '?'}",x,y+dp(4),ink)
                }
            } else {
                val geometry=wheel ?:return
                val center=geometry.center ?:return
                val scale=height*.35f/geometry.radius.coerceAtLeast(1f)
                positions.forEachIndexed { i,spot ->
                    val x=width*.5f+(spot.x-center.x)*scale
                    val y=height*.5f+(spot.y-center.y)*scale
                    canvas.drawCircle(x,y,dp(17).toFloat(),dot)
                    canvas.drawText("${i+1}:${letters.getOrNull(i) ?: '?'}",x,y+dp(4),ink)
                }
            }
        }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
}
