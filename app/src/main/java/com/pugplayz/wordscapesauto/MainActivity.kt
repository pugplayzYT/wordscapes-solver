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
    private lateinit var showControlsButton: Button
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
        showControlsButton = Button(this).apply {
            text = "SHOW FLOATING CONTROLS"
            setOnClickListener {
                val service = WordscapesService.instance
                if (service == null) {
                    Toast.makeText(this@MainActivity, "Enable Accessibility Service first.", Toast.LENGTH_LONG).show()
                } else if (service.showControls()) {
                    Toast.makeText(this@MainActivity, "Controls restored. Return to Wordscapes.", Toast.LENGTH_SHORT).show()
                    refreshStatus()
                }
            }
        }
        root.addView(showControlsButton, LinearLayout.LayoutParams(-1, dp(62)))
        root.addView(CheckBox(this).apply {
            text = "Auto-run after confident SCAN"
            textSize = 16f
            isChecked = getSharedPreferences("wordscapes.ocr", MODE_PRIVATE)
                .getBoolean("auto_run", true)
            setOnCheckedChangeListener { _, checked ->
                getSharedPreferences("wordscapes.ocr", MODE_PRIVATE)
                    .edit().putBoolean("auto_run", checked).apply()
            }
        }, LinearLayout.LayoutParams(-1, dp(54)))
        root.addView(TextView(this).apply {
            text = "When letters agree across OCR passes, SCAN starts solving. " +
                "Uncertain results open the correction editor. Confirmed corrections " +
                "teach the app to recognize those letter shapes next time."
            textSize = 13f
            setTextColor(Color.rgb(91, 105, 123))
            setPadding(0, 0, 0, dp(12))
        })
        val instructions = TextView(this).apply {
            text = "2 · Tap SHOW CONTROLS if hidden, then open Wordscapes:\n\n" +
                "CAL  — One-time center + radius setup\n\n" +
                "SCAN — Read letters; auto-run if confident, otherwise review\n\n" +
                "RUN  — Swipe possible words until the wheel disappears\n\n" +
                "STOP — Cancel immediately\n\n" +
                "HIDE — Stop and remove all floating controls\n\n" +
                "Reopen this app and tap SHOW FLOATING CONTROLS to restore.\n\n" +
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
        refreshStatus()
    }

    private fun refreshStatus() {
        val service = WordscapesService.instance
        val visible = service?.areControlsVisible() == true
        state.text = when {
            service == null -> "○ Service OFF — enable in Android Accessibility settings"
            visible -> "● Service ON — floating controls visible"
            else -> "● Service ON — controls hidden (tap SHOW to restore)"
        }
        state.setTextColor(if (service != null) Color.rgb(10, 130, 91) else Color.rgb(160, 88, 21))
        showControlsButton.isEnabled = service != null && !visible
        showControlsButton.text = if (visible) "FLOATING CONTROLS ALREADY VISIBLE" else "SHOW FLOATING CONTROLS"
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
}
