package com.myprojects.cad.presentation.editor

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myprojects.cad.core.engine.SnapEngine
import com.myprojects.cad.core.engine.SnapResult
import com.myprojects.cad.core.engine.SnapTargets
import com.myprojects.cad.core.engine.Viewport
import com.myprojects.cad.core.engine.WorldRect
import com.myprojects.cad.core.geometry.Geo
import com.myprojects.cad.core.geometry.Vec2
import com.myprojects.cad.data.dxf.DxfReader
import com.myprojects.cad.data.dxf.DxfUnit
import com.myprojects.cad.data.dxf.DxfWriter
import com.myprojects.cad.data.export.PaperSize
import com.myprojects.cad.data.export.PdfExporter
import com.myprojects.cad.data.io.DocumentIO
import com.myprojects.cad.domain.model.CadEntity
import com.myprojects.cad.domain.model.ChainBuilder
import com.myprojects.cad.domain.model.ColumnEntity
import com.myprojects.cad.domain.model.CadProject
import com.myprojects.cad.domain.model.CircleEntity
import com.myprojects.cad.domain.model.DimensionEntity
import com.myprojects.cad.domain.model.LineEntity
import com.myprojects.cad.domain.model.PolylineEntity
import com.myprojects.cad.domain.model.TextEntity
import com.myprojects.cad.domain.model.Wall
import com.myprojects.cad.domain.model.Xform
import com.myprojects.cad.domain.model.arcFrom3
import com.myprojects.cad.domain.model.insideRect
import com.myprojects.cad.domain.model.kindLabel
import com.myprojects.cad.domain.model.nextName
import com.myprojects.cad.domain.model.touchesRect
import com.myprojects.cad.domain.model.withLayer
import com.myprojects.cad.domain.model.withName
import com.myprojects.cad.domain.structural.BeamStudyRequest
import com.myprojects.cad.domain.structural.ColumnStudyRecord
import com.myprojects.cad.domain.structural.ColumnStudyRequest
import com.myprojects.cad.domain.structural.StudyRecord
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
import java.util.Locale
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
    private var savedStudies: List<StudyRecord> = emptyList()
    private var savedColumnStudies: List<ColumnStudyRecord> = emptyList()
    private var projectCreatedAt = System.currentTimeMillis()

    private data class Snapshot(
        val entities: List<CadEntity>,
        val undo: Boolean,
        val redo: Boolean,
        val studies: List<StudyRecord>,
        val columnStudies: List<ColumnStudyRecord>
    )

    init {
        viewModelScope.launch {
            combine(
                repository.entities, repository.canUndo, repository.canRedo, repository.studies, repository.columnStudies
            ) { e, u, r, st, cs -> Snapshot(e, u, r, st, cs) }.collect { snap ->
                snapTargets = snap.entities.toSnapTargets()
                val ids = snap.entities.map { it.id }.toSet()
                // A study is only shown while every element it was made from still exists.
                val active = snap.studies.filter { st -> st.entityIds.all { it in ids } }
                val activeColumns = snap.columnStudies.filter { it.entityId in ids }
                _state.update { s ->
                    s.copy(
                        entities = snap.entities,
                        canUndo = snap.undo,
                        canRedo = snap.redo,
                        studies = active,
                        columnStudies = activeColumns,
                        selectedIds = s.selectedIds.intersect(ids),
                        dirty = snap.entities != savedEntities || active != savedStudies || activeColumns != savedColumnStudies
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

    fun zoomExtents() = zoomToEntities(repository.entities.value)

    private fun zoomToEntities(list: List<CadEntity>) {
        if (canvasW <= 0.0 || canvasH <= 0.0) return
        val b = list.worldBounds()
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
            EditorTool.COLUMN -> {
                val pr = s.columnPreset
                addEntities(
                    listOf(
                        ColumnEntity(
                            center = p, widthM = pr.widthCm / 100.0, depthM = pr.depthCm / 100.0, shape = pr.shape
                        )
                    )
                )
            }
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

    // ---- Selection tools -----------------------------------------------------------------------------------

    /** Drag in the Select tool: left→right = window (fully inside), right→left = crossing (touching). */
    fun onSelectBox(startPx: Vec2, endPx: Vec2, finished: Boolean) {
        if (!finished) {
            _state.update { it.copy(selectBox = SelectBox(startPx, endPx)) }
            return
        }
        val s = _state.value
        _state.update { it.copy(selectBox = null) }
        val a = s.viewport.screenToWorld(startPx)
        val b = s.viewport.screenToWorld(endPx)
        val rect = WorldRect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
        val crossing = endPx.x < startPx.x
        val hits = s.entities.filter { if (crossing) it.touchesRect(rect) else it.insideRect(rect) }.map { it.id }
        if (hits.isEmpty()) post("Nothing ${if (crossing) "touches" else "inside"} that box")
        else _state.update { it.copy(selectedIds = it.selectedIds + hits) }
    }

    fun selectConnected() {
        val s = _state.value
        if (s.selectedIds.isEmpty()) {
            post("Select an element first")
            return
        }
        val ids = ChainBuilder.connectedIds(s.entities, s.selectedIds)
        _state.update { it.copy(selectedIds = ids) }
    }

    fun invertSelection() = _state.update { s ->
        s.copy(selectedIds = s.entities.map { it.id }.toSet() - s.selectedIds)
    }

    fun selectSameKind() {
        val s = _state.value
        val first = s.selected.firstOrNull()
        if (first == null) {
            post("Select an element first")
            return
        }
        val kind = first.kindLabel()
        _state.update { st -> st.copy(selectedIds = st.entities.filter { it.kindLabel() == kind }.map { it.id }.toSet()) }
    }

    // ---- Names & properties --------------------------------------------------------------------------------

    fun renameSelected(name: String) {
        val ids = _state.value.selectedIds
        if (ids.isEmpty()) return
        val clean = name.trim()
        repository.update { list -> list.map { if (it.id in ids) it.withName(clean) else it } }
        // Keep a beam study's mark in step with its elements.
        repository.studies.value.filter { it.entityIds.all { id -> id in ids } && clean.isNotEmpty() }
            .forEach { repository.upsertStudy(it.copy(name = clean)) }
    }

    /** Numbers the selected elements B1, B2 … continuing after the highest existing mark with that prefix. */
    fun autoNameSelected(prefix: String) {
        val s = _state.value
        val p = prefix.trim()
        if (p.isEmpty() || s.selectedIds.isEmpty()) return
        val existing = (s.entities.map { it.name } + s.studies.map { it.name }).toMutableList()
        val newNames = HashMap<String, String>()
        for (e in s.entities) {
            if (e.id in s.selectedIds) {
                val n = nextName(p, existing)
                existing.add(n)
                newNames[e.id] = n
            }
        }
        repository.update { list -> list.map { e -> newNames[e.id]?.let { e.withName(it) } ?: e } }
    }

    fun setLayerSelected(layer: String) {
        val ids = _state.value.selectedIds
        val clean = layer.trim()
        if (ids.isEmpty() || clean.isEmpty()) return
        repository.update { list -> list.map { if (it.id in ids) it.withLayer(clean) else it } }
    }

    fun setThicknessSelected(cm: Int) {
        val ids = _state.value.selectedIds
        if (ids.isEmpty() || cm !in 5..100) return
        repository.update { list ->
            list.map { if (it.id in ids && it is Wall) it.copy(thicknessM = cm / 100.0) else it }
        }
    }

    fun cycleLabelMode() = _state.update {
        val next = when (it.labelMode) {
            LabelMode.OFF -> LabelMode.NAMES
            LabelMode.NAMES -> LabelMode.FULL
            LabelMode.FULL -> LabelMode.OFF
        }
        it.copy(labelMode = next)
    }

    // ---- Beam study ------------------------------------------------------------------------------------------

    /** Builds the request for the selected line(s): one connected run of lines = one beam (continuous if several spans). */
    fun prepareStudy(): BeamStudyRequest? {
        val s = _state.value
        val chains = ChainBuilder.build(s.selected)
        val chain = chains.firstOrNull()
        if (chain == null) {
            post("Select a line, wall or polyline first, then press Study")
            return null
        }
        val ids = chain.entityIds
        val existing = s.studies.firstOrNull { it.entityIds.toSet() == ids.toSet() }
        val named = s.entities.firstOrNull { it.id in ids && it.name.isNotBlank() }?.name
        val name = existing?.name ?: named ?: nextName("B", s.entities.map { it.name } + s.studies.map { it.name })
        val notes = ArrayList<String>()
        if (chains.size > 1) notes.add("${chains.size} separate beams selected — studying the first; select the others one by one.")
        if (chain.spansM.size > 8) notes.add("More than 8 spans — only the first 8 are used.")
        if (!chain.straight) notes.add("The selected run bends; it is analysed as a straight continuous beam.")
        return BeamStudyRequest(
            entityIds = ids,
            name = name,
            spansM = chain.spansM.take(8).map { Math.round(it * 1000.0) / 1000.0 },
            note = notes.joinToString(" ").ifEmpty { null },
            projectName = s.projectName,
            boundaryPoints = chain.points.take(9)
        )
    }

    /** Selects and zooms to the elements of a saved study. */
    fun focusStudy(studyId: String) {
        val st = _state.value.studies.firstOrNull { it.id == studyId } ?: return
        val members = _state.value.entities.filter { it.id in st.entityIds }
        _state.update { it.copy(selectedIds = st.entityIds.toSet(), tool = EditorTool.SELECT, points = emptyList()) }
        zoomToEntities(members)
    }

    fun studyRequestFor(studyId: String): BeamStudyRequest? {
        val st = _state.value.studies.firstOrNull { it.id == studyId } ?: return null
        _state.update { it.copy(selectedIds = st.entityIds.toSet()) }
        return prepareStudy()
    }

    // ---- Columns --------------------------------------------------------------------------------------------

    fun setColumnPreset(preset: ColumnPreset) = _state.update { it.copy(columnPreset = preset) }

    /** Width / depth in cm and rotation for the selected columns (circular columns keep depth = width). */
    fun setColumnSizeSelected(widthCm: Int, depthCm: Int, rotationDeg: Double) {
        val ids = _state.value.selectedIds
        if (ids.isEmpty() || widthCm !in 15..200 || depthCm !in 15..200) return
        repository.update { list ->
            list.map { e ->
                if (e.id in ids && e is ColumnEntity) {
                    val d = if (e.shape == com.myprojects.cad.domain.model.ColumnShape.CIRCULAR) widthCm else depthCm
                    e.copy(widthM = widthCm / 100.0, depthM = d / 100.0, rotationDeg = rotationDeg)
                } else e
            }
        }
    }

    /**
     * Study request for the selected column. The axial load comes from the beams that rest on it: every saved
     * beam study that has a support inside (or within 15 cm of) the column footprint contributes its reactions.
     */
    fun prepareColumnStudy(): ColumnStudyRequest? {
        val s = _state.value
        val col = s.selected.filterIsInstance<ColumnEntity>().firstOrNull()
        if (col == null) {
            post("Select a column first, then press Study")
            return null
        }
        var pd = 0.0
        var pl = 0.0
        val sources = ArrayList<String>()
        for (study in s.studies) {
            for (sup in study.supports) {
                if (col.contains(sup.point) || sup.point.distanceTo(col.center) <= 0.15) {
                    pd += sup.deadKn
                    pl += sup.liveKn
                    sources.add("${study.name}: D ${"%.1f".format(Locale.US, sup.deadKn)} / L ${"%.1f".format(Locale.US, sup.liveKn)} kN")
                }
            }
        }
        val existing = s.columnStudies.firstOrNull { it.entityId == col.id }
        val name = existing?.name ?: col.name.ifBlank {
            nextName("C", s.entities.map { it.name } + s.columnStudies.map { it.name })
        }
        val notes = ArrayList<String>()
        if (sources.isEmpty()) notes.add("No studied beam rests on this column — enter the loads by hand (or study the beams first).")
        if (s.selected.count { it is ColumnEntity } > 1) notes.add("Several columns selected — studying the first.")
        return ColumnStudyRequest(
            entityId = col.id, name = name, shape = col.shape,
            bMm = Math.round(col.widthM * 1000.0).toDouble(), hMm = Math.round(col.depthM * 1000.0).toDouble(),
            pdKn = pd, plKn = pl, loadSources = sources,
            note = notes.joinToString(" ").ifEmpty { null }, projectName = s.projectName
        )
    }

    fun focusColumnStudy(studyId: String) {
        val st = _state.value.columnStudies.firstOrNull { it.id == studyId } ?: return
        val members = _state.value.entities.filter { it.id == st.entityId }
        _state.update { it.copy(selectedIds = setOf(st.entityId), tool = EditorTool.SELECT, points = emptyList()) }
        zoomToEntities(members)
    }

    fun columnRequestFor(studyId: String): ColumnStudyRequest? {
        val st = _state.value.columnStudies.firstOrNull { it.id == studyId } ?: return null
        _state.update { it.copy(selectedIds = setOf(st.entityId)) }
        return prepareColumnStudy()
    }

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
        savedStudies = p.studies
        savedColumnStudies = p.columnStudies
        projectCreatedAt = p.createdAt
        repository.setAll(p.entities)
        repository.setStudies(p.studies)
        repository.setColumnStudies(p.columnStudies)
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
        val ids = snapshot.map { it.id }.toSet()
        val studies = repository.studies.value.filter { st -> st.entityIds.all { it in ids } }
        val columnStudies = repository.columnStudies.value.filter { it.entityId in ids }
        val project = CadProject(
            id = s.projectId, name = s.projectName, entities = snapshot, studies = studies, columnStudies = columnStudies,
            createdAt = projectCreatedAt, modifiedAt = System.currentTimeMillis()
        )
        projects.save(project)
        projects.setLastOpened(project.id)
        savedEntities = snapshot
        savedStudies = studies
        savedColumnStudies = columnStudies
        repository.setStudies(studies)
        repository.setColumnStudies(columnStudies)
        _state.update { it.copy(dirty = repository.entities.value != savedEntities) }
    }

    private suspend fun persistIfDirty() {
        val ids = repository.entities.value.map { it.id }.toSet()
        val active = repository.studies.value.filter { st -> st.entityIds.all { it in ids } }
        val activeColumns = repository.columnStudies.value.filter { it.entityId in ids }
        if (repository.entities.value != savedEntities || active != savedStudies || activeColumns != savedColumnStudies) persist()
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
