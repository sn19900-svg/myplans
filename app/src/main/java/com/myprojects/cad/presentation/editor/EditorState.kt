package com.myprojects.cad.presentation.editor

import com.myprojects.cad.core.engine.SnapResult
import com.myprojects.cad.core.engine.SnapSettings
import com.myprojects.cad.core.engine.Viewport
import com.myprojects.cad.core.geometry.Geo
import com.myprojects.cad.core.geometry.Vec2
import com.myprojects.cad.domain.model.ArcEntity
import com.myprojects.cad.domain.model.CadEntity
import com.myprojects.cad.domain.model.CircleEntity
import com.myprojects.cad.domain.model.ColumnEntity
import com.myprojects.cad.domain.model.ColumnShape
import com.myprojects.cad.domain.model.DimensionEntity
import com.myprojects.cad.domain.model.LineEntity
import com.myprojects.cad.domain.model.PolylineEntity
import com.myprojects.cad.domain.model.ProjectInfo
import com.myprojects.cad.domain.model.Wall
import com.myprojects.cad.domain.model.WallThickness
import com.myprojects.cad.domain.model.Xform
import com.myprojects.cad.domain.model.arcFrom3
import com.myprojects.cad.domain.model.dimensionOffset
import com.myprojects.cad.domain.model.offsetBy
import com.myprojects.cad.domain.model.rectPolyline
import com.myprojects.cad.domain.model.transformed
import com.myprojects.cad.domain.structural.ColumnStudyRecord
import com.myprojects.cad.domain.structural.StudyRecord

enum class ToolCategory { VIEW, DRAW, MODIFY }

/** Column sizes offered by the Column tool. Circular presets use the width as the diameter. */
data class ColumnPreset(val label: String, val shape: ColumnShape, val widthCm: Int, val depthCm: Int) {
    companion object {
        val ALL: List<ColumnPreset> = listOf(
            ColumnPreset("25×25", ColumnShape.RECTANGULAR, 25, 25),
            ColumnPreset("30×30", ColumnShape.RECTANGULAR, 30, 30),
            ColumnPreset("30×50", ColumnShape.RECTANGULAR, 30, 50),
            ColumnPreset("40×40", ColumnShape.RECTANGULAR, 40, 40),
            ColumnPreset("40×60", ColumnShape.RECTANGULAR, 40, 60),
            ColumnPreset("50×50", ColumnShape.RECTANGULAR, 50, 50),
            ColumnPreset("Ø30", ColumnShape.CIRCULAR, 30, 30),
            ColumnPreset("Ø40", ColumnShape.CIRCULAR, 40, 40),
            ColumnPreset("Ø50", ColumnShape.CIRCULAR, 50, 50)
        )
    }
}

enum class LabelMode(val label: String) { OFF("Off"), NAMES("Names"), FULL("Full") }

/** Rubber-band selection rectangle in screen pixels. Dragging right-to-left = crossing, left-to-right = window. */
data class SelectBox(val startPx: Vec2, val endPx: Vec2) {
    val crossing: Boolean get() = endPx.x < startPx.x
}

enum class EditorTool(val label: String, val category: ToolCategory, val needsSelection: Boolean = false) {
    PAN("Pan", ToolCategory.VIEW),
    WALL("Wall", ToolCategory.DRAW),
    LINE("Line", ToolCategory.DRAW),
    POLYLINE("Polyline", ToolCategory.DRAW),
    RECT("Rect", ToolCategory.DRAW),
    CIRCLE("Circle", ToolCategory.DRAW),
    ARC("Arc", ToolCategory.DRAW),
    TEXT("Text", ToolCategory.DRAW),
    DIMENSION("Dim", ToolCategory.DRAW),
    COLUMN("Column", ToolCategory.DRAW),
    SELECT("Select", ToolCategory.MODIFY),
    MOVE("Move", ToolCategory.MODIFY, true),
    COPY("Copy", ToolCategory.MODIFY, true),
    ROTATE("Rotate", ToolCategory.MODIFY, true),
    MIRROR("Mirror", ToolCategory.MODIFY, true),
    OFFSET("Offset", ToolCategory.MODIFY)
}

data class EditorUiState(
    val viewport: Viewport = Viewport(),
    val entities: List<CadEntity> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val tool: EditorTool = EditorTool.WALL,
    val points: List<Vec2> = emptyList(),
    val offsetTargetId: String? = null,
    val cursorRaw: Vec2? = null,
    val cursor: SnapResult? = null,
    val snapSettings: SnapSettings = SnapSettings(),
    val orthoEnabled: Boolean = false,
    val thicknessCm: Int = WallThickness.DEFAULT_CM,
    val lengthText: String = "",
    val angleText: String = "",
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val message: String? = null,
    val textRequestAt: Vec2? = null,
    val projectId: String = "",
    val projectName: String = "",
    val dirty: Boolean = false,
    val projects: List<ProjectInfo> = emptyList(),
    val busy: Boolean = false,
    val studies: List<StudyRecord> = emptyList(),
    val columnStudies: List<ColumnStudyRecord> = emptyList(),
    val columnPreset: ColumnPreset = ColumnPreset.ALL[1],
    val labelMode: LabelMode = LabelMode.FULL,
    val selectBox: SelectBox? = null
) {
    val selected: List<CadEntity> get() = entities.filter { it.id in selectedIds }

    /** The point the numeric fields would place next, or null when they are empty / not applicable. */
    fun numericPoint(): Vec2? {
        val anchor = points.lastOrNull() ?: return null
        return when (tool) {
            EditorTool.RECT -> {
                val w = lengthText.toDoubleOrNull()
                val h = angleText.toDoubleOrNull()
                if (w == null || h == null || w == 0.0 || h == 0.0) null else Vec2(anchor.x + w, anchor.y + h)
            }
            EditorTool.ROTATE -> {
                val a = angleText.toDoubleOrNull() ?: return null
                anchor + Vec2.fromPolar(1.0, Math.toRadians(a))
            }
            EditorTool.TEXT, EditorTool.SELECT, EditorTool.PAN, EditorTool.OFFSET, EditorTool.DIMENSION, EditorTool.COLUMN -> null
            else -> {
                val len = lengthText.toDoubleOrNull()
                if (len == null || len <= 0.0) null
                else anchor + Vec2.fromPolar(len, Math.toRadians(angleText.toDoubleOrNull() ?: 0.0))
            }
        }
    }

    /** Rubber-band preview of what the next click/Apply would create. */
    val preview: List<CadEntity>
        get() {
            val c = numericPoint() ?: cursor?.point ?: return emptyList()
            val first = points.firstOrNull()
            val last = points.lastOrNull()
            val t = thicknessCm / 100.0
            return when (tool) {
                EditorTool.WALL -> if (last != null) listOf(Wall(start = last, end = c, thicknessM = t)) else emptyList()
                EditorTool.LINE -> if (last != null) listOf(LineEntity(start = last, end = c)) else emptyList()
                EditorTool.POLYLINE -> if (last != null) listOf(PolylineEntity(points = points + c)) else emptyList()
                EditorTool.RECT -> if (first != null) listOf(rectPolyline(first, c)) else emptyList()
                EditorTool.CIRCLE ->
                    if (first != null && first.distanceTo(c) > 1e-9) {
                        listOf(CircleEntity(center = first, radius = first.distanceTo(c)))
                    } else emptyList()
                EditorTool.ARC -> when (points.size) {
                    1 -> listOf(LineEntity(start = points[0], end = c))
                    2 -> {
                        val arc: CadEntity = arcFrom3(points[0], points[1], c) ?: LineEntity(start = points[0], end = c)
                        listOf(arc)
                    }
                    else -> emptyList()
                }
                EditorTool.DIMENSION -> when (points.size) {
                    1 -> listOf(LineEntity(start = points[0], end = c))
                    2 -> listOf(DimensionEntity(p1 = points[0], p2 = points[1], offset = dimensionOffset(points[0], points[1], c)))
                    else -> emptyList()
                }
                EditorTool.COLUMN -> listOf(
                    ColumnEntity(
                        center = c, widthM = columnPreset.widthCm / 100.0, depthM = columnPreset.depthCm / 100.0,
                        shape = columnPreset.shape
                    )
                )
                EditorTool.MOVE, EditorTool.COPY ->
                    if (first != null) selected.map { it.transformed(Xform.Translate(c - first), keepId = false) } else emptyList()
                EditorTool.ROTATE ->
                    if (first != null) {
                        val ang = Geo.angleDeg(first, c)
                        selected.map { it.transformed(Xform.Rotate(first, ang), keepId = false) }
                    } else emptyList()
                EditorTool.MIRROR ->
                    if (first != null && first.distanceTo(c) > 1e-9) {
                        listOf<CadEntity>(LineEntity(start = first, end = c)) +
                            selected.map { it.transformed(Xform.Mirror(first, c), keepId = false) }
                    } else emptyList()
                EditorTool.OFFSET -> {
                    val target = entities.firstOrNull { it.id == offsetTargetId }
                    val d = lengthText.toDoubleOrNull()
                    val off = if (target != null && d != null) target.offsetBy(d, c) else null
                    if (off != null) listOf(off) else emptyList()
                }
                else -> emptyList()
            }
        }

    val prompt: String
        get() = when (tool) {
            EditorTool.PAN -> "Drag to pan · pinch to zoom"
            EditorTool.WALL, EditorTool.LINE ->
                if (points.isEmpty()) "Pick start point" else "Pick next point · or Length/Angle then ✓"
            EditorTool.POLYLINE ->
                if (points.isEmpty()) "Pick first point" else "Pick next point · Done to finish · Close to close"
            EditorTool.RECT ->
                if (points.isEmpty()) "Pick first corner" else "Pick opposite corner · or Length=width, Angle=height"
            EditorTool.CIRCLE ->
                if (points.isEmpty()) "Pick center" else "Pick a point on the circle · or type Length = radius"
            EditorTool.ARC -> when (points.size) {
                0 -> "Pick arc start point"
                1 -> "Pick a point on the arc"
                else -> "Pick arc end point"
            }
            EditorTool.TEXT -> "Pick text position"
            EditorTool.COLUMN -> "Pick the column centre · ✓ Place (repeats) · size ${columnPreset.label} cm — change it below"
            EditorTool.DIMENSION -> when (points.size) {
                0 -> "Pick first point"
                1 -> "Pick second point"
                else -> "Pick dimension line position"
            }
            EditorTool.SELECT -> "Tap to select · drag a box (→ window, ← crossing) · ${selectedIds.size} selected"
            EditorTool.MOVE -> if (points.isEmpty()) "Pick base point" else "Pick destination · or Length/Angle then ✓"
            EditorTool.COPY -> if (points.isEmpty()) "Pick base point" else "Pick destination (repeats) · Done to end"
            EditorTool.ROTATE -> if (points.isEmpty()) "Pick base point" else "Pick angle point · or type Angle then ✓"
            EditorTool.MIRROR -> if (points.isEmpty()) "Pick first axis point" else "Pick second axis point"
            EditorTool.OFFSET ->
                if (offsetTargetId == null) "Type distance in Length, then pick the object" else "Pick the side to offset to"
        }
}
