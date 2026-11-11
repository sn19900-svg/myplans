package com.myprojects.cad.presentation.column

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myprojects.cad.domain.structural.InteractionCurve
import java.util.Locale
import kotlin.math.max

/** φPn–φMn design interaction curves for both axes, with the factored demand shown as dots. */
@Composable
fun InteractionPlot(
    title: String,
    curveX: InteractionCurve,
    curveY: InteractionCurve,
    demandX: Pair<Double, Double>,
    demandY: Pair<Double, Double>,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val colorX = scheme.primary
    val colorY = scheme.tertiary
    val demandColor = Color(0xFFD32F2F)
    val axis = scheme.outline
    val textColor = scheme.onSurface
    val paint = remember { android.graphics.Paint().apply { isAntiAlias = true } }

    Column(modifier) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Canvas(Modifier.fillMaxWidth().height(260.dp)) {
            val padL = 40.dp.toPx()
            val padR = 12.dp.toPx()
            val padT = 12.dp.toPx()
            val padB = 28.dp.toPx()
            val w = size.width - padL - padR
            val h = size.height - padT - padB

            val maxM = max(1.0, listOf(
                curveX.mKnM.maxOrNull() ?: 1.0, curveY.mKnM.maxOrNull() ?: 1.0, demandX.first, demandY.first
            ).max()) * 1.1
            val maxP = max(1.0, listOf(
                curveX.pKn.maxOrNull() ?: 1.0, curveY.pKn.maxOrNull() ?: 1.0, demandX.second
            ).max()) * 1.1

            fun px(m: Double): Float = (padL + m / maxM * w).toFloat()
            fun py(p: Double): Float = (padT + h - p / maxP * h).toFloat()

            drawLine(axis, Offset(padL, padT), Offset(padL, padT + h.toFloat()), 1.dp.toPx())
            drawLine(axis, Offset(padL, padT + h.toFloat()), Offset(padL + w.toFloat(), padT + h.toFloat()), 1.dp.toPx())

            fun curve(c: InteractionCurve, color: Color) {
                val path = Path()
                for (i in c.mKnM.indices) {
                    val o = Offset(px(c.mKnM[i]), py(c.pKn[i]))
                    if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                }
                drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
            }
            curve(curveX, colorX)
            curve(curveY, colorY)

            drawCircle(demandColor, radius = 5.dp.toPx(), center = Offset(px(demandX.first), py(demandX.second)))
            drawCircle(demandColor, radius = 5.dp.toPx(), center = Offset(px(demandY.first), py(demandY.second)))

            paint.textSize = 10.sp.toPx()
            paint.color = textColor.toArgb()
            paint.textAlign = android.graphics.Paint.Align.LEFT
            drawIntoCanvas { c ->
                c.nativeCanvas.drawText("φPn kN", 2.dp.toPx(), padT + 10.dp.toPx(), paint)
                c.nativeCanvas.drawText("φMn kN·m →", padL + w.toFloat() - 70.dp.toPx(), padT + h.toFloat() + 22.dp.toPx(), paint)
                c.nativeCanvas.drawText(String.format(Locale.US, "%.0f", maxP), 2.dp.toPx(), padT + 24.dp.toPx(), paint)
                c.nativeCanvas.drawText(String.format(Locale.US, "%.0f", maxM), padL + w.toFloat() - 28.dp.toPx(), padT + h.toFloat() + 12.dp.toPx(), paint)
            }
        }
        Text(
            "Blue: bending about X · Orange: about Y · Red dots: factored demand (Pu, M)",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
