package com.sharfo.myplans.feature.cad.domain.engine

import com.sharfo.myplans.feature.cad.domain.model.Point2D
import com.sharfo.myplans.feature.cad.domain.model.WallElement
import kotlin.math.abs
import kotlin.math.round

class SnapEngine(
    val gridSizeMeters: Double = 0.5, // مسافة مربعات الشبكة (50 سم)
    val snapThresholdPixels: Double = 25.0
) {
    // محاذاة النقطة مع أقرب تقاطع شبكي أو عنصر هندسي
    fun snapPoint(
        rawPoint: Point2D,
        walls: List<WallElement>,
        zoomScale: Float
    ): Point2D {
        val threshold = snapThresholdPixels / zoomScale.toDouble()

        // 1. التحقق من التقاط نهايات الجدران (Endpoints)
        for (wall in walls) {
            if (rawPoint.distanceTo(wall.start) <= threshold) return wall.start
            if (rawPoint.distanceTo(wall.end) <= threshold) return wall.end
            
            // منتصف الجدار (Midpoint)
            val mid = Point2D((wall.start.x + wall.end.x) / 2.0, (wall.start.y + wall.end.y) / 2.0)
            if (rawPoint.distanceTo(mid) <= threshold) return mid
        }

        // 2. الالتقاط بالشبكة (Grid Snap)
        val snappedX = round(rawPoint.x / gridSizeMeters) * gridSizeMeters
        val snappedY = round(rawPoint.y / gridSizeMeters) * gridSizeMeters
        val gridPoint = Point2D(snappedX, snappedY)
        
        if (rawPoint.distanceTo(gridPoint) <= threshold) {
            return gridPoint
        }

        return rawPoint
    }
}
