package com.myprojects.cad.presentation.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myprojects.cad.core.engine.ScaleSteps
import com.myprojects.cad.core.engine.SnapResult
import com.myprojects.cad.core.engine.SnapType
import com.myprojects.cad.core.engine.Viewport
import com.myprojects.cad.core.geometry.Units
import com.myprojects.cad.core.geometry.Vec2
import com.myprojects.cad.domain.model.ArcEntity
import com.myprojects.cad.domain.model.CadEntity
import com.myprojects.cad.domain.model.CircleEntity
import com.myprojects.cad.domain.model.DimensionEntity
import com.myprojects.cad.domain.model.LineEntity
import com.myprojects.cad.domain.model.PolylineEntity
import com.myprojects.cad.domain.model.TextEntity
import com.myprojects.cad.domain.model.Wall
import com.myprojects.cad.domain.model.labelAnchor
import com.myprojects.cad.domain.structural.StudyRecord
import com.myprojects.cad.domain.model.geometry
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor

internal fun Vec2.toOffset() = Offset(x.toFloat(), y.toFloat())

private enum class DrawMode { NORMAL, SELECTED, PREVIEW }

private class Palette(
    val wallFill: Color,
    val wallEdge: Color,
    val line: Color,
    val dim: Color,
    val selected: Color,
    val preview: Color
)

/**
 * One-finger drag: moves the crosshair like a trackpad (or pans in the Pan tool). Tap: puts the crosshair there.
 * Two fingers: pan + zoom, always.
 */
@Composable
fun CadCanvas(
    state: EditorUiState,
    onSizeChanged: (Int, Int) -> Unit,
    onTransform: (Vec2, Vec2, Double) -> Unit,
    onCursorDelta: (Double, Double) -> Unit,
    onTap: (Vec2) -> Unit,
    onBox: (Vec2, Vec2, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val background = scheme.surface
    val minorGrid = scheme.outlineVariant.copy(alpha = 0.35f)
    val majorGrid = scheme.outline.copy(alpha = 0.55f)
    val palette = Palette(
        wallFill = scheme.onSurface.copy(alpha = 0.8f),
        wallEdge = scheme.onSurface,
        line = scheme.onSurface,
        dim = scheme.tertiary,
        selected = Color(0xFFFF6D00),
        preview = scheme.primary
    )
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

    val isPan by rememberUpdatedState(state.tool == EditorTool.PAN)
    val currentTransform by rememberUpdatedState(onTransform)
    val currentCursorDelta by rememberUpdatedState(onCursorDelta)
    val currentTap by rememberUpdatedState(onTap)
    val currentBox by rememberUpdatedState(onBox)
    val isSelect by rememberUpdatedState(state.tool == EditorTool.SELECT)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { onSizeChanged(it.width, it.height) }
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var multi = false
                    var moved = false
                    var total = Offset.Zero
                    var lastPos = down.position
                    do {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }
                        if (pressedCount >= 2) {
                            multi = true
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            if (zoom != 1f || pan != Offset.Zero) {
                                currentTransform(
                                    Vec2(centroid.x.toDouble(), centroid.y.toDouble()),
                                    Vec2(pan.x.toDouble(), pan.y.toDouble()),
                                    zoom.toDouble()
                                )
                            }
                            event.changes.forEach { it.consume() }
                        } else if (!multi) {
                            val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                            val delta = change.positionChange()
                            total += delta
                            val wasMoved = moved
                            if (!moved && total.getDistance() > slop) moved = true
                            if (moved) {
                                val d = if (wasMoved) delta else total
                                lastPos = change.position
                                if (isPan) {
                                    currentTransform(
                                        Vec2(change.position.x.toDouble(), change.position.y.toDouble()),
                                        Vec2(d.x.toDouble(), d.y.toDouble()),
                                        1.0
                                    )
                                } else if (isSelect) {
                                    currentBox(
                                        Vec2(down.position.x.toDouble(), down.position.y.toDouble()),
                                        Vec2(change.position.x.toDouble(), change.position.y.toDouble()),
                                        false
                                    )
                                } else {
                                    currentCursorDelta(d.x.toDouble(), d.y.toDouble())
                                }
                                change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (!multi && !moved) {
                        currentTap(Vec2(down.position.x.toDouble(), down.position.y.toDouble()))
                    } else if (!multi && moved && isSelect) {
                        currentBox(
                            Vec2(down.position.x.toDouble(), down.position.y.toDouble()),
                            Vec2(lastPos.x.toDouble(), lastPos.y.toDouble()),
                            true
                        )
                    }
                }
            }
    ) {
        val vp = state.viewport
        drawRect(background)
        drawGrid(vp, minorGrid, majorGrid)
        drawAxes(vp, axisXColor, axisYColor)

        labelPaint.textSize = 12.sp.toPx()
        labelPaint.color = accent.toArgb()

        for (e in state.entities) {
            val mode = if (e.id in state.selectedIds) DrawMode.SELECTED else DrawMode.NORMAL
            drawEntity(vp, e, palette, mode, labelPaint)
        }
        for (e in state.preview) drawEntity(vp, e, palette, DrawMode.PREVIEW, labelPaint)

        drawLabels(state, vp, labelPaint, Color(0xFF2E7D32), Color(0xFFC62828), scheme.tertiary, background)
        for (p in state.points) drawPendingMarker(vp, p, accent)
        state.selectBox?.let { drawSelectBox(it, accent, Color(0xFF2E7D32)) }
        state.cursor?.let { drawCrosshair(vp, it, accent) }
    }
}

// ---- Grid & axes ----------------------------------------------------------------------------------

private fun DrawScope.drawGrid(vp: Viewport, minorColor: Color, majorColor: Color) {
    val bounds = vp.visibleBounds(size.width.toDouble(), size.height.toDouble())
    val step = ScaleSteps.forScale(vp.scale)
    val showMinor = step.minor * vp.scale >= 8.0
    val spacing = if (showMinor) step.minor else step.major
    val subs = if (showMinor) step.subdivisions.toLong() else 1L

    for (i in floor(bounds.minX / spacing).toLong()..ceil(bounds.maxX / spacing).toLong()) {
        val sx = vp.worldToScreenX(i * spacing).toFloat()
        drawLine(if (i % subs == 0L) majorColor else minorColor, Offset(sx, 0f), Offset(sx, size.height), 1f)
    }
    for (j in floor(bounds.minY / spacing).toLong()..ceil(bounds.maxY / spacing).toLong()) {
        val sy = vp.worldToScreenY(j * spacing).toFloat()
        drawLine(if (j % subs == 0L) majorColor else minorColor, Offset(0f, sy), Offset(size.width, sy), 1f)
    }
}

private fun DrawScope.drawAxes(vp: Viewport, xColor: Color, yColor: Color) {
    val sy = vp.worldToScreenY(0.0).toFloat()
    val sx = vp.worldToScreenX(0.0).toFloat()
    drawLine(xColor, Offset(0f, sy), Offset(size.width, sy), 2f)
    drawLine(yColor, Offset(sx, 0f), Offset(sx, size.height), 2f)
}

// ---- Entities -------------------------------------------------------------------------------------

private fun wallScreenPath(vp: Viewport, wall: Wall): Path {
    val c = wall.corners().map { vp.worldToScreen(it).toOffset() }
    return Path().apply {
        moveTo(c[0].x, c[0].y)
        lineTo(c[1].x, c[1].y)
        lineTo(c[2].x, c[2].y)
        lineTo(c[3].x, c[3].y)
        close()
    }
}

private fun DrawScope.drawEntity(
    vp: Viewport, e: CadEntity, pal: Palette, mode: DrawMode, labelPaint: android.graphics.Paint
) {
    val color = when (mode) {
        DrawMode.NORMAL -> if (e is DimensionEntity) pal.dim else pal.line
        DrawMode.SELECTED -> pal.selected
        DrawMode.PREVIEW -> pal.preview
    }
    val width = (if (mode == DrawMode.SELECTED) 2.5.dp else 1.5.dp).toPx()
    val dash = if (mode == DrawMode.PREVIEW) PathEffect.dashPathEffect(floatArrayOf(12f, 8f)) else null
    val stroke = Stroke(width = width, pathEffect = dash)

    when (e) {
        is Wall -> {
            val path = wallScreenPath(vp, e)
            val fill = when (mode) {
                DrawMode.NORMAL -> pal.wallFill
                DrawMode.SELECTED -> pal.selected.copy(alpha = 0.55f)
                DrawMode.PREVIEW -> pal.preview.copy(alpha = 0.25f)
            }
            drawPath(path, fill)
            drawPath(path, if (mode == DrawMode.NORMAL) pal.wallEdge else color, style = stroke)
            drawLengthLabel(vp, e.start, e.end, e.thicknessM, labelPaint)
        }
        is LineEntity -> {
            drawLine(
                color = color, start = vp.worldToScreen(e.start).toOffset(), end = vp.worldToScreen(e.end).toOffset(),
                strokeWidth = width, pathEffect = dash
            )
            if (mode == DrawMode.PREVIEW) drawLengthLabel(vp, e.start, e.end, 0.0, labelPaint)
        }
        is PolylineEntity -> {
            if (e.points.size >= 2) {
                val path = Path()
                e.points.forEachIndexed { i, p ->
                    val o = vp.worldToScreen(p).toOffset()
                    if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                }
                if (e.closed) path.close()
                drawPath(path, color, style = stroke)
            }
        }
        is CircleEntity -> {
            val c = vp.worldToScreen(e.center).toOffset()
            drawCircle(color = color, radius = (e.radius * vp.scale).toFloat(), center = c, style = stroke)
        }
        is ArcEntity -> {
            val c = vp.worldToScreen(e.center).toOffset()
            val r = (e.radius * vp.scale).toFloat()
            drawArc(
                color = color,
                startAngle = (-e.endDeg).toFloat(),
                sweepAngle = e.sweepDeg.toFloat(),
                useCenter = false,
                topLeft = Offset(c.x - r, c.y - r),
                size = Size(2 * r, 2 * r),
                style = stroke
            )
        }
        is TextEntity -> {
            val h = (e.heightM * vp.scale).toFloat()
            if (h >= 5f) {
                val pos = vp.worldToScreen(e.position).toOffset()
                val argb = color.toArgb()
                val paint = android.graphics.Paint(labelPaint).apply {
                    textSize = h
                    setColor(argb)
                    textAlign = android.graphics.Paint.Align.LEFT
                    isFakeBoldText = false
                }
                rotate(degrees = (-e.rotationDeg).toFloat(), pivot = pos) {
                    drawIntoCanvas { c -> c.nativeCanvas.drawText(e.text, pos.x, pos.y, paint) }
                }
            } else {
                drawCircle(color, radius = 2.dp.toPx(), center = vp.worldToScreen(e.position).toOffset())
            }
        }
        is DimensionEntity -> drawDimension(vp, e, color, width, dash, labelPaint)
    }
}

/** Real-time dimension text, parallel to the segment, offset outside its thickness. */
private fun DrawScope.drawLengthLabel(
    vp: Viewport, a: Vec2, b: Vec2, thicknessM: Double, paint: android.graphics.Paint
) {
    val sa = vp.worldToScreen(a)
    val sb = vp.worldToScreen(b)
    val dir = sb - sa
    if (dir.length < 48.0) return // too short on screen to label

    var angle = Math.toDegrees(atan2(dir.y, dir.x))
    if (angle > 90.0) angle -= 180.0 else if (angle < -90.0) angle += 180.0 // keep text upright

    val mid = (sa + sb) / 2.0
    val lift = (thicknessM * vp.scale / 2.0).toFloat() + 6.dp.toPx()
    val text = Units.formatLength(a.distanceTo(b))
    paint.textAlign = android.graphics.Paint.Align.CENTER
    rotate(degrees = angle.toFloat(), pivot = mid.toOffset()) {
        drawIntoCanvas { c -> c.nativeCanvas.drawText(text, mid.x.toFloat(), mid.y.toFloat() - lift, paint) }
    }
}

private fun DrawScope.drawDimension(
    vp: Viewport, e: DimensionEntity, color: Color, width: Float, dash: PathEffect?, paint: android.graphics.Paint
) {
    val g = e.geometry()
    fun s(p: Vec2) = vp.worldToScreen(p).toOffset()
    drawLine(color = color, start = s(g.p1), end = s(g.e1), strokeWidth = width * 0.6f, pathEffect = dash)
    drawLine(color = color, start = s(g.p2), end = s(g.e2), strokeWidth = width * 0.6f, pathEffect = dash)
    drawLine(color = color, start = s(g.d1), end = s(g.d2), strokeWidth = width, pathEffect = dash)

    val a = s(g.d1)
    val b = s(g.d2)
    val len = (b - a).getDistance()
    if (len > 1f) {
        val ux = (b.x - a.x) / len
        val uy = (b.y - a.y) / len
        val k = 5.dp.toPx()
        val tx = (ux - uy) * k
        val ty = (uy + ux) * k
        drawLine(color, Offset(a.x - tx, a.y - ty), Offset(a.x + tx, a.y + ty), width)
        drawLine(color, Offset(b.x - tx, b.y - ty), Offset(b.x + tx, b.y + ty), width)
    }
    drawLengthLabel(vp, g.d1, g.d2, 0.0, paint)
}

// ---- Markers --------------------------------------------------------------------------------------

private fun DrawScope.drawPendingMarker(vp: Viewport, p: Vec2, color: Color) {
    val c = vp.worldToScreen(p).toOffset()
    drawCircle(color, radius = 5.dp.toPx(), center = c)
    drawCircle(Color.White, radius = 2.dp.toPx(), center = c)
}

private fun DrawScope.drawCrosshair(vp: Viewport, snap: SnapResult, color: Color) {
    val c = vp.worldToScreen(snap.point).toOffset()
    val arm = 16.dp.toPx()
    val w = 1.5.dp.toPx()
    drawLine(color, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), w)
    drawLine(color, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), w)

    val r = 8.dp.toPx()
    val stroke = Stroke(width = 2.dp.toPx())
    when (snap.type) {
        SnapType.ENDPOINT ->
            drawRect(color, topLeft = Offset(c.x - r, c.y - r), size = Size(2 * r, 2 * r), style = stroke)
        SnapType.INTERSECTION -> {
            drawLine(color, Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r), 2.dp.toPx())
            drawLine(color, Offset(c.x - r, c.y + r), Offset(c.x + r, c.y - r), 2.dp.toPx())
        }
        SnapType.MIDPOINT -> {
            val tri = Path().apply {
                moveTo(c.x, c.y - r)
                lineTo(c.x + r, c.y + r)
                lineTo(c.x - r, c.y + r)
                close()
            }
            drawPath(tri, color, style = stroke)
        }
        SnapType.CENTER -> drawCircle(color, radius = r, center = c, style = stroke)
        SnapType.GRID -> drawRect(color, topLeft = Offset(c.x - 3.dp.toPx(), c.y - 3.dp.toPx()), size = Size(6.dp.toPx(), 6.dp.toPx()), style = stroke)
        SnapType.NONE -> Unit
    }
}


// ---- Labels (element marks and beam-study results) ------------------------------------------------------

private fun DrawScope.drawHaloText(
    text: String, x: Float, y: Float, paint: android.graphics.Paint, color: Color, halo: Color
) {
    drawIntoCanvas { c ->
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = 3.dp.toPx()
        paint.color = halo.copy(alpha = 0.85f).toArgb()
        c.nativeCanvas.drawText(text, x, y, paint)
        paint.style = android.graphics.Paint.Style.FILL
        paint.color = color.toArgb()
        c.nativeCanvas.drawText(text, x, y, paint)
    }
}

private fun DrawScope.drawLabels(
    state: EditorUiState, vp: Viewport, paint: android.graphics.Paint,
    okColor: Color, badColor: Color, nameColor: Color, halo: Color
) {
    if (state.labelMode == LabelMode.OFF) return
    val byFirst = HashMap<String, StudyRecord>()
    val skip = HashSet<String>()
    for (st in state.studies) {
        st.entityIds.firstOrNull()?.let { byFirst[it] = st }
        // One mark per beam: the other spans of the same beam stay unlabeled.
        st.entityIds.drop(1).forEach { id ->
            val e = state.entities.firstOrNull { it.id == id }
            if (e != null && e.name == st.name) skip.add(id)
        }
    }
    paint.textAlign = android.graphics.Paint.Align.CENTER
    for (e in state.entities) {
        if (e.name.isBlank() || e is TextEntity || e.id in skip) continue
        val pos = vp.worldToScreen(e.labelAnchor()).toOffset()
        if (pos.x < -120f || pos.y < -120f || pos.x > size.width + 120f || pos.y > size.height + 120f) continue
        val study = byFirst[e.id]
        val sum = study?.summary
        val color = when {
            sum == null -> nameColor
            sum.ok -> okColor
            else -> badColor
        }
        paint.isFakeBoldText = true
        paint.textSize = 12.sp.toPx()
        drawHaloText(e.name, pos.x, pos.y + 20.dp.toPx(), paint, color, halo)
        if (state.labelMode == LabelMode.FULL && sum != null) {
            paint.isFakeBoldText = false
            paint.textSize = 10.sp.toPx()
            drawHaloText("${sum.section} cm", pos.x, pos.y + 33.dp.toPx(), paint, color, halo)
            drawHaloText("▼ ${sum.bottom}   ▲ ${sum.top}", pos.x, pos.y + 45.dp.toPx(), paint, color, halo)
            drawHaloText(sum.stirrups, pos.x, pos.y + 57.dp.toPx(), paint, color, halo)
        }
    }
    paint.isFakeBoldText = true
}

private fun DrawScope.drawSelectBox(box: SelectBox, windowColor: Color, crossingColor: Color) {
    val color = if (box.crossing) crossingColor else windowColor
    val tl = Offset(minOf(box.startPx.x, box.endPx.x).toFloat(), minOf(box.startPx.y, box.endPx.y).toFloat())
    val sz = Size(
        kotlin.math.abs(box.endPx.x - box.startPx.x).toFloat(),
        kotlin.math.abs(box.endPx.y - box.startPx.y).toFloat()
    )
    drawRect(color.copy(alpha = 0.12f), topLeft = tl, size = sz)
    drawRect(
        color, topLeft = tl, size = sz,
        style = Stroke(
            width = 1.5.dp.toPx(),
            pathEffect = if (box.crossing) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null
        )
    )
}
