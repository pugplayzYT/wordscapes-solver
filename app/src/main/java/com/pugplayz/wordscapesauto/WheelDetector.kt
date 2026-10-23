package com.pugplayz.wordscapesauto

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import kotlin.math.*

/**
 * Finds the ACTIVE ring inside a permanent rectangular search area.
 * Unlike the old detector, no letter center or radius is saved or assumed.
 * Evaluates candidate circle centers, sizes, letter counts and phase from pixels.
 */
object WheelDetector {
    const val MIN_LETTERS = 3
    const val MAX_LETTERS = 12
    private const val ANGLES = 360

    private data class Candidate(
        val center: Spot, val radius: Float, val count: Int,
        val phase: Float, val score: Float, val signal: FloatArray
    )

    fun detectInBox(image: Bitmap, box: RectF, overrideCount: Int? = null): WheelResult {
        val left = floor(box.left).toInt().coerceIn(0, image.width)
        val top = floor(box.top).toInt().coerceIn(0, image.height)
        val right = ceil(box.right).toInt().coerceIn(0, image.width)
        val bottom = ceil(box.bottom).toInt().coerceIn(0, image.height)
        val w = right - left; val h = bottom - top
        if (w < 90 || h < 90) return WheelResult(emptyList(), 0f, 0)

        // One bitmap access rather than thousands of getPixel() calls.
        val argb = IntArray(w*h)
        image.getPixels(argb, 0, w, left, top, w, h)
        val scale = max(1, ceil(max(w,h) / 480.0).toInt())
        val sw = (w+scale-1)/scale; val sh = (h+scale-1)/scale
        val gray = IntArray(sw*sh)
        for (y in 0 until sh) for (x in 0 until sw) {
            val pixel = argb[min(h-1,y*scale)*w + min(w-1,x*scale)]
            gray[y*sw+x] = (Color.red(pixel)*299 + Color.green(pixel)*587 +
                Color.blue(pixel)*114)/1000
        }
        fun brightness(x: Float, y: Float): Int {
            val ix = ((x-left)/scale).roundToInt()
            val iy = ((y-top)/scale).roundToInt()
            return if (ix in 0 until sw && iy in 0 until sh)
                gray[iy*sw+ix] else -1
        }

        val center = Spot((left+right)/2f,(top+bottom)/2f)
        val shorterHalf = min(w,h)/2f
        val radii = (0..8).map { shorterHalf*(.51f + it*.055f) }
        // Small center shifts accommodate the wheel sliding within the saved box.
        val shifts = listOf(
            Spot(0f,0f),Spot(w*.045f,0f),Spot(-w*.045f,0f),
            Spot(0f,h*.045f),Spot(0f,-h*.045f)
        )
        var best: Candidate? = null
        for (shift in shifts) {
            val c = Spot(center.x+shift.x,center.y+shift.y)
            for (r in radii) {
                val candidate = examineRing(c,r,overrideCount,::brightness) ?: continue
                if (candidate.score > (best?.score ?: Float.NEGATIVE_INFINITY)) best = candidate
            }
        }
        val match = best ?: return WheelResult(emptyList(),0f,0)
        if (match.score < .28f) return WheelResult(emptyList(),0f,0)
        val n = match.count
        val points = (0 until n).map { i ->
            val expected = match.phase+i*360f/n
            val angle = localPeakAngle(match.signal,expected,7)
            val rad = Math.toRadians(angle.toDouble())
            Spot(match.center.x+cos(rad).toFloat()*match.radius,
                match.center.y+sin(rad).toFloat()*match.radius)
        }.sortedBy {
            (atan2(it.y-match.center.y,it.x-match.center.x)+2*PI+PI/2)%(2*PI)
        }
        val confidence = ((match.score-.18f)/.85f).coerceIn(0f,1f)
        return WheelResult(points,confidence,n,match.center,match.radius)
    }

    private fun examineRing(
        center: Spot, radius: Float, manualCount: Int?,
        lum: (Float,Float)->Int
    ): Candidate? {
        val values = mutableListOf<Int>()
        for (a in 0 until ANGLES step 3) {
            val t = Math.toRadians(a.toDouble())
            val v = lum(center.x+cos(t).toFloat()*radius,
                center.y+sin(t).toFloat()*radius)
            if (v >= 0) values.add(v)
        }
        if (values.size < 70) return null
        values.sort()
        val median = values[values.size/2]
        val upper = max(158,median+34)
        val lower = min(110,median-34)
        val light = FloatArray(ANGLES); val dark = FloatArray(ANGLES)
        val radial = floatArrayOf(-.115f,-.075f,-.035f,.005f,.045f,.085f,.125f)
        for (a in 0 until ANGLES) {
            for (offset in -3..3 step 3) {
                val t = Math.toRadians((a+offset).toDouble())
                val cos = cos(t).toFloat(); val sin = sin(t).toFloat()
                for (delta in radial) {
                    val rr = radius*(1f+delta)
                    val l = lum(center.x+cos*rr,center.y+sin*rr)
                    if (l < 0) continue
                    if (l >= upper) light[a] += 1f/3f
                    if (l <= lower) dark[a] += 1f/3f
                }
            }
        }
        fun smooth(input: FloatArray): FloatArray = FloatArray(ANGLES) { a ->
            var sum = 0f
            for (j in -4..4) sum += input[(a+j+ANGLES)%ANGLES]
            sum/9f
        }
        val candidates = listOf(smooth(light),smooth(dark))
        var best: Candidate? = null
        val counts = if (manualCount != null) listOf(manualCount.coerceIn(MIN_LETTERS,MAX_LETTERS))
            else (MIN_LETTERS..MAX_LETTERS).toList()
        for (signal in candidates) {
            val high = signal.maxOrNull() ?: 0f
            if (high < .45f) continue
            for (n in counts) {
                val period = 360f/n
                var bestPhase = 0f
                var bestScore = Float.NEGATIVE_INFINITY
                var bestHits = 0
                val steps = ceil(period).toInt()
                for (phaseInt in 0 until steps) {
                    val phase = phaseInt.toFloat()
                    var positive = 0f; var negative = 0f; var hits = 0
                    for (i in 0 until n) {
                        val t = phase+i*period
                        val strength = localMax(signal,t,6)
                        val outside = localMax(signal,t+period/2f,3)
                        positive += strength
                        negative += outside
                        if (strength >= high*.40f) hits++
                    }
                    val score = (positive-.77f*negative)/n/(high+.3f) +
                        .18f*hits/n - .007f*(n-MIN_LETTERS)
                    if (score > bestScore) {
                        bestPhase = phase
                        bestScore = score
                        bestHits = hits
                    }
                }
                // Discourage counts that require inventing too many invisible letters.
                val sufficient = bestHits >= max(3,(n*.65f).ceilToInt())
                if (!sufficient) continue
                val candidate = Candidate(center,radius,n,bestPhase,bestScore,signal)
                if (candidate.score > (best?.score ?: Float.NEGATIVE_INFINITY))
                    best = candidate
            }
        }
        return best
    }

    fun ringStillPresent(image: Bitmap, box: RectF): Boolean {
        val result = detectInBox(image,box)
        return result.points.size in MIN_LETTERS..MAX_LETTERS &&
            result.confidence > .23f
    }

    private fun localMax(values:FloatArray,angle:Float,reach:Int):Float {
        var best=0f
        for (d in -reach..reach) {
            val idx=((angle.roundToInt()+d)%ANGLES+ANGLES)%ANGLES
            if(values[idx]>best) best=values[idx]
        }
        return best
    }

    private fun localPeakAngle(values:FloatArray,angle:Float,reach:Int):Float {
        var max=Float.NEGATIVE_INFINITY; var winner=angle
        for(d in -reach..reach) {
            val idx=((angle.roundToInt()+d)%ANGLES+ANGLES)%ANGLES
            if(values[idx]>max) { max=values[idx]; winner=idx.toFloat() }
        }
        return winner
    }
}
