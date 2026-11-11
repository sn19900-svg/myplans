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
    const val COLUMNS = "COLUMNS"
}

/** Every drawable object. All coordinates are meters (Double), Y up, angles in degrees CCW. */
sealed interface CadEntity {
    val id: String
    val layer: String

    /** User-given mark such as "B1" or "W3". Empty when unnamed. */
    val name: String
}

data class LineEntity(
    override val id: String = newId(),
    val start: Vec2,
    val end: Vec2,
    override val layer: String = Layers.DEFAULT,
    override val name: String = ""
) : CadEntity

data class PolylineEntity(
    override val id: String = newId(),
    val points: List<Vec2>,
    val closed: Boolean = false,
    override val layer: String = Layers.DEFAULT,
    override val name: String = ""
) : CadEntity

data class CircleEntity(
    override val id: String = newId(),
    val center: Vec2,
    val radius: Double,
    override val layer: String = Layers.DEFAULT,
    override val name: String = ""
) : CadEntity

/** Arc from [startDeg] to [endDeg], counter-clockwise (same as DXF). */
data class ArcEntity(
    override val id: String = newId(),
    val center: Vec2,
    val radius: Double,
    val startDeg: Double,
    val endDeg: Double,
    override val layer: String = Layers.DEFAULT,
    override val name: String = ""
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
    override val layer: String = Layers.TEXT,
    override val name: String = ""
) : CadEntity

/** Aligned dimension between [p1] and [p2]; [offset] is the signed distance of the dimension line to the left of p1->p2. */
data class DimensionEntity(
    override val id: String = newId(),
    val p1: Vec2,
    val p2: Vec2,
    val offset: Double,
    override val layer: String = Layers.DIM,
    override val name: String = ""
) : CadEntity

enum class ColumnShape(val label: String) {
    RECTANGULAR("Rectangular / square"),
    CIRCULAR("Circular")
}

/**
 * A column in plan. [widthM] is the size along the column's local x axis, [depthM] along local y
 * (for circular columns [widthM] is the diameter). [rotationDeg] turns the local axes CCW.
 */
data class ColumnEntity(
    override val id: String = newId(),
    val center: Vec2,
    val widthM: Double,
    val depthM: Double,
    val shape: ColumnShape = ColumnShape.RECTANGULAR,
    val rotationDeg: Double = 0.0,
    override val layer: String = Layers.COLUMNS,
    override val name: String = ""
) : CadEntity {
    val radiusM: Double get() = widthM / 2.0

    /** Four plan corners (rectangular columns). */
    fun corners(): List<Vec2> {
        val hx = widthM / 2.0
        val hy = depthM / 2.0
        val rad = Math.toRadians(rotationDeg)
        return listOf(Vec2(-hx, -hy), Vec2(hx, -hy), Vec2(hx, hy), Vec2(-hx, hy)).map { center + it.rotated(rad) }
    }

    fun contains(p: Vec2): Boolean {
        if (shape == ColumnShape.CIRCULAR) return p.distanceTo(center) <= radiusM
        val local = (p - center).rotated(-Math.toRadians(rotationDeg))
        return kotlin.math.abs(local.x) <= widthM / 2.0 && kotlin.math.abs(local.y) <= depthM / 2.0
    }
}
