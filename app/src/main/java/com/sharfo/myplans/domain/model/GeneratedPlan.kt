package com.sharfo.myplans.domain.model

data class RoomRect(
    val name: String,
    val category: String,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double
) {
    val area: Double get() = width * height
}

data class ColumnPoint(
    val id: String,
    val x: Double,
    val y: Double,
    val widthCm: Int,
    val heightCm: Int,
    val mainBarsCount: Int,
    val mainBarDiaMm: Int,
    val stirrupText: String
)

data class WallLine(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float
)

data class StructuralSchedule(
    val slabThicknessCm: Int,
    val slabMainReinforcement: String,
    val slabSecondaryReinforcement: String,
    val beamDimensionsCm: String,
    val beamBottomReinforcement: String,
    val beamTopReinforcement: String,
    val totalConcreteVolumeM3: Double,
    val estimatedSteelWeightTon: Double
)

data class GeneratedPlan(
    val plotWidthM: Double,
    val plotDepthM: Double,
    val rooms: List<RoomRect>,
    val columns: List<ColumnPoint>,
    val customWalls: List<WallLine> = emptyList(),
    val schedule: StructuralSchedule
)
