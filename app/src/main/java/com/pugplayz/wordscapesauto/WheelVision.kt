package com.pugplayz.wordscapesauto

import android.graphics.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import kotlin.math.*

/**
 * OCR boxes and real glyph contours determine where to touch.
 * NO angular brightness peaks and NO equally spaced virtual points.
 *
 * The user's center/radius only bound the search area. We consider whole-wheel
 * ML Kit OCR (two styles) and visible single-letter components, and fit a
 * coherent ring to ACTUALLY observed character centers.
 */
class WheelVision {
    data class Reading(val wheel: WheelResult, val hints: List<Char?>,
        val identified: Int, val visible: Int)
    private data class Evidence(val at: Spot, val letter: Char?, val weight: Float)
    private data class Match(val points: List<Evidence>, val n: Int,
        val origin: Spot, val radius: Float, val score: Float, val coverage: Float)

    private val model=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun close()=model.close()

    fun locate(image: Bitmap, area: Calibration, requestedCount: Int?,
               callback:(Reading?)->Unit) {
        val c=area.center
        val r=area.radius
        val region=Rect(
            floor(c.x-r).toInt().coerceAtLeast(0),
            floor(c.y-r).toInt().coerceAtLeast(0),
            ceil(c.x+r).toInt().coerceAtMost(image.width),
            ceil(c.y+r).toInt().coerceAtMost(image.height)
        )
        if(region.width()<100||region.height()<100) { callback(null);return }
        val scale=(720f/max(region.width(),region.height())).coerceIn(1f,2.3f)
        val cropW=max(1,(region.width()*scale).roundToInt())
        val cropH=max(1,(region.height()*scale).roundToInt())
        val natural=Bitmap.createBitmap(cropW,cropH,Bitmap.Config.ARGB_8888)
        val paint=Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        natural.eraseColor(Color.WHITE)
        Canvas(natural).drawBitmap(image,region,Rect(0,0,cropW,cropH),paint)

        fun collect(bitmap:Bitmap,done:(List<Evidence>)->Unit) {
            model.process(InputImage.fromBitmap(bitmap,0)).addOnCompleteListener { task ->
                val out=mutableListOf<Evidence>()
                if(task.isSuccessful) {
                    for(block in task.result.textBlocks) for(line in block.lines)
                        for(element in line.elements) {
                            val letters=element.text.trim().uppercase(Locale.US)
                            if(letters.length!=1||letters[0] !in 'A'..'Z') continue
                            val rect=element.boundingBox ?: continue
                            val glyph=Spot(region.left+rect.exactCenterX()/scale,
                                region.top+rect.exactCenterY()/scale)
                            val d=hypot(glyph.x-c.x,glyph.y-c.y)
                            val height=rect.height()/scale
                            if(d !in (r*.24f)..(r*.93f)) continue
                            if(height !in (r*.060f)..(r*.40f)) continue
                            if(rect.width()/scale > r*.38f) continue
                            out.add(Evidence(glyph,letters[0],2.7f))
                        }
                }
                done(out)
            }
        }
        collect(natural) { raw ->
            natural.recycle()
            // A second OCR input: a different contrast mapping, not just an
            // identical retry; its coordinates still map to the original image.
            val contrast=Bitmap.createBitmap(cropW,cropH,Bitmap.Config.ARGB_8888)
            Canvas(contrast).drawBitmap(image,region,Rect(0,0,cropW,cropH),paint)
            val px=IntArray(cropW*cropH)
            contrast.getPixels(px,0,cropW,0,0,cropW,cropH)
            val median=IntArray(256)
            for(i in px.indices step 17) {
                val v=Color.red(px[i])*299/1000+Color.green(px[i])*587/1000+
                    Color.blue(px[i])*114/1000
                median[v]++
            }
            val count=median.sum()/2
            var total=0;var bg=128
            for(i in median.indices){total+=median[i];if(total>=count){bg=i;break}}
            for(i in px.indices) {
                val v=(Color.red(px[i])*299+Color.green(px[i])*587+Color.blue(px[i])*114)/1000
                val dark=if(bg<140) v>min(235,bg+45) else v<max(30,bg-45)
                px[i]=if(dark) Color.BLACK else Color.WHITE
            }
            contrast.setPixels(px,0,cropW,0,0,cropW,cropH)
            collect(contrast) { second ->
                contrast.recycle()
                // Components may be much better at positions than ML Kit:
                // isolate actual bright/dark letter-shaped connected regions.
                Thread {
                    val candidates=components(image,area)
                    val result=fit(area,raw+second,candidates,requestedCount)
                    callback(result)
                }.start()
            }
        }
    }

    private fun components(image:Bitmap,area:Calibration):List<Evidence> {
        val c=area.center;val r=area.radius
        val ratio=(max(1f,r/225f))
        val size=(2f*r/ratio).roundToInt().coerceIn(100,480)
        val crop=IntArray(size*size)
        val left=c.x-r;val top=c.y-r
        for(y in 0 until size) for(x in 0 until size) {
            val xx=(left+(x+.5f)*2*r/size).roundToInt().coerceIn(0,image.width-1)
            val yy=(top+(y+.5f)*2*r/size).roundToInt().coerceIn(0,image.height-1)
            val pixel=image.getPixel(xx,yy)
            crop[y*size+x]=(Color.red(pixel)*299+Color.green(pixel)*587+Color.blue(pixel)*114)/1000
        }
        val ringVals=mutableListOf<Int>()
        for(y in 0 until size step 4) for(x in 0 until size step 4) {
            val dist=hypot(x-size/2f,y-size/2f)/(size/2f)
            if(dist in .30f.. .89f) ringVals.add(crop[y*size+x])
        }
        if(ringVals.isEmpty())return emptyList()
        ringVals.sort()
        val bg=ringVals[ringVals.size/2]
        val thresholds=listOf(max(185,bg+48),min(88,bg-48))
        val result=mutableListOf<Evidence>()
        val scale=2*r/size
        for(polarity in 0..1) {
            val valid=BooleanArray(size*size)
            for(y in 1 until size-1) for(x in 1 until size-1) {
                val dist=hypot(x-size/2f,y-size/2f)/(size/2f)
                if(dist !in .23f.. .93f)continue
                val v=crop[y*size+x]
                valid[y*size+x]=if(polarity==0)v>=thresholds[0] else v<=thresholds[1]
            }
            val seen=BooleanArray(size*size)
            val queue=IntArray(size*size)
            val minH=size*.045f;val maxH=size*.18f
            for(idx in valid.indices) {
                if(!valid[idx]||seen[idx])continue
                var head=0;var tail=1
                queue[0]=idx;seen[idx]=true
                var leftmost=size;var rightmost=0;var upper=size;var lower=0;var mass=0
                while(head<tail) {
                    val cur=queue[head++];val x=cur%size;val y=cur/size
                    mass++;leftmost=min(leftmost,x);rightmost=max(rightmost,x)
                    upper=min(upper,y);lower=max(lower,y)
                    if(mass>size*size/8)break
                    for(dy in -1..1)for(dx in -1..1) {
                        if(dx==0&&dy==0)continue
                        val nx=x+dx;val ny=y+dy
                        if(nx !in 1 until size-1||ny !in 1 until size-1)continue
                        val ni=ny*size+nx
                        if(valid[ni]&&!seen[ni]){seen[ni]=true;queue[tail++]=ni}
                    }
                }
                val gw=rightmost-leftmost+1;val gh=lower-upper+1
                if(gh.toFloat() !in minH..maxH||gw<size*.009f||gw>size*.20f)continue
                if(mass<max(8f,gh*1.3f).toInt()||mass>gw*gh*.88f)continue
                val point=Spot(area.center.x+(leftmost+rightmost-size)*.5f*scale,
                    area.center.y+(upper+lower-size)*.5f*scale)
                val dist=hypot(point.x-c.x,point.y-c.y)
                if(dist !in r*.25f..r*.91f)continue
                result.add(Evidence(point,null,.95f))
            }
        }
        return result
    }

    private fun fit(area:Calibration,raw:List<Evidence>,other:List<Evidence>,
                    requested:Int?):Reading? {
        val all=raw+other
        if(all.size<3)return null
        // Nearby contours from different passes refer to one real glyph.
        val sorted=all.sortedByDescending {it.weight}
        val clusters=mutableListOf<MutableList<Evidence>>()
        for(e in sorted) {
            val cluster=clusters.firstOrNull { list ->
                val p=list[0].at
                hypot(p.x-e.at.x,p.y-e.at.y)<area.radius*.090f
            }
            if(cluster==null)clusters.add(mutableListOf(e)) else cluster.add(e)
        }
        val spots=clusters.map { candidates ->
            val labels=candidates.filter{it.letter!=null}
            val best=labels.groupBy{it.letter}.maxByOrNull{it.value.size}?.key
            val anchor=labels.firstOrNull()
            Evidence(anchor?.at ?: candidates[0].at,best,
                if(labels.isNotEmpty())2.5f else 1f)
        }.filter {
            val d=hypot(it.at.x-area.center.x,it.at.y-area.center.y)
            d in area.radius*.25f..area.radius*.92f
        }.take(90)
        if(spots.size<3)return null

        val minN=WheelDetector.MIN_LETTERS;val maxN=WheelDetector.MAX_LETTERS
        val counts=if(requested!=null) listOf(requested.coerceIn(minN,maxN))
                   else (minN..maxN).toList()
        var best:Match?=null
        val centerShifts=listOf(Spot(0f,0f),Spot(.025f,0f),Spot(-.025f,0f),
            Spot(0f,.025f),Spot(0f,-.025f))
        for(shift in centerShifts) {
            val origin=Spot(area.center.x+shift.x*area.radius,
                area.center.y+shift.y*area.radius)
            for(seed in spots) {
                val radius=hypot(seed.at.x-origin.x,seed.at.y-origin.y)
                if(radius !in area.radius*.30f..area.radius*.88f)continue
                val distanceTolerance=max(9f,radius*.16f)
                for(n in counts) {
                    val angle=Math.toDegrees(atan2((seed.at.y-origin.y).toDouble(),
                        (seed.at.x-origin.x).toDouble())).toFloat()
                    val step=360f/n
                    for(phaseOffset in listOf(-2.5f,0f,2.5f)) {
                        val phase=angle+phaseOffset
                        val remaining=spots.toMutableList()
                        val chosen=ArrayList<Evidence>(n)
                        var ocrHits=0;var error=0f
                        for(j in 0 until n) {
                            val theta=Math.toRadians((phase+j*step).toDouble())
                            val x=origin.x+cos(theta).toFloat()*radius
                            val y=origin.y+sin(theta).toFloat()*radius
                            val target=remaining.minByOrNull {
                                hypot(it.at.x-x,it.at.y-y)/
                                    (if(it.letter!=null)1.12f else 1f)
                            }
                            if(target!=null) {
                                val dist=hypot(target.at.x-x,target.at.y-y)
                                if(dist<=distanceTolerance) {
                                    chosen.add(target)
                                    if(target.letter!=null)ocrHits++
                                    error+=dist/distanceTolerance
                                    remaining.remove(target)
                                }
                            }
                        }
                        val hits=chosen.size
                        if(hits<3||hits<n-1)continue
                        if(ocrHits<2&&hits<n)continue
                        // Reject accidentally fitting every second position of a denser ring.
                        val inBand=spots.count{
                            abs(hypot(it.at.x-origin.x,it.at.y-origin.y)-radius)<
                                distanceTolerance
                        }
                        val coverage=hits.toFloat()/n
                        val score=coverage*4.4f +ocrHits.toFloat()/n*1.8f -
                            error/n*.8f -(n-hits)*.2f -
                            max(0,inBand-hits)*.20f
                        if(score>(best?.score ?: Float.NEGATIVE_INFINITY)) {
                            best=Match(chosen,n,origin,radius,score,coverage)
                        }
                    }
                }
            }
        }
        val hit=best ?:return null
        val available=hit.points
        // We do NOT invent coordinates for missing letters. An incomplete set
        // goes to review/rescan, rather than swiping arbitrary screen locations.
        if(available.size!=hit.n)return null
        val ordered=available.sortedBy{
            (atan2(it.at.y-hit.origin.y,it.at.x-hit.origin.x)+2*PI+PI/2)%(2*PI)
        }
        val anchors=ordered.count{it.letter!=null}
        val shapeAgreement=(hit.score/5f).coerceIn(0f,1f)
        val confidence=(.50f*hit.coverage+.40f*shapeAgreement+
            .10f*anchors/hit.n.toFloat()).coerceIn(0f,1f)
        return Reading(WheelResult(ordered.map{it.at},confidence,hit.n,
            hit.origin,hit.radius),ordered.map{it.letter},anchors,spots.size)
    }
}
