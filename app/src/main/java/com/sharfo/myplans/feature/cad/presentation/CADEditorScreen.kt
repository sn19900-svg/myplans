package com.sharfo.myplans.feature.cad.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sharfo.myplans.feature.cad.domain.engine.CADRulerEngine
import com.sharfo.myplans.feature.cad.domain.engine.SnapEngine
import com.sharfo.myplans.feature.cad.domain.model.Point2D
import kotlin.math.round

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
    var showRulers by remember { mutableStateOf(true) }
    var showNumericDialog by remember { mutableStateOf(false) }
    var manualLengthInput by remember { mutableStateOf("4.50") }

    val snapEngine = remember { SnapEngine() }
    val rulerEngine = remember { CADRulerEngine() }

    rulerEngine.scale = zoom
    rulerEngine.panX = panX
    rulerEngine.panY = panY

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("المصمم المعماري الاحترافي (CAD)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { showRulers = !showRulers }) {
                        Icon(Icons.Default.Straighten, contentDescription = "المسطرة", tint = if (showRulers) Color.Green else Color.White)
                    }
                    IconButton(onClick = { showNumericDialog = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "إدخال رقمي", tint = Color.White)
                    }
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
                        .padding(8.dp),
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
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, gestureZoom, _ ->
                            zoom = (zoom * gestureZoom).coerceIn(10f, 200f)
                            panX += pan.x
                            panY += pan.y
                        }
                    }
                    .pointerInput(zoom, panX, panY) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val pointer = event.changes.firstOrNull() ?: continue
                                
                                val worldPoint = rulerEngine.screenToWorld(
                                    pointer.position.x, 
                                    pointer.position.y, 
                                    size.width.toFloat(), 
                                    size.height.toFloat()
                                )
                                val snapped = snapEngine.snapPoint(worldPoint, state.walls, zoom.toDouble())

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
                    drawLine(Color(0x1FFFFFFF), Offset(x, 0f), Offset(x, size.height), 1f)
                    x += gridStep
                }
                var y = centerY % gridStep
                while (y < size.height) {
                    drawLine(Color(0x1FFFFFFF), Offset(0f, y), Offset(size.width, y), 1f)
                    y += gridStep
                }

                state.walls.forEach { wall ->
                    val startScr = rulerEngine.worldToScreen(wall.start, size.width, size.height)
                    val endScr = rulerEngine.worldToScreen(wall.end, size.width, size.height)
                    val strokeW = ((wall.thicknessCm / 100.0) * zoom).toFloat().coerceAtLeast(3f)

                    drawLine(
                        color = Color(0xFF90CAF9),
                        start = Offset(startScr.x.toFloat(), startScr.y.toFloat()),
                        end = Offset(endScr.x.toFloat(), endScr.y.toFloat()),
                        strokeWidth = strokeW
                    )

                    val lengthM = round(wall.start.distanceTo(wall.end) * 100) / 100.0
                    drawContext.canvas.nativeCanvas.drawText(
                        "$lengthM م",
                        ((startScr.x + endScr.x) / 2f).toFloat(),
                        ((startScr.y + endScr.y) / 2f - 10f).toFloat(),
                        android.graphics.Paint().apply {
                            color = android.graphics.Color.YELLOW
                            textSize = 28f
                            isAntiAlias = true
                            textAlign = android.graphics.Paint.Align.CENTER
                        }
                    )
                }

                val start = drawingStart
                val current = currentRawPoint
                if (start != null && current != null) {
                    val startScr = rulerEngine.worldToScreen(start, size.width, size.height)
                    val currScr = rulerEngine.worldToScreen(current, size.width, size.height)
                    
                    drawLine(
                        color = Color(0xFFFFB300),
                        start = Offset(startScr.x.toFloat(), startScr.y.toFloat()),
                        end = Offset(currScr.x.toFloat(), currScr.y.toFloat()),
                        strokeWidth = 6f
                    )
                }

                if (showRulers) {
                    val rulerPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.GRAY
                        textSize = 20f
                        isAntiAlias = true
                    }
                    drawRect(Color(0xCC001F3F), topLeft = Offset(0f, 0f), size = Size(size.width, 35f))
                    var wx = centerX % gridStep
                    var worldVal = (-panX / zoom).toDouble()
                    while (wx < size.width) {
                        drawLine(Color.White, Offset(wx, 0f), Offset(wx, 15f), 1f)
                        drawContext.canvas.nativeCanvas.drawText(
                            "%.1f".format(worldVal),
                            wx, 30f, rulerPaint
                        )
                        wx += gridStep
                        worldVal += 0.5
                    }
                }
            }

            if (showNumericDialog) {
                AlertDialog(
                    onDismissRequest = { showNumericDialog = false },
                    title = { Text("إدخال طول الجدار رقمياً") },
                    text = {
                        OutlinedTextField(
                            value = manualLengthInput,
                            onValueChange = { manualLengthInput = it },
                            label = { Text("الطول بالأمتار (مثال: 4.50)") }
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val len = manualLengthInput.toDoubleOrNull() ?: 4.0
                            val startPt = Point2D(0.0, 0.0)
                            val endPt = Point2D(len, 0.0)
                            viewModel.addWall(startPt, endPt)
                            showNumericDialog = false
                        }) {
                            Text("رسم الجدار")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showNumericDialog = false }) {
                            Text("إلغاء")
                        }
                    }
                )
            }
        }
    }
}
