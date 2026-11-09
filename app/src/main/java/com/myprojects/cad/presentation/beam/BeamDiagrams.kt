package com.myprojects.cad.presentation.beam

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Simple engineering plot. [positiveDown] = true draws larger values lower (bending moment on the tension
 * side, deflection); false draws them upward (shear).
 */
@Composable
fun DiagramPlot(
    title: String,
    xs: DoubleArray,
    series: List<DoubleArray>,
    supportsX: List<Double>,
    unit: String,
    positiveDown: Boolean,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val lineColors = listOf(scheme.primary, scheme.tertiary)
    val textColor = scheme.onSurface
    val axisColor = scheme.outline
    val paint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }

    Column(modifier) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Canvas(Modifier.fillMaxWidth().height(180.dp)) {
            if (xs.size < 2) return@Canvas
            val padL = 10.dp.toPx()
            val padR = 10.dp.toPx()
            val padT = 22.dp.toPx()
            val padB = 18.dp.toPx()
            val w = size.width - padL - padR
            val h = size.height - padT - padB

            var lo = 0.0
            var hi = 0.0
            var maxV = 0.0; var maxX = xs[0]
            var minV = 0.0; var minX = xs[0]
            for (s in series) {
                for (i in s.indices) {
                    val v = s[i]
                    lo = min(lo, v); hi = max(hi, v)
                    if (v > maxV) { maxV = v; maxX = xs[i] }
                    if (v < minV) { minV = v; minX = xs[i] }
                }
            }
            if (hi - lo < 1e-9) hi = lo + 1.0
            val x0 = xs.first()
            val span = max(xs.last() - x0, 1e-9)

            fun px(x: Double): Float = (padL + (x - x0) / span * w).toFloat()
            fun py(v: Double): Float =
                if (positiveDown) (padT + (v - lo) / (hi - lo) * h).toFloat()
                else (padT + (hi - v) / (hi - lo) * h).toFloat()

            val zy = py(0.0)
            drawLine(axisColor, Offset(padL, zy), Offset(padL + w.toFloat(), zy), 1.dp.toPx())

            series.forEachIndexed { si, s ->
                val line = Path()
                val fill = Path()
                fill.moveTo(px(xs[0]), zy)
                for (i in xs.indices) {
                    val ox = px(xs[i])
                    val oy = py(s[i])
                    if (i == 0) line.moveTo(ox, oy) else line.lineTo(ox, oy)
                    fill.lineTo(ox, oy)
                }
                fill.lineTo(px(xs.last()), zy)
                fill.close()
                val c = lineColors[si % lineColors.size]
                drawPath(fill, c.copy(alpha = 0.12f))
                drawPath(line, c, style = Stroke(width = 2.dp.toPx()))
            }

            for (sx in supportsX) {
                val x = px(sx)
                val tri = Path().apply {
                    moveTo(x, zy)
                    lineTo(x - 5.dp.toPx(), zy + 9.dp.toPx())
                    lineTo(x + 5.dp.toPx(), zy + 9.dp.toPx())
                    close()
                }
                drawPath(tri, textColor.copy(alpha = 0.55f))
            }

            paint.textSize = 11.sp.toPx()
            paint.color = textColor.toArgb()
            fun label(v: Double, x: Double) {
                val text = String.format(Locale.US, "%.1f %s", v, unit)
                val tx = px(x).coerceIn(padL + 34.dp.toPx(), padL + w.toFloat() - 34.dp.toPx())
                val up = (v >= 0) != positiveDown
                val ty = if (up) py(v) - 4.dp.toPx() else py(v) + 14.dp.toPx()
                drawIntoCanvas { c -> c.nativeCanvas.drawText(text, tx, ty, paint) }
            }
            if (maxV > 1e-9) label(maxV, maxX)
            if (minV < -1e-9) label(minV, minX)
        }
    }
}
