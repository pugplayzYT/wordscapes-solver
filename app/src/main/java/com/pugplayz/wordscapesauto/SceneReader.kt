package com.pugplayz.wordscapesauto

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale

/**
 * Grounds Gemma's *classification* in actual on-screen text coordinates.
 * OCR can recognize a target box more accurately than a vision LLM can
 * estimate a tap pixel. Nothing is tapped by this class.
 */
class SceneReader {
    enum class Kind { TAP_TO_SKIP, LEVEL_BUTTON, AD_CLOSE }
    data class Action(val kind: Kind, val x: Float, val y: Float, val text: String)

    private val recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun inspect(bitmap: Bitmap, expectedKind: Kind? = null, callback: (Action?)->Unit) {
        recognizer.process(InputImage.fromBitmap(bitmap,0))
            .addOnCompleteListener { task ->
                if(!task.isSuccessful){callback(null);return@addOnCompleteListener}
                val lines=task.result.textBlocks.flatMap {it.lines}
                val candidates=mutableListOf<Action>()
                fun add(raw:String, box:Rect?) {
                    val b=box ?:return
                    val normalized=raw.trim().lowercase(Locale.US)
                        .replace(Regex("\\s+")," ")
                    val x=b.exactCenterX()
                    val y=b.exactCenterY()
                    val w=bitmap.width.toFloat()
                    val h=bitmap.height.toFloat()
                    if(y<h*.10f)return // ignore accessibility floating toolbar
                    val centered=x in w*.14f..w*.87f
                    if((normalized.contains("tap to skip") ||
                        normalized.contains("tap to continue") ||
                        normalized.contains("tap anywhere to continue") ||
                        normalized=="continue" || normalized=="next") &&
                        centered && y>h*.20f) {
                        candidates.add(Action(Kind.TAP_TO_SKIP,x,y,raw))
                    }
                    if(Regex("^level\\s*#?\\s*[0-9]{0,7}$").matches(normalized) &&
                        centered && y in h*.24f..h*.94f) {
                        candidates.add(Action(Kind.LEVEL_BUTTON,x,y,raw))
                    }
                    // Only considered after Gemma explicitly classifies
                    // the screenshot as an advertisement. A bare X must
                    // be in the upper-right portion of the screen.
                    val shortX=normalized=="x" || normalized=="×" ||
                        normalized=="✕" || normalized=="✖"
                    val adSkip=normalized=="skip ad" ||
                        normalized=="skip ads" || normalized=="close ad" ||
                        normalized=="skip" || normalized=="close"
                    if((shortX && x>w*.70f && y<h*.34f) ||
                        (adSkip && x>w*.47f && y<h*.92f)) {
                        candidates.add(Action(Kind.AD_CLOSE,x,y,raw))
                    }
                }
                for(line in lines) {
                    add(line.text,line.boundingBox)
                    for(element in line.elements) add(element.text,element.boundingBox)
                }
                // Prefer explicitly labeled buttons over generic X/Skip.
                callback(if(expectedKind != null) {
                    candidates.firstOrNull {it.kind==expectedKind}
                } else {
                    candidates.firstOrNull {it.kind==Kind.LEVEL_BUTTON}
                        ?: candidates.firstOrNull {it.kind==Kind.TAP_TO_SKIP}
                        ?: candidates.firstOrNull {it.kind==Kind.AD_CLOSE}
                })
            }
    }
    fun close()=recognizer.close()
}
