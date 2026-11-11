package com.myprojects.cad.presentation.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myprojects.cad.core.geometry.Units
import com.myprojects.cad.data.dxf.DxfUnit
import com.myprojects.cad.data.export.PaperSize
import com.myprojects.cad.domain.model.CadEntity
import com.myprojects.cad.domain.model.ColumnEntity
import com.myprojects.cad.domain.model.ProjectInfo
import com.myprojects.cad.domain.model.Wall
import com.myprojects.cad.domain.model.kindLabel
import com.myprojects.cad.domain.model.lengthM
import com.myprojects.cad.domain.structural.BeamStudyRequest
import com.myprojects.cad.domain.structural.ColumnStudyRecord
import com.myprojects.cad.domain.structural.ColumnStudyRequest
import com.myprojects.cad.domain.structural.StudyRecord
import com.myprojects.cad.domain.model.WallThickness
import com.myprojects.cad.presentation.beam.BeamStudyScreen
import com.myprojects.cad.presentation.column.ColumnStudyScreen
import com.myprojects.cad.core.engine.SnapType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val HorizontalRulerHeight = 24.dp
private val VerticalRulerWidth = 32.dp

private enum class Dialog { NONE, NEW, RENAME, OPEN, EXPORT_DXF, IMPORT_DXF, EXPORT_PDF, CONFIRM_CLEAR, PROPERTIES, SCHEDULE }
private enum class PanelTab { DRAW, MODIFY, SNAP }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CadEditorScreen(viewModel: CadEditorViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf(Dialog.NONE) }
    var exportUnit by remember { mutableStateOf(DxfUnit.METERS) }
    var importUnit by remember { mutableStateOf<Double?>(null) }
    var paper by remember { mutableStateOf(PaperSize.A3) }
    var beamRequest by remember { mutableStateOf<BeamStudyRequest?>(null) }
    var columnRequest by remember { mutableStateOf<ColumnStudyRequest?>(null) }

    val dxfExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) viewModel.exportDxf(uri, exportUnit) }
    val pdfExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri -> if (uri != null) viewModel.exportPdf(uri, paper) }
    val dxfImport = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.importDxf(uri, importUnit) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    val activeRequest = beamRequest
    if (activeRequest != null) {
        BeamStudyScreen(request = activeRequest, onBack = { beamRequest = null })
        return
    }
    val activeColumn = columnRequest
    if (activeColumn != null) {
        ColumnStudyScreen(request = activeColumn, onBack = { columnRequest = null })
        return
    }

    val fileBase = state.projectName.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
        .joinToString("").ifBlank { "drawing" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.projectName.ifBlank { "مشاريعي" } + if (state.dirty) " •" else "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                actions = {
                    IconButton(onClick = viewModel::undo, enabled = state.canUndo) { Text("↶") }
                    IconButton(onClick = viewModel::redo, enabled = state.canRedo) { Text("↷") }
                    IconButton(onClick = viewModel::zoomExtents) { Text("⤢") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Text("⋮") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            MenuEntry("Save project", { menuOpen = false }) { viewModel.saveNow() }
                            MenuEntry("New project…", { menuOpen = false }) { dialog = Dialog.NEW }
                            MenuEntry("Open project…", { menuOpen = false }) { viewModel.refreshProjects(); dialog = Dialog.OPEN }
                            MenuEntry("Rename…", { menuOpen = false }) { dialog = Dialog.RENAME }
                            MenuEntry("Import DXF…", { menuOpen = false }) { dialog = Dialog.IMPORT_DXF }
                            MenuEntry("Export DXF…", { menuOpen = false }) { dialog = Dialog.EXPORT_DXF }
                            MenuEntry("Study selected element", { menuOpen = false }) {
                                if (state.selected.any { it is ColumnEntity }) columnRequest = viewModel.prepareColumnStudy()
                                else beamRequest = viewModel.prepareStudy()
                            }
                            MenuEntry("Study schedule…", { menuOpen = false }) { dialog = Dialog.SCHEDULE }
                            MenuEntry("Export PDF…", { menuOpen = false }) { dialog = Dialog.EXPORT_PDF }
                            MenuEntry("Clear drawing", { menuOpen = false }) { dialog = Dialog.CONFIRM_CLEAR }
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            // Drawing area is always LTR so rulers/canvas geometry never mirrors in Arabic locales.
            Box(Modifier.weight(1f)) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().height(HorizontalRulerHeight)) {
                            Box(
                                Modifier
                                    .size(VerticalRulerWidth, HorizontalRulerHeight)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            )
                            CadRuler(
                                viewport = state.viewport,
                                orientation = RulerOrientation.HORIZONTAL,
                                cursorWorld = state.cursor?.point?.x,
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            CadRuler(
                                viewport = state.viewport,
                                orientation = RulerOrientation.VERTICAL,
                                cursorWorld = state.cursor?.point?.y,
                                modifier = Modifier.width(VerticalRulerWidth).fillMaxHeight()
                            )
                            CadCanvas(
                                state = state,
                                onSizeChanged = viewModel::onCanvasSizeChanged,
                                onTransform = viewModel::onTransform,
                                onCursorDelta = viewModel::onCursorDelta,
                                onTap = viewModel::onTap,
                                onBox = viewModel::onSelectBox,
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }
                    }
                }
            }
            EditorControls(
                state, viewModel,
                onStudy = {
                    if (state.selected.any { it is ColumnEntity }) columnRequest = viewModel.prepareColumnStudy()
                    else beamRequest = viewModel.prepareStudy()
                },
                onProps = { dialog = Dialog.PROPERTIES }
            )
        }
    }

    // ---- Dialogs ----------------------------------------------------------------------------------------

    when (dialog) {
        Dialog.NONE -> Unit
        Dialog.NEW -> NameDialog("New project", "", "Create", { dialog = Dialog.NONE }) {
            dialog = Dialog.NONE
            viewModel.newProject(it)
        }
        Dialog.RENAME -> NameDialog("Rename project", state.projectName, "Rename", { dialog = Dialog.NONE }) {
            dialog = Dialog.NONE
            viewModel.renameProject(it)
        }
        Dialog.OPEN -> ProjectListDialog(
            projects = state.projects,
            currentId = state.projectId,
            onOpen = { dialog = Dialog.NONE; viewModel.openProject(it) },
            onDelete = viewModel::deleteProject,
            onDismiss = { dialog = Dialog.NONE }
        )
        Dialog.EXPORT_DXF -> OptionDialog(
            title = "Export DXF",
            note = "AutoCAD-compatible (R12). Walls become closed polylines on layer WALLS; dimensions are exported as lines + text.",
            confirm = "Export",
            onDismiss = { dialog = Dialog.NONE },
            onConfirm = { dialog = Dialog.NONE; dxfExport.launch("$fileBase.dxf") }
        ) {
            Text("Drawing unit", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DxfUnit.values().forEach { u ->
                    FilterChip(selected = exportUnit == u, onClick = { exportUnit = u }, label = { Text(u.label) })
                }
            }
        }
        Dialog.IMPORT_DXF -> OptionDialog(
            title = "Import DXF",
            note = "Objects are added to the current drawing (undo-able). Supported: lines, polylines, circles, arcs, text.",
            confirm = "Choose file…",
            onDismiss = { dialog = Dialog.NONE },
            onConfirm = { dialog = Dialog.NONE; dxfImport.launch(arrayOf("*/*")) }
        ) {
            Text("File unit", style = MaterialTheme.typography.labelMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = importUnit == null, onClick = { importUnit = null }, label = { Text("Auto") })
                FilterChip(selected = importUnit == 1.0, onClick = { importUnit = 1.0 }, label = { Text("m") })
                FilterChip(selected = importUnit == 0.01, onClick = { importUnit = 0.01 }, label = { Text("cm") })
                FilterChip(selected = importUnit == 0.001, onClick = { importUnit = 0.001 }, label = { Text("mm") })
            }
        }
        Dialog.EXPORT_PDF -> OptionDialog(
            title = "Export PDF",
            note = "Vector PDF with title block. The architectural scale (1:50, 1:100 …) is chosen automatically to fit the sheet.",
            confirm = "Export",
            onDismiss = { dialog = Dialog.NONE },
            onConfirm = { dialog = Dialog.NONE; pdfExport.launch("$fileBase.pdf") }
        ) {
            Text("Sheet size", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaperSize.values().forEach { p ->
                    FilterChip(selected = paper == p, onClick = { paper = p }, label = { Text(p.label) })
                }
            }
        }
        Dialog.PROPERTIES -> PropertiesDialog(
            selected = state.selected,
            onDismiss = { dialog = Dialog.NONE },
            onRename = viewModel::renameSelected,
            onAutoName = viewModel::autoNameSelected,
            onLayer = viewModel::setLayerSelected,
            onThickness = viewModel::setThicknessSelected,
            onColumnSize = viewModel::setColumnSizeSelected
        )
        Dialog.SCHEDULE -> ScheduleDialog(
            studies = state.studies,
            columns = state.columnStudies,
            onZoom = { id -> dialog = Dialog.NONE; viewModel.focusStudy(id) },
            onOpen = { id -> dialog = Dialog.NONE; beamRequest = viewModel.studyRequestFor(id) },
            onZoomColumn = { id -> dialog = Dialog.NONE; viewModel.focusColumnStudy(id) },
            onOpenColumn = { id -> dialog = Dialog.NONE; columnRequest = viewModel.columnRequestFor(id) },
            onDismiss = { dialog = Dialog.NONE }
        )
        Dialog.CONFIRM_CLEAR -> OptionDialog(
            title = "Clear drawing?",
            note = "Removes every object. You can undo this.",
            confirm = "Clear",
            onDismiss = { dialog = Dialog.NONE },
            onConfirm = { dialog = Dialog.NONE; viewModel.clearDrawing() }
        ) {}
    }

    if (state.textRequestAt != null) {
        TextDialog(onDismiss = viewModel::dismissText, onConfirm = viewModel::confirmText)
    }
}

@Composable
private fun MenuEntry(label: String, closeMenu: () -> Unit, action: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = { closeMenu(); action() })
}

// ---- Bottom controls ----------------------------------------------------------------------------------

@Composable
private fun EditorControls(
    state: EditorUiState,
    vm: CadEditorViewModel,
    onStudy: () -> Unit,
    onProps: () -> Unit
) {
    var tab by remember { mutableStateOf(PanelTab.DRAW) }
    var showNumeric by remember { mutableStateOf(false) }

    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(statusText(state), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                state.prompt,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2
            )

            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PanelTab.values().forEach { t ->
                    TextButton(onClick = { tab = t }) {
                        Text(
                            t.name.lowercase().replaceFirstChar { it.uppercase() },
                            color = if (tab == t) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                ToolChip(EditorTool.PAN, state, vm)
                when (tab) {
                    PanelTab.DRAW -> {
                        EditorTool.values().filter { it.category == ToolCategory.DRAW }
                            .forEach { ToolChip(it, state, vm) }
                        if (state.tool == EditorTool.COLUMN) {
                            ColumnPreset.ALL.forEach { pr ->
                                FilterChip(
                                    selected = state.columnPreset == pr,
                                    onClick = { vm.setColumnPreset(pr) },
                                    label = { Text(pr.label) }
                                )
                            }
                        }
                    }
                    PanelTab.MODIFY -> {
                        EditorTool.values().filter { it.category == ToolCategory.MODIFY }
                            .forEach { ToolChip(it, state, vm) }
                        TextButton(onClick = vm::selectAll) { Text("All") }
                        TextButton(onClick = vm::selectConnected, enabled = state.selectedIds.isNotEmpty()) { Text("Chain") }
                        TextButton(onClick = vm::selectSameKind, enabled = state.selectedIds.isNotEmpty()) { Text("Same") }
                        TextButton(onClick = vm::invertSelection) { Text("Invert") }
                    }
                    PanelTab.SNAP -> {
                        FilterChip(state.snapSettings.gridEnabled, vm::toggleGridSnap, { Text("Grid") })
                        FilterChip(state.snapSettings.objectEnabled, vm::toggleObjectSnap, { Text("Object") })
                        FilterChip(state.orthoEnabled, vm::toggleOrtho, { Text("Ortho") })
                        FilterChip(state.labelMode != LabelMode.OFF, vm::cycleLabelMode, { Text("Labels: ${state.labelMode.label}") })
                        Text("Wall", style = MaterialTheme.typography.labelMedium)
                        WallThickness.STANDARD_CM.forEach { cm ->
                            FilterChip(state.thicknessCm == cm, { vm.setThickness(cm) }, { Text("$cm cm") })
                        }
                    }
                }
            }

            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = vm::onConfirm) { Text("✓ Place") }
                OutlinedButton(onClick = vm::onDone) { Text("Done") }
                OutlinedButton(
                    onClick = vm::onClose,
                    enabled = state.tool == EditorTool.POLYLINE && state.points.size >= 3
                ) { Text("Close") }
                OutlinedButton(onClick = vm::cancel) { Text("Esc") }
                OutlinedButton(onClick = vm::deleteSelected, enabled = state.selectedIds.isNotEmpty()) { Text("Delete") }
                Button(onClick = onStudy, enabled = state.selectedIds.isNotEmpty()) { Text("Study") }
                OutlinedButton(onClick = onProps, enabled = state.selectedIds.isNotEmpty()) { Text("Props") }
                TextButton(onClick = { showNumeric = !showNumeric }) { Text(if (showNumeric) "123 ▾" else "123 ▸") }
            }

            if (showNumeric) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = state.lengthText,
                        onValueChange = vm::onLengthChange,
                        label = { Text(lengthLabel(state.tool)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = state.angleText,
                        onValueChange = vm::onAngleChange,
                        label = { Text(angleLabel(state.tool)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolChip(tool: EditorTool, state: EditorUiState, vm: CadEditorViewModel) {
    FilterChip(
        selected = state.tool == tool,
        onClick = { vm.setTool(tool) },
        label = { Text(tool.label) }
    )
}

private fun lengthLabel(tool: EditorTool): String = when (tool) {
    EditorTool.RECT -> "Width (m)"
    EditorTool.CIRCLE -> "Radius (m)"
    EditorTool.OFFSET -> "Distance (m)"
    EditorTool.ROTATE -> "—"
    else -> "Length (m)"
}

private fun angleLabel(tool: EditorTool): String = when (tool) {
    EditorTool.RECT -> "Height (m)"
    else -> "Angle (°)"
}

private fun statusText(state: EditorUiState): String {
    if (state.selectedIds.isNotEmpty() && state.tool == EditorTool.SELECT) {
        val sel = state.selected
        val names = sel.map { it.name }.filter { it.isNotBlank() }.take(4).joinToString(", ")
        val total = sel.mapNotNull { it.lengthM() }.sum()
        return "${sel.size} selected · total ${Units.formatLength(total)}" + if (names.isNotEmpty()) " · $names" else ""
    }
    val cursor = state.cursor ?: return "Tap the canvas, then drag to fine-tune the crosshair"
    val pos = "X ${Units.formatCoord(cursor.point.x)}  Y ${Units.formatCoord(cursor.point.y)}  · ${snapLabel(cursor.type)}"
    val last = state.points.lastOrNull() ?: return pos
    val d = last.distanceTo(cursor.point)
    val a = Math.toDegrees(Math.atan2(cursor.point.y - last.y, cursor.point.x - last.x))
    return pos + "  |  L ${Units.formatLength(d)}  ∠ ${Units.formatAngle(if (a < 0) a + 360.0 else a)}"
}

private fun snapLabel(type: SnapType): String = when (type) {
    SnapType.NONE -> "Free"
    SnapType.ENDPOINT -> "Endpoint"
    SnapType.INTERSECTION -> "Intersection"
    SnapType.MIDPOINT -> "Midpoint"
    SnapType.CENTER -> "Center"
    SnapType.GRID -> "Grid"
}

// ---- Dialog building blocks ----------------------------------------------------------------------------

@Composable
private fun NameDialog(
    title: String, initial: String, confirmLabel: String,
    onDismiss: () -> Unit, onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Project name") })
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun OptionDialog(
    title: String, note: String, confirm: String,
    onDismiss: () -> Unit, onConfirm: () -> Unit,
    content: @Composable () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(note, style = MaterialTheme.typography.bodySmall)
                content()
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ProjectListDialog(
    projects: List<ProjectInfo>, currentId: String,
    onOpen: (String) -> Unit, onDelete: (String) -> Unit, onDismiss: () -> Unit
) {
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Projects") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (projects.isEmpty()) Text("No saved projects yet")
                projects.forEach { p ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onOpen(p.id) }, modifier = Modifier.weight(1f)) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(
                                    p.name + if (p.id == currentId) "  (open)" else "",
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${fmt.format(Date(p.modifiedAt))} · ${p.entityCount} objects",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                        TextButton(onClick = { onDelete(p.id) }, enabled = p.id != currentId) { Text("✕") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun TextDialog(onDismiss: () -> Unit, onConfirm: (String, Double) -> Unit) {
    var text by remember { mutableStateOf("") }
    var height by remember { mutableStateOf("0.25") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add text") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Text") })
                OutlinedTextField(
                    value = height,
                    onValueChange = { height = it.replace(',', '.').filter { c -> c in '0'..'9' || c == '.' } },
                    label = { Text("Height (m)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text, height.toDoubleOrNull() ?: 0.0) },
                enabled = text.isNotBlank() && (height.toDoubleOrNull() ?: 0.0) > 0.0
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}


// ---- Properties & schedule dialogs --------------------------------------------------------------------------

@Composable
private fun PropertiesDialog(
    selected: List<CadEntity>,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onAutoName: (String) -> Unit,
    onLayer: (String) -> Unit,
    onThickness: (Int) -> Unit,
    onColumnSize: (Int, Int, Double) -> Unit
) {
    val single = selected.singleOrNull()
    val col = single as? ColumnEntity
    var colW by remember(single?.id) { mutableStateOf(col?.let { Math.round(it.widthM * 100).toString() } ?: "") }
    var colD by remember(single?.id) { mutableStateOf(col?.let { Math.round(it.depthM * 100).toString() } ?: "") }
    var colRot by remember(single?.id) { mutableStateOf(col?.rotationDeg?.let { Math.round(it).toString() } ?: "0") }
    var name by remember(single?.id) { mutableStateOf(single?.name ?: "") }
    var layer by remember(single?.id) { mutableStateOf(single?.layer ?: "") }
    var prefix by remember { mutableStateOf("B") }
    var thick by remember(single?.id) { mutableStateOf(((single as? Wall)?.thicknessM?.times(100.0))?.toInt()?.toString() ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (single != null) "${single.kindLabel()} properties" else "${selected.size} elements") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (single != null) {
                    val len = single.lengthM()
                    Text(
                        (if (len != null) "Length ${Units.formatLength(len)}" else "") +
                            (if (single is Wall) " · angle ${Units.formatAngle(single.angleDeg)}" else ""),
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = name, onValueChange = { name = it }, singleLine = true,
                        label = { Text("Name / mark (e.g. B1)") }
                    )
                    OutlinedTextField(value = layer, onValueChange = { layer = it }, singleLine = true, label = { Text("Layer") })
                    if (col != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = colW, onValueChange = { colW = it.filter { c -> c.isDigit() } }, singleLine = true,
                                label = { Text(if (col.shape == com.myprojects.cad.domain.model.ColumnShape.CIRCULAR) "Ø (cm)" else "Width (cm)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f)
                            )
                            if (col.shape != com.myprojects.cad.domain.model.ColumnShape.CIRCULAR) {
                                OutlinedTextField(
                                    value = colD, onValueChange = { colD = it.filter { c -> c.isDigit() } }, singleLine = true,
                                    label = { Text("Depth (cm)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f)
                                )
                            }
                            OutlinedTextField(
                                value = colRot, onValueChange = { colRot = it.filter { c -> c.isDigit() || c == '-' } }, singleLine = true,
                                label = { Text("Rotation °") }, modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    if (single is Wall) {
                        OutlinedTextField(
                            value = thick, onValueChange = { thick = it.filter { c -> c.isDigit() } }, singleLine = true,
                            label = { Text("Wall thickness (cm)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                } else {
                    val total = selected.mapNotNull { it.lengthM() }.sum()
                    Text("Total length ${Units.formatLength(total)}", style = MaterialTheme.typography.bodySmall)
                    Text("Number them in drawing order:", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = prefix, onValueChange = { prefix = it }, singleLine = true,
                            label = { Text("Prefix") }, modifier = Modifier.weight(1f)
                        )
                        Button(onClick = { onAutoName(prefix) }, enabled = prefix.isNotBlank()) { Text("Auto-number") }
                    }
                    OutlinedTextField(value = layer, onValueChange = { layer = it }, singleLine = true, label = { Text("Layer for all") })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (single != null) onRename(name)
                if (layer.isNotBlank()) onLayer(layer)
                thick.toIntOrNull()?.let { onThickness(it) }
                if (col != null) {
                    val w = colW.toIntOrNull()
                    val depthCm = colD.toIntOrNull() ?: w
                    if (w != null && depthCm != null) onColumnSize(w, depthCm, colRot.toDoubleOrNull() ?: 0.0)
                }
                onDismiss()
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun ScheduleDialog(
    studies: List<StudyRecord>,
    columns: List<ColumnStudyRecord>,
    onZoom: (String) -> Unit,
    onOpen: (String) -> Unit,
    onZoomColumn: (String) -> Unit,
    onOpenColumn: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Study schedule — ${studies.size} beams, ${columns.size} columns") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (studies.isEmpty() && columns.isEmpty()) Text("Nothing studied yet. Select a line or a column and press Study.")
                if (columns.isNotEmpty()) Text("Columns", style = MaterialTheme.typography.titleMedium)
                columns.sortedBy { it.name }.forEach { cs ->
                    val sum = cs.summary
                    Column {
                        Text(cs.name + (if (sum != null) "  " + (if (sum.ok) "✓" else "✗") else ""), style = MaterialTheme.typography.titleSmall)
                        if (sum != null) {
                            Text("${sum.section} cm · ${sum.bars} · ${sum.ties}", style = MaterialTheme.typography.bodySmall)
                            Text(
                                "Pu ${"%.0f".format(Locale.US, sum.puKn)} kN · ratio ${"%.2f".format(Locale.US, sum.ratio)}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Row {
                            TextButton(onClick = { onZoomColumn(cs.id) }) { Text("Show") }
                            TextButton(onClick = { onOpenColumn(cs.id) }) { Text("Open study") }
                        }
                    }
                }
                if (studies.isNotEmpty()) Text("Beams", style = MaterialTheme.typography.titleMedium)
                studies.sortedBy { it.name }.forEach { st ->
                    val sum = st.summary
                    Column {
                        Text(
                            st.name + (if (sum != null) "  " + (if (sum.ok) "✓" else "✗") else ""),
                            style = MaterialTheme.typography.titleSmall
                        )
                        if (sum != null) {
                            Text(
                                "${sum.section} cm · bottom ${sum.bottom} · top ${sum.top} · stirrups ${sum.stirrups}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "Mu max ${"%.1f".format(Locale.US, sum.maxMomentKnM)} kN·m · Vu max ${"%.1f".format(Locale.US, sum.maxShearKn)} kN",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Row {
                            TextButton(onClick = { onZoom(st.id) }) { Text("Show") }
                            TextButton(onClick = { onOpen(st.id) }) { Text("Open study") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
