package com.pugplayz.wordscapesauto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.util.Locale
import kotlin.math.*

/**
 * Independent, pretrained Tesseract 5 LSTM letter recognizer.
 *
 * The bundled official English model is copied into filesDir once.
 * OCR is synchronous here; call from a worker thread only.
 * Single API instance is serialized with synchronized.
 */
class SingleCharacterModel(private val context: Context) {
    data class Answer(val letter: Char, val confidence: Int)

    private var api: TessBaseAPI? = null
    private var failed = false

    @Synchronized
    private fun ensure(): TessBaseAPI? {
        if (failed) return null
        api?.let { return it }
        try {
            val dir = File(context.filesDir,"tesseract")
            val data = File(dir,"tessdata")
            if (!data.exists()) data.mkdirs()
            val dest = File(data,"eng.traineddata")
            if (!dest.exists() || dest.length() < 1000000L) {
                context.assets.open("tessdata/eng.traineddata").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }
            val tess = TessBaseAPI()
            if (!tess.init(dir.absolutePath,"eng")) {
                tess.recycle()
                failed = true
                return null
            }
            tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_CHAR)
            tess.setVariable(TessBaseAPI.VAR_CHAR_WHITELIST, "ABCDEFGHIJKLMNOPQRSTUVWXYZ")
            tess.setVariable("load_system_dawg", "0")
            tess.setVariable("load_freq_dawg", "0")
            api = tess
            return tess
        } catch (_: Exception) {
            failed = true
            return null
        }
    }

    @Synchronized
    fun read(image: Bitmap, at: Spot, radius: Float): Answer? {
        val tess = ensure() ?: return null
        val half = (radius*.24f).coerceAtLeast(15f)
        val l=floor(at.x-half).toInt().coerceIn(0,image.width-1)
        val t=floor(at.y-half).toInt().coerceIn(0,image.height-1)
        val r=ceil(at.x+half).toInt().coerceIn(l+1,image.width)
        val b=ceil(at.y+half).toInt().coerceIn(t+1,image.height)
        val patch=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888)
        patch.eraseColor(Color.WHITE)
        Canvas(patch).drawBitmap(image,Rect(l,t,r,b),RectF(24f,24f,232f,232f),
            Paint(Paint.FILTER_BITMAP_FLAG))
        // Tesseract was trained on ordinary printed lettering; Wordscapes
        // letters may be white on blue/purple. Normalizing to black-on-white
        // prevents the background of a game tile from dominating prediction.
        val normalized = Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888)
        val pix=IntArray(256*256)
        patch.getPixels(pix,0,256,0,0,256,256)
        val lum=IntArray(256*256) { i ->
            val p=pix[i]
            (Color.red(p)*299+Color.green(p)*587+Color.blue(p)*114)/1000
        }
        val histogram=IntArray(256)
        for(y in 44 until 212 step 3) for(x in 44 until 212 step 3)
            histogram[lum[y*256+x]]++
        var halfCount=histogram.sum()/2;var background=127
        for(i in histogram.indices) {
            halfCount-=histogram[i]
            if(halfCount<=0){background=i;break}
        }
        val lightGlyph=background<145
        val brightThreshold=max(157,background+37).coerceAtMost(245)
        val darkThreshold=min(117,background-37).coerceAtLeast(18)
        for(i in pix.indices) {
            val darkInk=if(lightGlyph) lum[i]>=brightThreshold else lum[i]<=darkThreshold
            pix[i]=if(darkInk) Color.BLACK else Color.WHITE
        }
        normalized.setPixels(pix,0,256,0,0,256,256)
        return try {
            val readings=mutableListOf<Answer>()
            for(view in listOf(normalized,patch)) {
                tess.setImage(view)
                val str=tess.getUTF8Text()?.trim()?.uppercase(Locale.US) ?: ""
                val confidence=tess.meanConfidence()
                // Numeric zero is impossible in the letter wheel.
                val letter=str.singleOrNull()?.let { if(it=='0') 'O' else it }
                if(letter!=null && letter in 'A'..'Z')
                    readings.add(Answer(letter,confidence))
            }
            readings.maxByOrNull { it.confidence }
        } catch (_: Exception) {
            null
        } finally {
            normalized.recycle()
            patch.recycle()
        }
    }

    @Synchronized
    fun close() {
        api?.recycle()
        api=null
    }
}
