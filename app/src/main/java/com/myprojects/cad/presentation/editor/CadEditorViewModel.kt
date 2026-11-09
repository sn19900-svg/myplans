package com.myprojects.cad.presentation.editor

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myprojects.cad.core.engine.SnapEngine
import com.myprojects.cad.core.engine.SnapResult
import com.myprojects.cad.core.engine.SnapTargets
import com.myprojects.cad.core.engine.Viewport
import com.myprojects.cad.core.geometry.Geo
import com.myprojects.cad.core.geometry.Vec2
import com.myprojects.cad.data.dxf.DxfReader
import com.myprojects.cad.data.dxf.DxfUnit
import com.myprojects.cad.data.dxf.DxfWriter
import com.myprojects.cad.data.export.PaperSize
import com.myprojects.cad.data.export.PdfExporter
import com.myprojects.cad.data.io.DocumentIO
import com.myprojects.cad.domain.model.CadEntity
import com.myprojects.cad.domain.model.CadProject
import com.myprojects.cad.domain.model.CircleEntity
import com.myprojects.cad.domain.model.DimensionEntity
import com.myprojects.cad.domain.model.LineEntity
import com.myprojects.cad.domain.model.PolylineEntity
import com.myprojects.cad.domain.model.TextEntity
import com.myprojects.cad.domain.model.Wall
import com.myprojects.cad.domain.model.Xform
import com.myprojects.cad.domain.model.arcFrom3
import com.myprojects.cad.domain.model.dimensionOffset
import com.myprojects.cad.domain.model.distanceTo
import com.myprojects.cad.domain.model.offsetBy
import com.myprojects.cad.domain.model.rectPolyline
import com.myprojects.cad.domain.model.toSnapTargets
import com.myprojects.cad.domain.model.transformed
import com.myprojects.cad.domain.model.worldBounds
import com.myprojects.cad.domain.repository.CadRepository
import com.myprojects.cad.domain.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@HiltViewModel
class CadEditorViewModel @Inject constructor(
    private val repository: CadRepository,
    private val projects: ProjectRepository,
    private val snapEngine: SnapEngine,
    private val documents: DocumentIO,
    private val pdfExporter: PdfExporter
) : ViewModel() {

    private val _state = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _state.asStateFlow()

    private var canvasW = 0.0
    private var canvasH = 0.0
    private var viewportInitialized = false
    private var snapTargets = SnapTargets()
    private var savedEntities: List<CadEntity> = emptyList()
    private var projectCreatedAt = System.currentTimeMillis()

    init {
        viewModelScope.launch {
            combine(repository.entities, repository.canUndo, repository.canRedo) { e, u, r ->
                Triple(e, u, r)
            }.collect { (entities, undo, redo) ->
                snapTargets = entities.toSnapTargets()
                val ids = entities.map { it.id }.toSet()
                _state.update { s ->
                    s.copy(
                        entities = entities,
                        canUndo = undo,
                        canRedo = redo,
                        selectedIds = s.selectedIds.intersect(ids),
                        dirty = entities != savedEntities
                    )
                }
            }
        }
        viewModelScope.launch {
            val last = withContext(Dispatchers.IO) { projects.lastOpenedId()?.let { projects.load(it) } }
            if (last != null) applyProject(last) else applyProject(CadProject(name = "My Project"))
        }
    }

    // ---- Viewport ---------------------------------------------------------------------------------

    fun onCanvasSizeChanged(widthPx: Int, heightPx: Int) {
        canvasW = widthPx.toDouble()
        canvasH = heightPx.toDouble()
        if (!viewportInitialized && canvasW > 0 && canvasH > 0) {
            viewportInitialized = true
            if (repository.entities.value.isNotEmpty()) zoomExtents()
            else _state.update { it.copy(viewport = Viewport.centered(canvasW, canvasH)) }
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
        val b = repository.entities.value.worldBounds()
        if (b == null) {
            _state.update { it.copy(viewport = Viewport.centered(canvasW, canvasH)) }
            return
        }
        val margin = 48.0
        val w = max(b.width, 0.5)
        val h = max(b.height, 0.5)
        val scale = min((canvasW - 2 * margin) / w, (canvasH - 2 * margin) / h)
            .coerceIn(Viewport.MIN_SCALE, Viewport.MAX_SCALE)
        val cx = (b.minX + b.maxX) / 2.0
        val cy = (b.minY + b.maxY) / 2.0
        _state.update {
            it.copy(viewport = Viewport(canvasW / 2.0 - cx * scale, canvasH / 2.0 + cy * scale, scale))
        }
    }

    // ---- Cursor (touch is a trackpad: drag moves the crosshair, ✓ places) ----------------------------

    fun onTap(screenPx: Vec2) {
        val s = _state.value
        if (s.tool == EditorTool.PAN) return
        val raw = s.viewport.screenToWorld(screenPx)
        val snapped = snapCursor(raw, s)
        _state.update { it.copy(cursorRaw = raw, cursor = snapped) }
        if (s.tool == EditorTool.SELECT) toggleSelectionAt(raw)
    }

    fun onCursorDelta(dxPx: Double, dyPx: Double) {
        val s = _state.value
        val base = s.cursorRaw ?: s.viewport.screenToWorld(Vec2(canvasW / 2.0, canvasH / 2.0))
        val raw = Vec2(base.x + dxPx / s.viewport.scale, base.y - dyPx / s.viewport.scale)
        _state.update { it.copy(cursorRaw = raw, cursor = snapCursor(raw, it)) }
    }

    private fun snapCursor(raw: Vec2, s: EditorUiState): SnapResult {
        var r = snapEngine.snap(raw, snapTargets, s.viewport, s.snapSettings)
        val anchor = s.points.lastOrNull()
        if (s.orthoEnabled && anchor != null) r = r.copy(point = orthoConstrain(anchor, r.point))
        return r
    }

    private fun orthoConstrain(anchor: Vec2, p: Vec2): Vec2 =
        if (abs(p.x - anchor.x) >= abs(p.y - anchor.y)) Vec2(p.x, anchor.y) else Vec2(anchor.x, p.y)

    // ---- Commands ---------------------------------------------------------------------------------

    fun setTool(tool: EditorTool) {
        val s = _state.value
        if (tool.needsSelection && s.selectedIds.isEmpty()) {
            _state.update {
                it.copy(
                    tool = EditorTool.SELECT, points = emptyList(), offsetTargetId = null,
                    message = "Select objects first, then choose ${tool.label}"
                )
            }
            return
        }
        val clearSelection = tool.category == ToolCategory.DRAW
        _state.update {
            it.copy(
                tool = tool, points = emptyList(), offsetTargetId = null,
                selectedIds = if (clearSelection) emptySet() else it.selectedIds
            )
        }
    }

    /** The big ✓ button: numeric entry wins when the fields describe a point, otherwise the crosshair. */
    fun onConfirm() {
        val s = _state.value
        val p = s.numericPoint() ?: s.cursor?.point
        if (p == null) {
            post("Tap or drag on the canvas to place the crosshair")
            return
        }
        val usedNumeric = s.numericPoint() != null
        placePoint(p)
        if (usedNumeric) {
            _state.update {
                it.copy(
                    lengthText = if (it.tool == EditorTool.OFFSET) it.lengthText else "",
                    angleText = if (it.tool == EditorTool.RECT || it.tool == EditorTool.ROTATE) "" else it.angleText
                )
            }
        }
    }

    private fun placePoint(p: Vec2) {
        val s = _state.value
        val pts = s.points
        val t = s.thicknessCm / 100.0
        when (s.tool) {
            EditorTool.WALL -> chain(pts, p) { a, b -> Wall(start = a, end = b, thicknessM = t) }
            EditorTool.LINE -> chain(pts, p) { a, b -> LineEntity(start = a, end = b) }
            EditorTool.POLYLINE -> {
                if (pts.isEmpty() || pts.last().distanceTo(p) > MIN_LEN) setPoints(pts + p)
            }
            EditorTool.RECT -> {
                if (pts.isEmpty()) {
                    setPoints(listOf(p))
                } else {
                    if (abs(p.x - pts[0].x) > MIN_LEN && abs(p.y - pts[0].y) > MIN_LEN) {
                        addEntities(listOf(rectPolyline(pts[0], p)))
                    }
                    setPoints(emptyList())
                }
            }
            EditorTool.CIRCLE -> {
                if (pts.isEmpty()) {
                    setPoints(listOf(p))
                } else {
                    val r = pts[0].distanceTo(p)
                    if (r > MIN_LEN) addEntities(listOf(CircleEntity(center = pts[0], radius = r)))
                    setPoints(emptyList())
                }
            }
            EditorTool.ARC -> when (pts.size) {
                0 -> setPoints(listOf(p))
                1 -> setPoints(pts + p)
                else -> {
                    val arc = arcFrom3(pts[0], pts[1], p)
                    if (arc == null) post("Those points are in a straight line — no arc possible")
                    else addEntities(listOf(arc))
                    setPoints(emptyList())
                }
            }
            EditorTool.TEXT -> _state.update { it.copy(textRequestAt = p) }
            EditorTool.DIMENSION -> when (pts.size) {
                0 -> setPoints(listOf(p))
                1 -> if (pts[0].distanceTo(p) > MIN_LEN) setPoints(pts + p)
                else -> {
                    addEntities(listOf(DimensionEntity(p1 = pts[0], p2 = pts[1], offset = dimensionOffset(pts[0], pts[1], p))))
                    setPoints(emptyList())
                }
            }
            EditorTool.SELECT -> toggleSelectionAt(p)
            EditorTool.MOVE -> {
                if (pts.isEmpty()) setPoints(listOf(p))
                else {
                    val x = Xform.Translate(p - pts[0])
                    transformSelection(x, copy = false)
                    setPoints(emptyList())
                }
            }
            EditorTool.COPY -> {
                if (pts.isEmpty()) setPoints(listOf(p))
                else {
                    transformSelection(Xform.Translate(p - pts[0]), copy = true)
                    keepBasePoint(pts[0])
                }
            }
            EditorTool.ROTATE -> {
                if (pts.isEmpty()) setPoints(listOf(p))
                else {
                    transformSelection(Xform.Rotate(pts[0], Geo.angleDeg(pts[0], p)), copy = false)
                    setPoints(emptyList())
                }
            }
            EditorTool.MIRROR -> {
                if (pts.isEmpty()) setPoints(listOf(p))
                else {
                    if (pts[0].distanceTo(p) > MIN_LEN) transformSelection(Xform.Mirror(pts[0], p), copy = true)
                    setPoints(emptyList())
                }
            }
            EditorTool.OFFSET -> placeOffset(s, p)
            else -> Unit
        }
    }

    private fun placeOffset(s: EditorUiState, p: Vec2) {
        val dist = s.lengthText.toDoubleOrNull()
        if (dist == null || dist <= 0.0) {
            post("Type the offset distance (m) in Length first")
            return
        }
        val target = s.entities.firstOrNull { it.id == s.offsetTargetId }
        if (target == null) {
            val hit = pickEntity(p, s)
            if (hit == null) post("No object at that point")
            else _state.update { it.copy(offsetTargetId = hit.id) }
            return
        }
        val result = target.offsetBy(dist, p)
        if (result == null) post("This object can't be offset that way")
        else addEntities(listOf(result))
        _state.update { it.copy(offsetTargetId = null) }
    }

    private fun chain(pts: List<Vec2>, p: Vec2, make: (Vec2, Vec2) -> CadEntity) {
        val last = pts.lastOrNull()
        if (last == null) {
            setPoints(listOf(p))
            return
        }
        if (last.distanceTo(p) < MIN_LEN) return
        addEntities(listOf(make(last, p)))
        setPoints(listOf(p))
    }

    fun onDone() = finish(close = false)
    fun onClose() = finish(close = true)

    private fun finish(close: Boolean) {
        val s = _state.value
        if (s.tool == EditorTool.POLYLINE && s.points.size >= 2) {
            addEntities(listOf(PolylineEntity(points = s.points, closed = close && s.points.size >= 3)))
        }
        _state.update { it.copy(points = emptyList(), offsetTargetId = null) }
    }

    /** Esc: cancel the running command; pressed again with nothing running, clears the selection. */
    fun cancel() {
        val s = _state.value
        if (s.points.isNotEmpty() || s.offsetTargetId != null || s.textRequestAt != null) {
            _state.update { it.copy(points = emptyList(), offsetTargetId = null, textRequestAt = null) }
        } else {
            _state.update { it.copy(selectedIds = emptySet()) }
        }
    }

    private fun setPoints(list: List<Vec2>) = _state.update { it.copy(points = list) }
    private fun keepBasePoint(base: Vec2) = _state.update { it.copy(points = listOf(base)) }

    private fun addEntities(list: List<CadEntity>) {
        if (list.isNotEmpty()) repository.update { it + list }
    }

    // ---- Selection & modify --------------------------------------------------------------------------

    private fun pickEntity(world: Vec2, s: EditorUiState): CadEntity? {
        val tol = PICK_TOLERANCE_PX / s.viewport.scale
        return s.entities
            .map { it to it.distanceTo(world) }
            .filter { it.second <= tol }
            .minByOrNull { it.second }
            ?.first
    }

    private fun toggleSelectionAt(world: Vec2) {
        val s = _state.value
        val hit = pickEntity(world, s) ?: return
        _state.update {
            it.copy(selectedIds = if (hit.id in it.selectedIds) it.selectedIds - hit.id else it.selectedIds + hit.id)
        }
    }

    fun selectAll() = _state.update { s -> s.copy(selectedIds = s.entities.map { it.id }.toSet()) }

    fun deleteSelected() {
        val ids = _state.value.selectedIds
        if (ids.isEmpty()) return
        repository.update { list -> list.filterNot { it.id in ids } }
        _state.update { it.copy(selectedIds = emptySet(), points = emptyList()) }
    }

    private fun transformSelection(x: Xform, copy: Boolean) {
        val ids = _state.value.selectedIds
        if (ids.isEmpty()) return
        repository.update { list ->
            if (copy) {
                list + list.filter { it.id in ids }.map { it.transformed(x, keepId = false) }
            } else {
                list.map { if (it.id in ids) it.transformed(x) else it }
            }
        }
    }

    /** Lengths (m) of the selected walls/lines, in drawing order — used to pre-fill the beam study. */
    fun selectedSpanLengths(): List<Double> = _state.value.selected.mapNotNull { e ->
        when (e) {
            is Wall -> e.length
            is LineEntity -> e.start.distanceTo(e.end)
            else -> null
        }
    }.map { Math.round(it * 1000.0) / 1000.0 }

    // ---- Text ---------------------------------------------------------------------------------------

    fun confirmText(text: String, heightM: Double) {
        val at = _state.value.textRequestAt
        if (at != null && text.isNotBlank() && heightM > 0.0) {
            addEntities(listOf(TextEntity(position = at, text = text.trim(), heightM = heightM)))
        }
        _state.update { it.copy(textRequestAt = null) }
    }

    fun dismissText() = _state.update { it.copy(textRequestAt = null) }

    // ---- Inputs / toggles -----------------------------------------------------------------------------

    fun onLengthChange(text: String) = _state.update { it.copy(lengthText = sanitize(text, false)) }
    fun onAngleChange(text: String) = _state.update { it.copy(angleText = sanitize(text, true)) }
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
    fun clearDrawing() {
        repository.update { emptyList() }
        _state.update { it.copy(points = emptyList(), selectedIds = emptySet()) }
    }

    private fun post(text: String) = _state.update { it.copy(message = text) }

    private fun sanitize(input: String, allowNegative: Boolean): String {
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

    // ---- Projects (save / open / new / rename) ---------------------------------------------------------

    private fun applyProject(p: CadProject) {
        savedEntities = p.entities
        projectCreatedAt = p.createdAt
        repository.setAll(p.entities)
        _state.update {
            it.copy(
                projectId = p.id, projectName = p.name, selectedIds = emptySet(),
                points = emptyList(), offsetTargetId = null, textRequestAt = null,
                entities = p.entities, dirty = false
            )
        }
        if (viewportInitialized) zoomExtents()
    }

    private suspend fun persist() {
        val s = _state.value
        if (s.projectId.isEmpty()) return
        val snapshot = repository.entities.value
        val project = CadProject(
            id = s.projectId, name = s.projectName, entities = snapshot,
            createdAt = projectCreatedAt, modifiedAt = System.currentTimeMillis()
        )
        projects.save(project)
        projects.setLastOpened(project.id)
        savedEntities = snapshot
        _state.update { it.copy(dirty = repository.entities.value != savedEntities) }
    }

    private suspend fun persistIfDirty() {
        if (repository.entities.value != savedEntities) persist()
    }

    /** Called from Activity.onStop so work is never lost. */
    fun saveIfDirty() {
        viewModelScope.launch { runCatching { persistIfDirty() } }
    }

    fun saveNow() {
        viewModelScope.launch {
            runCatching { persist() }
                .onSuccess { post("Project saved") }
                .onFailure { post("Save failed: ${it.message}") }
        }
    }

    fun newProject(name: String) {
        viewModelScope.launch {
            runCatching { persistIfDirty() }
            applyProject(CadProject(name = name.ifBlank { "New Project" }))
            runCatching { persist() }
        }
    }

    fun renameProject(name: String) {
        if (name.isBlank()) return
        _state.update { it.copy(projectName = name.trim()) }
        viewModelScope.launch { runCatching { persist() } }
    }

    fun refreshProjects() {
        viewModelScope.launch {
            val list = runCatching { projects.list() }.getOrDefault(emptyList())
            _state.update { it.copy(projects = list) }
        }
    }

    fun openProject(id: String) {
        viewModelScope.launch {
            runCatching { persistIfDirty() }
            val p = runCatching { projects.load(id) }.getOrNull()
            if (p == null) {
                post("Could not open that project")
            } else {
                applyProject(p)
                runCatching { projects.setLastOpened(p.id) }
            }
        }
    }

    fun deleteProject(id: String) {
        if (id == _state.value.projectId) {
            post("This project is open — open another one first")
            return
        }
        viewModelScope.launch {
            runCatching { projects.delete(id) }
            refreshProjects()
        }
    }

    // ---- Import / export -------------------------------------------------------------------------------

    fun exportDxf(uri: Uri, unit: DxfUnit) {
        val list = repository.entities.value
        runIo("DXF exported (${unit.label})") {
            documents.write(uri) { out -> out.write(DxfWriter.write(list, unit).toByteArray(Charsets.UTF_8)) }
        }
    }

    fun exportPdf(uri: Uri, paper: PaperSize) {
        val list = repository.entities.value
        val title = _state.value.projectName
        runIo("PDF exported (${paper.label})") {
            documents.write(uri) { out -> pdfExporter.export(title, list, paper, out) }
        }
    }

    /** [unitFactor]: meters per drawing unit, or null to auto-detect from the file. */
    fun importDxf(uri: Uri, unitFactor: Double?) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = runCatching {
                withContext(Dispatchers.IO) { DxfReader.read(documents.read(uri), unitFactor) }
            }
            _state.update { it.copy(busy = false) }
            result.onSuccess { r ->
                if (r.entities.isEmpty()) {
                    post("No supported objects found in this DXF")
                } else {
                    repository.update { it + r.entities }
                    zoomExtents()
                    val skipped = if (r.skipped.isEmpty()) "" else
                        " · skipped " + r.skipped.entries.joinToString { "${it.value}× ${it.key}" }
                    val warn = r.warnings.firstOrNull()?.let { " · $it" } ?: ""
                    post("Imported ${r.entities.size} objects$skipped$warn")
                }
            }.onFailure { post("Import failed: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    private fun runIo(okMessage: String, block: () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = runCatching { withContext(Dispatchers.IO) { block() } }
            _state.update {
                it.copy(
                    busy = false,
                    message = result.fold({ okMessage }, { e -> "Failed: ${e.message ?: e.javaClass.simpleName}" })
                )
            }
        }
    }

    private companion object {
        const val MIN_LEN = 0.001
        const val PICK_TOLERANCE_PX = 16.0
    }
}
