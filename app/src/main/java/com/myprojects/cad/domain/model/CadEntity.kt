package com.myprojects.cad.domain.model

import com.myprojects.cad.core.geometry.Geo
import com.myprojects.cad.core.geometry.Vec2
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

object Layers {
    const val DEFAULT = "0"
    const val WALLS = "WALLS"
    const val DIM = "DIM"
    const val TEXT = "TEXT"
}

/** Every drawable object. All coordinates are meters (Double), Y up, angles in degrees CCW. */
sealed interface CadEntity {
    val id: String
    val layer: String
}

data class LineEntity(
    override val id: String = newId(),
    val start: Vec2,
    val end: Vec2,
    override val layer: String = Layers.DEFAULT
) : CadEntity

data class PolylineEntity(
    override val id: String = newId(),
    val points: List<Vec2>,
    val closed: Boolean = false,
    override val layer: String = Layers.DEFAULT
) : CadEntity

data class CircleEntity(
    override val id: String = newId(),
    val center: Vec2,
    val radius: Double,
    override val layer: String = Layers.DEFAULT
) : CadEntity

/** Arc from [startDeg] to [endDeg], counter-clockwise (same as DXF). */
data class ArcEntity(
    override val id: String = newId(),
    val center: Vec2,
    val radius: Double,
    val startDeg: Double,
    val endDeg: Double,
    override val layer: String = Layers.DEFAULT
) : CadEntity {
    val sweepDeg: Double get() = Geo.sweepDeg(startDeg, endDeg)
    fun pointAt(deg: Double): Vec2 = center + Vec2.fromPolar(radius, Math.toRadians(deg))
    val startPoint: Vec2 get() = pointAt(startDeg)
    val endPoint: Vec2 get() = pointAt(endDeg)
    val midPoint: Vec2 get() = pointAt(startDeg + sweepDeg / 2.0)
}

data class TextEntity(
    override val id: String = newId(),
    val position: Vec2,
    val text: String,
    val heightM: Double = 0.25,
    val rotationDeg: Double = 0.0,
    override val layer: String = Layers.TEXT
) : CadEntity

/** Aligned dimension between [p1] and [p2]; [offset] is the signed distance of the dimension line to the left of p1->p2. */
data class DimensionEntity(
    override val id: String = newId(),
    val p1: Vec2,
    val p2: Vec2,
    val offset: Double,
    override val layer: String = Layers.DIM
) : CadEntity
