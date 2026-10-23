package com.pugplayz.wordscapesauto

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.widget.*
import android.text.InputType

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
            text = "AI GAME AUTO"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(16, 53, 92))
        })
        root.addView(TextView(this).apply {
            text = "Local Gemma-powered puzzle and match-3 agent"
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
        val gamePrefs=getSharedPreferences("wordscapes.game",MODE_PRIVATE)
        root.addView(TextView(this).apply {
            text="SELECT GAME"
            textSize=20f
            typeface=Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(16,53,92))
            setPadding(0,dp(17),0,dp(8))
        })
        val games=arrayOf(
            "Wordscapes — word wheel",
            "Royal Match — AI match-3"
        )
        val picker=Spinner(this)
        picker.adapter=ArrayAdapter(
            this,android.R.layout.simple_spinner_dropdown_item,games)
        picker.setSelection(if(gamePrefs.getString("mode","wordscapes")=="royal")1 else 0)
        picker.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent:android.widget.AdapterView<*>?,
                                        view:android.view.View?,position:Int,id:Long) {
                gamePrefs.edit().putString(
                    "mode",if(position==1)"royal" else "wordscapes").apply()
            }
            override fun onNothingSelected(parent:android.widget.AdapterView<*>?) {}
        }
        root.addView(picker,LinearLayout.LayoutParams(-1,dp(57)))
        root.addView(TextView(this).apply {
            text="Royal Match: open the game's Level screen and press AUTO. " +
                "Gemma decides one swipe/tap at a time, then examines the result. " +
                "It avoids coins, in-app purchases and paid boosters. " +
                "Wordscapes keeps the existing wheel solver."
            textSize=13f
            setTextColor(Color.rgb(79,93,115))
            setPadding(0,dp(4),0,dp(12))
        })
        // Ollama/Gemma is preferred, but disabling it restores the original
        // on-device scanner without removing the old CAL controls.
        val aiPrefs=getSharedPreferences(GemmaClient.PREF,MODE_PRIVATE)
        root.addView(TextView(this).apply {
            text="GEMMA 4 — LOCAL VISION CONTROLLER"
            textSize=20f
            typeface=Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(16,53,92))
            setPadding(0,dp(16),0,dp(9))
        })
        root.addView(CheckBox(this).apply {
            text="Use Gemma to read screens (dictionary solves Wordscapes)"
            textSize=16f
            isChecked=GemmaClient.enabled(this@MainActivity)
            setOnCheckedChangeListener { _, enabled ->
                aiPrefs.edit().putBoolean("enabled",enabled).apply()
            }
        })
        root.addView(TextView(this).apply {
            text="Ollama server URL (use ADB reverse for USB)"
            textSize=14f
            setTextColor(Color.DKGRAY)
        })
        val hostInput=EditText(this).apply {
            setSingleLine(true)
            textSize=16f
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(aiPrefs.getString("url",GemmaClient.DEFAULT_URL))
        }
        root.addView(hostInput,LinearLayout.LayoutParams(-1,dp(50)))
        root.addView(TextView(this).apply {
            text="Installed Ollama model"
            textSize=14f
            setTextColor(Color.DKGRAY)
        })
        val modelInput=EditText(this).apply {
            setSingleLine(true)
            textSize=16f
            setText(aiPrefs.getString("model",GemmaClient.DEFAULT_MODEL))
        }
        root.addView(modelInput,LinearLayout.LayoutParams(-1,dp(50)))
        val connectionStatus=TextView(this).apply {
            text="The phone sends screenshots to YOUR Ollama server."
            textSize=13f
            setTextColor(Color.rgb(79,93,115))
        }
        root.addView(Button(this).apply {
            text="SAVE SETTINGS + TEST OLLAMA"
            setOnClickListener {
                aiPrefs.edit()
                    .putString("url",hostInput.text.toString().trim())
                    .putString("model",modelInput.text.toString().trim())
                    .apply()
                connectionStatus.text="Testing connection…"
                GemmaClient(this@MainActivity).test { result ->
                    runOnUiThread { connectionStatus.text=result }
                }
            }
        },LinearLayout.LayoutParams(-1,dp(58)))
        root.addView(connectionStatus)
        root.addView(TextView(this).apply {
            text="USB: on the PC run adb reverse tcp:11434 tcp:11434, " +
                "then use http://127.0.0.1:11434 here. Keep Ollama and " +
                "the USB connection running. Use only a trusted local " +
                "server: screenshot content is sent to that address."
            textSize=13f
            setTextColor(Color.rgb(79,93,115))
            setPadding(0,dp(8),0,dp(16))
        })
        val curtainPrefs=getSharedPreferences("wordscapes.curtain",MODE_PRIVATE)
        root.addView(CheckBox(this).apply {
            text="Black screen curtain during AUTO (OLED-friendly)"
            textSize=16f
            isChecked=curtainPrefs.getBoolean("auto_dark",false)
            setOnCheckedChangeListener { _,enabled ->
                curtainPrefs.edit().putBoolean("auto_dark",enabled).apply()
                WordscapesService.instance?.setCurtainPreference(enabled)
            }
        },LinearLayout.LayoutParams(-1,dp(64)))
        root.addView(TextView(this).apply {
            text="The display stays logically ON for screenshots and touches. " +
                "The game stays covered in black while Android 14+ captures " +
                "the underlying Wordscapes window directly: no repeating " +
                "screenshot flashes. Tap WAKE at the top-right to reveal " +
                "the game without stopping AUTO. The physical screen remains on."
            textSize=13f
            setTextColor(Color.rgb(91,105,123))
            setPadding(0,0,0,dp(15))
        })
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
            text = "Only when letter positions and OCR agree, SCAN starts solving. " +
                "Uncertain results open the correction editor. Confirmed corrections " +
                "teach the app to recognize those letter shapes next time."
            textSize = 13f
            setTextColor(Color.rgb(91, 105, 123))
            setPadding(0, 0, 0, dp(12))
        })
        val instructions = TextView(this).apply {
            text = "FLOATING GAME CONTROLS:\n\n" +
                "WORDSCAPES AUTO — Gemma reads wheel letters and finds menus. " +
                "The offline dictionary supplies frequent real words " +
                "and Android swipes them. No AI word guessing.\\n\\n" +
                "ROYAL MATCH — AUTO clicks Level/Play, observes the grid, " +
                "chooses one match-3 swipe, checks what changed, and repeats.\\n\\n" +
                "SCAN — One Wordscapes wheel scan and review.\\n\\n" +
                "DARK — Cover the display in black while AUTO keeps running.\n\n" +
                "CAL — Optional wheel crop area; mainly for classic OCR.\n\n" +
                "STOP — Immediately cancel the current run.\n\n" +
                "HIDE — Stop and remove controls.\n\n" +
                "To switch back to the original on-device OCR, uncheck Gemma. " +
                "Ads are only closed when a visible actionable close/skip button " +
                "is confirmed. Uncertain screens cause AUTO to pause."
            textSize=15f
            setTextColor(Color.rgb(29,47,68))
            setLineSpacing(dp(4).toFloat(),1f)
        }
        root.addView(instructions, LinearLayout.LayoutParams(-1, -2))
        root.addView(TextView(this).apply {
            text = "Android 11+ • Local Gemma via Ollama • 2 game modes"
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
