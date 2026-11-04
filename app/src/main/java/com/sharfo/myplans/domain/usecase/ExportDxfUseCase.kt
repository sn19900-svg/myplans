package com.sharfo.myplans.domain.usecase

import android.content.Context
import com.sharfo.myplans.domain.model.ProjectPlan
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

class ExportDxfUseCase @Inject constructor(
    @ApplicationContext private val context: Context
) {
    operator fun invoke(project: ProjectPlan): File {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeName = project.architectural.projectName.replace(" ", "_")
        val file = File(exportDir, "${safeName}_${project.id}.dxf")

        val sb = StringBuilder()
        // DXF Header & Entities section
        sb.appendLine("0")
        sb.appendLine("SECTION")
        sb.appendLine("2")
        sb.appendLine("ENTITIES")

        // 1) Draw Room Walls on Layer WALLS (Color 7 - White/Black)
        project.generated.rooms.forEach { room ->
            appendRect(sb, "WALLS", 7, room.x, room.y, room.width, room.height)
            val centerX = room.x + room.width / 2.0
            val centerY = room.y + room.height / 2.0
            appendText(
                sb = sb,
                layer = "TEXT",
                color = 3,
                x = centerX - (room.width * 0.25),
                y = centerY,
                height = 0.22,
                text = "${room.name} (${room.width}x${room.height}m)"
            )
        }

        // 2) Draw Structural Grid Axes on Layer AXES (Color 1 - Red)
        val distinctX = project.generated.columns.map { it.x }.distinct()
        val distinctY = project.generated.columns.map { it.y }.distinct()
        distinctX.forEach { x ->
            appendLine(sb, "AXES", 1, x, -1.0, x, project.generated.plotDepthM + 1.0)
        }
        distinctY.forEach { y ->
            appendLine(sb, "AXES", 1, -1.0, y, project.generated.plotWidthM + 1.0, y)
        }

        // 3) Draw Structural Columns on Layer COLUMNS (Color 5 - Blue)
        project.generated.columns.forEach { col ->
            val wM = col.widthCm / 100.0
            val hM = col.heightCm / 100.0
            appendRect(sb, "COLUMNS", 5, col.x - wM / 2.0, col.y - hM / 2.0, wM, hM)
            appendText(
                sb = sb,
                layer = "COLUMNS_TAG",
                color = 2,
                x = col.x + 0.15,
                y = col.y + 0.15,
                height = 0.18,
                text = "${col.id} (${col.mainBarsCount}T${col.mainBarDiaMm})"
            )
        }

        sb.appendLine("0")
        sb.appendLine("ENDSEC")
        sb.appendLine("0")
        sb.appendLine("EOF")

        file.writeText(sb.toString(), Charsets.UTF_8)
        return file
    }

    private fun appendLine(
        sb: StringBuilder,
        layer: String,
        color: Int,
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double
    ) {
        sb.appendLine("0")
        sb.appendLine("LINE")
        sb.appendLine("8")
        sb.appendLine(layer)
        sb.appendLine("62")
        sb.appendLine(color.toString())
        sb.appendLine("10")
        sb.appendLine(x1.toString())
        sb.appendLine("20")
        sb.appendLine(y1.toString())
        sb.appendLine("30")
        sb.appendLine("0.0")
        sb.appendLine("11")
        sb.appendLine(x2.toString())
        sb.appendLine("21")
        sb.appendLine(y2.toString())
        sb.appendLine("31")
        sb.appendLine("0.0")
    }

    private fun appendRect(
        sb: StringBuilder,
        layer: String,
        color: Int,
        x: Double,
        y: Double,
        w: Double,
        h: Double
    ) {
        appendLine(sb, layer, color, x, y, x + w, y)
        appendLine(sb, layer, color, x + w, y, x + w, y + h)
        appendLine(sb, layer, color, x + w, y + h, x, y + h)
        appendLine(sb, layer, color, x, y + h, x, y)
    }

    private fun appendText(
        sb: StringBuilder,
        layer: String,
        color: Int,
        x: Double,
        y: Double,
        height: Double,
        text: String
    ) {
        sb.appendLine("0")
        sb.appendLine("TEXT")
        sb.appendLine("8")
        sb.appendLine(layer)
        sb.appendLine("62")
        sb.appendLine(color.toString())
        sb.appendLine("10")
        sb.appendLine(x.toString())
        sb.appendLine("20")
        sb.appendLine(y.toString())
        sb.appendLine("30")
        sb.appendLine("0.0")
        sb.appendLine("40")
        sb.appendLine(height.toString())
        sb.appendLine("1")
        sb.appendLine(text)
    }
}
