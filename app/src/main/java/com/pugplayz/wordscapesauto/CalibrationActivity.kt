package com.pugplayz.wordscapesauto

import android.app.Activity
import android.graphics.*
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.*
import java.io.File
import kotlin.math.*

/** Select TWO opposite corners around the entire letter wheel, one time. */
class CalibrationActivity : Activity() {
    private lateinit var bitmap: Bitmap
    private lateinit var preview: BoxPreview
    private lateinit var info: TextView
    private var first: Spot? = null
    private var bounds: RectF? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val loaded = BitmapFactory.decodeFile(File(cacheDir, "calibration_capture.png").absolutePath)
        if (loaded == null) {
            Toast.makeText(this, "No screenshot. Go back to Wordscapes and press CAL.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        bitmap = loaded
        bounds = Settings.load(this, bitmap.width, bitmap.height)?.bounds

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
            setBackgroundColor(Color.rgb(246, 249, 253))
        }
        root.addView(TextView(this).apply {
            text = "Set wheel search box"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(21, 42, 70))
        })
        info = TextView(this).apply { textSize = 15f; setTextColor(Color.DKGRAY) }
        root.addView(info)
        root.addView(TextView(this).apply {
            text = "Tap the TOP-LEFT and BOTTOM-RIGHT corners around the entire letter wheel. " +
                "Include all the places letters can appear, with a small margin. " +
                "Future levels can have different wheel sizes and letter counts."
            textSize = 14f
        })
        preview = BoxPreview().apply { onTap = { point -> applyTap(point) } }
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "RESET BOX"
            setOnClickListener { bounds = null; first = null; refresh() }
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        row.addView(Button(this).apply {
            text = "USE PREVIOUS"
            setOnClickListener {
                val saved = Settings.load(this@CalibrationActivity, bitmap.width, bitmap.height)
                bounds = saved?.bounds
                first = null
                refresh()
            }
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(row)
        root.addView(Button(this).apply {
            text = "SAVE BOX & RETURN TO GAME"
            setOnClickListener {
                val rect = bounds
                if (rect == null || rect.width() < 90 || rect.height() < 90) {
                    Toast.makeText(this@CalibrationActivity,
                        "Tap two corners around the entire wheel first.", Toast.LENGTH_SHORT).show()
                } else {
                    Settings.save(this@CalibrationActivity,
                        Calibration(RectF(rect)), bitmap.width, bitmap.height)
                    Toast.makeText(this@CalibrationActivity,
                        "Box saved! Future SCANs find fresh positions and count.", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }, LinearLayout.LayoutParams(-1, dp(58)))
        setContentView(root)
        refresh()
    }

    private fun applyTap(point: Spot) {
        if (first == null) {
            first = point
            bounds = null
        } else {
            val p = first!!
            val rect = RectF(min(p.x, point.x), min(p.y, point.y),
                max(p.x, point.x), max(p.y, point.y))
            bounds = rect.takeIf { it.width() >= 90f && it.height() >= 90f }
            first = null
        }
        refresh()
    }

    private fun refresh() {
        val rect = bounds
        info.text = when {
            first != null -> "Now tap the OPPOSITE corner of the wheel."
            rect == null -> "Tap first corner to begin. No radius or letter count needed."
            else -> "Selected ${rect.width().toInt()} × ${rect.height().toInt()} px. " +
                "Check the gold box encloses the letters, then SAVE."
        }
        preview.invalidate()
    }

    private inner class BoxPreview : View(this@CalibrationActivity) {
        var onTap: (Spot) -> Unit = {}
        private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val border = Paint(3).apply {
            color = Color.rgb(255, 184, 0)
            style = Paint.Style.STROKE
            strokeWidth = dp(3).toFloat()
        }
        private val marker = Paint(3).apply { color = Color.rgb(220, 48, 44) }
        private var scale = 1f
        private var left = 0f
        private var top = 0f

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            scale = min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
            left = (width - bitmap.width * scale) / 2f
            top = (height - bitmap.height * scale) / 2f
            canvas.drawBitmap(bitmap, null,
                RectF(left, top, left + bitmap.width * scale, top + bitmap.height * scale),
                imagePaint)
            bounds?.let {
                canvas.drawRect(left + it.left*scale, top + it.top*scale,
                    left + it.right*scale, top + it.bottom*scale, border)
            }
            first?.let { canvas.drawCircle(left+it.x*scale,top+it.y*scale,dp(6).toFloat(),marker) }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action != MotionEvent.ACTION_UP) return true
            val x = ((event.x-left)/scale).coerceIn(0f, bitmap.width.toFloat())
            val y = ((event.y-top)/scale).coerceIn(0f, bitmap.height.toFloat())
            onTap(Spot(x,y))
            return true
        }
    }

    override fun onDestroy() {
        if (::bitmap.isInitialized && !bitmap.isRecycled) bitmap.recycle()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v*resources.displayMetrics.density+.5f).toInt()
}
