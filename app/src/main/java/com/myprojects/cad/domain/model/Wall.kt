package com.myprojects.cad.domain.model

import com.myprojects.cad.core.geometry.Geo
import com.myprojects.cad.core.geometry.Vec2

/** A straight wall. Coordinates in meters (Double). Thickness in meters (0.20 = 20 cm). */
data class Wall(
    override val id: String = newId(),
    val start: Vec2,
    val end: Vec2,
    val thicknessM: Double,
    override val layer: String = Layers.WALLS
) : CadEntity {
    val length: Double get() = start.distanceTo(end)
    val midpoint: Vec2 get() = start.midpoint(end)

    /** CCW from +X, 0..360. */
    val angleDeg: Double get() = Geo.normDeg(Geo.angleDeg(start, end))

    /** The four outline corners (start+n, end+n, end-n, start-n). */
    fun corners(): List<Vec2> {
        val n = (end - start).normalized().perpLeft() * (thicknessM / 2.0)
        return listOf(start + n, end + n, end - n, start - n)
    }
}

object WallThickness {
    val STANDARD_CM: List<Int> = listOf(10, 15, 20, 25)
    const val DEFAULT_CM = 20
}
