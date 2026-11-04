package com.sharfo.myplans.feature.cad.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sharfo.myplans.feature.cad.domain.engine.SnapEngine
import com.sharfo.myplans.feature.cad.domain.model.Point2D

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CADEditorScreen(
    onBack: () -> Unit,
    viewModel: CADViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    
    var zoom by remember { mutableStateOf(40.0f) }
    var panX by remember { mutableStateOf(0f) }
    var panY by remember { mutableStateOf(0f) }

    var drawingStart by remember { mutableStateOf<Point2D?>(null) }
    var currentRawPoint by remember { mutableStateOf<Point2D?>(null) }

    val snapEngine = remember { SnapEngine() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("المصمم المعماري الهندسي الاحترافي (CAD)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.undo() }) {
                        Icon(Icons.Default.Undo, contentDescription = "تراجع", tint = Color.White)
                    }
                    IconButton(onClick = { viewModel.redo() }) {
                        Icon(Icons.Default.Redo, contentDescription = "إعادة", tint = Color.White)
                    }
                    IconButton(onClick = { viewModel.clearAll() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "مسح الكل", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary)
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = state.currentThicknessCm == 20.0,
                        onClick = { viewModel.setThickness(20.0) },
                        label = { Text("جدار 20 سم") }
                    )
                    FilterChip(
                        selected = state.currentThicknessCm == 15.0,
                        onClick = { viewModel.setThickness(15.0) },
                        label = { Text("جدار 15 سم") }
                    )
                    FilterChip(
                        selected = state.currentThicknessCm == 10.0,
                        onClick = { viewModel.setThickness(10.0) },
                        label = { Text("جدار 10 سم") }
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF0D1B2A))
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        zoom = (zoom * gestureZoom).coerceIn(10f, 200f)
                        panX += pan.x
                        panY += pan.y
                    }
                }
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(zoom, panX, panY) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val pointer = event.changes.firstOrNull() ?: continue
                                
                                val screenX = pointer.position.x
                                val screenY = pointer.position.y
                                
                                val centerX = size.width / 2f + panX
                                val centerY = size.height / 2f + panY
                                
                                val metersX = ((screenX - centerX) / zoom).toDouble()
                                val metersY = ((screenY - centerY) / zoom).toDouble()
                                val rawPoint = Point2D(metersX, metersY)
                                val snapped = snapEngine.snapPoint(rawPoint, state.walls, zoom)

                                when {
                                    pointer.pressed && drawingStart == null -> {
                                        drawingStart = snapped
                                        currentRawPoint = snapped
                                        pointer.consume()
                                    }
                                    pointer.pressed && drawingStart != null -> {
                                        currentRawPoint = snapped
                                        pointer.consume()
                                    }
                                    !pointer.pressed && drawingStart != null -> {
                                        viewModel.addWall(drawingStart!!, snapped)
                                        drawingStart = null
                                        currentRawPoint = null
                                        pointer.consume()
                                    }
                                }
                            }
                        }
                    }
            ) {
                val centerX = size.width / 2f + panX
                val centerY = size.height / 2f + panY

                val gridStep = zoom * 0.5f
                var x = centerX % gridStep
                while (x < size.width) {
                    drawLine(Color(0x22FFFFFF), Offset(x, 0f), Offset(x, size.height), 1f)
                    x += gridStep
                }
                var y = centerY % gridStep
                while (y < size.height) {
                    drawLine(Color(0x22FFFFFF), Offset(0f, y), Offset(size.width, y), 1f)
                    y += gridStep
                }

                state.walls.forEach { wall ->
                    val sx = (wall.start.x * zoom).toFloat() + centerX
                    val sy = (wall.start.y * zoom).toFloat() + centerY
                    val ex = (wall.end.x * zoom).toFloat() + centerX
                    val ey = (wall.end.y * zoom).toFloat() + centerY

                    val strokeW = (wall.thicknessCm / 100.0 * zoom).toFloat().coerceAtLeast(3f)
                    drawLine(
                        color = Color(0xFF90CAF9),
                        start = Offset(sx, sy),
                        end = Offset(ex, ey),
                        strokeWidth = strokeW
                    )
                }

                val start = drawingStart
                val current = currentRawPoint
                if (start != null && current != null) {
                    val sx = (start.x * zoom).toFloat() + centerX
                    val sy = (start.y * zoom).toFloat() + centerY
                    val ex = (current.x * zoom).toFloat() + centerX
                    val ey = (current.y * zoom).toFloat() + centerY

                    drawLine(
                        color = Color(0xFFFFB300),
                        start = Offset(sx, sy),
                        end = Offset(ex, ey),
                        strokeWidth = 6f
                    )
                }
            }
        }
    }
}
