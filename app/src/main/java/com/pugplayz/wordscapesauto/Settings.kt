package com.pugplayz.wordscapesauto

import android.content.Context
import android.graphics.RectF
import kotlin.math.*

/** The only saved calibration is a rectangle containing every possible wheel letter. */
data class Calibration(val bounds: RectF) {
    val center: Spot get() = Spot(bounds.centerX(), bounds.centerY())
    val radius: Float get() = min(bounds.width(), bounds.height()) * .40f
}

object Settings {
    private const val PREF = "wordscapes.settings"

    fun save(ctx: Context, cal: Calibration, imageWidth: Int, imageHeight: Int) {
        val w = imageWidth.coerceAtLeast(1).toFloat()
        val h = imageHeight.coerceAtLeast(1).toFloat()
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean("box_calibrated", true)
            .putFloat("box_l", cal.bounds.left / w)
            .putFloat("box_t", cal.bounds.top / h)
            .putFloat("box_r", cal.bounds.right / w)
            .putFloat("box_b", cal.bounds.bottom / h)
            .remove("n")
            .apply()
    }

    fun hasCalibration(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        // Older v1.3 center + radius settings migrate automatically.
        return p.getBoolean("box_calibrated", false) ||
            p.contains("center_x_ratio") || p.contains("cx")
    }

    fun load(ctx: Context, imageWidth: Int, imageHeight: Int): Calibration? {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val w = imageWidth.toFloat(); val h = imageHeight.toFloat()
        if (w <= 0f || h <= 0f) return null
        val box = if (p.getBoolean("box_calibrated", false)) {
            RectF(p.getFloat("box_l", 0f) * w, p.getFloat("box_t", 0f) * h,
                p.getFloat("box_r", 1f) * w, p.getFloat("box_b", 1f) * h)
        } else if (p.contains("center_x_ratio")) {
            val cx = p.getFloat("center_x_ratio", .5f) * w
            val cy = p.getFloat("center_y_ratio", .7f) * h
            val r = p.getFloat("radius_ratio", .2f) * min(w,h)
            // Migration estimate only. User can replace it with a box using CAL.
            RectF(cx - r * 1.35f, cy - r * 1.35f,
                  cx + r * 1.35f, cy + r * 1.35f)
        } else if (p.contains("cx")) {
            val cx = p.getFloat("cx", .5f*w)
            val cy = p.getFloat("cy", .7f*h)
            val r = p.getFloat("radius", 150f)
            RectF(cx-r*1.35f,cy-r*1.35f,cx+r*1.35f,cy+r*1.35f)
        } else return null

        val safe = RectF(
            box.left.coerceIn(0f,w), box.top.coerceIn(0f,h),
            box.right.coerceIn(0f,w), box.bottom.coerceIn(0f,h))
        return if (safe.width() >= 90f && safe.height() >= 90f) Calibration(safe) else null
    }
}
