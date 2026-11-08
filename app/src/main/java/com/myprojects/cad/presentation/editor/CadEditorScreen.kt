package com.myprojects.cad.presentation.editor

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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myprojects.cad.core.engine.SnapType
import com.myprojects.cad.core.geometry.Units
import com.myprojects.cad.domain.model.WallThickness

private val HorizontalRulerHeight = 24.dp
private val VerticalRulerWidth = 32.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CadEditorScreen(viewModel: CadEditorViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("مشاريعي") },
                actions = {
                    IconButton(onClick = viewModel::undo, enabled = state.canUndo) { Text("↶") }
                    IconButton(onClick = viewModel::redo, enabled = state.canRedo) { Text("↷") }
                    IconButton(onClick = viewModel::zoomExtents) { Text("⤢") }
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
                                onTap = viewModel::onCanvasTap,
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }
                    }
                }
            }
            EditorControls(state, viewModel)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorControls(state: EditorUiState, vm: CadEditorViewModel) {
    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = statusText(state),
                style = MaterialTheme.typography.labelMedium
            )

            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = state.tool == EditorTool.PAN,
                    onClick = { vm.setTool(EditorTool.PAN) },
                    label = { Text("Pan") }
                )
                FilterChip(
                    selected = state.tool == EditorTool.WALL,
                    onClick = { vm.setTool(EditorTool.WALL) },
                    label = { Text("Wall") }
                )
                FilterChip(
                    selected = state.snapSettings.gridEnabled,
                    onClick = vm::toggleGridSnap,
                    label = { Text("Grid") }
                )
                FilterChip(
                    selected = state.snapSettings.objectEnabled,
                    onClick = vm::toggleObjectSnap,
                    label = { Text("Object") }
                )
                FilterChip(
                    selected = state.orthoEnabled,
                    onClick = vm::toggleOrtho,
                    label = { Text("Ortho") }
                )
                TextButton(onClick = vm::finishChain, enabled = state.pendingStart != null) {
                    Text("Done")
                }
            }

            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Thickness", style = MaterialTheme.typography.labelMedium)
                WallThickness.STANDARD_CM.forEach { cm ->
                    FilterChip(
                        selected = state.thicknessCm == cm,
                        onClick = { vm.setThickness(cm) },
                        label = { Text("$cm cm") }
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.lengthText,
                    onValueChange = vm::onLengthChange,
                    label = { Text("Length (m)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = state.angleText,
                    onValueChange = vm::onAngleChange,
                    label = { Text("Angle (°)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = vm::onAddNumericWall, enabled = state.pendingStart != null) {
                    Text("Add")
                }
            }
        }
    }
}

private fun statusText(state: EditorUiState): String {
    val cursor = state.cursor
    val pos = if (cursor == null) {
        "Tap to place the first point"
    } else {
        "X ${Units.formatCoord(cursor.point.x)}  Y ${Units.formatCoord(cursor.point.y)}  · ${snapLabel(cursor.type)}"
    }
    val hint = if (state.pendingStart == null) "" else "  |  next point or enter length/angle"
    return pos + hint
}

private fun snapLabel(type: SnapType): String = when (type) {
    SnapType.NONE -> "Free"
    SnapType.ENDPOINT -> "Endpoint"
    SnapType.MIDPOINT -> "Midpoint"
    SnapType.GRID -> "Grid"
}
