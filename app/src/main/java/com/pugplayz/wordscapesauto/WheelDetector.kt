package com.pugplayz.wordscapesauto

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.*

/** Finds characters arranged as a regular ring, from only a center and radius. */
object WheelDetector {
    private const val SAMPLES = 360

    /** Defaults to automatic 3-8 letters; overrideCount only when a user corrects detection. */
    fun detect(image: Bitmap, center: Spot, radius: Float, overrideCount: Int? = null): WheelResult {
        if (radius < 35f || center.x !in 0f..image.width.toFloat() || center.y !in 0f..image.height.toFloat())
            return WheelResult(emptyList(), 0f, 0)

        // Estimate ring background brightness and find light or dark text contrast.
        val samples = ArrayList<Int>(360 * 6)
        for (a in 0 until 360 step 3) {
            val t = Math.toRadians(a.toDouble())
            for (f in listOf(.78f, .87f, .96f, 1.04f, 1.13f, 1.22f)) {
                val v = lumAt(image, center.x + cos(t).toFloat() * radius * f,
                    center.y + sin(t).toFloat() * radius * f)
                if (v >= 0) samples.add(v)
            }
        }
        if (samples.isEmpty()) return WheelResult(emptyList(), 0f, 0)
        samples.sort()
        val median = samples[samples.size / 2]
        val lightThreshold = max(154, median + 28)
        val darkThreshold = min(115, median - 28)
        val light = FloatArray(SAMPLES)
        val dark = FloatArray(SAMPLES)
        val offsets = floatArrayOf(-.14f, -.10f, -.06f, -.02f, .02f, .06f, .10f, .14f)
        for (a in 0 until SAMPLES) {
            // Sample a narrow angular strip rather than one infinitely thin ray.
            // A single ray regularly misses slender I/T glyph strokes.
            for (offsetAngle in -3..3 step 3) {
                val t = Math.toRadians((a + offsetAngle).toDouble())
                val dx = cos(t).toFloat(); val dy = sin(t).toFloat()
                for (off in offsets) {
                    val r = radius * (1f + off)
                    val v = lumAt(image, center.x + dx * r, center.y + dy * r)
                    if (v >= 0) {
                        if (v >= lightThreshold) light[a] += (1f / 3f)
                        if (v <= darkThreshold) dark[a] += (1f / 3f)
                    }
                }
            }
        }
        val signal = if (light.count { it >= 1f } >= dark.count { it >= 1f }) light else dark
        val smooth = FloatArray(SAMPLES) { a ->
            var sum = 0f
            for (o in -5..5) sum += signal[(a + o + SAMPLES) % SAMPLES]
            sum / 11f
        }
        val high = smooth.maxOrNull() ?: 0f
        // A blank screen must never be interpreted as a fabricated four-letter wheel.\n        if (high < .10f) return WheelResult(emptyList(), 0f, 0)

        // First count independent angular peaks. This avoids the harmonic trap:
        // a six-letter ring can otherwise look like a strong three-letter ring.
        val selected = mutableListOf<Int>()
        for (a in (0 until SAMPLES).sortedByDescending { smooth[it] }) {
            if (smooth[a] < max(.04f, high * .16f)) break
            if (selected.all { old -> min(abs(a - old), SAMPLES - abs(a - old)) >= 35 }) selected.add(a)
        }
        selected.sort()
        if (overrideCount == null && selected.size in 3..8) {
            val idealGap = 360f / selected.size
            val gapError = selected.indices.sumOf { i ->
                val actualGap = (selected[(i + 1) % selected.size] - selected[i] + SAMPLES) % SAMPLES
                abs(actualGap - idealGap).toDouble()
            } / selected.size
            if (gapError < 13.0) {
                val points = selected.map { a ->
                    val t = Math.toRadians(a.toDouble())
                    Spot(center.x + cos(t).toFloat() * radius, center.y + sin(t).toFloat() * radius)
                }.sortedBy { atan2(it.y - center.y, it.x - center.x).let { a -> (a + 2 * PI + PI / 2) % (2 * PI) } }
                return WheelResult(points, (.65f + high / 18f).coerceAtMost(.95f), selected.size)
            }
        }

        val countOptions = if (overrideCount != null) listOf(overrideCount.coerceIn(3, 8)) else (3..8).toList()
        var bestScore = Float.NEGATIVE_INFINITY
        var bestCount = 4
        var bestPhase = 270f
        for (n in countOptions) {
            val period = 360f / n
            for (phaseStep in 0 until (period * 2).toInt()) {
                val phase = phaseStep / 2f
                var on = 0f
                var off = 0f
                var hits = 0
                for (j in 0 until n) {
                    val theta = phase + j * period
                    val strength = localPeak(smooth, theta, 8)
                    on += strength
                    if (strength >= high * .37f) hits++
                    off += localPeak(smooth, theta + period / 2, 5)
                }
                // Penalize predicting empty slots (e.g. choosing 8 for an actual 4-letter ring).
                val score = (on - .59f * off) / n + hits.toFloat() / n * 1.5f - (n - 3) * .055f
                if (score > bestScore) { bestScore = score; bestCount = n; bestPhase = phase }
            }
        }
        val n = overrideCount?.coerceIn(3, 8) ?: bestCount
        val period = 360f / n
        val centers = (0 until n).map { j ->
            val expected = bestPhase + j * period
            // Fine-tune marker towards the center of each individual glyph.
            val angle = localPeakAngle(smooth, expected, 10)
            val rad = Math.toRadians(angle.toDouble())
            Spot(center.x + cos(rad).toFloat() * radius,
                center.y + sin(rad).toFloat() * radius)
        }.sortedBy { atan2(it.y - center.y, it.x - center.x).let { a -> (a + 2 * PI + PI / 2) % (2 * PI) } }
        val confidence = ((bestScore / (high + 1f)) * .8f).coerceIn(0f, 1f)
        return WheelResult(centers, confidence, n)
    }

    fun ringStillPresent(image: Bitmap, center: Spot, radius: Float): Boolean {
        val result = detect(image, center, radius)
        return result.confidence > .23f && result.points.size in 3..8
    }

    private fun fallback(c: Spot, r: Float, n: Int): WheelResult {
        val pts = (0 until n.coerceIn(3, 8)).map { i ->
            val theta = -PI / 2 + i * 2 * PI / n
            Spot(c.x + cos(theta).toFloat() * r, c.y + sin(theta).toFloat() * r)
        }
        return WheelResult(pts, 0f, n)
    }

    private fun localPeak(signal: FloatArray, angle: Float, tolerance: Int): Float {
        var best = 0f
        for (d in -tolerance..tolerance) {
            val idx = ((angle.roundToInt() + d) % SAMPLES + SAMPLES) % SAMPLES
            best = max(best, signal[idx])
        }
        return best
    }
    private fun localPeakAngle(signal: FloatArray, angle: Float, tolerance: Int): Float {
        var best = -1f; var index = angle.roundToInt()
        for (d in -tolerance..tolerance) {
            val idx = ((angle.roundToInt() + d) % SAMPLES + SAMPLES) % SAMPLES
            if (signal[idx] > best) { best = signal[idx]; index = idx }
        }
        return index.toFloat()
    }
    private fun lumAt(b: Bitmap, x: Float, y: Float): Int {
        val px = x.roundToInt(); val py = y.roundToInt()
        if (px !in 0 until b.width || py !in 0 until b.height) return -1
        val c = b.getPixel(px, py)
        return (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
    }
}
