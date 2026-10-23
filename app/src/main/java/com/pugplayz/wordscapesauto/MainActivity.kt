package com.pugplayz.wordscapesauto

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {
    private lateinit var state: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(60), dp(24), dp(20))
            setBackgroundColor(Color.rgb(246, 249, 253))
        }
        root.addView(TextView(this).apply {
            text = "WORDSCAPES AUTO"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(16, 53, 92))
        })
        root.addView(TextView(this).apply {
            text = "Accessibility-powered puzzle solver"
            textSize = 15f
            setTextColor(Color.rgb(91, 105, 123))
        })
        state = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, dp(22))
        }
        root.addView(state)
        root.addView(Button(this).apply {
            text = "1 · ENABLE ACCESSIBILITY SERVICE"
            setOnClickListener { startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }, LinearLayout.LayoutParams(-1, dp(62)))
        val instructions = TextView(this).apply {
            text = "2 · Open Wordscapes, then use the floating buttons:\n\n" +
                "CAL  — Capture puzzle and calibrate with center + radius slider\n\n" +
                "SCAN — Recognize letters and review them\n\n" +
                "RUN  — Swipe possible words until the wheel disappears\n\n" +
                "STOP — Cancel immediately\n\n" +
                "After completing a level, advance yourself and press SCAN again. " +
                "Ads are NOT touched. Screenshots stay on the phone."
            textSize = 16f
            setTextColor(Color.rgb(29, 47, 68))
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        root.addView(instructions, LinearLayout.LayoutParams(-1, -2))
        root.addView(TextView(this).apply {
            text = "Android 11+ • No PC or ADB • Offline dictionary • On-device OCR"
            textSize = 12f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, 0)
        })
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }
    override fun onResume() {
        super.onResume()
        state.text = if (WordscapesService.instance != null) "● Service ON — return to game to use CAL / SCAN"
        else "○ Service OFF — enable it in Android Accessibility settings"
        state.setTextColor(if (WordscapesService.instance != null) Color.rgb(10, 130, 91) else Color.rgb(160, 88, 21))
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
}
