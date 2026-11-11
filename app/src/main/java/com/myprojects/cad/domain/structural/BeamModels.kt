package com.myprojects.cad.domain.structural

import com.myprojects.cad.core.geometry.Vec2
import com.myprojects.cad.domain.model.newId

/** Units everywhere in this package: m, mm, MPa, kN, kN·m. */
enum class EndCondition(val label: String) {
    PINNED("Pinned"),
    FIXED("Fixed"),
    FREE("Overhang (free end)")
}

enum class BeamType(val label: String) {
    SIMPLY_SUPPORTED("Simply supported"),
    CANTILEVER("Cantilever"),
    FIXED_FIXED("Fixed–fixed"),
    PROPPED("Propped cantilever"),
    CONTINUOUS("Continuous")
}

/** Point load on a span. [posM] is measured from the left end of that span. Loads act downward. */
data class BeamPointLoad(
    val spanIndex: Int,
    val posM: Double,
    val deadKn: Double,
    val liveKn: Double
)

data class BeamInput(
    val type: BeamType,
    val spansM: List<Double>,
    /** Superimposed dead load per span (kN/m), WITHOUT the beam's own weight. */
    val deadKnPerM: List<Double>,
    val liveKnPerM: List<Double>,
    val pointLoads: List<BeamPointLoad> = emptyList(),
    val bMm: Double = 250.0,
    val hMm: Double = 500.0,
    val fcMpa: Double = 25.0,
    val fyMpa: Double = 420.0,
    val fytMpa: Double = 420.0,
    val coverMm: Double = 40.0,
    val stirrupDiaMm: Double = 10.0,
    val barDiaMm: Double = 16.0,
    val supportWidthMm: Double = 250.0,
    val includeSelfWeight: Boolean = true,
    val deadFactor: Double = 1.2,
    val liveFactor: Double = 1.6,
    /** Only used by [BeamType.CONTINUOUS]; the other types imply their end conditions. */
    val leftEnd: EndCondition = EndCondition.PINNED,
    val rightEnd: EndCondition = EndCondition.PINNED
) {
    val leftCondition: EndCondition
        get() = when (type) {
            BeamType.SIMPLY_SUPPORTED -> EndCondition.PINNED
            BeamType.CANTILEVER, BeamType.FIXED_FIXED, BeamType.PROPPED -> EndCondition.FIXED
            BeamType.CONTINUOUS -> leftEnd
        }

    val rightCondition: EndCondition
        get() = when (type) {
            BeamType.SIMPLY_SUPPORTED, BeamType.PROPPED -> EndCondition.PINNED
            BeamType.CANTILEVER -> EndCondition.FREE
            BeamType.FIXED_FIXED -> EndCondition.FIXED
            BeamType.CONTINUOUS -> rightEnd
        }

    /** Number of vertical supports (every span boundary except free ends). */
    val supportCount: Int
        get() {
            val n = spansM.size
            var c = n + 1
            if (leftCondition == EndCondition.FREE) c--
            if (rightCondition == EndCondition.FREE) c--
            return c
        }

    /** Reinforced-concrete unit weight 25 kN/m³. */
    val selfWeightKnPerM: Double
        get() = if (includeSelfWeight) 25.0 * (bMm / 1000.0) * (hMm / 1000.0) else 0.0

    /** Returns a human-readable problem, or null when the input is usable. */
    fun validate(): String? {
        val n = spansM.size
        if (n == 0) return "Add at least one span"
        if (type != BeamType.CONTINUOUS && n != 1) return "${type.label} beams have exactly one span"
        val hasFixedEnd = leftCondition == EndCondition.FIXED || rightCondition == EndCondition.FIXED
        if (supportCount < 2 && !hasFixedEnd) return "This end-condition combination is unstable — add a support or fix an end"
        if (supportCount < 1) return "The beam has no support"
        if (type == BeamType.CONTINUOUS && n < 2) return "A continuous beam needs at least two spans"
        if (n > 8) return "Up to 8 spans are supported"
        if (deadKnPerM.size != n || liveKnPerM.size != n) return "Loads must match the number of spans"
        if (spansM.any { !it.isFinite() || it < 0.3 || it > 30.0 }) return "Each span must be between 0.3 m and 30 m"
        if (deadKnPerM.any { !it.isFinite() || it < 0.0 } || liveKnPerM.any { !it.isFinite() || it < 0.0 }) {
            return "Loads must be zero or positive"
        }
        for (p in pointLoads) {
            if (p.spanIndex !in 0 until n) return "A point load refers to a span that does not exist"
            if (!p.posM.isFinite() || p.posM < 0.0 || p.posM > spansM[p.spanIndex]) {
                return "Point load position must lie inside its span"
            }
            if (!p.deadKn.isFinite() || !p.liveKn.isFinite() || p.deadKn < 0.0 || p.liveKn < 0.0) {
                return "Point loads must be zero or positive"
            }
        }
        if (bMm !in 100.0..1500.0) return "Width b must be between 100 and 1500 mm"
        if (hMm !in 150.0..2500.0) return "Depth h must be between 150 and 2500 mm"
        if (fcMpa !in 15.0..80.0) return "f'c must be between 15 and 80 MPa"
        if (fyMpa !in 240.0..600.0 || fytMpa !in 240.0..600.0) return "Steel yield strength must be 240–600 MPa"
        if (coverMm !in 15.0..100.0) return "Cover must be between 15 and 100 mm"
        if (stirrupDiaMm !in 6.0..20.0) return "Stirrup diameter must be 6–20 mm"
        if (barDiaMm !in 10.0..40.0) return "Bar diameter must be 10–40 mm"
        if (supportWidthMm !in 0.0..1500.0) return "Support width must be 0–1500 mm"
        if (deadFactor !in 1.0..3.0 || liveFactor !in 1.0..3.0) return "Load factors must be between 1.0 and 3.0"
        if (hMm - coverMm - stirrupDiaMm - barDiaMm - 12.5 < 50.0) return "Depth is too small for this cover and bar size"
        return null
    }
}

// ---- Results -----------------------------------------------------------------------------------------

data class SupportReaction(
    val label: String,
    val xM: Double,
    val deadKn: Double,
    val liveKn: Double,
    val ultimateMaxKn: Double,
    /** Largest live reaction over all load patterns (service). */
    val liveMaxKn: Double = liveKn
)

data class FlexureDesign(
    val label: String,
    val xM: Double,
    val muKnM: Double,
    val dMm: Double,
    val asReqMm2: Double,
    val asMinMm2: Double,
    val asProvMm2: Double,
    val bars: Int,
    val barDiaMm: Int,
    val layers: Int,
    val phiMnKnM: Double,
    val ok: Boolean,
    val note: String?,
    /** true = top (negative-moment) steel, false = bottom steel. */
    val top: Boolean = false
) {
    val barsText: String get() = if (bars > 0) "${bars}Φ$barDiaMm" else "—"
}

data class ShearDesign(
    val label: String,
    val vuKn: Double,
    val dMm: Double,
    val phiVcKn: Double,
    val s1Mm: Double,
    val s2Mm: Double,
    val zoneLengthM: Double,
    val stirrupText: String,
    val ok: Boolean,
    val note: String?
)

data class DeflectionRow(
    val label: String,
    val spanM: Double,
    val liveMm: Double,
    val afterAttachmentMm: Double,
    val liveLimitMm: Double,
    val limit480Mm: Double,
    val limit240Mm: Double,
    val status: String,
    val ok: Boolean
)

data class HMinRow(val label: String, val spanM: Double, val hMinMm: Double, val ok: Boolean)

data class BeamDesign(
    val flexure: List<FlexureDesign>,
    val shear: List<ShearDesign>,
    val deflection: List<DeflectionRow>,
    val hMin: List<HMinRow>,
    val ieScale: Double,
    val deflectionNote: String,
    val warnings: List<String>
) {
    val allOk: Boolean get() = flexure.all { it.ok } && shear.all { it.ok } && warnings.isEmpty()
}


// ---- Study records (stored with the project) ----------------------------------------------------------

data class StudySummary(
    val section: String,
    val bottom: String,
    val top: String,
    val stirrups: String,
    val ok: Boolean,
    val maxMomentKnM: Double,
    val maxShearKn: Double
)

/** The saved study of one beam. [entityIds] are the drawing elements (in order) that make up the beam. */
data class StudyRecord(
    val id: String = newId(),
    val entityIds: List<String>,
    val name: String,
    val input: BeamInput,
    val summary: StudySummary? = null,
    val modifiedAt: Long = System.currentTimeMillis(),
    /** Loads this beam delivers to its supports, located in the drawing (for column design). */
    val supports: List<SupportLoad> = emptyList()
)

/** What the drawing hands to the beam-study screen when the user presses Study. */
data class BeamStudyRequest(
    val entityIds: List<String>,
    val name: String,
    val spansM: List<Double>,
    val note: String? = null,
    val projectName: String = "",
    /** Plan points at the span boundaries (n + 1 points), used to locate the supports. */
    val boundaryPoints: List<Vec2> = emptyList()
)

fun BeamDesign.toSummary(input: BeamInput, maxMomentKnM: Double, maxShearKn: Double): StudySummary {
    fun pick(top: Boolean): String =
        flexure.filter { it.top == top }.maxByOrNull { it.asProvMm2 }?.barsText ?: "—"
    val s1 = shear.minOfOrNull { it.s1Mm }?.toInt()
    val s2 = shear.minOfOrNull { it.s2Mm }?.toInt()
    val dia = input.stirrupDiaMm.toInt()
    val stirrups = when {
        s1 == null || s2 == null -> "—"
        s1 >= s2 -> "Φ$dia@$s2"
        else -> "Φ$dia@$s1/$s2"
    }
    val section = "${(input.bMm / 10.0).toInt()}×${(input.hMm / 10.0).toInt()}"
    return StudySummary(section, pick(false), pick(true), stirrups, allOk, maxMomentKnM, maxShearKn)
}
