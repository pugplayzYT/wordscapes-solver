package com.pugplayz.wordscapesauto

import android.app.Activity
import android.graphics.*
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.*
import java.io.File
import kotlin.math.*

/** Tap the wheel CENTER once and use a slider to enclose the whole wheel. */
class CalibrationActivity : Activity() {
    private lateinit var bitmap: Bitmap
    private lateinit var preview: CirclePreview
    private lateinit var info: TextView
    private lateinit var radiusBar: SeekBar
    private var center: Spot? = null
    private var radius = 180f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val loaded = BitmapFactory.decodeFile(File(cacheDir,"calibration_capture.png").absolutePath)
        if (loaded == null) {
            Toast.makeText(this,"No screenshot — open a Wordscapes level and tap CAL.",Toast.LENGTH_LONG).show()
            finish();return
        }
        bitmap=loaded
        val previous=Settings.load(this, bitmap.width,bitmap.height)
        center=previous?.center
        radius=previous?.radius ?: min(bitmap.width,bitmap.height)*.23f

        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(14),dp(12),dp(14),dp(10))
            setBackgroundColor(Color.rgb(246,249,253))
        }
        root.addView(TextView(this).apply {
            text="Calibrate circular search area"
            textSize=23f
            typeface=Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(21,42,70))
        })
        info=TextView(this).apply { textSize=14f;setTextColor(Color.DKGRAY) }
        root.addView(info)
        root.addView(TextView(this).apply {
            text="Tap the exact CENTER of the wheel. Then move the slider until the gold CIRCLE fully surrounds every letter, with a small margin. You do NOT need to align the circle to the letters."
            textSize=14f
        })
        preview=CirclePreview().apply {
            onTap={ point -> center=point;refresh() }
        }
        root.addView(preview,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(TextView(this).apply {
            text="Outer search radius"
            textSize=15f
        })
        radiusBar=SeekBar(this).apply {
            max=(min(bitmap.width,bitmap.height)*.49f).toInt()-45
            progress=(radius-45f).roundToInt().coerceIn(0,max)
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar:SeekBar,position:Int,fromUser:Boolean) {
                    if(fromUser) { radius=45f+position;refresh() }
                }
                override fun onStartTrackingTouch(bar:SeekBar) {}
                override fun onStopTrackingTouch(bar:SeekBar) {}
            })
        }
        root.addView(radiusBar)
        root.addView(Button(this).apply {
            text="SAVE SEARCH CIRCLE"
            setOnClickListener {
                val point=center
                if(point==null) {
                    Toast.makeText(this@CalibrationActivity,"Tap the wheel center first.",Toast.LENGTH_SHORT).show()
                } else {
                    Settings.save(this@CalibrationActivity,Calibration(point,radius),bitmap.width,bitmap.height)
                    Toast.makeText(this@CalibrationActivity,"Saved. SCAN now locates individual letters automatically.",Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        },LinearLayout.LayoutParams(-1,dp(58)))
        setContentView(root)
        refresh()
    }

    private fun refresh() {
        info.text=if(center==null) "1. Tap center   2. Enclose the entire wheel with the slider   3. Save"
        else "Search radius: ${radius.roundToInt()} px. This is an OUTER boundary, not a fixed letter ring."
        preview.invalidate()
    }

    private inner class CirclePreview:View(this@CalibrationActivity) {
        var onTap:(Spot)->Unit={}
        private val imagePaint=Paint(Paint.FILTER_BITMAP_FLAG)
        private val ring=Paint(3).apply {
            color=Color.rgb(255,184,0)
            style=Paint.Style.STROKE
            strokeWidth=dp(3).toFloat()
        }
        private val dot=Paint(3).apply {color=Color.rgb(220,48,44)}
        private var scale=1f
        private var left=0f
        private var top=0f
        override fun onDraw(canvas:Canvas) {
            super.onDraw(canvas)
            scale=min(width.toFloat()/bitmap.width,height.toFloat()/bitmap.height)
            left=(width-bitmap.width*scale)/2f
            top=(height-bitmap.height*scale)/2f
            canvas.drawBitmap(bitmap,null,RectF(left,top,left+bitmap.width*scale,
                top+bitmap.height*scale),imagePaint)
            center?.let {
                val x=left+it.x*scale
                val y=top+it.y*scale
                canvas.drawCircle(x,y,radius*scale,ring)
                canvas.drawCircle(x,y,dp(6).toFloat(),dot)
            }
        }
        override fun onTouchEvent(event:MotionEvent):Boolean {
            if(event.action != MotionEvent.ACTION_UP) return true
            val x=((event.x-left)/scale).coerceIn(0f,bitmap.width.toFloat())
            val y=((event.y-top)/scale).coerceIn(0f,bitmap.height.toFloat())
            onTap(Spot(x,y))
            return true
        }
    }

    override fun onDestroy() {
        if(::bitmap.isInitialized&&!bitmap.isRecycled) bitmap.recycle()
        super.onDestroy()
    }
    private fun dp(n:Int)=(n*resources.displayMetrics.density+.5f).toInt()
}
