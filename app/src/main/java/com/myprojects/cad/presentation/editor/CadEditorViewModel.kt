package com.myprojects.cad.presentation.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myprojects.cad.core.engine.SnapEngine
import com.myprojects.cad.core.engine.Viewport
import com.myprojects.cad.core.geometry.Vec2
import com.myprojects.cad.domain.model.Wall
import com.myprojects.cad.domain.model.toSnapTargets
import com.myprojects.cad.domain.repository.CadRepository
import com.myprojects.cad.domain.usecase.AddWallUseCase
import com.myprojects.cad.domain.usecase.CreateWallFromPolarUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@HiltViewModel
class CadEditorViewModel @Inject constructor(
    private val repository: CadRepository,
    private val snapEngine: SnapEngine,
    private val createWallPolar: CreateWallFromPolarUseCase,
    private val addWall: AddWallUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _state.asStateFlow()

    private var canvasW = 0.0
    private var canvasH = 0.0
    private var viewportInitialized = false

    init {
        viewModelScope.launch {
            combine(repository.walls, repository.canUndo, repository.canRedo) { w, u, r ->
                Triple(w, u, r)
            }.collect { (walls, undo, redo) ->
                _state.update { it.copy(walls = walls, canUndo = undo, canRedo = redo) }
            }
        }
    }

    // ---- Viewport ---------------------------------------------------------------------------

    fun onCanvasSizeChanged(widthPx: Int, heightPx: Int) {
        canvasW = widthPx.toDouble()
        canvasH = heightPx.toDouble()
        if (!viewportInitialized && canvasW > 0 && canvasH > 0) {
            viewportInitialized = true
            _state.update { it.copy(viewport = Viewport.centered(canvasW, canvasH)) }
        }
    }

    fun onTransform(centroidPx: Vec2, panPx: Vec2, zoom: Double) {
        _state.update { s ->
            val zoomed = if (zoom == 1.0) s.viewport else s.viewport.zoomAt(centroidPx, zoom)
            s.copy(viewport = zoomed.panBy(panPx.x, panPx.y))
        }
    }

    fun zoomExtents() {
        if (canvasW <= 0.0 || canvasH <= 0.0) return
        val walls = _state.value.walls
        if (walls.isEmpty()) {
            _state.update { it.copy(viewport = Viewport.centered(canvasW, canvasH)) }
            return
        }
        val pts = walls.flatMap { listOf(it.start, it.end) }
        val minX = pts.minOf { it.x }
        val maxX = pts.maxOf { it.x }
        val minY = pts.minOf { it.y }
        val maxY = pts.maxOf { it.y }
        val margin = 48.0
        val w = max(maxX - minX, 0.5)
        val h = max(maxY - minY, 0.5)
        val scale = min((canvasW - 2 * margin) / w, (canvasH - 2 * margin) / h)
            .coerceIn(Viewport.MIN_SCALE, Viewport.MAX_SCALE)
        val cx = (minX + maxX) / 2.0
        val cy = (minY + maxY) / 2.0
        _state.update {
            it.copy(viewport = Viewport(canvasW / 2.0 - cx * scale, canvasH / 2.0 + cy * scale, scale))
        }
    }

    // ---- Drawing ----------------------------------------------------------------------------

    fun onCanvasTap(screenPx: Vec2) {
        val s = _state.value
        if (s.tool != EditorTool.WALL) return

        val raw = s.viewport.screenToWorld(screenPx)
        var snapped = snapEngine.snap(raw, s.walls.toSnapTargets(), s.viewport, s.snapSettings)
        val start = s.pendingStart

        if (start == null) {
            _state.update { it.copy(pendingStart = snapped.point, cursor = snapped) }
            return
        }
        if (s.orthoEnabled) snapped = snapped.copy(point = orthoConstrain(start, snapped.point))

        if (start.distanceTo(snapped.point) < MIN_WALL_LENGTH_M) {
            _state.update { it.copy(cursor = snapped) }
            return
        }
        addWall(Wall(start = start, end = snapped.point, thicknessM = s.thicknessCm / 100.0))
        _state.update { it.copy(pendingStart = snapped.point, cursor = snapped) }
    }

    fun onAddNumericWall() {
        val s = _state.value
        val start = s.pendingStart
        if (start == null) {
            showMessage("Tap the canvas to set a start point first")
            return
        }
        val length = s.lengthText.toDoubleOrNull()
        if (length == null) {
            showMessage("Enter a valid length in meters")
            return
        }
        val angle = s.angleText.toDoubleOrNull() ?: 0.0
        createWallPolar(start, length, angle, s.thicknessCm / 100.0)
            .onSuccess { wall ->
                addWall(wall)
                _state.update { it.copy(pendingStart = wall.end, lengthText = "") }
            }
            .onFailure { e -> showMessage(e.message ?: "Invalid wall") }
    }

    fun finishChain() = _state.update { it.copy(pendingStart = null) }

    // ---- Inputs / toggles -------------------------------------------------------------------

    fun onLengthChange(text: String) =
        _state.update { it.copy(lengthText = sanitizeNumber(text, allowNegative = false)) }

    fun onAngleChange(text: String) =
        _state.update { it.copy(angleText = sanitizeNumber(text, allowNegative = true)) }

    fun setTool(tool: EditorTool) = _state.update { it.copy(tool = tool) }
    fun setThickness(cm: Int) = _state.update { it.copy(thicknessCm = cm) }
    fun toggleOrtho() = _state.update { it.copy(orthoEnabled = !it.orthoEnabled) }

    fun toggleGridSnap() = _state.update {
        it.copy(snapSettings = it.snapSettings.copy(gridEnabled = !it.snapSettings.gridEnabled))
    }

    fun toggleObjectSnap() = _state.update {
        it.copy(snapSettings = it.snapSettings.copy(objectEnabled = !it.snapSettings.objectEnabled))
    }

    fun undo() = repository.undo()
    fun redo() = repository.redo()
    fun consumeMessage() = _state.update { it.copy(message = null) }

    // ---- Helpers ----------------------------------------------------------------------------

    private fun showMessage(text: String) = _state.update { it.copy(message = text) }

    private fun orthoConstrain(anchor: Vec2, p: Vec2): Vec2 =
        if (abs(p.x - anchor.x) >= abs(p.y - anchor.y)) Vec2(p.x, anchor.y) else Vec2(anchor.x, p.y)

    private fun sanitizeNumber(input: String, allowNegative: Boolean): String {
        val sb = StringBuilder()
        var hasDot = false
        for ((i, c) in input.replace(',', '.').withIndex()) {
            if (c in '0'..'9') {
                sb.append(c)
            } else if (c == '.' && !hasDot) {
                hasDot = true
                sb.append(c)
            } else if (c == '-' && allowNegative && i == 0) {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    private companion object {
        const val MIN_WALL_LENGTH_M = 0.001
    }
}
