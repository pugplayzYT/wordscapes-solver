package com.pugplayz.wordscapesauto

import android.content.Context

data class Calibration(val center: Spot, val radius: Float, val countOverride: Int?)
object Settings {
    private const val PREF = "wordscapes.settings"
    fun save(ctx: Context, cal: Calibration) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .edit().putFloat("cx", cal.center.x).putFloat("cy", cal.center.y)
        .putFloat("radius", cal.radius).putInt("n", cal.countOverride ?: 0).apply()

    fun load(ctx: Context): Calibration? {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (!p.contains("cx")) return null
        return Calibration(Spot(p.getFloat("cx", 0f), p.getFloat("cy", 0f)),
            p.getFloat("radius", 160f), p.getInt("n", 0).takeIf { it in 3..8 })
    }
}
