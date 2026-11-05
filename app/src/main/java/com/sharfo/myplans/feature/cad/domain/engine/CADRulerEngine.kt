package com.sharfo.myplans.feature.cad.domain.engine

import com.sharfo.myplans.feature.cad.domain.model.Point2D
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

class CADRulerEngine {
    var scale: Float = 40.0f // بكسل لكل متر
    var panX: Float = 0f
    var panY: Float = 0f

    // تحويل إحداثيات الشاشة إلى إحداثيات عالمية حقيقية (متر)
    fun screenToWorld(screenX: Float, screenY: Float, canvasWidth: Float, canvasHeight: Float): Point2D {
        val centerX = canvasWidth / 2f + panX
        val centerY = canvasHeight / 2f + panY
        val worldX = ((screenX - centerX) / scale).toDouble()
        val worldY = ((screenY - centerY) / scale).toDouble()
        return Point2D(worldX, worldY)
    }

    // تحويل إحداثيات العالم الحقيقي إلى إحداثيات الشاشة
    fun worldToScreen(worldPoint: Point2D, canvasWidth: Float, canvasHeight: Float): Point2D {
        val centerX = canvasWidth / 2f + panX
        val centerY = canvasHeight / 2f + panY
        val screenX = worldPoint.x * scale + centerX
        val screenY = worldPoint.y * scale + centerY
        return Point2D(screenX, screenY)
    }

    // حساب المسافة الحقيقية بالأمتار بين نقطتين بدقة مزدوجة
    fun calculateDistance(p1: Point2D, p2: Point2D): Double {
        val dx = p2.x - p1.x
        val dy = p2.y - p1.y
        return sqrt(dx.pow(2.0) + dy.pow(2.0))
    }

    // حساب زاوية الاتجاه بالدرجات
    fun calculateAngleDegrees(p1: Point2D, p2: Point2D): Double {
        val dx = p2.x - p1.x
        val dy = p2.y - p1.y
        var angle = Math.toDegrees(kotlin.math.atan2(dy, dx))
        if (angle < 0) angle += 360.0
        return angle
    }
}
