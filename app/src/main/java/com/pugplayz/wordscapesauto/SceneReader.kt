package com.pugplayz.wordscapesauto

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale

/**
 * Recognizes user-described Wordscapes interstitial screens. Only returns
 * precise, explicitly recognized actions, never generic guesses/ads.
 */
class SceneReader {
    enum class Kind { TAP_TO_SKIP, LEVEL_BUTTON }
    data class Action(val kind: Kind, val x: Float, val y: Float, val text: String)

    private val recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun inspect(bitmap: Bitmap, callback: (Action?)->Unit) {
        recognizer.process(InputImage.fromBitmap(bitmap,0))
            .addOnCompleteListener { task ->
                if(!task.isSuccessful){callback(null);return@addOnCompleteListener}
                val lines=task.result.textBlocks.flatMap { it.lines }
                val candidates=mutableListOf<Action>()
                for(line in lines) {
                    val raw=line.text.trim()
                    val name=raw.lowercase(Locale.US).replace(Regex("\\s+")," ")
                    val box=line.boundingBox ?:continue
                    if(box.centerY()<bitmap.height*.12f)continue // overlay and status
                    // Wordscapes post-puzzle celebration; safe to tap the visible label.
                    if(name.contains("tap to skip") || name.contains("tap to continue") ||
                       name.contains("tap anywhere to continue")) {
                        candidates.add(Action(Kind.TAP_TO_SKIP,box.exactCenterX(),
                            box.exactCenterY(),raw))
                        continue
                    }
                    // Stronger than contains("level"): avoid tapping a puzzle title,
                    // ad copy, or "level complete" by accident.
                    if(Regex("^level\\s*[0-9]{1,7}$").matches(name) ||
                       name=="level") {
                        if(box.centerY()>bitmap.height*.26f &&
                            box.centerY()<bitmap.height*.91f &&
                            box.centerX() in (bitmap.width*.15f)..(bitmap.width*.85f))
                            candidates.add(Action(Kind.LEVEL_BUTTON,box.exactCenterX(),
                                box.exactCenterY(),raw))
                    }
                }
                callback(candidates.firstOrNull { it.kind==Kind.TAP_TO_SKIP }
                    ?: candidates.firstOrNull { it.kind==Kind.LEVEL_BUTTON })
            }
    }

    fun close()=recognizer.close()
}
