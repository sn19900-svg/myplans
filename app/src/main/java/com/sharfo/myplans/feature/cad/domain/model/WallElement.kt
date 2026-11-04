package com.sharfo.myplans.feature.cad.domain.model

data class WallOpening(
    val id: String,
    val offsetFromStart: Double, // المسافة من بداية الجدار
    val width: Double,
    val type: OpeningType // DOOR or WINDOW
)

enum class OpeningType {
    DOOR, WINDOW
}

data class WallElement(
    val id: String,
    val start: Point2D,
    val end: Point2D,
    val thicknessCm: Double = 20.0, // سماكة الجدار 10، 15، 20، 25 سم
    val isExternal: Boolean = true,
    val openings: List<WallOpening> = emptyList()
) {
    val length: Double get() = start.distanceTo(end)
}
