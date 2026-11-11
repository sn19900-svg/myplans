package com.myprojects.cad.data.export

import android.graphics.pdf.PdfDocument
import com.myprojects.cad.domain.structural.ColumnDesign
import com.myprojects.cad.domain.structural.ColumnInput
import com.myprojects.cad.domain.structural.ColumnTies
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** A4 calculation sheet for one column (ACI 318-19). */
class ColumnReportPdf @Inject constructor() {

    fun export(
        project: String, name: String, i: ColumnInput, d: ColumnDesign,
        loadSources: List<String>, out: OutputStream
    ) {
        val doc = PdfDocument()
        try {
            val s = ReportSheet(doc, "$project  ·  $name")
            s.newPage()
            write(s, project, name, i, d, loadSources)
            s.finish()
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    private fun write(s: ReportSheet, project: String, name: String, i: ColumnInput, d: ColumnDesign, sources: List<String>) {
        s.title("Column calculation sheet — $name")
        s.line("Project: ${project.ifBlank { "—" }}     Date: ${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}")
        s.line("Code: ACI 318-19 (SI units)     Units: mm, m, kN, kN·m, MPa")

        s.heading("1. Input")
        val size = if (i.isCircular) "Ø${f0(i.bMm)} mm" else "${f0(i.bMm)} × ${f0(i.hMm)} mm (b × h)"
        s.line("Section: ${i.shape.label}, $size, ${if (i.ties == ColumnTies.SPIRAL) "spiral" else "tied"}")
        s.line("Unsupported height lu = ${f2(i.heightM)} m, ${if (i.braced) "braced (non-sway)" else "sway"} frame, kx = ${f2(i.kx)}, ky = ${f2(i.ky)}")
        s.line("Materials: f'c = ${f0(i.fcMpa)} MPa, fy = ${f0(i.fyMpa)} MPa, fyt = ${f0(i.fytMpa)} MPa; cover ${f0(i.coverMm)} mm")
        s.line("Axial: PD = ${f1(i.pdKn)} kN, PL = ${f1(i.plKn)} kN     Factors ${f2(i.deadFactor)} D + ${f2(i.liveFactor)} L")
        s.line("Moments: Mx D/L = ${f1(i.mxDKnM)} / ${f1(i.mxLKnM)},  My D/L = ${f1(i.myDKnM)} / ${f1(i.myLKnM)} kN·m;  M1/M2 = ${f2(i.m1m2x)} / ${f2(i.m1m2y)}")
        s.line("Minimum eccentricity (15 + 0.03h) ${if (i.minEccentricity) "applied" else "not applied"}")
        sources.forEach { s.para("• load from beam — $it") }

        s.heading("2. Design actions and slenderness")
        s.line("Load type: ${d.typeLabel}", s.bold)
        s.line("Pu = ${f1(d.puKn)} kN")
        s.table(
            listOf("Axis", "kℓu/r", "Limit", "Slender", "Pc kN", "δ", "M2", "Mdesign"),
            listOf(d.axisX, d.axisY).map {
                listOf(
                    it.axis.substringBefore(' '), f1(it.kluOverR), f1(it.limit), if (it.slender) "yes" else "no",
                    if (it.pcKn.isFinite()) f0(it.pcKn) else "—", if (it.delta.isFinite()) f2(it.delta) else "∞",
                    f1(it.m2KnM), if (it.designMomentKnM.isFinite()) f1(it.designMomentKnM) else "∞"
                )
            },
            listOf(0f, 40f, 90f, 135f, 190f, 250f, 295f, 350f)
        )

        s.heading("3. Reinforcement and capacity")
        s.line("Longitudinal: ${d.arrangement}   As = ${f0(d.asMm2)} mm²   ρ = ${f2(d.rho * 100)} %", s.bold)
        s.para(d.tiesText)
        s.line("φPn,max = ${f0(d.phiPnMaxKn)} kN")
        d.phiMnxKnM?.let { s.line("φMnx at Pu = ${f1(it)} kN·m") }
        d.phiMnyKnM?.let { s.line("φMny at Pu = ${f1(it)} kN·m") }
        s.line("Method: ${d.method}")
        s.line("Design moments checked: Mux = ${f1(d.muxKnM)} kN·m, Muy = ${f1(d.muyKnM)} kN·m")
        s.line("Capacity ratio = ${f2(d.ratio)}  →  ${if (d.ok) "OK" else "NOT OK"}", s.bold)

        s.heading("4. Interaction diagram")
        s.interaction(
            "φPn – φMn (compression at the top of each bending direction)",
            d.curveX.mKnM, d.curveX.pKn, d.curveY.mKnM, d.curveY.pKn, d.demandX, d.demandY
        )

        s.heading("5. Notes and checks")
        d.warnings.forEach { s.para("• $it") }
        d.notes.forEach { s.para("• $it") }
        s.para(
            "Preliminary design aid for gravity-loaded columns. Column shear, seismic detailing, joint design, " +
                "splices and footing transfer are not covered. Must be checked and approved by a licensed engineer before use."
        )
    }

    private fun f0(v: Double) = String.format(Locale.US, "%.0f", v)
    private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun f2(v: Double) = String.format(Locale.US, "%.2f", v)
}
