package com.pugplayz.wordscapesauto

import android.content.Context
import kotlin.math.min

/**
 * The saved radius is the OUTER SEARCH BOUNDARY, not the distance of letters
 * from the center. Ring layout is estimated from fresh OCR boxes every scan.
 */
data class Calibration(val center: Spot, val radius: Float)

object Settings {
    private const val PREF = "wordscapes.settings"

    fun save(ctx: Context, cal: Calibration, width: Int, height: Int) {
        val w = width.coerceAtLeast(1).toFloat()
        val h = height.coerceAtLeast(1).toFloat()
        val minDim = min(w,h)
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putFloat("search_cx", cal.center.x / w)
            .putFloat("search_cy", cal.center.y / h)
            .putFloat("search_r", cal.radius / minDim)
            .putBoolean("circle_calibrated", true)
            .remove("box_calibrated")
            .apply()
    }

    fun hasCalibration(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return p.getBoolean("circle_calibrated",false) ||
            p.contains("box_l") || p.contains("center_x_ratio") || p.contains("cx")
    }

    fun load(ctx: Context, width: Int, height: Int): Calibration? {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val w = width.toFloat(); val h = height.toFloat()
        if (w < 90f || h < 90f) return null
        val m = min(w,h)
        val c = when {
            p.getBoolean("circle_calibrated",false) -> Calibration(
                Spot(p.getFloat("search_cx",.5f)*w, p.getFloat("search_cy",.75f)*h),
                p.getFloat("search_r",.22f)*m
            )
            p.getBoolean("box_calibrated",false) -> {
                val l=p.getFloat("box_l",0f)*w
                val t=p.getFloat("box_t",0f)*h
                val r=p.getFloat("box_r",1f)*w
                val b=p.getFloat("box_b",1f)*h
                Calibration(Spot((l+r)/2f,(t+b)/2f),min(r-l,b-t)*.5f)
            }
            p.contains("center_x_ratio") -> Calibration(
                Spot(p.getFloat("center_x_ratio",.5f)*w,p.getFloat("center_y_ratio",.75f)*h),
                p.getFloat("radius_ratio",.2f)*m*1.3f
            )
            p.contains("cx") -> Calibration(
                Spot(p.getFloat("cx",w*.5f),p.getFloat("cy",h*.75f)),
                p.getFloat("radius",150f)*1.3f
            )
            else -> return null
        }
        return c.takeIf {
            it.radius >= 45f && it.radius <= m*.75f &&
            it.center.x in 0f..w && it.center.y in 0f..h
        }
    }
}
