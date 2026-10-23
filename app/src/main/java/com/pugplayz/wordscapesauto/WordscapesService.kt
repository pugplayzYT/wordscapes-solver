package com.pugplayz.wordscapesauto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.hardware.HardwareBuffer
import android.os.Handler
import android.os.Looper
import android.view.*
import android.view.accessibility.AccessibilityEvent
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
    private var autoPilot = false
    private var autoRetries = 0
    private var autoUiRetries = 0
    private var lastUiAction: SceneReader.Action? = null
    private var stableUiFrames = 0
    private lateinit var topBar: LinearLayout
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
    override fun onInterrupt() { stop("Accessibility interrupted") }
    override fun onDestroy() {
        stop("Service disabled")
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

    private fun buildBar() {
        if (::topBar.isInitialized && topBar.isAttachedToWindow) return
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(5), dp(7), dp(5))
            setBackgroundColor(android.graphics.Color.argb(238, 17, 40, 71))
        }
        fun button(label: String, click: () -> Unit) = Button(this).apply {
            text = label; textSize = 10f; minWidth = 0
            setPadding(dp(2), 0, dp(2), 0)
            setOnClickListener { click() }
        }
        topBar.addView(button("CAL") { calibrate() })
        topBar.addView(button("AUTO") { if (autoPilot) stopAutomation("AUTO stopped") else startAutomation() })
        topBar.addView(button("SCAN") { scan() })
        topBar.addView(button("STOP") { stopAutomation("Stopped by user") })
        topBar.addView(button("HIDE") { hideControls() })
        status = TextView(this).apply {
            text = "Ready"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 11f
            maxLines = 2
            setPadding(dp(6), 0, 0, 0)
        }
        topBar.addView(status, LinearLayout.LayoutParams(dp(74), -2))
        wm.addView(topBar, overlayParams(-2, dp(54), false, dp(30)))
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
        if (::status.isInitialized) status.text = text
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

    private fun stopAutomation(reason: String) {
        autoPilot = false
        autoRetries = 0
        autoUiRetries = 0
        lastUiAction = null
        stableUiFrames = 0
        keepAwake(false)
        stop(reason)
    }

    private fun startAutomation() {
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
        inspectScene(true)
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

    private fun capture(callback: (Bitmap?) -> Unit) {
        // Android 11+: works without MediaProjection and without ADB.
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer: HardwareBuffer = result.hardwareBuffer
                    val wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    val copy = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                    wrapped?.recycle()
                    buffer.close()
                    callback(copy)
                }
                override fun onFailure(errorCode: Int) {
                    message("Screenshot error $errorCode — retry")
                    callback(null)
                }
            })
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
                            message("Reading ${result.inferredCount} real positions…")
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
                                        message("${uncertainSlots.size} uncertain • OCR anchors: ${reading.identified}/${result.inferredCount}")
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
        wm.addView(panel, overlayParams((resources.displayMetrics.widthPixels * .94f).toInt(), -2, true, dp(95)))
    }

    private fun removeReview() {
        val panel = reviewPanel ?: return
        try { wm.removeView(panel) } catch (_: Exception) {}
        reviewPanel = null
    }

    private fun startSolving(letters: String) {
        val cal = pendingCalibration ?: run { message("No scanned wheel"); return }
        actualLetters = letters
        if (autoPilot) { autoRetries = 0; autoUiRetries = 0 }
        activePoints = pendingPoints.toList()
        activeCalibration = cal
        activeWheel = pendingWheel
        solveWords = WordEngine.solve(letters)
        solveIndex = 0
        isRunning = true
        generation++
        val ticket = generation
        message("${solveWords.size} candidates")
        baselineImage?.recycle(); baselineImage = null
        // Datamuse is best-effort; it never delays an offline solution.
        Thread {
            val extras = DatamuseClient.supplementalWords(letters)
            handler.post {
                if (ticket == generation && isRunning) {
                    solveWords = (solveWords + extras).distinct().take(170)
                }
            }
        }.start()
        capture { bitmap ->
            if (ticket != generation) { bitmap?.recycle(); return@capture }
            baselineImage = bitmap
            handler.postDelayed({ nextWord(ticket) }, 100)
        }
    }

    private fun nextWord(ticket: Int) {
        if (ticket != generation || !isRunning) return
        if (solveIndex >= solveWords.size) {
            stop("Words exhausted. Review OCR or letter positions.")
            if (autoPilot) {
                // Could already be on the completion screen. Check rather than
                // immediately restarting from the wrong state.
                handler.postDelayed({ if(autoPilot) inspectScene(false) },700)
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
                                handler.postDelayed({ if(autoPilot) inspectScene(false) }, 1400)
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
