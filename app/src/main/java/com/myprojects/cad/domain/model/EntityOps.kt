package com.myprojects.cad.domain.model

import com.myprojects.cad.core.engine.Segment
import com.myprojects.cad.core.engine.SnapTargets
import com.myprojects.cad.core.engine.WorldRect
import com.myprojects.cad.core.geometry.Geo
import com.myprojects.cad.core.geometry.Vec2
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// ---- Transforms (move / rotate / mirror) ---------------------------------------------------------

sealed interface Xform {
    fun point(p: Vec2): Vec2
    fun angle(deg: Double): Double
    val mirrored: Boolean

    data class Translate(val d: Vec2) : Xform {
        override fun point(p: Vec2): Vec2 = p + d
        override fun angle(deg: Double): Double = deg
        override val mirrored: Boolean get() = false
    }

    data class Rotate(val c: Vec2, val deg: Double) : Xform {
        override fun point(p: Vec2): Vec2 = c + (p - c).rotated(Math.toRadians(deg))
        override fun angle(deg: Double): Double = deg + this.deg
        override val mirrored: Boolean get() = false
    }

    data class Mirror(val a: Vec2, val b: Vec2) : Xform {
        private val dir: Vec2 = (b - a).normalized()
        override fun point(p: Vec2): Vec2 {
            val v = p - a
            val proj = dir * v.dot(dir)
            return a + proj * 2.0 - v
        }
        override fun angle(deg: Double): Double = 2.0 * Math.toDegrees(dir.angleRad()) - deg
        override val mirrored: Boolean get() = true
    }
}

fun CadEntity.withId(newId: String): CadEntity = when (this) {
    is Wall -> copy(id = newId)
    is LineEntity -> copy(id = newId)
    is PolylineEntity -> copy(id = newId)
    is CircleEntity -> copy(id = newId)
    is ArcEntity -> copy(id = newId)
    is TextEntity -> copy(id = newId)
    is DimensionEntity -> copy(id = newId)
}

fun CadEntity.transformed(x: Xform, keepId: Boolean = true): CadEntity {
    val moved: CadEntity = when (this) {
        is Wall -> copy(start = x.point(start), end = x.point(end))
        is LineEntity -> copy(start = x.point(start), end = x.point(end))
        is PolylineEntity -> copy(points = points.map { x.point(it) })
        is CircleEntity -> copy(center = x.point(center))
        is ArcEntity ->
            if (x.mirrored) {
                copy(
                    center = x.point(center),
                    startDeg = Geo.normDeg(x.angle(endDeg)),
                    endDeg = Geo.normDeg(x.angle(startDeg))
                )
            } else {
                copy(
                    center = x.point(center),
                    startDeg = Geo.normDeg(x.angle(startDeg)),
                    endDeg = Geo.normDeg(x.angle(endDeg))
                )
            }
        is TextEntity -> copy(
            position = x.point(position),
            rotationDeg = if (x.mirrored) rotationDeg else x.angle(rotationDeg)
        )
        is DimensionEntity -> copy(
            p1 = x.point(p1),
            p2 = x.point(p2),
            offset = if (x.mirrored) -offset else offset
        )
    }
    return if (keepId) moved else moved.withId(newId())
}

// ---- Construction helpers ------------------------------------------------------------------------

fun rectPolyline(a: Vec2, b: Vec2): PolylineEntity =
    PolylineEntity(points = listOf(Vec2(a.x, a.y), Vec2(b.x, a.y), Vec2(b.x, b.y), Vec2(a.x, b.y)), closed = true)

/** Arc through three points: start, a point on the arc, and end. Null if collinear. */
fun arcFrom3(start: Vec2, mid: Vec2, end: Vec2): ArcEntity? {
    val fit = Geo.circumcircle(start, mid, end) ?: return null
    val ccw = (mid - start).cross(end - mid) > 0.0
    val a1 = Geo.normDeg(Geo.angleDeg(fit.center, start))
    val a2 = Geo.normDeg(Geo.angleDeg(fit.center, end))
    return if (ccw) {
        ArcEntity(center = fit.center, radius = fit.radius, startDeg = a1, endDeg = a2)
    } else {
        ArcEntity(center = fit.center, radius = fit.radius, startDeg = a2, endDeg = a1)
    }
}

fun dimensionOffset(p1: Vec2, p2: Vec2, through: Vec2): Double {
    val n = (p2 - p1).normalized().perpLeft()
    return (through - p1).dot(n)
}

fun PolylineEntity.segments(): List<Segment> {
    if (points.size < 2) return emptyList()
    val list = ArrayList<Segment>()
    for (i in 0 until points.size - 1) list.add(Segment(points[i], points[i + 1]))
    if (closed && points.size > 2) list.add(Segment(points.last(), points.first()))
    return list
}

data class DimGeometry(
    val p1: Vec2,
    val p2: Vec2,
    val d1: Vec2,
    val d2: Vec2,
    val e1: Vec2,
    val e2: Vec2,
    val angleDeg: Double,
    val length: Double
)

fun DimensionEntity.geometry(): DimGeometry {
    val dir = (p2 - p1).normalized()
    val n = dir.perpLeft()
    val d1 = p1 + n * offset
    val d2 = p2 + n * offset
    val ext = if (offset >= 0.0) 0.1 else -0.1
    return DimGeometry(
        p1 = p1, p2 = p2, d1 = d1, d2 = d2,
        e1 = d1 + n * ext, e2 = d2 + n * ext,
        angleDeg = Geo.angleDeg(p1, p2),
        length = p1.distanceTo(p2)
    )
}

// ---- Hit testing & bounds --------------------------------------------------------------------------

private fun textWidth(t: TextEntity): Double = max(1, t.text.length) * t.heightM * 0.6

fun CadEntity.distanceTo(p: Vec2): Double = when (this) {
    is Wall -> (Geo.distPointSegment(p, start, end) - thicknessM / 2.0).coerceAtLeast(0.0)
    is LineEntity -> Geo.distPointSegment(p, start, end)
    is PolylineEntity -> segments().minOfOrNull { Geo.distPointSegment(p, it.a, it.b) } ?: Double.MAX_VALUE
    is CircleEntity -> abs(p.distanceTo(center) - radius)
    is ArcEntity -> {
        val ang = Geo.normDeg(Geo.angleDeg(center, p))
        if (Geo.inArc(ang, startDeg, endDeg)) abs(p.distanceTo(center) - radius)
        else min(p.distanceTo(startPoint), p.distanceTo(endPoint))
    }
    is TextEntity -> {
        val w = textWidth(this)
        val dx = max(max(position.x - p.x, 0.0), p.x - (position.x + w))
        val dy = max(max(position.y - p.y, 0.0), p.y - (position.y + heightM))
        hypot(dx, dy)
    }
    is DimensionEntity -> {
        val g = geometry()
        min(
            Geo.distPointSegment(p, g.d1, g.d2),
            min(Geo.distPointSegment(p, g.p1, g.e1), Geo.distPointSegment(p, g.p2, g.e2))
        )
    }
}

fun CadEntity.extentPoints(): List<Vec2> = when (this) {
    is Wall -> corners()
    is LineEntity -> listOf(start, end)
    is PolylineEntity -> points
    is CircleEntity -> listOf(
        Vec2(center.x - radius, center.y - radius), Vec2(center.x + radius, center.y + radius)
    )
    is ArcEntity -> {
        val steps = 24
        (0..steps).map { pointAt(startDeg + sweepDeg * it / steps) }
    }
    is TextEntity -> listOf(position, Vec2(position.x + textWidth(this), position.y + heightM))
    is DimensionEntity -> {
        val g = geometry()
        listOf(g.p1, g.p2, g.e1, g.e2)
    }
}

fun List<CadEntity>.worldBounds(): WorldRect? {
    var minX = Double.MAX_VALUE
    var minY = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE
    var maxY = -Double.MAX_VALUE
    var any = false
    for (e in this) {
        for (p in e.extentPoints()) {
            any = true
            minX = min(minX, p.x); maxX = max(maxX, p.x)
            minY = min(minY, p.y); maxY = max(maxY, p.y)
        }
    }
    return if (any) WorldRect(minX, minY, maxX, maxY) else null
}

// ---- Snapping ---------------------------------------------------------------------------------------

fun List<CadEntity>.toSnapTargets(): SnapTargets {
    val endpoints = ArrayList<Vec2>()
    val midpoints = ArrayList<Vec2>()
    val centers = ArrayList<Vec2>()
    val segments = ArrayList<Segment>()
    for (e in this) {
        when (e) {
            is Wall -> {
                endpoints.add(e.start); endpoints.add(e.end); midpoints.add(e.midpoint)
                segments.add(Segment(e.start, e.end))
            }
            is LineEntity -> {
                endpoints.add(e.start); endpoints.add(e.end); midpoints.add(e.start.midpoint(e.end))
                segments.add(Segment(e.start, e.end))
            }
            is PolylineEntity -> {
                endpoints.addAll(e.points)
                for (s in e.segments()) {
                    midpoints.add(s.a.midpoint(s.b))
                    segments.add(s)
                }
            }
            is CircleEntity -> {
                centers.add(e.center)
                endpoints.add(Vec2(e.center.x + e.radius, e.center.y))
                endpoints.add(Vec2(e.center.x - e.radius, e.center.y))
                endpoints.add(Vec2(e.center.x, e.center.y + e.radius))
                endpoints.add(Vec2(e.center.x, e.center.y - e.radius))
            }
            is ArcEntity -> {
                centers.add(e.center); endpoints.add(e.startPoint); endpoints.add(e.endPoint)
                midpoints.add(e.midPoint)
            }
            is TextEntity -> endpoints.add(e.position)
            is DimensionEntity -> {
                endpoints.add(e.p1); endpoints.add(e.p2)
            }
        }
    }
    return SnapTargets(endpoints, midpoints, centers, segments)
}

// ---- Offset -------------------------------------------------------------------------------------------

private fun sideNormal(a: Vec2, b: Vec2, side: Vec2): Vec2? {
    val d = (b - a).normalized()
    if (d == Vec2.ZERO) return null
    val n = d.perpLeft()
    return if ((side - a).dot(n) >= 0.0) n else n * -1.0
}

/** New entity offset by [distance] toward the side containing [side]; null if unsupported/invalid. */
fun CadEntity.offsetBy(distance: Double, side: Vec2): CadEntity? {
    if (distance <= 0.0) return null
    return when (this) {
        is LineEntity -> {
            val n = sideNormal(start, end, side) ?: return null
            copy(id = newId(), start = start + n * distance, end = end + n * distance)
        }
        is Wall -> {
            val n = sideNormal(start, end, side) ?: return null
            copy(id = newId(), start = start + n * distance, end = end + n * distance)
        }
        is CircleEntity -> {
            val r = if (side.distanceTo(center) > radius) radius + distance else radius - distance
            if (r <= 1e-9) null else copy(id = newId(), radius = r)
        }
        is ArcEntity -> {
            val r = if (side.distanceTo(center) > radius) radius + distance else radius - distance
            if (r <= 1e-9) null else copy(id = newId(), radius = r)
        }
        is PolylineEntity -> offsetPolyline(this, distance, side)
        is TextEntity, is DimensionEntity -> null
    }
}

private fun offsetPolyline(pl: PolylineEntity, d: Double, side: Vec2): PolylineEntity? {
    val segs = pl.segments()
    if (segs.isEmpty()) return null
    val nearest = segs.minByOrNull { Geo.distPointSegment(side, it.a, it.b) } ?: return null
    val n0 = (nearest.b - nearest.a).normalized().perpLeft()
    val sign = if ((side - nearest.a).dot(n0) >= 0.0) 1.0 else -1.0
    val shifted = segs.map { s ->
        val n = (s.b - s.a).normalized().perpLeft() * (sign * d)
        Segment(s.a + n, s.b + n)
    }
    val out = ArrayList<Vec2>()
    if (pl.closed && shifted.size >= 3) {
        for (i in shifted.indices) {
            val prev = shifted[(i - 1 + shifted.size) % shifted.size]
            val next = shifted[i]
            out.add(Geo.lineIntersection(prev.a, prev.b, next.a, next.b) ?: next.a)
        }
    } else {
        out.add(shifted.first().a)
        for (i in 1 until shifted.size) {
            val prev = shifted[i - 1]
            val next = shifted[i]
            out.add(Geo.lineIntersection(prev.a, prev.b, next.a, next.b) ?: next.a)
        }
        out.add(shifted.last().b)
    }
    return pl.copy(id = newId(), points = out)
}

@Suppress("unused")
private fun angleOf(from: Vec2, to: Vec2): Double = atan2(to.y - from.y, to.x - from.x)
