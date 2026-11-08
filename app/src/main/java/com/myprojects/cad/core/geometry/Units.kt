package com.myprojects.cad.core.geometry

import java.util.Locale

/** Formatting helpers. Always Latin digits (Locale.US) for engineering output. */
object Units {
    fun formatLength(meters: Double): String = String.format(Locale.US, "%.2f m", meters)
    fun formatCoord(meters: Double): String = String.format(Locale.US, "%.3f", meters)
    fun formatAngle(deg: Double): String = String.format(Locale.US, "%.1f°", deg)
}
