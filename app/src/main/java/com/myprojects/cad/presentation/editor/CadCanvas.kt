package com.myprojects.cad.presentation.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myprojects.cad.core.engine.ScaleSteps
import com.myprojects.cad.core.engine.SnapResult
import com.myprojects.cad.core.engine.SnapType
import com.myprojects.cad.core.engine.Viewport
import com.myprojects.cad.core.geometry.Units
import com.myprojects.cad.core.geometry.Vec2
import com.myprojects.cad.domain.model.Wall
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor

internal fun Vec2.toOffset() = Offset(x.toFloat(), y.toFloat())

@Composable
fun CadCanvas(
    state: EditorUiState,
    onSizeChanged: (Int, Int) -> Unit,
    onTransform: (Vec2, Vec2, Double) -> Unit,
    onTap: (Vec2) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val background = scheme.surface
    val minorGrid = scheme.outlineVariant.copy(alpha = 0.35f)
    val majorGrid = scheme.outline.copy(alpha = 0.55f)
    val wallFill = scheme.onSurface.copy(alpha = 0.85f)
    val wallEdge = scheme.onSurface
    val accent = scheme.primary
    val axisXColor = Color(0xFFD32F2F)
    val axisYColor = Color(0xFF388E3C)
    val labelPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            isFakeBoldText = true
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { onSizeChanged(it.width, it.height) }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    onTransform(
                        Vec2(centroid.x.toDouble(), centroid.y.toDouble()),
                        Vec2(pan.x.toDouble(), pan.y.toDouble()),
                        zoom.toDouble()
                    )
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { p -> onTap(Vec2(p.x.toDouble(), p.y.toDouble())) }
            }
    ) {
        val vp = state.viewport
        drawRect(background)
        drawGrid(vp, minorGrid, majorGrid)
        drawAxes(vp, axisXColor, axisYColor)

        state.walls.forEach { drawWall(vp, it, wallFill, wallEdge) }
        state.numericPreview?.let { drawWallPreview(vp, it, accent) }

        labelPaint.textSize = 12.sp.toPx()
        labelPaint.color = accent.toArgb()
        state.walls.forEach { drawDimension(vp, it, labelPaint) }
        state.numericPreview?.let { drawDimension(vp, it, labelPaint) }

        state.pendingStart?.let { drawPendingMarker(vp, it, accent) }
        state.cursor?.let { drawSnapMarker(vp, it, accent) }
    }
}

// ---- Grid & axes ----------------------------------------------------------------------------

private fun DrawScope.drawGrid(vp: Viewport, minorColor: Color, majorColor: Color) {
    val bounds = vp.visibleBounds(size.width.toDouble(), size.height.toDouble())
    val step = ScaleSteps.forScale(vp.scale)
    val showMinor = step.minor * vp.scale >= 8.0
    val spacing = if (showMinor) step.minor else step.major
    val subs = if (showMinor) step.subdivisions.toLong() else 1L

    for (i in floor(bounds.minX / spacing).toLong()..ceil(bounds.maxX / spacing).toLong()) {
        val sx = vp.worldToScreenX(i * spacing).toFloat()
        val major = i % subs == 0L
        drawLine(if (major) majorColor else minorColor, Offset(sx, 0f), Offset(sx, size.height), 1f)
    }
    for (j in floor(bounds.minY / spacing).toLong()..ceil(bounds.maxY / spacing).toLong()) {
        val sy = vp.worldToScreenY(j * spacing).toFloat()
        val major = j % subs == 0L
        drawLine(if (major) majorColor else minorColor, Offset(0f, sy), Offset(size.width, sy), 1f)
    }
}

private fun DrawScope.drawAxes(vp: Viewport, xColor: Color, yColor: Color) {
    val sy = vp.worldToScreenY(0.0).toFloat()
    val sx = vp.worldToScreenX(0.0).toFloat()
    drawLine(xColor, Offset(0f, sy), Offset(size.width, sy), 2f)
    drawLine(yColor, Offset(sx, 0f), Offset(sx, size.height), 2f)
}

// ---- Walls ----------------------------------------------------------------------------------

/** Wall as a thick quad (true thickness in world units, so it scales with zoom). */
private fun wallScreenPath(vp: Viewport, wall: Wall): Path {
    val dir = (wall.end - wall.start).normalized()
    val n = dir.perpLeft() * (wall.thicknessM / 2.0)
    val p1 = vp.worldToScreen(wall.start + n).toOffset()
    val p2 = vp.worldToScreen(wall.end + n).toOffset()
    val p3 = vp.worldToScreen(wall.end - n).toOffset()
    val p4 = vp.worldToScreen(wall.start - n).toOffset()
    return Path().apply {
        moveTo(p1.x, p1.y)
        lineTo(p2.x, p2.y)
        lineTo(p3.x, p3.y)
        lineTo(p4.x, p4.y)
        close()
    }
}

private fun DrawScope.drawWall(vp: Viewport, wall: Wall, fill: Color, edge: Color) {
    val path = wallScreenPath(vp, wall)
    drawPath(path, fill)
    drawPath(path, edge, style = Stroke(width = 1.5.dp.toPx()))
}

private fun DrawScope.drawWallPreview(vp: Viewport, wall: Wall, color: Color) {
    val path = wallScreenPath(vp, wall)
    drawPath(path, color.copy(alpha = 0.25f))
    drawPath(
        path,
        color,
        style = Stroke(
            width = 1.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
        )
    )
}

/** Real-time dimension text, parallel to the wall, offset outside its thickness. */
private fun DrawScope.drawDimension(vp: Viewport, wall: Wall, paint: android.graphics.Paint) {
    val a = vp.worldToScreen(wall.start)
    val b = vp.worldToScreen(wall.end)
    val dir = b - a
    if (dir.length < 48.0) return // too short on screen to label

    var angle = Math.toDegrees(atan2(dir.y, dir.x))
    if (angle > 90.0) angle -= 180.0 else if (angle < -90.0) angle += 180.0 // keep text upright

    val mid = (a + b) / 2.0
    val lift = (wall.thicknessM * vp.scale / 2.0).toFloat() + 6.dp.toPx()
    val text = Units.formatLength(wall.length)
    rotate(degrees = angle.toFloat(), pivot = mid.toOffset()) {
        drawIntoCanvas { c ->
            c.nativeCanvas.drawText(text, mid.x.toFloat(), mid.y.toFloat() - lift, paint)
        }
    }
}

// ---- Markers --------------------------------------------------------------------------------

private fun DrawScope.drawPendingMarker(vp: Viewport, p: Vec2, color: Color) {
    val c = vp.worldToScreen(p).toOffset()
    drawCircle(color, radius = 5.dp.toPx(), center = c)
    drawCircle(Color.White, radius = 2.dp.toPx(), center = c)
}

private fun DrawScope.drawSnapMarker(vp: Viewport, snap: SnapResult, color: Color) {
    val c = vp.worldToScreen(snap.point).toOffset()
    val r = 8.dp.toPx()
    val stroke = Stroke(width = 2.dp.toPx())
    when (snap.type) {
        SnapType.ENDPOINT ->
            drawRect(color, topLeft = Offset(c.x - r, c.y - r), size = Size(2 * r, 2 * r), style = stroke)
        SnapType.MIDPOINT -> {
            val tri = Path().apply {
                moveTo(c.x, c.y - r)
                lineTo(c.x + r, c.y + r)
                lineTo(c.x - r, c.y + r)
                close()
            }
            drawPath(tri, color, style = stroke)
        }
        SnapType.GRID -> {
            drawLine(color, Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r), 2.dp.toPx())
            drawLine(color, Offset(c.x - r, c.y + r), Offset(c.x + r, c.y - r), 2.dp.toPx())
        }
        SnapType.NONE -> drawCircle(color, radius = r / 2f, center = c, style = stroke)
    }
}
