package com.myprojects.cad.presentation.editor

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myprojects.cad.core.engine.ScaleSteps
import com.myprojects.cad.core.engine.Viewport
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

enum class RulerOrientation { HORIZONTAL, VERTICAL }

/**
 * Engineering ruler driven by the same [Viewport] as the canvas, so ticks always line up.
 * The horizontal ruler must share the canvas' X extent; the vertical ruler its Y extent.
 */
@Composable
fun CadRuler(
    viewport: Viewport,
    orientation: RulerOrientation,
    cursorWorld: Double?,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val background = scheme.surfaceVariant
    val tickColor = scheme.onSurfaceVariant
    val cursorColor = scheme.primary
    val paint = remember { android.graphics.Paint().apply { isAntiAlias = true } }

    Canvas(modifier = modifier.clipToBounds()) {
        drawRect(background)
        val horizontal = orientation == RulerOrientation.HORIZONTAL
        val lengthPx = if (horizontal) size.width else size.height
        val thickness = if (horizontal) size.height else size.width

        val worldMin = if (horizontal) viewport.screenToWorldX(0.0)
        else viewport.screenToWorldY(lengthPx.toDouble())
        val worldMax = if (horizontal) viewport.screenToWorldX(lengthPx.toDouble())
        else viewport.screenToWorldY(0.0)

        val step = ScaleSteps.forScale(viewport.scale)
        val showMinor = step.minor * viewport.scale >= 8.0
        val spacing = if (showMinor) step.minor else step.major
        val subs = if (showMinor) step.subdivisions.toLong() else 1L

        paint.textSize = 10.sp.toPx()
        paint.color = tickColor.toArgb()
        val labelInset = 3.dp.toPx()
        val baseline = paint.textSize + 2.dp.toPx()

        for (i in floor(worldMin / spacing).toLong()..ceil(worldMax / spacing).toLong()) {
            val worldV = i * spacing
            val pos = (if (horizontal) viewport.worldToScreenX(worldV)
            else viewport.worldToScreenY(worldV)).toFloat()

            val isMajor = i % subs == 0L
            val isHalf = !isMajor && showMinor && subs % 2L == 0L && i % (subs / 2L) == 0L
            val len = when {
                isMajor -> thickness * 0.5f
                isHalf -> thickness * 0.35f
                else -> thickness * 0.22f
            }

            if (horizontal) {
                drawLine(tickColor, Offset(pos, thickness), Offset(pos, thickness - len), 1f)
            } else {
                drawLine(tickColor, Offset(thickness, pos), Offset(thickness - len, pos), 1f)
            }

            if (isMajor) {
                val label = formatRulerLabel(worldV)
                if (horizontal) {
                    drawIntoCanvas { c ->
                        c.nativeCanvas.drawText(label, pos + labelInset, baseline, paint)
                    }
                } else {
                    rotate(degrees = -90f, pivot = Offset(0f, pos)) {
                        drawIntoCanvas { c ->
                            c.nativeCanvas.drawText(label, labelInset, pos + baseline, paint)
                        }
                    }
                }
            }
        }

        if (cursorWorld != null) {
            val pos = (if (horizontal) viewport.worldToScreenX(cursorWorld)
            else viewport.worldToScreenY(cursorWorld)).toFloat()
            if (horizontal) drawLine(cursorColor, Offset(pos, 0f), Offset(pos, thickness), 2f)
            else drawLine(cursorColor, Offset(0f, pos), Offset(thickness, pos), 2f)
        }
    }
}

/** Whole meters -> "3 m"; sub-meter -> centimeters "50 cm". */
internal fun formatRulerLabel(v: Double): String {
    if (abs(v) < 1e-9) return "0"
    val whole = Math.rint(v)
    return if (abs(v - whole) < 1e-9) "${whole.toLong()} m" else "${Math.rint(v * 100.0).toLong()} cm"
}
