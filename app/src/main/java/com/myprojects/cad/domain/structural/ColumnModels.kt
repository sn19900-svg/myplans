package com.myprojects.cad.domain.structural

import com.myprojects.cad.core.geometry.Vec2
import com.myprojects.cad.domain.model.ColumnShape
import com.myprojects.cad.domain.model.newId

enum class ColumnTies(val label: String) {
    TIED("Tied (ties)"),
    SPIRAL("Spiral")
}

/**
 * Column design input. Units: mm, m, MPa, kN, kN·m.
 * For circular columns [bMm] and [hMm] both hold the diameter.
 * Moment convention: Mx acts about the x axis (bends the section over its depth h), My about the y axis (over width b).
 * M1/M2 is negative for single curvature, positive for double curvature (ACI 318-19 6.2.5).
 */
data class ColumnInput(
    val shape: ColumnShape = ColumnShape.RECTANGULAR,
    val ties: ColumnTies = ColumnTies.TIED,
    val bMm: Double = 300.0,
    val hMm: Double = 300.0,
    val heightM: Double = 3.0,
    val braced: Boolean = true,
    val kx: Double = 1.0,
    val ky: Double = 1.0,
    val pdKn: Double = 0.0,
    val plKn: Double = 0.0,
    val mxDKnM: Double = 0.0,
    val mxLKnM: Double = 0.0,
    val myDKnM: Double = 0.0,
    val myLKnM: Double = 0.0,
    val m1m2x: Double = -1.0,
    val m1m2y: Double = -1.0,
    val fcMpa: Double = 25.0,
    val fyMpa: Double = 420.0,
    val fytMpa: Double = 420.0,
    val coverMm: Double = 40.0,
    val barDiaMm: Double = 16.0,
    val tieDiaMm: Double = 10.0,
    /** 0 = choose the smallest number of bars that works. */
    val barCount: Int = 0,
    val minEccentricity: Boolean = true,
    /** Try Φ14…Φ32 and keep the lightest design that works (overrides [barDiaMm]). */
    val autoBarDia: Boolean = false,
    val deadFactor: Double = 1.2,
    val liveFactor: Double = 1.6
) {
    val isCircular: Boolean get() = shape == ColumnShape.CIRCULAR

    fun validate(): String? {
        if (bMm !in 150.0..2000.0 || hMm !in 150.0..2000.0) return "Section sizes must be between 150 and 2000 mm"
        if (isCircular && bMm != hMm) return "A circular column has one diameter"
        if (heightM !in 0.5..30.0) return "Unsupported height must be between 0.5 and 30 m"
        if (kx !in 0.5..4.0 || ky !in 0.5..4.0) return "Effective-length factor k must be between 0.5 and 4"
        if (!braced && (kx < 1.0 || ky < 1.0)) return "Sway columns need k ≥ 1.0"
        if (pdKn < 0.0 || plKn < 0.0) return "Axial loads must be zero or positive (tension columns are not covered)"
        if (deadFactor * pdKn + liveFactor * plKn < 1.0) return "Enter the axial load (dead and/or live)"
        if (m1m2x !in -1.0..1.0 || m1m2y !in -1.0..1.0) return "M1/M2 must be between −1 and +1"
        if (fcMpa !in 15.0..80.0) return "f'c must be between 15 and 80 MPa"
        if (fyMpa !in 240.0..600.0 || fytMpa !in 240.0..600.0) return "Steel yield strength must be 240–600 MPa"
        if (coverMm !in 15.0..100.0) return "Cover must be between 15 and 100 mm"
        if (barDiaMm !in 12.0..40.0) return "Bar diameter must be 12–40 mm"
        if (tieDiaMm !in 6.0..20.0) return "Tie / spiral diameter must be 6–20 mm"
        if (barCount < 0 || barCount > 60) return "Bar count must be 0 (auto) to 60"
        if (deadFactor !in 1.0..3.0 || liveFactor !in 1.0..3.0) return "Load factors must be between 1.0 and 3.0"
        if (coverMm + tieDiaMm + barDiaMm >= minOf(bMm, hMm) / 2.0) return "Cover and bars do not fit in this section"
        return null
    }
}

// ---- Results ---------------------------------------------------------------------------------------

data class AxisSlenderness(
    val axis: String,
    val kluOverR: Double,
    val limit: Double,
    val slender: Boolean,
    val pcKn: Double,
    val cm: Double,
    val delta: Double,
    val m2KnM: Double,
    val designMomentKnM: Double,
    val unstable: Boolean
)

data class InteractionCurve(
    /** φMn (kN·m) and φPn (kN) of the design strength curve, compression at the top. */
    val mKnM: DoubleArray,
    val pKn: DoubleArray
)

data class ColumnDesign(
    val typeLabel: String,
    val puKn: Double,
    val muxKnM: Double,
    val muyKnM: Double,
    val axisX: AxisSlenderness,
    val axisY: AxisSlenderness,
    val bars: Int,
    val barDiaMm: Int,
    val arrangement: String,
    val rho: Double,
    val asMm2: Double,
    val tiesText: String,
    val phiPnMaxKn: Double,
    val phiMnxKnM: Double?,
    val phiMnyKnM: Double?,
    val ratio: Double,
    val method: String,
    val ok: Boolean,
    val notes: List<String>,
    val warnings: List<String>,
    val curveX: InteractionCurve,
    val curveY: InteractionCurve,
    val demandX: Pair<Double, Double>,
    val demandY: Pair<Double, Double>
)

data class ColumnSummary(
    val section: String,
    val bars: String,
    val ties: String,
    val puKn: Double,
    val ratio: Double,
    val ok: Boolean
)

/** The saved study of one column in the drawing. */
data class ColumnStudyRecord(
    val id: String = newId(),
    val entityId: String,
    val name: String,
    val input: ColumnInput,
    val summary: ColumnSummary? = null,
    val modifiedAt: Long = System.currentTimeMillis()
)

/** Reaction a beam delivers to one of its supports (service level) — used to load the column below it. */
data class SupportLoad(
    val point: Vec2,
    val beamName: String,
    val deadKn: Double,
    val liveKn: Double,
    /** Beam end moment (kN·m, ultimate) when the beam is rigidly connected at this end; else 0. */
    val endMomentKnM: Double
)

data class ColumnStudyRequest(
    val entityId: String,
    val name: String,
    val shape: ColumnShape,
    val bMm: Double,
    val hMm: Double,
    val pdKn: Double,
    val plKn: Double,
    val loadSources: List<String>,
    val note: String? = null,
    val projectName: String = ""
)

fun ColumnDesign.toSummary(input: ColumnInput): ColumnSummary {
    val section = if (input.isCircular) "Ø${(input.bMm / 10.0).toInt()}" else "${(input.bMm / 10.0).toInt()}×${(input.hMm / 10.0).toInt()}"
    return ColumnSummary(section, "${bars}Φ$barDiaMm", tiesText, puKn, ratio, ok)
}
