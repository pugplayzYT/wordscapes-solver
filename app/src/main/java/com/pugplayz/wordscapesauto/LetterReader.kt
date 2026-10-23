package com.pugplayz.wordscapesauto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import kotlin.math.*

/** Latin ML Kit OCR; never forcibly changes correctly recognized letters. */
class LetterReader {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun recognize(bitmap: Bitmap, positions: List<Spot>, radius: Float, finished: (String) -> Unit) {
        val results = StringBuilder()
        fun next(i: Int) {
            if (i == positions.size) { finished(results.toString()); return }
            recognizeOne(bitmap, positions[i], radius) { letter ->
                results.append(letter)
                next(i + 1)
            }
        }
        next(0)
    }

    private fun recognizeOne(bitmap: Bitmap, pos: Spot, radius: Float, callback: (Char) -> Unit) {
        fun pass(half: Float, retry: Boolean) {
            val crop = preparedCrop(bitmap, pos, half)
            val task = recognizer.process(InputImage.fromBitmap(crop, 0))
            task.addOnCompleteListener { result ->
                val text = if (result.isSuccessful) result.result?.text ?: "" else ""
                val letter = parseSingleLetter(text)
                crop.recycle()
                when {
                    letter != '?' -> callback(letter)
                    retry -> pass(radius * .185f, false)
                    else -> callback(if (looksLikeNarrowI(bitmap, pos, radius * .22f)) 'I' else '?')
                }
            }
        }
        pass(radius * .265f, true)
    }

    private fun parseSingleLetter(value: String): Char {
        val normalized = value.trim().uppercase(Locale.US).replace(" ", "").replace("\n", "")
        // Reject complete words and ambiguous OCR. This keeps S as S, not a heuristic E.
        return if (normalized.length == 1 && normalized[0] in 'A'..'Z') normalized[0] else '?'
    }

    private fun preparedCrop(original: Bitmap, center: Spot, half: Float): Bitmap {
        val left = (center.x - half).toInt().coerceIn(0, original.width - 1)
        val top = (center.y - half).toInt().coerceIn(0, original.height - 1)
        val right = (center.x + half).toInt().coerceIn(left + 1, original.width)
        val bottom = (center.y + half).toInt().coerceIn(top + 1, original.height)
        val output = Bitmap.createBitmap(288, 288, Bitmap.Config.ARGB_8888)
        val background = original.getPixel(left, top)
        output.eraseColor(background)
        Canvas(output).drawBitmap(original, Rect(left, top, right, bottom), RectF(30f, 30f, 258f, 258f),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return output
    }

    /** Used ONLY after both OCR attempts return '?'. A slim upright ink stroke may be I. */
    private fun looksLikeNarrowI(b: Bitmap, p: Spot, half: Float): Boolean {
        val left = (p.x - half).roundToInt().coerceAtLeast(0)
        val right = (p.x + half).roundToInt().coerceAtMost(b.width - 1)
        val top = (p.y - half).roundToInt().coerceAtLeast(0)
        val bottom = (p.y + half).roundToInt().coerceAtMost(b.height - 1)
        if (right <= left || bottom <= top) return false
        val histogram = IntArray(256)
        for (y in top..bottom step 2) for (x in left..right step 2) histogram[brightness(b.getPixel(x, y))]++
        val total = histogram.sum()
        var running = 0; var median = 127
        for (v in histogram.indices) { running += histogram[v]; if (running >= total / 2) { median = v; break } }
        val isLight = median < 160
        var minX = right; var maxX = left; var minY = bottom; var maxY = top; var count = 0
        for (y in top..bottom) for (x in left..right) {
            val v = brightness(b.getPixel(x, y))
            val ink = if (isLight) v > max(190, median + 65) else v < min(70, median - 65)
            if (ink) { count++; minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y) }
        }
        if (count < 18) return false
        val width = maxX - minX + 1
        val height = maxY - minY + 1
        return height > half * .55f && width.toFloat() / height < .34f
    }

    private fun brightness(color: Int) = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
    fun close() { recognizer.close() }
}
