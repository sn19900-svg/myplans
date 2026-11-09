package com.myprojects.cad.core.engine

import com.myprojects.cad.core.geometry.Geo
import com.myprojects.cad.core.geometry.Vec2
import javax.inject.Inject
import kotlin.math.round

enum class SnapType { NONE, ENDPOINT, INTERSECTION, MIDPOINT, CENTER, GRID }

data class SnapResult(val point: Vec2, val type: SnapType)

data class SnapSettings(
    val gridEnabled: Boolean = true,
    val objectEnabled: Boolean = true,
    val tolerancePx: Double = 18.0
)

data class Segment(val a: Vec2, val b: Vec2)

data class SnapTargets(
    val endpoints: List<Vec2> = emptyList(),
    val midpoints: List<Vec2> = emptyList(),
    val centers: List<Vec2> = emptyList(),
    val segments: List<Segment> = emptyList()
)

/**
 * Priority: Endpoint > Intersection > Midpoint > Center > Grid > Free.
 * Tolerance is in screen pixels, so it feels constant at any zoom.
 */
class SnapEngine @Inject constructor() {

    fun snap(raw: Vec2, targets: SnapTargets, viewport: Viewport, settings: SnapSettings): SnapResult {
        val tol = settings.tolerancePx / viewport.scale
        if (settings.objectEnabled) {
            nearest(raw, targets.endpoints, tol)?.let { return SnapResult(it, SnapType.ENDPOINT) }
            nearestIntersection(raw, targets.segments, tol)?.let { return SnapResult(it, SnapType.INTERSECTION) }
            nearest(raw, targets.midpoints, tol)?.let { return SnapResult(it, SnapType.MIDPOINT) }
            nearest(raw, targets.centers, tol)?.let { return SnapResult(it, SnapType.CENTER) }
        }
        if (settings.gridEnabled) {
            val step = ScaleSteps.minorStep(viewport.scale)
            return SnapResult(
                Vec2(round(raw.x / step) * step, round(raw.y / step) * step),
                SnapType.GRID
            )
        }
        return SnapResult(raw, SnapType.NONE)
    }

    private fun nearest(p: Vec2, candidates: List<Vec2>, tolerance: Double): Vec2? {
        var best: Vec2? = null
        var bestDist = tolerance
        for (c in candidates) {
            val d = c.distanceTo(p)
            if (d <= bestDist) {
                bestDist = d
                best = c
            }
        }
        return best
    }

    private fun nearestIntersection(p: Vec2, segments: List<Segment>, tolerance: Double): Vec2? {
        val near = segments.filter { Geo.distPointSegment(p, it.a, it.b) <= tolerance }
        if (near.size < 2 || near.size > 60) return null
        var best: Vec2? = null
        var bestDist = tolerance
        for (i in near.indices) {
            for (j in i + 1 until near.size) {
                val x = Geo.segmentIntersection(near[i].a, near[i].b, near[j].a, near[j].b) ?: continue
                val d = x.distanceTo(p)
                if (d <= bestDist) {
                    bestDist = d
                    best = x
                }
            }
        }
        return best
    }
}
