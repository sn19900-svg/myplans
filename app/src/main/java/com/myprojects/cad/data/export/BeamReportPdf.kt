package com.myprojects.cad.data.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.myprojects.cad.domain.structural.BeamAnalysis
import com.myprojects.cad.domain.structural.BeamDesign
import com.myprojects.cad.domain.structural.BeamType
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

/** A4 calculation sheet for one beam: inputs, analysis, diagrams, ACI 318-19 design and checks. */
class BeamReportPdf @Inject constructor() {

    fun export(project: String, name: String, a: BeamAnalysis, d: BeamDesign, out: OutputStream) {
        val doc = PdfDocument()
        try {
            val sheet = Sheet(doc, "$project  ·  $name")
            sheet.newPage()
            write(sheet, project, name, a, d)
            sheet.finish()
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    private fun write(s: Sheet, project: String, name: String, a: BeamAnalysis, d: BeamDesign) {
        val i = a.input
        s.title("Beam calculation sheet — $name")
        s.line("Project: ${project.ifBlank { "—" }}     Date: ${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}")
        s.line("Code: ACI 318-19 (SI units)     Units: m, mm, kN, kN·m, MPa")

        s.heading("1. Input")
        val ends = if (i.type == BeamType.CONTINUOUS) "  (left: ${i.leftCondition.label}, right: ${i.rightCondition.label})" else ""
        s.line("Type: ${i.type.label}$ends")
        s.line("Section: b = ${f0(i.bMm)} mm, h = ${f0(i.hMm)} mm, cover = ${f0(i.coverMm)} mm, bars Φ${f0(i.barDiaMm)}, stirrups Φ${f0(i.stirrupDiaMm)}")
        s.line("Materials: f'c = ${f0(i.fcMpa)} MPa, fy = ${f0(i.fyMpa)} MPa, fyt = ${f0(i.fytMpa)} MPa, Ec = ${f0(a.ecMpa)} MPa")
        s.line("Load factors: ${f2(i.deadFactor)} D + ${f2(i.liveFactor)} L (live-load pattern loading); self-weight ${if (i.includeSelfWeight) f2(i.selfWeightKnPerM) + " kN/m" else "excluded"}")
        s.table(
            listOf("Span", "Length m", "Dead kN/m", "Live kN/m"),
            i.spansM.indices.map { k -> listOf("${k + 1}", f2(i.spansM[k]), f2(i.deadKnPerM[k]), f2(i.liveKnPerM[k])) },
            listOf(0f, 70f, 160f, 260f)
        )
        if (i.pointLoads.isNotEmpty()) {
            s.table(
                listOf("Point load", "Span", "x m", "Dead kN", "Live kN"),
                i.pointLoads.mapIndexed { k, p -> listOf("P${k + 1}", "${p.spanIndex + 1}", f2(p.posM), f2(p.deadKn), f2(p.liveKn)) },
                listOf(0f, 70f, 130f, 200f, 290f)
            )
        }

        s.heading("2. Analysis results (ultimate envelope)")
        s.table(
            listOf("Support", "x m", "R dead kN", "R live kN", "R ult. max kN"),
            a.reactions.map { listOf(it.label, f2(it.xM), f1(it.deadKn), f1(it.liveKn), f1(it.ultimateMaxKn)) },
            listOf(0f, 70f, 130f, 210f, 290f)
        )
        a.spanPositiveKnM.forEachIndexed { j, m ->
            if (m > 0.01) s.line("Span ${j + 1}: Mu+ = ${f1(m)} kN·m at x = ${f2(a.spanPositiveAtX[j])} m")
        }
        a.supportNegativeKnM.forEachIndexed { k, m ->
            if (m > 0.01) s.line("Support ${'A' + k}: Mu− = ${f1(m)} kN·m")
        }
        s.line("Vu,max = ${f1(max(a.vMax.max(), -a.vMin.min()))} kN")

        s.heading("3. Diagrams")
        s.plot("Shear force Vu (kN)", a.xs, listOf(a.vMax, a.vMin), a.supportsX, positiveDown = false)
        s.plot("Bending moment Mu (kN·m), sagging drawn down", a.xs, listOf(a.mMax, a.mMin), a.supportsX, positiveDown = true)
        val defl = DoubleArray(a.deflXs.size) { (a.deflDeadMm[it] + a.deflLiveMm[it]) * d.ieScale }
        s.plot("Service deflection D+L, cracked section (mm)", a.deflXs, listOf(defl), a.supportsX, positiveDown = true)

        s.heading("4. Flexure design")
        s.table(
            listOf("Section", "Mu", "d mm", "As req", "As prov", "Bars", "φMn"),
            d.flexure.map {
                listOf(
                    it.label, f1(it.muKnM), f0(it.dMm),
                    if (it.asReqMm2.isFinite()) f0(it.asReqMm2) else "—",
                    f0(it.asProvMm2), it.barsText, f1(it.phiMnKnM) + if (it.ok) "" else " ✗"
                )
            },
            listOf(0f, 135f, 180f, 225f, 280f, 335f, 390f)
        )
        d.flexure.filter { it.note != null }.forEach { s.para("• ${it.label}: ${it.note}") }

        s.heading("5. Shear design (critical section d from the support face)")
        d.shear.forEach {
            s.line("${it.label}: Vu = ${f1(it.vuKn)} kN, φVc = ${f1(it.phiVcKn)} kN, d = ${f0(it.dMm)} mm", s.bold)
            s.para(it.stirrupText + (it.note?.let { n -> "  — $n" } ?: ""))
        }

        s.heading("6. Deflection (ACI Table 24.2.2)")
        s.table(
            listOf("Span", "Live", "After attach.", "L/360", "L/480", "L/240"),
            d.deflection.map { listOf(it.label, f1(it.liveMm), f1(it.afterAttachmentMm), f1(it.liveLimitMm), f1(it.limit480Mm), f1(it.limit240Mm)) },
            listOf(0f, 110f, 170f, 250f, 305f, 360f)
        )
        d.deflection.forEach { s.para("• ${it.label}: ${it.status}") }
        s.para(d.deflectionNote)
        d.hMin.forEach { s.line("Min. depth (Table 9.3.1.1) ${it.label}: ${f0(it.hMinMm)} mm — ${if (it.ok) "satisfied" else "NOT satisfied"}") }

        s.heading(if (d.warnings.isEmpty()) "7. Result: all checks passed" else "7. Result: ${d.warnings.size} check(s) failed")
        d.warnings.forEach { s.para("• $it") }
        s.para(
            "Preliminary design aid. Singly reinforced rectangular sections, gravity loads only, supports assumed to compress the beam ends. " +
                "Torsion, seismic detailing, development/splice lengths and bar cut-off points are not covered. " +
                "Must be checked and approved by a licensed engineer before use."
        )
    }

    private fun f0(v: Double) = String.format(Locale.US, "%.0f", v)
    private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun f2(v: Double) = String.format(Locale.US, "%.2f", v)

    /** Minimal flowing A4 layout (points). */
    private class Sheet(val doc: PdfDocument, val header: String) {
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
    }
}
