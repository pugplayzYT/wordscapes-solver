package com.pugplayz.wordscapesauto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/**
 * Learns letter shapes ONLY from letters the user explicitly confirms in the
 * review editor. Templates remain in the app's private preferences, never sent
 * over the network. No automatic self-training on speculative OCR guesses.
 */
object GlyphMemory {
    private const val SIDE = 24
    private const val SIZE = SIDE * SIDE
    private const val MAX_PER_LETTER = 18
    private const val PREF = "wordscapes.learned_glyphs"
    private const val KEY = "confirmed_samples_v1"

    data class Sample(val bits: BooleanArray, val aspect: Float)
    data class Prediction(val letter: Char, val score: Float, val margin: Float)
    private data class Entry(val letter: Char, val sample: Sample)

    private fun luminance(color: Int): Int =
        (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000

    /** Tight, aspect-preserving normalized mask of ONE letter in a wheel tile. */
    fun extract(bitmap: Bitmap, at: Spot, radius: Float): Sample? {
        val half = radius * .265f
        val left = floor(at.x - half).toInt().coerceIn(0, bitmap.width - 1)
        val right = ceil(at.x + half).toInt().coerceIn(left + 1, bitmap.width)
        val top = floor(at.y - half).toInt().coerceIn(0, bitmap.height - 1)
        val bottom = ceil(at.y + half).toInt().coerceIn(top + 1, bitmap.height)
        val w = right - left; val h = bottom - top
        if (w < 9 || h < 9) return null

        // Most pixels around the edge of the crop are letter-tile background.
        val border = ArrayList<Int>()
        for (x in 0 until w step 2) {
            border.add(luminance(bitmap.getPixel(left + x, top)))
            border.add(luminance(bitmap.getPixel(left + x, bottom - 1)))
        }
        for (y in 0 until h step 2) {
            border.add(luminance(bitmap.getPixel(left, top + y)))
            border.add(luminance(bitmap.getPixel(right - 1, top + y)))
        }
        if (border.isEmpty()) return null
        border.sort()
        val background = border[border.size / 2]
        val values = IntArray(w * h)
        var maxDifference = 0
        for (y in 0 until h) for (x in 0 until w) {
            val delta = abs(luminance(bitmap.getPixel(left + x, top + y)) - background)
            values[y * w + x] = delta
            maxDifference = max(maxDifference, delta)
        }
        if (maxDifference < 28) return null
        val threshold = max(28, (maxDifference * .46f).toInt())
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        var ink = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (values[y * w + x] >= threshold) {
                minX = min(minX, x); maxX = max(maxX, x)
                minY = min(minY, y); maxY = max(maxY, y)
                ink++
            }
        }
        if (ink < 10 || maxX < minX || maxY < minY ||
            ink > w * h * .55f) return null

        val glyphW = maxX - minX + 1; val glyphH = maxY - minY + 1
        if (glyphW < 2 || glyphH < 4) return null
        val usable = SIDE - 6
        val scale = min(usable.toFloat() / glyphW, usable.toFloat() / glyphH)
        val newW = max(1, (glyphW * scale).roundToInt())
        val newH = max(1, (glyphH * scale).roundToInt())
        val dx = (SIDE - newW) / 2
        val dy = (SIDE - newH) / 2
        val bits = BooleanArray(SIZE)
        for (y in 0 until newH) for (x in 0 until newW) {
            val sx = (minX + ((x + .5f) / scale).toInt()).coerceIn(0, w - 1)
            val sy = (minY + ((y + .5f) / scale).toInt()).coerceIn(0, h - 1)
            bits[(dy + y) * SIDE + dx + x] = values[sy * w + sx] >= threshold
        }
        return Sample(bits, glyphW.toFloat() / glyphH)
    }

    private fun similarity(a: Sample, b: Sample): Float {
        var aInk = 0; var bInk = 0; var intersection = 0
        for (i in 0 until SIZE) {
            if (a.bits[i]) aInk++
            if (b.bits[i]) bInk++
            if (a.bits[i] && b.bits[i]) intersection++
        }
        if (aInk == 0 || bInk == 0) return 0f
        // Dice coefficient ignores the large empty area around narrow letters.
        val overlap = 2f * intersection / (aInk + bInk)
        val aspectPenalty = (abs(ln(a.aspect.coerceAtLeast(.06f) /
            b.aspect.coerceAtLeast(.06f))) * .14f).coerceAtMost(.24f)
        return (overlap - aspectPenalty).coerceIn(0f, 1f)
    }

    private fun pack(sample: Sample): String {
        val data = ByteArray(SIZE / 8)
        for (i in 0 until SIZE) if (sample.bits[i]) {
            val at = i / 8
            data[at] = (data[at].toInt() or (1 shl (i % 8))).toByte()
        }
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun unpack(encoded: String, aspect: Float): Sample? = try {
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        if (data.size != SIZE / 8) null else Sample(
            BooleanArray(SIZE) { i -> (data[i / 8].toInt() and (1 shl (i % 8))) != 0 },
            aspect
        )
    } catch (_: Exception) { null }

    private fun read(context: Context): MutableList<Entry> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            val result = mutableListOf<Entry>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val text = item.optString("letter")
                val letter = text.singleOrNull() ?: continue
                if (letter !in 'A'..'Z') continue
                val sample = unpack(item.optString("bits"), item.optDouble("aspect", 1.0).toFloat())
                    ?: continue
                result.add(Entry(letter, sample))
            }
            result
        } catch (_: Exception) { mutableListOf() }
    }

    fun predict(context: Context, sample: Sample?): Prediction? {
        if (sample == null) return null
        val byLabel = mutableMapOf<Char, Float>()
        for (entry in read(context)) {
            val score = similarity(sample, entry.sample)
            if (score > (byLabel[entry.letter] ?: 0f)) byLabel[entry.letter] = score
        }
        val sorted = byLabel.entries.sortedByDescending { it.value }
        if (sorted.isEmpty()) return null
        val best = sorted[0]
        val second = sorted.getOrNull(1)?.value ?: 0f
        return Prediction(best.key, best.value, best.value - second)
    }

    /** Called only when the user taps RUN after examining/editing the letters. */
    fun learn(context: Context, samples: List<Sample?>, confirmed: String) {
        if (samples.size != confirmed.length) return
        val entries = read(context)
        for (i in confirmed.indices) {
            val letter = confirmed[i]
            val sample = samples[i] ?: continue
            if (letter !in 'A'..'Z') continue
            if (entries.any { it.letter == letter && similarity(it.sample, sample) >= .985f })
                continue
            val sameLabel = entries.withIndex().filter { it.value.letter == letter }
            if (sameLabel.size >= MAX_PER_LETTER) entries.removeAt(sameLabel.first().index)
            entries.add(Entry(letter, sample))
        }
        val json = JSONArray()
        for (entry in entries) json.put(JSONObject().apply {
            put("letter", entry.letter.toString())
            put("bits", pack(entry.sample))
            put("aspect", entry.sample.aspect.toDouble())
        })
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, json.toString()).apply()
    }

    fun learnedCount(context: Context): Int = read(context).size
}
