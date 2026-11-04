package com.sharfo.myplans.feature.cad.domain.model

import kotlin.math.atan2
import kotlin.math.hypot

data class Point2D(val x: Double, val y: Double) {
    operator fun plus(other: Point2D) = Point2D(x + other.x, y + other.y)
    operator fun minus(other: Point2D) = Point2D(x - other.x, y - other.y)
    operator fun times(scalar: Double) = Point2D(x * scalar, y * scalar)
    
    fun distanceTo(other: Point2D): Double = hypot(other.x - x, other.y - y)
    
    fun angleTo(other: Point2D): Double {
        return atan2(other.y - y, other.x - x) * (180.0 / Math.PI)
    }
}

enum class MeasurementUnit(val symbol: String, val scaleToMeters: Double) {
    METERS("م", 1.0),
    CENTIMETERS("سم", 0.01),
    MILLIMETERS("مم", 0.001)
}
