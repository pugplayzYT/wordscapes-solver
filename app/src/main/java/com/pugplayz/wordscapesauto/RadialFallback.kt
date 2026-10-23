package com.pugplayz.wordscapesauto

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.*

/**
 * Low-confidence geometric recovery when ML Kit and the contour fitter find
 * too few independent letter boxes. Does NOT claim it detected every glyph.
 *
 * Samples ink *inside* a series of possible letter rings, compares on-letter
 * versus between-letter angles, and optionally rewards real OCR anchors.
 * The result must go through separate per-letter OCR and review, not AUTO.
 */
object RadialFallback {
    data class Estimate(val wheel: WheelResult, val score: Float, val anchors: Int)
    private const val BINS = 180
    private const val ANGLE_STEP = 2f

    fun estimate(image: Bitmap, cal: Calibration, countHint: Int?,
                 anchors: List<Spot>): Estimate? {
        val center=cal.center
        val maxRadius=cal.radius
        if (maxRadius<65f || image.width<90 || image.height<90)return null
        val x0=floor(center.x-maxRadius).toInt().coerceAtLeast(0)
        val y0=floor(center.y-maxRadius).toInt().coerceAtLeast(0)
        val x1=ceil(center.x+maxRadius).toInt().coerceAtMost(image.width)
        val y1=ceil(center.y+maxRadius).toInt().coerceAtMost(image.height)
        val w=x1-x0;val h=y1-y0
        if(w<80||h<80)return null
        val pixels=IntArray(w*h)
        image.getPixels(pixels,0,w,x0,y0,w,h)
        fun lum(x:Float,y:Float):Int {
            val ix=(x-x0).roundToInt();val iy=(y-y0).roundToInt()
            if(ix !in 0 until w||iy !in 0 until h)return -1
            val p=pixels[iy*w+ix]
            return (Color.red(p)*299+Color.green(p)*587+Color.blue(p)*114)/1000
        }
        val cosTable=FloatArray(BINS){i->cos(Math.toRadians(i*ANGLE_STEP.toDouble())).toFloat()}
        val sinTable=FloatArray(BINS){i->sin(Math.toRadians(i*ANGLE_STEP.toDouble())).toFloat()}
        val counts=if(countHint!=null)listOf(countHint.coerceIn(3,12))
                   else (3..10).toList()

        var best=-Float.MAX_VALUE
        var bestN=5
        var bestR=maxRadius*.65f
        var bestPhase=0f
        var bestEvidence=0

        // Because the user draws a circle AROUND the letters, their actual
        // radius is generally only 30–86% of the selected search radius.
        for(step in 0..24) {
            val radius=maxRadius*(.30f+step*.023f)
            if(radius<35f)continue
            val rawBright=FloatArray(BINS)
            val rawDark=FloatArray(BINS)

            // Estimate background colour on this concentric ring. Compare
            // glyph pixels against it rather than relying on a fixed 180.
            val centerLums=IntArray(BINS)
            for(a in 0 until BINS) {
                centerLums[a]=lum(center.x+radius*cosTable[a],
                    center.y+radius*sinTable[a]).coerceAtLeast(0)
            }
            val sorted=centerLums.sorted()
            val median=sorted[BINS/2]
            val high=(median+34).coerceAtMost(252)
            val low=(median-34).coerceAtLeast(4)
            if(high==252 && low==4)continue
            for(a in 0 until BINS) {
                var bright=0;var dark=0;var total=0
                for(da in -3..3 step 2) {
                    val angle=(a+da+BINS)%BINS
                    for(dr in -3..3) {
                        val rr=radius*(1f+dr*.023f)
                        val v=lum(center.x+rr*cosTable[angle],
                            center.y+rr*sinTable[angle])
                        if(v<0)continue
                        total++
                        if(v>high)bright++
                        if(v<low)dark++
                    }
                }
                if(total>0) {
                    rawBright[a]=bright.toFloat()/total
                    rawDark[a]=dark.toFloat()/total
                }
            }
            fun smooth(raw:FloatArray):FloatArray=FloatArray(BINS){a->
                var sum=0f
                for(d in -2..2)sum+=raw[(a+d+BINS)%BINS]
                sum/5f
            }
            val signals=listOf(smooth(rawBright),smooth(rawDark))
            for(signal in signals) {
                val globalPeak=signal.maxOrNull() ?: 0f
                if(globalPeak<.018f)continue
                for(n in counts) {
                    val period=360f/n
                    // Phase is the center of a glyph; sample all n slots.
                    val phaseSteps=(period/ANGLE_STEP).roundToInt().coerceAtLeast(1)
                    for(phaseStep in 0 until phaseSteps) {
                        val phase=phaseStep*ANGLE_STEP
                        var peakSum=0f;var between=0f;var minPeak=1f
                        var hits=0;var anchorEvidence=0
                        for(i in 0 until n) {
                            val angle=phase+i*period
                            val idx=((angle/ANGLE_STEP).roundToInt()%BINS+BINS)%BINS
                            val midIdx=(((angle+period/2f)/ANGLE_STEP).roundToInt()%BINS+BINS)%BINS
                            val peak=signal[idx]
                            peakSum+=peak
                            minPeak=min(minPeak,peak)
                            between+=signal[midIdx]
                            if(peak>globalPeak*.32f)hits++
                            for(p in anchors) {
                                val d=hypot(p.x-(center.x+radius*cosTable[idx]),
                                    p.y-(center.y+radius*sinTable[idx]))
                                if(d < radius*.19f) {
                                    anchorEvidence++
                                    break
                                }
                            }
                        }
                        val coverage=hits.toFloat()/n
                        val coherence=peakSum/n - .60f*between/n
                        // Actual OCR symbol centers are strong evidence. But
                        // low-contrast glyphs may have no whole-wheel OCR.
                        val score=coherence+.26f*coverage +
                            .18f*(anchorEvidence.toFloat()/n) +
                            .08f*minPeak -
                            .006f*(n-3)
                        if(score>best) {
                            best=score
                            bestN=n;bestR=radius;bestPhase=phase
                            bestEvidence=anchorEvidence
                        }
                    }
                }
            }
        }
        // Extremely flat images aren't good enough even for review.
        if(best < .06f)return null
        val positions=(0 until bestN).map{i->
            val a=Math.toRadians((bestPhase+i*360f/bestN).toDouble())
            Spot(center.x+cos(a).toFloat()*bestR,center.y+sin(a).toFloat()*bestR)
        }.sortedBy {
            (atan2(it.y-center.y,it.x-center.x)+2*PI+PI/2)%(2*PI)
        }
        // Deliberately under .80 auto-run threshold: slot positions inferred
        // from pixel periodicity have not been verified as tappable glyphs.
        return Estimate(WheelResult(positions,.28f,bestN,center,bestR),
            best,bestEvidence)
    }
}
