package com.sharfo.myplans.feature.cad.domain.model

enum class RoomCategory(val titleAr: String) {
    BEDROOM("غرفة نوم"),
    LIVING_ROOM("غرفة معيشة"),
    KITCHEN("مطبخ"),
    BATHROOM("حمام"),
    TOILET("مرحاض"),
    ENTRANCE("مدخل"),
    CORRIDOR("ممر"),
    BALCONY("شرفة"),
    DINING("غرفة طعام"),
    MULTI_USE("غرفة متعددة الاستخدامات")
}

data class RoomSpace(
    val id: String,
    val name: String,
    val category: RoomCategory,
    val polygonPoints: List<Point2D>,
    val floorColorHex: String = "#E0F7FA",
    val floorType: String = السيراميك
) {
    // حساب المساحة تلقائياً بالمتر المربع باستخدام صيغة المضلع (Shoelace formula)
    val areaSqm: Double
        get() {
            if (polygonPoints.size < 3) return 0.0
            var sum = 0.0
            val n = polygonPoints.size
            for (i in 0 until n) {
                val j = (i + 1) % n
                sum += polygonPoints[i].x * polygonPoints[j].y
                sum -= polygonPoints[j].x * polygonPoints[i].y
            }
            return kotlin.math.abs(sum) / 2.0
        }

    // حساب المحيط
    val perimeterMeters: Double
        get() {
            if (polygonPoints.size < 2) return 0.0
            var p = 0.0
            val n = polygonPoints.size
            for (i in 0 until n) {
                p += polygonPoints[i].distanceTo(polygonPoints[(i + 1) % n])
            }
            return p
        }
}
