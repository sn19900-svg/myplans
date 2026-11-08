package com.myprojects.cad.domain.model

import com.myprojects.cad.core.engine.SnapTargets
import com.myprojects.cad.core.geometry.Vec2
import java.util.UUID
import kotlin.math.atan2

/** A straight wall. Coordinates in meters (Double). Thickness in meters (0.20 = 20 cm). */
data class Wall(
    val id: String = UUID.randomUUID().toString(),
    val start: Vec2,
    val end: Vec2,
    val thicknessM: Double
) {
    val length: Double get() = start.distanceTo(end)
    val midpoint: Vec2 get() = start.midpoint(end)

    /** CCW from +X, 0..360. */
    val angleDeg: Double
        get() {
            val d = Math.toDegrees(atan2(end.y - start.y, end.x - start.x))
            return if (d < 0.0) d + 360.0 else d
        }
}

object WallThickness {
    val STANDARD_CM: List<Int> = listOf(10, 15, 20, 25)
    const val DEFAULT_CM = 20
}

fun List<Wall>.toSnapTargets() = SnapTargets(
    endpoints = flatMap { listOf(it.start, it.end) },
    midpoints = map { it.midpoint }
)
