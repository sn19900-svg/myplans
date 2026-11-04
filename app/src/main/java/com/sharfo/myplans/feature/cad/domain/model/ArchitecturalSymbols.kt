package com.sharfo.myplans.feature.cad.domain.model

enum class DoorType {
    SINGLE, DOUBLE, SLIDING, MAIN_ENTRANCE, BATHROOM
}

enum class WindowType {
    SINGLE, DOUBLE, SLIDING, LARGE, GLASS_FACADE
}

data class DoorElement(
    val id: String,
    val wallId: String,
    val positionAlongWall: Double,
    val width: Double = 0.9,
    val height: Double = 2.2,
    val type: DoorType = DoorType.SINGLE,
    val openingAngleDeg: Double = 90.0
)

data class WindowElement(
    val id: String,
    val wallId: String,
    val positionAlongWall: Double,
    val width: Double = 1.2,
    val height: Double = 1.0,
    val type: WindowType = WindowType.SINGLE
)

data class FurnitureElement(
    val id: String,
    val nameAr: String,
    val category: String, // BED, SOFA, TABLE, KITCHEN, BATHROOM, COLUMN, CAR
    val x: Double,
    val y: Double,
    val width: Double,
    val depth: Double,
    val rotationDeg: Double = 0.0
)
