package com.myprojects.cad.core.geometry

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/** Pure geometry helpers (Double precision). Angles in degrees, CCW from +X. */
object Geo {

    fun normDeg(a: Double): Double {
        var r = a % 360.0
        if (r < 0.0) r += 360.0
        return r
    }

    fun angleDeg(from: Vec2, to: Vec2): Double = Math.toDegrees(atan2(to.y - from.y, to.x - from.x))

    /** CCW sweep from [startDeg] to [endDeg]; start == end means a full circle. */
    fun sweepDeg(startDeg: Double, endDeg: Double): Double {
        val s = normDeg(endDeg - startDeg)
        return if (s < 1e-9) 360.0 else s
    }

    fun inArc(angleDeg: Double, startDeg: Double, endDeg: Double): Boolean {
        val t = normDeg(angleDeg - startDeg)
        return t <= sweepDeg(startDeg, endDeg) + 1e-9
    }

    fun closestOnSegment(p: Vec2, a: Vec2, b: Vec2): Vec2 {
        val ab = b - a
        val len2 = ab.dot(ab)
        if (len2 < 1e-24) return a
        val t = ((p - a).dot(ab) / len2).coerceIn(0.0, 1.0)
        return a + ab * t
    }

    fun distPointSegment(p: Vec2, a: Vec2, b: Vec2): Double = p.distanceTo(closestOnSegment(p, a, b))

    /** Intersection of two finite segments, or null. */
    fun segmentIntersection(a1: Vec2, a2: Vec2, b1: Vec2, b2: Vec2): Vec2? {
        val r = a2 - a1
        val s = b2 - b1
        val denom = r.cross(s)
        if (abs(denom) < 1e-14) return null
        val qp = b1 - a1
        val t = qp.cross(s) / denom
        val u = qp.cross(r) / denom
        val e = 1e-9
        return if (t >= -e && t <= 1.0 + e && u >= -e && u <= 1.0 + e) a1 + r * t else null
    }

    /** Intersection of two infinite lines, or null when parallel. */
    fun lineIntersection(a1: Vec2, a2: Vec2, b1: Vec2, b2: Vec2): Vec2? {
        val r = a2 - a1
        val s = b2 - b1
        val denom = r.cross(s)
        if (abs(denom) < 1e-14) return null
        val t = (b1 - a1).cross(s) / denom
        return a1 + r * t
    }

    data class CircleFit(val center: Vec2, val radius: Double)

    fun circumcircle(a: Vec2, b: Vec2, c: Vec2): CircleFit? {
        val bx = b.x - a.x
        val by = b.y - a.y
        val cx = c.x - a.x
        val cy = c.y - a.y
        val d = 2.0 * (bx * cy - by * cx)
        if (abs(d) < 1e-12) return null
        val b2 = bx * bx + by * by
        val c2 = cx * cx + cy * cy
        val ux = (cy * b2 - by * c2) / d
        val uy = (bx * c2 - cx * b2) / d
        val center = Vec2(a.x + ux, a.y + uy)
        return CircleFit(center, center.distanceTo(a))
    }

    fun minMax(values: List<Double>): Pair<Double, Double>? {
        if (values.isEmpty()) return null
        var lo = values[0]
        var hi = values[0]
        for (v in values) {
            lo = min(lo, v)
            hi = max(hi, v)
        }
        return lo to hi
    }
}
