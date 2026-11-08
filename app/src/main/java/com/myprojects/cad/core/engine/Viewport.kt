package com.myprojects.cad.core.engine

import com.myprojects.cad.core.geometry.Vec2

data class WorldRect(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    val width: Double get() = maxX - minX
    val height: Double get() = maxY - minY
}

/**
 * World <-> Screen mapping.
 *  - World: meters, Y up.   - Screen: pixels, Y down, origin top-left of the canvas.
 *  - [originX]/[originY] = screen position (px) of world (0,0).  - [scale] = pixels per meter.
 * Everything is Double; conversion to Float happens only at the final draw call.
 */
data class Viewport(
    val originX: Double = 0.0,
    val originY: Double = 0.0,
    val scale: Double = DEFAULT_SCALE
) {
    fun worldToScreenX(x: Double): Double = originX + x * scale
    fun worldToScreenY(y: Double): Double = originY - y * scale
    fun screenToWorldX(sx: Double): Double = (sx - originX) / scale
    fun screenToWorldY(sy: Double): Double = (originY - sy) / scale

    fun worldToScreen(p: Vec2) = Vec2(worldToScreenX(p.x), worldToScreenY(p.y))
    fun screenToWorld(s: Vec2) = Vec2(screenToWorldX(s.x), screenToWorldY(s.y))

    fun panBy(dxPx: Double, dyPx: Double) = copy(originX = originX + dxPx, originY = originY + dyPx)

    /** Zoom keeping the world point under [focalPx] fixed on screen. */
    fun zoomAt(focalPx: Vec2, factor: Double): Viewport {
        val newScale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        val w = screenToWorld(focalPx)
        return Viewport(
            originX = focalPx.x - w.x * newScale,
            originY = focalPx.y + w.y * newScale,
            scale = newScale
        )
    }

    fun visibleBounds(widthPx: Double, heightPx: Double) = WorldRect(
        minX = screenToWorldX(0.0),
        minY = screenToWorldY(heightPx),
        maxX = screenToWorldX(widthPx),
        maxY = screenToWorldY(0.0)
    )

    companion object {
        const val MIN_SCALE = 0.5      // px per meter
        const val MAX_SCALE = 5000.0   // px per meter (keeps ruler cm labels integral)
        const val DEFAULT_SCALE = 50.0

        fun centered(widthPx: Double, heightPx: Double) =
            Viewport(widthPx / 2.0, heightPx / 2.0, DEFAULT_SCALE)
    }
}
