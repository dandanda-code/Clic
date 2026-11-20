package com.clic.app

import android.content.Context

/** Configuración guardada en SharedPreferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("clic", Context.MODE_PRIVATE)

    var tapX: Int
        get() = prefs.getInt("tapX", -1)
        set(v) = prefs.edit().putInt("tapX", v).apply()

    var tapY: Int
        get() = prefs.getInt("tapY", -1)
        set(v) = prefs.edit().putInt("tapY", v).apply()

    /** 0..100, más alto = detecta destellos más débiles. */
    var sensitivity: Int
        get() = prefs.getInt("sensitivity", 60)
        set(v) = prefs.edit().putInt("sensitivity", v).apply()

    /** Tiempo mínimo entre clics, en décimas de segundo. */
    var cooldownTenths: Int
        get() = prefs.getInt("cooldown", 5)
        set(v) = prefs.edit().putInt("cooldown", v).apply()

    /** Diferencia de brillo (0..255) necesaria para considerar un destello. */
    val brightnessThreshold: Float
        get() = 5f + (100 - sensitivity) * 1.5f   // 5 (muy sensible) .. 155 (poco sensible)

    val cooldownMs: Long
        get() = cooldownTenths.coerceAtLeast(1) * 100L
}
