package com.tunelag.app

import android.content.Context
import android.content.SharedPreferences

/** Preferencias persistentes. Un solo sitio para todas las claves. */
object AppSettings {

    private const val FILE = "tunelag"
    const val KEY_PROFILE = "profile"
    const val KEY_AUTO = "auto"
    const val KEY_BIDI = "bidi"
    const val KEY_BUBBLE = "bubble"

    fun get(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 0 suave, 1 medio, 2 agresivo. Techo duro de 800 ms. */
    fun burstRange(ctx: Context): IntRange = when (get(ctx).getInt(KEY_PROFILE, 1)) {
        0 -> 250..400
        2 -> 600..800
        else -> 400..600
    }
}
