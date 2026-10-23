package com.pugplayz.wordscapesauto

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
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

data class LetterScan(
    val text: String,
    val uncertainPositions: List<Int>,
    val samples: List<GlyphMemory.Sample?>
) {
    val allConfident: Boolean get() = uncertainPositions.isEmpty() && '?' !in text
}

/**
 * Three genuinely different OCR inputs, then vote. Confirmed glyph templates
 * can override repeated ML Kit mistakes after the user has taught that shape.
 */
class LetterReader {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val ui = Handler(Looper.getMainLooper())
    private var tess: SingleCharacterModel? = null
    private data class Decision(val char: Char, val confident: Boolean)

    fun recognize(
        context: Context,
        bitmap: Bitmap,
        positions: List<Spot>,
        radius: Float,
        hints: List<Char?> = emptyList(),
        finished: (LetterScan) -> Unit
    ) {
        val letters = StringBuilder()
        val uncertain = mutableListOf<Int>()
        if (tess == null) tess = SingleCharacterModel(context.applicationContext)
        val samples = positions.map { GlyphMemory.extract(bitmap, it, radius) }
        fun next(i: Int) {
            if (i == positions.size) {
                finished(LetterScan(letters.toString(), uncertain.toList(), samples))
                return
            }
            recognizeOne(context, bitmap, positions[i], radius, samples[i]) { prediction ->
                val hint = hints.getOrNull(i)?.takeIf { it in 'A'..'Z' }
                val chosen = if (prediction.char == '?' && hint != null)
                    hint else prediction.char
                // OCR on the entire wheel is independent evidence of the
                // per-letter crops; disagreement MUST prevent automatic RUN.
                val confident = if (hint != null)
                    prediction.confident && prediction.char == hint
                    else prediction.confident
                letters.append(chosen)
                if (!confident) uncertain.add(i)
                next(i + 1)
            }
        }
        next(0)
    }

    private fun recognizeOne(
        context: Context,
        bitmap: Bitmap,
        pos: Spot,
        radius: Float,
        sample: GlyphMemory.Sample?,
        callback: (Decision) -> Unit
    ) {
        val results = mutableListOf<Char>()
        fun processPass(pass: Int) {
            if (pass >= 3) {
                val known = results.filter { it in 'A'..'Z' }
                val frequencies = known.groupingBy { it }.eachCount()
                val winner = frequencies.maxByOrNull { it.value }
                val mlChar = winner?.key ?: '?'
                val voteCount = winner?.value ?: 0
                val other = known.any { it != mlChar }
                val learned = GlyphMemory.predict(context, sample)
                val solidMemory = learned != null &&
                    learned.score >= .88f && learned.margin >= .09f
                val veryStrongMemory = learned != null &&
                    learned.score >= .93f && learned.margin >= .12f

                // Independent pretrained LSTM model: do not assume three
                // variants of the same ML Kit model are three independent votes.
                val neural = tess
                Thread {
                    val secondModel = neural?.read(bitmap, pos, radius)
                    ui.post {
                        val tessChar = secondModel?.letter
                        val tessConf = secondModel?.confidence ?: 0
                        val chosen = when {
                            veryStrongMemory && learned != null -> learned.letter
                            tessChar != null && tessConf >= 78 -> tessChar
                            solidMemory && learned != null && mlChar == learned.letter -> mlChar
                            tessChar != null && tessConf >= 48 && mlChar == '?' -> tessChar
                            else -> mlChar
                        }
                        val confident = when {
                            chosen == '?' -> false
                            tessChar != null && chosen == tessChar &&
                                tessConf >= 84 -> true
                            tessChar != null && chosen == tessChar &&
                                tessConf >= 65 && mlChar == tessChar -> true
                            veryStrongMemory && learned != null &&
                                chosen == learned.letter -> true
                            tessChar == null && voteCount == 3 &&
                                solidMemory && learned?.letter == chosen -> true
                            else -> false
                        }
                        if(chosen != '?') {
                            callback(Decision(chosen, confident))
                        } else {
                            val maybeI = looksLikeNarrowI(bitmap,pos,radius*.22f)
                            callback(Decision(if(maybeI) 'I' else '?',false))
                        }
                    }
                }.start()
                return
            }
            val crop = preparedCrop(bitmap, pos, radius, pass)
            recognizer.process(InputImage.fromBitmap(crop, 0))
                .addOnCompleteListener { task ->
                    val text = if (task.isSuccessful) task.result?.text ?: "" else ""
                    results.add(parseSingleLetter(text))
                    crop.recycle()
                    processPass(pass + 1)
                }
        }
        processPass(0)
    }

    private fun parseSingleLetter(value: String): Char {
        val normalized = value.trim().uppercase(Locale.US)
            .replace(" ", "").replace("\n", "")
            // A wheel contains letters, never numeric zero.
            .replace('0', 'O')
        return if (normalized.length == 1 && normalized[0] in 'A'..'Z')
            normalized[0] else '?'
    }

    private fun preparedCrop(original: Bitmap, center: Spot, radius: Float, pass: Int): Bitmap {
        val half = radius * when (pass) {
            0 -> .280f
            1 -> .205f
            else -> .245f
        }
        val left = (center.x - half).toInt().coerceIn(0, original.width - 1)
        val top = (center.y - half).toInt().coerceIn(0, original.height - 1)
        val right = (center.x + half).toInt().coerceIn(left + 1, original.width)
        val bottom = (center.y + half).toInt().coerceIn(top + 1, original.height)
        val output = Bitmap.createBitmap(288, 288, Bitmap.Config.ARGB_8888)
        val background = original.getPixel(left, top)
        output.eraseColor(background)
        Canvas(output).drawBitmap(
            original, Rect(left, top, right, bottom),
            RectF(24f, 24f, 264f, 264f),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        )

        if (pass == 2) {
            // Deliberately unlike the natural-color passes: high-contrast white
            // background with dark text, helpful for isolated light glyphs.
            val px = IntArray(288 * 288)
            output.getPixels(px, 0, 288, 0, 0, 288, 288)
            fun lum(color: Int): Int =
                (Color.red(color) * 299 + Color.green(color) * 587 +
                    Color.blue(color) * 114) / 1000
            val bg = lum(background)
            for (i in px.indices) {
                val diff = abs(lum(px[i]) - bg)
                px[i] = if (diff > 38) Color.BLACK else Color.WHITE
            }
            output.setPixels(px, 0, 288, 0, 0, 288, 288)
        }
        return output
    }

    /** Safeguard: fallback I only when all OCR inputs produced no A-Z letter. */
    private fun looksLikeNarrowI(b: Bitmap, p: Spot, half: Float): Boolean {
        val left = (p.x - half).roundToInt().coerceAtLeast(0)
        val right = (p.x + half).roundToInt().coerceAtMost(b.width - 1)
        val top = (p.y - half).roundToInt().coerceAtLeast(0)
        val bottom = (p.y + half).roundToInt().coerceAtMost(b.height - 1)
        if (right <= left || bottom <= top) return false
        val pixels = IntArray(256)
        fun lum(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 +
            Color.blue(c) * 114) / 1000
        for (y in top..bottom step 2) for (x in left..right step 2)
            pixels[lum(b.getPixel(x, y))]++
        val halfCount = pixels.sum() / 2
        var n = 0; var median = 127
        for (v in pixels.indices) {
            n += pixels[v]
            if (n >= halfCount) { median = v; break }
        }
        val light = median < 160
        var minX = right; var maxX = left
        var minY = bottom; var maxY = top; var ink = 0
        for (y in top..bottom) for (x in left..right) {
            val value = lum(b.getPixel(x, y))
            val active = if (light) value > max(190, median + 65)
                else value < min(70, median - 65)
            if (active) {
                ink++; minX = min(minX, x); maxX = max(maxX, x)
                minY = min(minY, y); maxY = max(maxY, y)
            }
        }
        if (ink < 18) return false
        val width = maxX - minX + 1
        val height = maxY - minY + 1
        return height > half * .55f && width.toFloat() / height < .34f
    }

    fun close() { recognizer.close(); tess?.close() }
}
