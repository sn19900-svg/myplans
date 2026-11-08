package com.myprojects.cad.presentation.editor

import com.myprojects.cad.core.engine.SnapResult
import com.myprojects.cad.core.engine.SnapSettings
import com.myprojects.cad.core.engine.Viewport
import com.myprojects.cad.core.geometry.Vec2
import com.myprojects.cad.domain.model.Wall
import com.myprojects.cad.domain.model.WallThickness

enum class EditorTool { PAN, WALL }

data class EditorUiState(
    val viewport: Viewport = Viewport(),
    val walls: List<Wall> = emptyList(),
    val tool: EditorTool = EditorTool.WALL,
    val pendingStart: Vec2? = null,
    val cursor: SnapResult? = null,
    val snapSettings: SnapSettings = SnapSettings(),
    val orthoEnabled: Boolean = false,
    val thicknessCm: Int = WallThickness.DEFAULT_CM,
    val lengthText: String = "",
    val angleText: String = "0",
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val message: String? = null
) {
    /** Live preview of the wall that "Add" would create from the numeric fields. */
    val numericPreview: Wall?
        get() {
            val start = pendingStart ?: return null
            val len = lengthText.toDoubleOrNull() ?: return null
            if (len <= 0.0) return null
            val ang = angleText.toDoubleOrNull() ?: 0.0
            return Wall(
                start = start,
                end = start + Vec2.fromPolar(len, Math.toRadians(ang)),
                thicknessM = thicknessCm / 100.0
            )
        }
}
