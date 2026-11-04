package com.sharfo.myplans.domain.usecase

import com.sharfo.myplans.domain.model.ArchitecturalInput
import com.sharfo.myplans.domain.model.ColumnPoint
import com.sharfo.myplans.domain.model.GeneratedPlan
import com.sharfo.myplans.domain.model.RoomRect
import com.sharfo.myplans.domain.model.SlabType
import com.sharfo.myplans.domain.model.StructuralInput
import com.sharfo.myplans.domain.model.StructuralSchedule
import javax.inject.Inject
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class GenerateEngineeringPlanUseCase @Inject constructor() {

    operator fun invoke(
        arch: ArchitecturalInput,
        struct: StructuralInput
    ): GeneratedPlan {
        val safeArea = max(40.0, arch.landAreaSqm)
        val width = if (arch.frontageWidthM > 4.0) arch.frontageWidthM else sqrt(safeArea)
        val depth = (safeArea / width * 10.0).roundToInt() / 10.0

        val rooms = generateRoomsLayout(width, depth, arch)
        val maxSpan = rooms.maxOfOrNull { max(it.width, it.height) } ?: 4.5
        val schedule = calculateStructuralSchedule(safeArea, arch.floorsCount, maxSpan, struct)
        val columns = generateColumnsGrid(width, depth, arch.floorsCount, struct)

        return GeneratedPlan(
            plotWidthM = width,
            plotDepthM = depth,
            rooms = rooms,
            columns = columns,
            schedule = schedule
        )
    }

    private fun generateRoomsLayout(
        width: Double,
        depth: Double,
        arch: ArchitecturalInput
    ): List<RoomRect> {
        val list = mutableListOf<RoomRect>()
        val corridorW = min(1.6, width * 0.16)
        val leftZoneW = (width - corridorW) * 0.52
        val rightZoneW = width - leftZoneW - corridorW

        // تقسيم الجناح الأيمن (غرف النوم)
        val roomsCount = max(1, arch.roomsCount)
        val roomDepth = depth / roomsCount
        for (i in 0 until roomsCount) {
            list.add(
                RoomRect(
                    name = "غرفة نوم ${i + 1}",
                    category = "ROOM",
                    x = leftZoneW + corridorW,
                    y = i * roomDepth,
                    width = round2(rightZoneW),
                    height = round2(roomDepth)
                )
            )
        }

        // الممر الوسطي وبيت الدرج
        if (arch.hasStaircase) {
            val stairDepth = min(3.2, depth * 0.28)
            list.add(
                RoomRect(
                    name = "بيت الدرج",
                    category = "UTILITY",
                    x = leftZoneW,
                    y = 0.0,
                    width = round2(corridorW),
                    height = round2(stairDepth)
                )
            )
            list.add(
                RoomRect(
                    name = "ممر التوزيع",
                    category = "CORRIDOR",
                    x = leftZoneW,
                    y = stairDepth,
                    width = round2(corridorW),
                    height = round2(depth - stairDepth)
                )
            )
        } else {
            list.add(
                RoomRect(
                    name = "ممر التوزيع",
                    category = "CORRIDOR",
                    x = leftZoneW,
                    y = 0.0,
                    width = round2(corridorW),
                    height = round2(depth)
                )
            )
        }

        // تقسيم الجناح الأيسر (استقبال + معيشة + مطبخ + حمامات)
        val leftSpaces = mutableListOf<Pair<String, String>>()
        if (arch.hasReception) leftSpaces.add("صالون استقبال" to "LIVING")
        if (arch.hasLivingRoom) leftSpaces.add("غرفة معيشة" to "LIVING")
        if (arch.hasKitchen) leftSpaces.add("مطبخ" to "SERVICE")
        for (b in 1..max(1, arch.bathroomsCount)) {
            leftSpaces.add("حمام $b" to "BATH")
        }

        val totalWeights = leftSpaces.sumOf {
            when (it.second) {
                "LIVING" -> 2.2
                "SERVICE" -> 1.5
                else -> 1.0
            }
        }

        var currentY = 0.0
        leftSpaces.forEach { (title, cat) ->
            val weight = when (cat) {
                "LIVING" -> 2.2
                "SERVICE" -> 1.5
                else -> 1.0
            }
            val h = depth * (weight / totalWeights)
            list.add(
                RoomRect(
                    name = title,
                    category = cat,
                    x = 0.0,
                    y = round2(currentY),
                    width = round2(leftZoneW),
                    height = round2(h)
                )
            )
            currentY += h
        }

        return list
    }

    private fun calculateStructuralSchedule(
        area: Double,
        floors: Int,
        maxSpan: Double,
        struct: StructuralInput
    ): StructuralSchedule {
        val slabThicknessCm = when (struct.slabType) {
            SlabType.SOLID -> max(14, ceil((maxSpan * 100.0) / 28.0).toInt())
            SlabType.RIBBED -> max(24, ceil((maxSpan * 100.0) / 20.0).toInt())
            SlabType.FLAT -> max(20, ceil((maxSpan * 100.0) / 30.0).toInt())
        }

        val loadFactor = (struct.buildingCode.deadLoadFactor + struct.buildingCode.liveLoadFactor) / 2.0
        val slabBarArea = Math.PI * (struct.preferredSlabBarDiaMm / 2.0) * (struct.preferredSlabBarDiaMm / 2.0)
        val baseBarsPerMeter = ceil((maxSpan * 1.45 * loadFactor * 100.0) / slabBarArea).toInt().coerceIn(5, 10)
        val secondaryBars = max(5, baseBarsPerMeter - 1)

        val beamDepthCm = when (struct.slabType) {
            SlabType.SOLID -> max(45, (ceil(maxSpan * 10.0).toInt() / 5) * 5)
            SlabType.RIBBED -> slabThicknessCm
            SlabType.FLAT -> slabThicknessCm
        }
        val beamWidthCm = if (struct.slabType == SlabType.RIBBED) 60 else 25

        val beamBarsCount = max(4, ceil((maxSpan * 1.3) / (struct.preferredBeamBarDiaMm / 12.0)).toInt())
        val topBeamBarsCount = max(2, beamBarsCount - 2)

        val eqThicknessM = slabThicknessCm / 100.0 * 1.35
        val totalConcreteM3 = round2(area * floors * eqThicknessM)
        val steelRatioKgPerM3 = when (struct.slabType) {
            SlabType.SOLID -> 110.0
            SlabType.RIBBED -> 95.0
            SlabType.FLAT -> 135.0
        }
        val totalSteelTon = round2((totalConcreteM3 * steelRatioKgPerM3) / 1000.0)

        return StructuralSchedule(
            slabThicknessCm = slabThicknessCm,
            slabMainReinforcement = "$baseBarsPerMeter Ø${struct.preferredSlabBarDiaMm} / م.ط",
            slabSecondaryReinforcement = "$secondaryBars Ø${max(8, struct.preferredSlabBarDiaMm - 2)} / م.ط",
            beamDimensionsCm = "$beamWidthCm × $beamDepthCm سم",
            beamBottomReinforcement = "$beamBarsCount Ø${struct.preferredBeamBarDiaMm} سفلي",
            beamTopReinforcement = "$topBeamBarsCount Ø${ max(12, struct.preferredBeamBarDiaMm - 2) } علوي",
            totalConcreteVolumeM3 = totalConcreteM3,
            estimatedSteelWeightTon = totalSteelTon
        )
    }

    private fun generateColumnsGrid(
        width: Double,
        depth: Double,
        floors: Int,
        struct: StructuralInput
    ): List<ColumnPoint> {
        val colsX = max(2, ceil(width / 4.5).toInt() + 1)
        val colsY = max(2, ceil(depth / 4.5).toInt() + 1)
        val stepX = width / (colsX - 1)
        val stepY = depth / (colsY - 1)

        val colWidthCm = 25
        val colHeightCm = (30 + (floors * 10)).coerceAtMost(80)
        val colAreaMm2 = colWidthCm * colHeightCm * 100.0
        val minSteelAreaMm2 = colAreaMm2 * 0.012
        val singleBarArea = Math.PI * (struct.preferredColumnBarDiaMm / 2.0) * (struct.preferredColumnBarDiaMm / 2.0)
        var barsCount = ceil(minSteelAreaMm2 / singleBarArea).toInt().coerceAtLeast(6)
        if (barsCount % 2 != 0) barsCount += 1

        val columns = mutableListOf<ColumnPoint>()
        var counter = 1
        for (iy in 0 until colsY) {
            for (ix in 0 until colsX) {
                columns.add(
                    ColumnPoint(
                        id = "C$counter",
                        x = round2(ix * stepX),
                        y = round2(iy * stepY),
                        widthCm = colWidthCm,
                        heightCm = colHeightCm,
                        mainBarsCount = barsCount,
                        mainBarDiaMm = struct.preferredColumnBarDiaMm,
                        stirrupText = "5 Ø8 / م"
                    )
                )
                counter++
            }
        }
        return columns
    }

    private fun round2(value: Double): Double = (value * 100.0).roundToInt() / 100.0
}
