package com.myprojects.cad.data.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import kotlin.math.max
import kotlin.math.min

/** Minimal flowing A4 layout (points) shared by the calculation sheets. */
internal class ReportSheet(val doc: PdfDocument, val header: String) {
    val w = 595
    val h = 842
    val m = 40f
    var no = 0
    lateinit var page: PdfDocument.Page
    lateinit var c: Canvas
    var y = 0f

    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 9f }
    val bold = Paint(text).apply { isFakeBoldText = true }
    private val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; strokeWidth = 0.6f; style = Paint.Style.STROKE }

    fun newPage() {
        if (no > 0) {
            footer()
            doc.finishPage(page)
        }
        no++
        page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, no).create())
        c = page.canvas
        c.drawColor(Color.WHITE)
        y = m + 8f
    }

    private fun footer() {
        val p = Paint(text).apply { textSize = 7.5f; color = Color.DKGRAY }
        c.drawLine(m, h - m + 6f, w - m, h - m + 6f, thin)
        c.drawText(header.take(70), m, h - m + 18f, p)
        c.drawText("MyProjects · page $no", w - m - 80f, h - m + 18f, p)
    }

    fun ensure(space: Float) {
        if (y + space > h - m - 4f) newPage()
    }

    fun finish() {
        footer()
        doc.finishPage(page)
    }

    fun title(t: String) {
        val p = Paint(bold).apply { textSize = 15f }
        c.drawText(t, m, y + 12f, p)
        y += 26f
    }

    fun heading(t: String) {
        ensure(40f)
        y += 10f
        val p = Paint(bold).apply { textSize = 11f }
        c.drawText(t, m, y, p)
        y += 4f
        c.drawLine(m, y, w - m, y, thin)
        y += 12f
    }

    fun line(t: String, p: Paint = text) {
        ensure(14f)
        c.drawText(t, m, y, p)
        y += 13f
    }

    /** Wrapped paragraph. */
    fun para(t: String) {
        var rest = t
        val maxW = w - 2 * m
        while (rest.isNotEmpty()) {
            val n = text.breakText(rest, true, maxW, null).coerceAtLeast(1)
            var cut = n
            if (n < rest.length) {
                val sp = rest.lastIndexOf(' ', n)
                if (sp > 0) cut = sp
            }
            line(rest.substring(0, cut).trimEnd())
            rest = rest.substring(cut).trimStart()
        }
    }

    fun table(headers: List<String>, rows: List<List<String>>, xs: List<Float>) {
        ensure(14f * (rows.size + 2))
        headers.forEachIndexed { k, hh -> c.drawText(hh, m + xs[k], y, bold) }
        y += 4f
        c.drawLine(m, y, w - m, y, thin)
        y += 10f
        for (r in rows) {
            ensure(14f)
            r.forEachIndexed { k, cell -> c.drawText(cell, m + xs[k], y, text) }
            y += 12f
        }
        y += 4f
    }

    fun plot(title: String, xs: DoubleArray, series: List<DoubleArray>, supports: List<Double>, positiveDown: Boolean) {
        val height = 110f
        ensure(height + 30f)
        c.drawText(title, m, y, bold)
        y += 8f
        val left = m + 4f
        val width = w - 2 * m - 8f
        val top = y + 14f
        val h2 = height - 28f
        var lo = 0.0
        var hi = 0.0
        var maxV = 0.0; var minV = 0.0
        for (sr in series) for (v in sr) { lo = min(lo, v); hi = max(hi, v); maxV = max(maxV, v); minV = min(minV, v) }
        if (hi - lo < 1e-9) hi = lo + 1.0
        val x0 = xs.first()
        val span = max(xs.last() - x0, 1e-9)
        fun px(x: Double) = (left + (x - x0) / span * width).toFloat()
        fun py(v: Double) = (if (positiveDown) top + (v - lo) / (hi - lo) * h2 else top + (hi - v) / (hi - lo) * h2).toFloat()
        c.drawRect(left, top - 10f, left + width.toFloat(), top + h2.toFloat() + 10f, thin)
        val zero = py(0.0)
        c.drawLine(left, zero, left + width.toFloat(), zero, thin)
        val colors = listOf(Color.rgb(20, 90, 180), Color.rgb(200, 90, 20))
        series.forEachIndexed { si, sr ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f; color = colors[si % 2] }
            val path = android.graphics.Path()
            for (k in xs.indices) if (k == 0) path.moveTo(px(xs[k]), py(sr[k])) else path.lineTo(px(xs[k]), py(sr[k]))
            c.drawPath(path, p)
        }
        for (sx in supports) {
            val t = android.graphics.Path()
            t.moveTo(px(sx), zero); t.lineTo(px(sx) - 4f, zero + 8f); t.lineTo(px(sx) + 4f, zero + 8f); t.close()
            c.drawPath(t, Paint(text).apply { style = Paint.Style.FILL })
        }
        val small = Paint(text).apply { textSize = 8f }
        c.drawText("max %.1f   min %.1f".format(Locale.US, maxV, minV), left + 4f, top + h2.toFloat() + 8f, small)
        y = top + h2.toFloat() + 22f
    }

    /**
     * Column interaction diagram: φPn–φMn for both axes with the demand points.
     * Axes: moment to the right, axial load up.
     */
    fun interaction(
        title: String,
        mX: DoubleArray, pX: DoubleArray, mY: DoubleArray, pY: DoubleArray,
        demandX: Pair<Double, Double>, demandY: Pair<Double, Double>
    ) {
        val height = 170f
        ensure(height + 30f)
        c.drawText(title, m, y, bold)
        y += 8f
        val left = m + 26f
        val width = w - 2 * m - 34f
        val top = y + 6f
        val h2 = height - 24f
        val maxM = max(1.0, max(mX.maxOrNull() ?: 1.0, max(mY.maxOrNull() ?: 1.0, max(demandX.first, demandY.first)))) * 1.1
        val maxP = max(1.0, max(pX.maxOrNull() ?: 1.0, max(pY.maxOrNull() ?: 1.0, demandX.second))) * 1.1
        fun px(v: Double) = (left + v / maxM * width).toFloat()
        fun py(v: Double) = (top + h2 - v / maxP * h2).toFloat()
        c.drawRect(left, top, left + width.toFloat(), top + h2.toFloat(), thin)
        val colors = listOf(Color.rgb(20, 90, 180), Color.rgb(200, 90, 20))
        listOf(mX to pX, mY to pY).forEachIndexed { k, (mm, pp) ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.3f; color = colors[k] }
            val path = android.graphics.Path()
            for (i in mm.indices) if (i == 0) path.moveTo(px(mm[i]), py(pp[i])) else path.lineTo(px(mm[i]), py(pp[i]))
            c.drawPath(path, paint)
        }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.RED }
        c.drawCircle(px(demandX.first), py(demandX.second), 2.6f, dot)
        c.drawCircle(px(demandY.first), py(demandY.second), 2.6f, dot)
        val small = Paint(text).apply { textSize = 8f }
        c.drawText("φMn (kN·m) →", left + width.toFloat() - 62f, top + h2.toFloat() + 10f, small)
        c.drawText("φPn (kN) ↑", m, top + 8f, small)
        c.drawText("blue: about X   orange: about Y   red: demand", left + 6f, top + 10f, small)
        y = top + h2.toFloat() + 22f
    }
}

