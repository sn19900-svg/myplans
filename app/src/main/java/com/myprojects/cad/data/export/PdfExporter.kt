package com.myprojects.cad.data.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
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
import com.myprojects.cad.domain.model.geometry
import com.myprojects.cad.domain.model.worldBounds
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt

enum class PaperSize(val label: String, val shortMm: Double, val longMm: Double) {
    A4("A4", 210.0, 297.0),
    A3("A3", 297.0, 420.0)
}

/**
 * Vector PDF at a true architectural scale (1:50, 1:100 …) chosen automatically so the drawing fits the sheet.
 * Uses the platform PdfDocument, so there is no extra dependency.
 */
class PdfExporter @Inject constructor() {

    fun export(title: String, entities: List<CadEntity>, paper: PaperSize, out: OutputStream) {
        val bounds = entities.worldBounds()
        val landscape = bounds == null || bounds.width >= bounds.height
        val pageW = if (landscape) paper.longMm else paper.shortMm
        val pageH = if (landscape) paper.shortMm else paper.longMm
        val ptPerMm = 72.0 / 25.4

        val margin = 10.0
        val titleH = 22.0
        val pad = 8.0
        val areaL = margin + pad
        val areaT = margin + pad
        val areaR = pageW - margin - pad
        val areaB = pageH - margin - titleH - pad
        val areaW = areaR - areaL
        val areaH = areaB - areaT

        val wM = max(bounds?.width ?: 0.0, 0.1)
        val hM = max(bounds?.height ?: 0.0, 0.1)
        val needed = max(wM * 1000.0 / areaW, hM * 1000.0 / areaH)
        val scale = STANDARD_SCALES.firstOrNull { it >= needed } ?: STANDARD_SCALES.last()
        val k = 1000.0 / scale // paper millimeters per world meter

        val originX = areaL + (areaW - wM * k) / 2.0
        val originYBottom = areaB - (areaH - hM * k) / 2.0
        val minX = bounds?.minX ?: 0.0
        val minY = bounds?.minY ?: 0.0
        fun px(p: Vec2) = (originX + (p.x - minX) * k).toFloat()
        fun py(p: Vec2) = (originYBottom - (p.y - minY) * k).toFloat()

        val doc = PdfDocument()
        try {
            val info = PdfDocument.PageInfo.Builder(
                (pageW * ptPerMm).roundToInt(), (pageH * ptPerMm).roundToInt(), 1
            ).create()
            val page = doc.startPage(info)
            val c = page.canvas
            c.scale(ptPerMm.toFloat(), ptPerMm.toFloat()) // from here on, 1 unit = 1 mm on paper
            c.drawColor(Color.WHITE)

            drawSheetFrame(c, pageW, pageH, margin, titleH, title, scale, paper)

            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; color = Color.BLACK; strokeWidth = 0.25f
                strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.MITER
            }
            val wallFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.rgb(150, 150, 150) }
            val wallEdge = Paint(stroke).apply { strokeWidth = 0.35f }
            val dimPaint = Paint(stroke).apply { color = Color.rgb(0, 90, 160); strokeWidth = 0.18f }
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.BLACK }

            c.save()
            c.clipRect(margin, margin, pageW - margin, pageH - margin - titleH)
            for (e in entities) {
                when (e) {
                    is Wall -> {
                        val path = android.graphics.Path()
                        e.corners().forEachIndexed { i, p ->
                            if (i == 0) path.moveTo(px(p), py(p)) else path.lineTo(px(p), py(p))
                        }
                        path.close()
                        c.drawPath(path, wallFill)
                        c.drawPath(path, wallEdge)
                    }
                    is LineEntity -> c.drawLine(px(e.start), py(e.start), px(e.end), py(e.end), stroke)
                    is PolylineEntity -> {
                        val path = android.graphics.Path()
                        e.points.forEachIndexed { i, p ->
                            if (i == 0) path.moveTo(px(p), py(p)) else path.lineTo(px(p), py(p))
                        }
                        if (e.closed) path.close()
                        c.drawPath(path, stroke)
                    }
                    is CircleEntity -> c.drawCircle(px(e.center), py(e.center), (e.radius * k).toFloat(), stroke)
                    is ArcEntity -> drawArc(c, e, ::px, ::py, k, stroke)
                    is TextEntity -> {
                        textPaint.textSize = max(1.8, e.heightM * k).toFloat()
                        c.save()
                        c.rotate((-e.rotationDeg).toFloat(), px(e.position), py(e.position))
                        c.drawText(e.text, px(e.position), py(e.position), textPaint)
                        c.restore()
                    }
                    is DimensionEntity -> drawDimension(c, e, ::px, ::py, dimPaint, textPaint)
                }
            }
            c.restore()

            doc.finishPage(page)
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    private fun drawArc(
        c: Canvas, a: ArcEntity, px: (Vec2) -> Float, py: (Vec2) -> Float, k: Double, paint: Paint
    ) {
        val cx = px(a.center)
        val cy = py(a.center)
        val r = (a.radius * k).toFloat()
        // World CCW angles appear mirrored on a Y-down page: start at -end, sweep clockwise.
        c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), (-a.endDeg).toFloat(), a.sweepDeg.toFloat(), false, paint)
    }

    private fun drawDimension(
        c: Canvas, d: DimensionEntity, px: (Vec2) -> Float, py: (Vec2) -> Float,
        paint: Paint, textPaint: Paint
    ) {
        val g = d.geometry()
        c.drawLine(px(g.p1), py(g.p1), px(g.e1), py(g.e1), paint)
        c.drawLine(px(g.p2), py(g.p2), px(g.e2), py(g.e2), paint)
        c.drawLine(px(g.d1), py(g.d1), px(g.d2), py(g.d2), paint)
        // 45° architectural ticks, 1.2 mm on paper
        val ax = px(g.d1); val ay = py(g.d1); val bx = px(g.d2); val by = py(g.d2)
        val len = Math.hypot((bx - ax).toDouble(), (by - ay).toDouble())
        if (len > 1e-6) {
            val ux = ((bx - ax) / len).toFloat(); val uy = ((by - ay) / len).toFloat()
            val tx = (ux - uy) * 0.85f; val ty = (uy + ux) * 0.85f
            c.drawLine(ax - tx, ay - ty, ax + tx, ay + ty, paint)
            c.drawLine(bx - tx, by - ty, bx + tx, by + ty, paint)
            textPaint.textSize = 2.5f
            textPaint.textAlign = Paint.Align.CENTER
            var angle = -g.angleDeg
            if (angle > 90.0) angle -= 180.0 else if (angle < -90.0) angle += 180.0
            val mx = (ax + bx) / 2f; val my = (ay + by) / 2f
            c.save()
            c.rotate(angle.toFloat(), mx, my)
            c.drawText(Units.formatLength(g.length), mx, my - 0.8f, textPaint)
            c.restore()
            textPaint.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawSheetFrame(
        c: Canvas, w: Double, h: Double, margin: Double, titleH: Double,
        title: String, scale: Int, paper: PaperSize
    ) {
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.BLACK; strokeWidth = 0.6f }
        val thin = Paint(line).apply { strokeWidth = 0.25f }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.BLACK }
        val l = margin.toFloat(); val t = margin.toFloat()
        val r = (w - margin).toFloat(); val b = (h - margin).toFloat()
        c.drawRect(l, t, r, b, line)
        val ty = b - titleH.toFloat()
        c.drawLine(l, ty, r, ty, line)
        val split1 = l + (r - l) * 0.55f
        val split2 = l + (r - l) * 0.75f
        c.drawLine(split1, ty, split1, b, thin)
        c.drawLine(split2, ty, split2, b, thin)

        text.textSize = 3f; text.isFakeBoldText = false
        c.drawText("PROJECT", l + 2f, ty + 5f, text)
        c.drawText("SCALE / SHEET", split1 + 2f, ty + 5f, text)
        c.drawText("DATE", split2 + 2f, ty + 5f, text)

        text.textSize = 6f; text.isFakeBoldText = true
        c.drawText(title, l + 2f, ty + 15f, text)
        text.textSize = 5f
        c.drawText("1 : $scale   ${paper.label}", split1 + 2f, ty + 15f, text)
        text.isFakeBoldText = false
        c.drawText(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()), split2 + 2f, ty + 15f, text)
        text.textSize = 2.6f
        c.drawText("MyProjects", r - 22f, b - 1.8f, text)
    }

    private companion object {
        val STANDARD_SCALES = listOf(1, 2, 5, 10, 20, 25, 50, 75, 100, 125, 150, 200, 250, 500, 1000, 2000, 5000, 10000)
    }
}
