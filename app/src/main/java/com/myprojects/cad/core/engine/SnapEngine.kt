package com.myprojects.cad.core.engine

import com.myprojects.cad.core.geometry.Vec2
import javax.inject.Inject
import kotlin.math.round

enum class SnapType { NONE, ENDPOINT, MIDPOINT, GRID }

data class SnapResult(val point: Vec2, val type: SnapType)

data class SnapSettings(
    val gridEnabled: Boolean = true,
    val objectEnabled: Boolean = true,
    val tolerancePx: Double = 18.0
)

data class SnapTargets(val endpoints: List<Vec2>, val midpoints: List<Vec2>)

/** Priority: Endpoint > Midpoint > Grid > Free. Tolerance is in screen pixels, so it feels constant at any zoom. */
class SnapEngine @Inject constructor() {

    fun snap(raw: Vec2, targets: SnapTargets, viewport: Viewport, settings: SnapSettings): SnapResult {
        val tolWorld = settings.tolerancePx / viewport.scale
        if (settings.objectEnabled) {
            nearest(raw, targets.endpoints, tolWorld)?.let { return SnapResult(it, SnapType.ENDPOINT) }
            nearest(raw, targets.midpoints, tolWorld)?.let { return SnapResult(it, SnapType.MIDPOINT) }
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
}
