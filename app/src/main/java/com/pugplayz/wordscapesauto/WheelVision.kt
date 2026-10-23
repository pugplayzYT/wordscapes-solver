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
        val identified: Int, val visible: Int, val recoveredSlots: Int)
    private data class Evidence(val at: Spot, val letter: Char?, val weight: Float)
    private data class Match(val slots: List<Evidence?>, val n: Int,
        val origin: Spot, val radius: Float, val score: Float, val coverage: Float,
        val missingAngle: Float?)

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
                    // ML Kit groups nearby glyphs into entire words.
                    // OLD BUG: element.text.length != 1 discarded whole
                    // groups, even when every Symbol had a valid A-Z box.
                    // Extract individual Symbol bounding boxes instead.
                    fun addGlyph(text:String,rect:Rect?) {
                        val value=text.trim().uppercase(Locale.US)
                            .replace('0','O')
                        if(value.length!=1||value[0] !in 'A'..'Z')return
                        val b=rect ?:return
                        val glyph=Spot(region.left+b.exactCenterX()/scale,
                            region.top+b.exactCenterY()/scale)
                        val d=hypot(glyph.x-c.x,glyph.y-c.y)
                        val height=b.height()/scale
                        if(d !in (r*.20f)..(r*.97f))return
                        if(height !in (r*.045f)..(r*.48f))return
                        if(b.width()/scale>r*.45f)return
                        out.add(Evidence(glyph,value[0],2.7f))
                    }
                    for(block in task.result.textBlocks)for(line in block.lines)
                        for(element in line.elements) {
                            val symbols=element.symbols
                            if(symbols.isNotEmpty()) {
                                for(symbol in symbols) {
                                    addGlyph(symbol.text,symbol.boundingBox)
                                }
                            } else {
                                addGlyph(element.text,element.boundingBox)
                            }
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
                    val observations=raw+second
                    val fitted=fit(image,area,observations,candidates,requestedCount)
                    // The two full-frame OCR passes are useful hints, not
                    // hard prerequisites for ever trying per-letter OCR.
                    val result=fitted ?: RadialFallback.estimate(
                        image,area,requestedCount,observations.map{it.at}
                    )?.let { estimated ->
                        Reading(estimated.wheel,
                            List(estimated.wheel.inferredCount){null},
                            estimated.anchors,candidates.size,
                            estimated.wheel.inferredCount)
                    }
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

    private fun fit(image: Bitmap, area:Calibration,raw:List<Evidence>,other:List<Evidence>,
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
                        // Keep the exact angular slot index even if the OCR/
                        // contour detector misses one glyph. Previously any
                        // four-of-five wheel was rejected unconditionally.
                        val slots=MutableList<Evidence?>(n){null}
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
                                    slots[j]=target
                                    if(target.letter!=null)ocrHits++
                                    error+=dist/distanceTolerance
                                    remaining.remove(target)
                                }
                            }
                        }
                        val hits=slots.count { it != null }
                        val missing=n-hits
                        if(hits<3 || missing>1 || (n<4 && missing>0))continue
                        val missingAngle=slots.indexOfFirst { it==null }
                            .takeIf{it>=0}?.let {phase+it*step}
                        // A missing center is considered only where the
                        // *screenshot* contains real contrasting glyph ink.
                        // It must also be read by the individual OCR stage.
                        if(missingAngle!=null) {
                            val theta=Math.toRadians(missingAngle.toDouble())
                            val proposed=Spot(origin.x+cos(theta).toFloat()*radius,
                                origin.y+sin(theta).toFloat()*radius)
                            if(!hasVisibleGlyph(image,proposed,radius)) continue
                        }
                        // Prioritise the number of real observed characters.
                        // A four-of-five match beats an accidental three-of-
                        // three pattern, but a clean complete fit is preferred.
                        val inBand=spots.count{
                            abs(hypot(it.at.x-origin.x,it.at.y-origin.y)-radius)<
                                distanceTolerance
                        }
                        val coverage=hits.toFloat()/n
                        val score=hits*1.45f + coverage*.85f +
                            ocrHits*.29f - error*.50f -
                            missing*.45f - max(0,inBand-hits)*.21f
                        if(score>(best?.score ?: Float.NEGATIVE_INFINITY)) {
                            best=Match(slots,n,origin,radius,score,coverage,missingAngle)
                        }
                    }
                }
            }
        }
        val hit=best ?:return null
        val recovered=hit.slots.count { it==null }
        val points=hit.slots.mapIndexed { j, actual ->
            actual ?: run {
                val angle=Math.toRadians(
                    (hit.missingAngle ?: (j*360f/hit.n)).toDouble())
                Evidence(Spot(hit.origin.x+cos(angle).toFloat()*hit.radius,
                    hit.origin.y+sin(angle).toFloat()*hit.radius),null,0f)
            }
        }
        // The estimated missing point is a candidate for *independent*
        // per-letter OCR, not a claimed visual recognition.
        val ordered=points.sortedBy {
            (atan2(it.at.y-hit.origin.y,it.at.x-hit.origin.x)+2*PI+PI/2)%(2*PI)
        }
        val anchors=ordered.count{it.letter!=null}
        val shapeAgreement=(hit.score/(hit.n*1.9f)).coerceIn(0f,1f)
        val confidence=(.43f*hit.coverage+.42f*shapeAgreement+
            .15f*anchors/hit.n.toFloat()-recovered*.16f).coerceIn(0f,1f)
        return Reading(WheelResult(ordered.map{it.at},confidence,hit.n,
            hit.origin,hit.radius),ordered.map{it.letter},anchors,spots.size,recovered)
    }

    /** Does the missing predicted slot contain a contrasting letter-shaped mark? */
    private fun hasVisibleGlyph(image:Bitmap,at:Spot,ringRadius:Float):Boolean {
        val half=(ringRadius*.23f).roundToInt().coerceAtLeast(13)
        val x0=(at.x-half).roundToInt().coerceIn(0,image.width-1)
        val y0=(at.y-half).roundToInt().coerceIn(0,image.height-1)
        val x1=(at.x+half).roundToInt().coerceIn(x0+1,image.width)
        val y1=(at.y+half).roundToInt().coerceIn(y0+1,image.height)
        if(x1-x0<16||y1-y0<16)return false
        fun luminance(x:Int,y:Int):Int {
            val p=image.getPixel(x,y)
            return (Color.red(p)*299+Color.green(p)*587+Color.blue(p)*114)/1000
        }
        val border=mutableListOf<Int>()
        for(x in x0 until x1 step 3) {
            border.add(luminance(x,y0))
            border.add(luminance(x,y1-1))
        }
        for(y in y0 until y1 step 3) {
            border.add(luminance(x0,y))
            border.add(luminance(x1-1,y))
        }
        border.sort()
        val background=border[border.size/2]
        var total=0;var distinct=0
        val inset=max(2,(half*.25f).roundToInt())
        for(y in (y0+inset) until (y1-inset) step 2)
            for(x in (x0+inset) until (x1-inset) step 2) {
                total++
                if(abs(luminance(x,y)-background)>48)distinct++
            }
        val share=if(total>0)distinct.toFloat()/total else 0f
        return share in .035f.. .78f
    }
}
