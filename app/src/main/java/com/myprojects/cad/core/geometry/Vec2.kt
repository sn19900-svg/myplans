package com.myprojects.cad.core.geometry

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Immutable 2D vector. World units are METERS, Y axis points UP. All math is Double. */
data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(k: Double) = Vec2(x * k, y * k)
    operator fun div(k: Double) = Vec2(x / k, y / k)

    val length: Double get() = hypot(x, y)

    fun dot(o: Vec2): Double = x * o.x + y * o.y
    fun cross(o: Vec2): Double = x * o.y - y * o.x
    fun distanceTo(o: Vec2): Double = (this - o).length
    fun midpoint(o: Vec2) = Vec2((x + o.x) / 2.0, (y + o.y) / 2.0)
    fun perpLeft() = Vec2(-y, x)
    fun angleRad(): Double = atan2(y, x)

    fun rotated(rad: Double): Vec2 {
        val c = cos(rad)
        val s = sin(rad)
        return Vec2(x * c - y * s, x * s + y * c)
    }

    fun normalized(): Vec2 {
        val l = length
        return if (l < EPS) ZERO else Vec2(x / l, y / l)
    }

    companion object {
        const val EPS = 1e-12
        val ZERO = Vec2(0.0, 0.0)

        /** Angle is CCW from +X (east), in radians. Float noise like 6e-17 is cleaned to 0. */
        fun fromPolar(length: Double, angleRad: Double): Vec2 =
            Vec2(clean(length * cos(angleRad)), clean(length * sin(angleRad)))

        private fun clean(v: Double) = if (abs(v) < EPS) 0.0 else v
    }
}
