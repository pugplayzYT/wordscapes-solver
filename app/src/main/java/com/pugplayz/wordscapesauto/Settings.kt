package com.pugplayz.wordscapesauto

import android.content.Context
import kotlin.math.min

/** Only the ring geometry is saved; letter count is recalculated every puzzle. */
data class Calibration(val center: Spot, val radius: Float)

object Settings {
    private const val PREF = "wordscapes.settings"

    fun save(ctx: Context, cal: Calibration, imageWidth: Int, imageHeight: Int) {
        val minDim = min(imageWidth, imageHeight).coerceAtLeast(1).toFloat()
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putFloat("center_x_ratio", cal.center.x / imageWidth.coerceAtLeast(1))
            .putFloat("center_y_ratio", cal.center.y / imageHeight.coerceAtLeast(1))
            .putFloat("radius_ratio", cal.radius / minDim)
            .putFloat("cx", cal.center.x).putFloat("cy", cal.center.y)
            .putFloat("radius", cal.radius)
            .remove("n")
            .apply()
    }

    fun hasCalibration(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).contains("cx")

    fun load(ctx: Context, imageWidth: Int, imageHeight: Int): Calibration? {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (!p.contains("cx") && !p.contains("center_x_ratio")) return null
        val minDim = min(imageWidth, imageHeight).coerceAtLeast(1).toFloat()
        val cal = if (p.contains("center_x_ratio") && p.contains("center_y_ratio") && p.contains("radius_ratio")) {
            Calibration(
                Spot(p.getFloat("center_x_ratio", .5f) * imageWidth,
                     p.getFloat("center_y_ratio", .7f) * imageHeight),
                p.getFloat("radius_ratio", .2f) * minDim
            )
        } else {
            // Upgrade-safe fallback for existing installations.
            Calibration(Spot(p.getFloat("cx", 0f), p.getFloat("cy", 0f)), p.getFloat("radius", 160f))
        }
        return cal.takeIf {
            it.radius >= 35f && it.center.x in 0f..imageWidth.toFloat() &&
                it.center.y in 0f..imageHeight.toFloat()
        }
    }
}
