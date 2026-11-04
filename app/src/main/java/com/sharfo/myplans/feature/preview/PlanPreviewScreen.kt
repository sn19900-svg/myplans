package com.sharfo.myplans.feature.preview

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.sharfo.myplans.domain.model.WallLine
import java.io.File
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanPreviewScreen(
    onBack: () -> Unit,
    viewModel: PlanPreviewViewModel = hiltViewModel()
) {
    val project by viewModel.project.collectAsState()
    val context = LocalContext.current
    var showStructuralAxes by remember { mutableStateOf(true) }
    var isManualDrawingMode by remember { mutableStateOf(false) }

    // قائمة تخزين الخطوط المرسومة يدوياً في الجلسة الحالية
    val manualWalls = remember { mutableStateListOf<WallLine>() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(project?.architectural?.projectName ?: "معاينة المخطط") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "رجوع",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        val current = project
        if (current == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isManualDrawingMode) "وضع الرسم اليدوي للجدران" else "المسقط الهندسي التلقائي",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(
                            selected = isManualDrawingMode,
                            onClick = { isManualDrawingMode = !isManualDrawingMode },
                            label = { Text(if (isManualDrawingMode) "الوضع التلقائي" else "✏️ رسم يدوي") }
                        )
                    }
                }

                if (isManualDrawingMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("ارسم الجدران بإصبعك داخل اللوحة أدناه:", style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = { manualWalls.clear() }) {
                            Icon(Icons.Default.Delete, contentDescription = "مسح الرسومات", tint = Color.Red)
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        FilterChip(
                            selected = showStructuralAxes,
                            onClick = { showStructuralAxes = !showStructuralAxes },
                            label = { Text(if (showStructuralAxes) "إخفاء المحاور" else "إظهار المحاور") }
                        )
                    }
                }

                // لوحة الرسم التفاعلية
                InteractiveBlueprintCanvas(
                    project = current,
                    showStructural = showStructuralAxes,
                    isDrawingMode = isManualDrawingMode,
                    manualWalls = manualWalls
                )

                // أزرار التصدير الثلاثة (DXF, PDF, Excel)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "تصدير ومشاركة المخططات والجداول:",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    viewModel.exportDxf { file, mime ->
                                        shareExportedFile(context, file, mime)
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null)
                                Text(" DXF")
                            }
                            Button(
                                onClick = {
                                    viewModel.exportPdf { file, mime ->
                                        shareExportedFile(context, file, mime)
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null)
                                Text(" PDF")
                            }
                            Button(
                                onClick = {
                                    viewModel.exportExcel { file, mime ->
                                        shareExportedFile(context, file, mime)
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null)
                                Text(" Excel")
                            }
                        }
                    }
                }

                // الملخص الإنشائي وحصر الكميات
                val sched = current.generated.schedule
                val colSample = current.generated.columns.firstOrNull()
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "التقرير الإنشائي وحصر الكميات (${current.structural.buildingCode.titleAr})",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text("• نوع البلاطة: ${current.structural.slabType.titleAr} (سماكة ${sched.slabThicknessCm} سم)")
                        Text("• تسليح البلاطة الرئيسي: ${sched.slabMainReinforcement}")
                        Text("• تسليح البلاطة الثانوي: ${sched.slabSecondaryReinforcement}")
                        Text("• الجسور (الكمرات): ${sched.beamDimensionsCm}")
                        Text("• الأعمدة (${current.generated.columns.size} عمود): ${colSample?.widthCm ?: 25}×${colSample?.heightCm ?: 40} سم")
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "إجمالي البيتون: ${sched.totalConcreteVolumeM3} م³ | إجمالي الحديد: ${sched.estimatedSteelWeightTon} طن",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InteractiveBlueprintCanvas(
    project: com.sharfo.myplans.domain.model.ProjectPlan,
    showStructural: Boolean,
    isDrawingMode: Boolean,
    manualWalls: MutableList<WallLine>
) {
    var currentLineStart by remember { mutableStateOf<Offset?>(null) }
    var currentLineEnd by remember { mutableStateOf<Offset?>(null) }

    val textPaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 24f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(380.dp)
            .background(Color(0xFF0A274E))
            .padding(12.dp)
            .pointerInput(isDrawingMode) {
                if (isDrawingMode) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            currentLineStart = offset
                            currentLineEnd = offset
                        },
                        onDrag = { change, _ ->
                            currentLineEnd = change.position
                        },
                        onDragEnd = {
                            val start = currentLineStart
                            val end = currentLineEnd
                            if (start != null && end != null) {
                                manualWalls.add(WallLine(start.x, start.y, end.x, end.y))
                            }
                            currentLineStart = null
                            currentLineEnd = null
                        },
                        onDragCancel = {
                            currentLineStart = null
                            currentLineEnd = null
                        }
                    )
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val pad = 24f
            val availW = size.width - pad * 2
            val availH = size.height - pad * 2
            val scaleX = availW / project.generated.plotWidthM.toFloat()
            val scaleY = availH / project.generated.plotDepthM.toFloat()
            val scale = min(scaleX, scaleY)

            val offsetX = (size.width - project.generated.plotWidthM.toFloat() * scale) / 2f
            val offsetY = (size.height - project.generated.plotDepthM.toFloat() * scale) / 2f

            // 1. رسم الغرف التلقائية إذا لم يكن في وضع الرسم اليدوي المحض
            if (!isDrawingMode) {
                project.generated.rooms.forEach { room ->
                    val rx = offsetX + room.x.toFloat() * scale
                    val ry = offsetY + room.y.toFloat() * scale
                    val rw = room.width.toFloat() * scale
                    val rh = room.height.toFloat() * scale

                    drawRect(
                        color = Color(0xFF1E56A0),
                        topLeft = Offset(rx, ry),
                        size = Size(rw, rh)
                    )
                    drawRect(
                        color = Color.White,
                        topLeft = Offset(rx, ry),
                        size = Size(rw, rh),
                        style = Stroke(width = 3f)
                    )

                    drawContext.canvas.nativeCanvas.drawText(
                        room.name,
                        rx + rw / 2f,
                        ry + rh / 2f,
                        textPaint
                    )
                }

                if (showStructural) {
                    project.generated.columns.forEach { col ->
                        val cx = offsetX + col.x.toFloat() * scale
                        val cy = offsetY + col.y.toFloat() * scale
                        drawRect(
                            color = Color(0xFFFFB300),
                            topLeft = Offset(cx - 6f, cy - 6f),
                            size = Size(12f, 12f)
                        )
                    }
                }
            }

            // 2. رسم الجدران اليدوية التي رسمها المستخدم
            manualWalls.forEach { wall ->
                drawLine(
                    color = Color(0xFFFFD54F),
                    start = Offset(wall.startX, wall.startY),
                    end = Offset(wall.endX, wall.endY),
                    strokeWidth = 6f,
                    cap = StrokeCap.Round
                )
            }

            // رسم الخط الحالي أثناء السحب
            val start = currentLineStart
            val end = currentLineEnd
            if (start != null && end != null) {
                drawLine(
                    color = Color(0xFFFF5252),
                    start = start,
                    end = end,
                    strokeWidth = 6f,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

private fun shareExportedFile(context: Context, file: File, mimeType: String) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "مشاركة المخطط عبر"))
}
