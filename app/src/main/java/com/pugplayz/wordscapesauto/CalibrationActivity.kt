package com.pugplayz.wordscapesauto

import android.app.Activity
import android.graphics.*
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import java.io.File
import kotlin.math.*

class CalibrationActivity : Activity() {
    private lateinit var bitmap: Bitmap
    private lateinit var preview: RingPreview
    private lateinit var info: TextView
    private lateinit var seek: SeekBar
    private var center: Spot? = null
    private var radius = 180f
    private var result = WheelResult(emptyList(), 0f, 0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = File(cacheDir, "calibration_capture.png")
        val loaded = BitmapFactory.decodeFile(file.absolutePath)
        if (loaded == null) {
            Toast.makeText(this, "No screenshot. Return to the game and press CAL.", Toast.LENGTH_LONG).show()
            finish(); return
        }
        bitmap = loaded
        val previous = Settings.load(this, bitmap.width, bitmap.height)
        center = previous?.center
        radius = previous?.radius ?: bitmap.width * .19f

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
            setBackgroundColor(Color.rgb(246, 249, 253))
        }
        val title = TextView(this).apply {
            text = "Calibrate letter wheel"
            textSize = 24f
            setTextColor(Color.rgb(21, 42, 70))
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(title)
        info = TextView(this).apply { textSize = 14f; setTextColor(Color.DKGRAY) }
        root.addView(info)
        preview = RingPreview().apply { onCenter = { point -> center = point; refresh() } }
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        val radiusLabel = TextView(this).apply { text = "Radius (through letter centers)"; textSize = 15f }
        root.addView(radiusLabel)
        seek = SeekBar(this).apply {
            max = (min(bitmap.width, bitmap.height) * .48f).toInt() - 35
            progress = (radius - 35).toInt().coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar, value: Int, fromUser: Boolean) { radius = (35 + value).toFloat(); refresh() }
                override fun onStartTrackingTouch(b: SeekBar) {}
                override fun onStopTrackingTouch(b: SeekBar) {}
            })
        }
        root.addView(seek)
        val save = Button(this).apply {
            text = "SAVE & RETURN TO GAME"
            setOnClickListener {
                val c = center
                if (c == null) Toast.makeText(this@CalibrationActivity, "Tap wheel center first", Toast.LENGTH_SHORT).show()
                else {
                    Settings.save(this@CalibrationActivity, Calibration(c, radius), bitmap.width, bitmap.height)
                    Toast.makeText(this@CalibrationActivity, "Saved. Press SCAN in the game.", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
        root.addView(save, LinearLayout.LayoutParams(-1, dp(58)))
        setContentView(root)
        refresh()
    }

    private fun refresh() {
        val c = center
        result = if (c == null) WheelResult(emptyList(), 0f, 0) else WheelDetector.detect(bitmap, c, radius)
        info.text = if (c == null) "ONE-TIME SETUP: tap wheel center, set radius through letters, then Save."
        else "Saved geometry for ALL levels • radius ${radius.toInt()}px • preview: ${result.inferredCount} letters. Every SCAN detects a new count; correct it in SCAN review if needed."
        preview.invalidate()
    }

    private inner class RingPreview : View(this@CalibrationActivity) {
        var onCenter: (Spot) -> Unit = {}
        private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val stroke = Paint(3).apply { color = Color.rgb(255, 184, 0); style = Paint.Style.STROKE; strokeWidth = 4f }
        private val pointPaint = Paint(3).apply { color = Color.rgb(12, 171, 115); style = Paint.Style.FILL }
        private val textPaint = Paint(3).apply { color = Color.WHITE; textSize = 30f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
        private var scale = 1f
        private var left = 0f
        private var top = 0f
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            scale = min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
            left = (width - bitmap.width * scale) / 2f
            top = (height - bitmap.height * scale) / 2f
            canvas.drawBitmap(bitmap, null, RectF(left, top, left + bitmap.width * scale, top + bitmap.height * scale), imagePaint)
            val c = center ?: return
            canvas.drawCircle(left + c.x * scale, top + c.y * scale, radius * scale, stroke)
            result.points.forEachIndexed { index, p ->
                val x = left + p.x * scale; val y = top + p.y * scale
                canvas.drawCircle(x, y, 18f, pointPaint)
                canvas.drawText("${index + 1}", x, y + 10f, textPaint)
            }
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action != MotionEvent.ACTION_UP) return true
            val x = ((event.x - left) / scale).coerceIn(0f, bitmap.width.toFloat())
            val y = ((event.y - top) / scale).coerceIn(0f, bitmap.height.toFloat())
            onCenter(Spot(x, y))
            return true
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + .5f).toInt()
}
