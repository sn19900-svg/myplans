package com.myprojects.cad.core.engine

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Picks "nice" 1-2-5 grid/ruler steps (in meters) for the current zoom level. */
object ScaleSteps {
    data class Step(val major: Double, val subdivisions: Int) {
        val minor: Double get() = major / subdivisions
    }

    fun forScale(pixelsPerMeter: Double, targetPx: Double = 90.0): Step {
        val raw = targetPx / pixelsPerMeter
        val base = 10.0.pow(floor(log10(raw)))
        val f = raw / base
        return when {
            f <= 1.0 -> Step(base, 10)
            f <= 2.0 -> Step(2.0 * base, 4)
            f <= 5.0 -> Step(5.0 * base, 5)
            else -> Step(10.0 * base, 10)
        }
    }

    fun minorStep(pixelsPerMeter: Double): Double = forScale(pixelsPerMeter).minor
}
