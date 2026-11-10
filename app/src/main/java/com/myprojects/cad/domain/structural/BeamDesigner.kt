package com.myprojects.cad.domain.structural

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Reinforced-concrete rectangular beam design to ACI 318-19 (SI units).
 * Flexure: tension-controlled singly reinforced sections (φ = 0.9). Shear: Vc = 0.17λ√f'c·bw·d, φ = 0.75.
 * Deflection: Branson effective inertia (24.2.3.5), long-term multiplier 2.0 (≥ 5 years, no compression steel).
 */
object BeamDesigner {
    private const val PHI_FLEX = 0.9
    private const val PHI_SHEAR = 0.75
    private const val ES_MPA = 200_000.0
    private const val LONG_TERM = 2.0

    fun design(a: BeamAnalysis): BeamDesign {
        val inp = a.input
        val n = inp.spansM.size
        val warnings = ArrayList<String>()

        // ---- Flexure ----
        val flex = ArrayList<FlexureDesign>()
        for (j in 0 until n) {
            if (isOverhang(inp, j)) continue // overhangs only have top steel at their support
            flex.add(designSection("${spanLabel(inp, j)} (bottom)", a.spanPositiveAtX[j], a.spanPositiveKnM[j], inp, top = false))
        }
        if (inp.type != BeamType.SIMPLY_SUPPORTED) {
            for (i in a.supportsX.indices) {
                flex.add(designSection("Support ${'A' + i} (top)", a.supportsX[i], a.supportNegativeKnM[i], inp, top = true))
            }
        }
        for (f in flex) if (!f.ok) warnings.add("${f.label}: ${f.note ?: "check section"}")

        // ---- Shear ----
        val shear = ArrayList<ShearDesign>()
        for (j in 0 until n) shear.add(designShear(a, j, warnings))

        // ---- Deflection ----
        val (scale, scaleNote) = inertiaScale(a, flex)
        val rows = ArrayList<DeflectionRow>()
        for (j in 0 until n) {
            val len = inp.spansM[j]
            val lo = a.spanStartX[j]
            val hi = a.spanStartX[j + 1]
            var liveMax = 0.0
            var deadMax = 0.0
            for (i in a.deflXs.indices) {
                val x = a.deflXs[i]
                if (x >= lo - 1e-9 && x <= hi + 1e-9) {
                    liveMax = max(liveMax, abs(a.deflLiveMm[i]))
                    deadMax = max(deadMax, abs(a.deflDeadMm[i]))
                }
            }
            val live = liveMax * scale
            val after = LONG_TERM * deadMax * scale + live
            val l = len * 1000.0
            val okLive = live <= l / 360.0
            val status = when {
                !okLive -> "Live deflection exceeds L/360"
                after <= l / 480.0 -> "OK (also for damage-prone finishes)"
                after <= l / 240.0 -> "OK unless finishes are damage-prone"
                else -> "Long-term deflection exceeds L/240"
            }
            rows.add(
                DeflectionRow(
                    label = spanLabel(inp, j),
                    spanM = len, liveMm = live, afterAttachmentMm = after,
                    liveLimitMm = l / 360.0, limit480Mm = l / 480.0, limit240Mm = l / 240.0,
                    status = status, ok = okLive && after <= l / 240.0
                )
            )
        }
        for (r in rows) if (!r.ok) warnings.add("${r.label}: ${r.status}")

        // ---- Minimum depth (ACI Table 9.3.1.1) ----
        val fyFactor = 0.4 + inp.fyMpa / 700.0
        val hMin = (0 until n).map { j ->
            val l = inp.spansM[j] * 1000.0
            val div = if (isOverhang(inp, j)) {
                8.0
            } else {
                val leftCont = j > 0 || inp.leftCondition == EndCondition.FIXED
                val rightCont = j < n - 1 || inp.rightCondition == EndCondition.FIXED
                when {
                    leftCont && rightCont -> 21.0
                    leftCont || rightCont -> 18.5
                    else -> 16.0
                }
            }
            val hm = l / div * fyFactor
            HMinRow(spanLabel(inp, j), inp.spansM[j], hm, inp.hMm >= hm)
        }

        return BeamDesign(flex, shear, rows, hMin, scale, scaleNote, warnings)
    }

    internal fun isOverhang(inp: BeamInput, j: Int): Boolean {
        val n = inp.spansM.size
        return (j == 0 && inp.leftCondition == EndCondition.FREE) ||
            (j == n - 1 && inp.rightCondition == EndCondition.FREE)
    }

    internal fun spanLabel(inp: BeamInput, j: Int): String {
        val n = inp.spansM.size
        return when {
            inp.type == BeamType.CANTILEVER -> "Cantilever"
            j == 0 && inp.leftCondition == EndCondition.FREE -> "Overhang (left)"
            j == n - 1 && inp.rightCondition == EndCondition.FREE -> "Overhang (right)"
            else -> "Span ${j + 1}"
        }
    }

    // ---- Flexure helpers --------------------------------------------------------------------------

    private fun beta1(fc: Double): Double = when {
        fc <= 28.0 -> 0.85
        fc >= 55.0 -> 0.65
        else -> 0.85 - 0.05 * (fc - 28.0) / 7.0
    }

    /** Required tension steel (mm²) or null if the section cannot be tension-controlled without compression steel. */
    internal fun requiredAs(muKnM: Double, b: Double, d: Double, fc: Double, fy: Double): Double? {
        if (muKnM <= 1e-9) return 0.0
        val rn = muKnM * 1e6 / (PHI_FLEX * b * d * d)
        val arg = 1.0 - 2.0 * rn / (0.85 * fc)
        if (arg <= 0.0) return null
        val rho = 0.85 * fc / fy * (1.0 - sqrt(arg))
        val asReq = rho * b * d
        val aDepth = asReq * fy / (0.85 * fc * b)
        if (aDepth > beta1(fc) * 0.375 * d) return null // εt < 0.005
        return asReq
    }

    private fun designSection(label: String, x: Double, muKnM: Double, inp: BeamInput, top: Boolean): FlexureDesign {
        val b = inp.bMm
        val h = inp.hMm
        val fc = inp.fcMpa
        val fy = inp.fyMpa
        val dia = inp.barDiaMm
        val ds = inp.stirrupDiaMm
        val cover = inp.coverMm
        val ab = PI * dia * dia / 4.0
        val gap = max(25.0, dia)
        val perLayer = max(1, floor((b - 2.0 * cover - 2.0 * ds + gap) / (dia + gap)).toInt())

        var layers = 1
        var d = h - cover - ds - dia / 2.0
        var asReq = 0.0
        var asMin = 0.0
        var inadequate = false
        var bars = 2
        for (attempt in 0 until 2) {
            d = if (layers == 1) h - cover - ds - dia / 2.0 else h - cover - ds - dia - gap / 2.0
            asMin = max(0.25 * sqrt(fc) / fy * b * d, 1.4 * b * d / fy)
            val r = requiredAs(muKnM, b, d, fc, fy)
            inadequate = r == null
            asReq = r ?: Double.NaN
            val asDesign = if (r == null) asMin else max(r, asMin)
            bars = max(2, ceil(asDesign / ab - 1e-9).toInt())
            if (bars <= perLayer || layers == 2) break
            layers = 2
        }
        val asProv = bars * ab
        val a = asProv * fy / (0.85 * fc * b)
        val phiMn = PHI_FLEX * asProv * fy * (d - a / 2.0) / 1e6

        var note: String? = null
        var ok = true
        if (inadequate) {
            ok = false
            note = "Section too small to be tension-controlled — increase depth or width (compression steel is not designed here)"
        } else if (bars > 2 * perLayer) {
            ok = false
            note = "$bars bars do not fit in 2 layers — use larger bars or a wider beam"
        } else if (phiMn + 1e-6 < muKnM) {
            ok = false
            note = "Provided steel gives φMn = ${"%.1f".format(phiMn)} kN·m < Mu"
        } else if (layers == 2) {
            note = "2 layers of bars (d reduced to ${"%.0f".format(d)} mm)"
        } else if (asReq.isFinite() && asReq < asMin) {
            note = "Minimum steel governs"
        }
        return FlexureDesign(label, x, muKnM, d, asReq, asMin, asProv, bars, dia.toInt(), layers, phiMn, ok, note, top)
    }

    // ---- Shear -------------------------------------------------------------------------------------

    private fun designShear(a: BeamAnalysis, j: Int, warnings: MutableList<String>): ShearDesign {
        val inp = a.input
        val label = spanLabel(inp, j)
        val b = inp.bMm
        val fc = inp.fcMpa
        val fyt = inp.fytMpa
        val ds = inp.stirrupDiaMm
        val d = inp.hMm - inp.coverMm - ds - inp.barDiaMm / 2.0
        val lo = a.spanStartX[j]
        val hi = a.spanStartX[j + 1]
        val mid = (lo + hi) / 2.0
        val half = inp.supportWidthMm / 2000.0
        val dM = d / 1000.0

        // Critical section: d from the support face (supports are assumed to compress the beam end).
        val n = inp.spansM.size
        val leftIsSupport = !(j == 0 && inp.leftCondition == EndCondition.FREE)
        val rightIsSupport = !(j == n - 1 && inp.rightCondition == EndCondition.FREE)
        val xl = if (leftIsSupport) min(lo + half + dM, mid) else lo
        val xr = if (rightIsSupport) max(hi - half - dM, mid) else hi
        val vu = max(a.shearEnvelopeAbsAt(xl), a.shearEnvelopeAbsAt(xr))

        val vc = 0.17 * sqrt(fc) * b * d / 1000.0
        val phiVc = PHI_SHEAR * vc
        val vsReq = max(0.0, vu / PHI_SHEAR - vc)
        val vsMax = 0.66 * sqrt(fc) * b * d / 1000.0
        val avMin = max(0.062 * sqrt(fc) * b / fyt, 0.35 * b / fyt) // mm²/mm
        val avReq = vsReq * 1000.0 / (fyt * d)
        val avs = max(avReq, avMin)
        val av = 2.0 * PI * ds * ds / 4.0
        val sMaxReq = if (vsReq <= 0.33 * sqrt(fc) * b * d / 1000.0) min(d / 2.0, 600.0) else min(d / 4.0, 300.0)
        val s1 = floor(min(av / avs, sMaxReq) / 25.0) * 25.0
        val s2 = floor(min(av / avMin, min(d / 2.0, 600.0)) / 25.0) * 25.0

        var ok = true
        var note: String? = null
        if (vsReq > vsMax) {
            ok = false
            note = "Vu too high for this section (Vs > 0.66√f'c·bw·d) — enlarge the section"
        } else if (s1 < 50.0) {
            ok = false
            note = "Required stirrup spacing < 50 mm — use larger stirrups or a bigger section"
        }

        // Length of the end zones where Φ(Vc+Vs at s2) is not enough.
        val capMid = PHI_SHEAR * (vc + av * fyt * d / s2.coerceAtLeast(25.0) / 1000.0)
        var zoneLeft = 0.0
        var zoneRight = 0.0
        for (i in a.xs.indices) {
            val x = a.xs[i]
            if (x < lo - 1e-9 || x > hi + 1e-9) continue
            val v = max(abs(a.vMax[i]), abs(a.vMin[i]))
            if (v > capMid) {
                if (x <= mid) zoneLeft = max(zoneLeft, x - lo) else zoneRight = max(zoneRight, hi - x)
            }
        }
        val zoneRaw = max(zoneLeft, zoneRight)
        val zone = min(ceil(zoneRaw / 0.05) * 0.05, (hi - lo) / 2.0)

        val text = if (!ok) "—" else if (s1 >= s2 || zone <= 0.0) {
            "Φ${ds.toInt()} @ ${s2.toInt()} mm throughout"
        } else {
            "Φ${ds.toInt()} @ ${s1.toInt()} mm over ${"%.2f".format(zone)} m at each end, then @ ${s2.toInt()} mm"
        }
        if (vu <= 0.5 * phiVc) {
            note = note ?: "Vu ≤ ½φVc: stirrups not required by ACI, minimum stirrups still recommended"
        }
        return ShearDesign(label, vu, d, phiVc, s1, s2, zone, text, ok, note)
    }

    // ---- Deflection: effective inertia ---------------------------------------------------------------

    /** Returns Ig/Ie (≥ 1) from the most heavily loaded section, plus an explanation. */
    private fun inertiaScale(a: BeamAnalysis, flex: List<FlexureDesign>): Pair<Double, String> {
        val inp = a.input
        val b = inp.bMm
        val h = inp.hMm
        val ig = b * h * h * h / 12.0
        val fr = 0.62 * sqrt(inp.fcMpa)
        val mcr = fr * ig / (h / 2.0) / 1e6 // kN·m
        val nRatio = ES_MPA / a.ecMpa

        var bestMa = 0.0
        var best: FlexureDesign? = null
        for (f in flex) {
            val ma = abs(a.serviceMomentAt(f.xM))
            if (ma > bestMa) { bestMa = ma; best = f }
        }
        if (best == null || bestMa < 1e-6) return 1.0 to "Section stays uncracked (Ie = Ig)."
        val d = best.dMm
        val asP = best.asProvMm2
        val nAs = nRatio * asP
        val y = (-nAs + sqrt(nAs * nAs + 2.0 * b * nAs * d)) / b
        val icr = b * y * y * y / 3.0 + nAs * (d - y) * (d - y)
        val ie = if (bestMa <= 2.0 / 3.0 * mcr) ig else {
            val r = (2.0 / 3.0 * mcr / bestMa)
            icr / (1.0 - r * r * (1.0 - icr / ig))
        }
        val ieClamped = ie.coerceIn(icr, ig)
        val scale = ig / ieClamped
        val note = "Ie = ${"%.0f".format(ieClamped / 1e6)}×10⁶ mm⁴ (Ig = ${"%.0f".format(ig / 1e6)}×10⁶) from " +
            "${best.label} where service Ma = ${"%.1f".format(bestMa)} kN·m, Mcr = ${"%.1f".format(mcr)} kN·m; " +
            "applied to the whole beam. Live load placed on the worst pattern; long-term factor 2.0."
        return scale to note
    }
}
