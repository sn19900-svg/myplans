package com.myprojects.cad.domain.structural

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Reinforced-concrete column design to ACI 318-19 (SI units; internal calculation in N and mm).
 *
 *  - Rectangular/square and circular sections, tied or spiral.
 *  - Short and slender columns (moment magnification, non-sway δns; sway δs approximated per column).
 *  - Axial, uniaxial and biaxial bending: strain-compatibility interaction diagrams for both axes,
 *    Bresler reciprocal-load check for Pu ≥ 0.1 f'c Ag, linear interaction below that.
 *  - φ varies with the extreme-fibre tension strain (ACI 21.2.2); Pn,max = 0.80 Po (tied) / 0.85 Po (spiral).
 */
object ColumnDesigner {
    private const val ES = 200_000.0
    private const val ECU = 0.003

    internal data class Bar(val y: Double, val area: Double)

    internal data class PM(val pn: Double, val mn: Double, val phi: Double, val phiPn: Double, val phiMn: Double)

    internal class Layout(val barsX: List<Bar>, val barsY: List<Bar>, val nb: Int, val nh: Int)

    internal fun beta1(fc: Double): Double = when {
        fc <= 28.0 -> 0.85
        fc >= 55.0 -> 0.65
        else -> 0.85 - 0.05 * (fc - 28.0) / 7.0
    }

    internal fun phiOf(epsT: Double, epsY: Double, spiral: Boolean): Double {
        val base = if (spiral) 0.75 else 0.65
        return when {
            epsT <= epsY -> base
            epsT >= epsY + 0.003 -> 0.90
            else -> base + (0.90 - base) * (epsT - epsY) / 0.003
        }
    }

    // ---- Section geometry (compression zone of depth a measured from the compression face) ----

    internal fun compressionArea(circular: Boolean, depth: Double, width: Double, a: Double): Double {
        val aa = a.coerceIn(0.0, depth)
        if (!circular) return width * aa
        val r = depth / 2.0
        if (aa <= 1e-9) return 0.0
        val th = acos(((r - aa) / r).coerceIn(-1.0, 1.0))
        return r * r * (th - sin(th) * cos(th))
    }

    /** Distance of the compression-zone centroid from the compression face. */
    internal fun compressionCentroid(circular: Boolean, depth: Double, a: Double): Double {
        val aa = a.coerceIn(0.0, depth)
        if (!circular) return aa / 2.0
        val r = depth / 2.0
        if (aa <= 1e-9) return 0.0
        val th = acos(((r - aa) / r).coerceIn(-1.0, 1.0))
        val denom = 3.0 * (th - sin(th) * cos(th))
        if (denom < 1e-12) return 0.0
        val fromCenter = 2.0 * r * sin(th).pow(3) / denom
        return r - fromCenter
    }

    // ---- Bar layouts -----------------------------------------------------------------------------------

    private fun minClear(db: Double) = max(40.0, 1.5 * db)

    /** Even bar counts only: bars sit on the perimeter, [nb] along each b-face and [nh] along each h-face. */
    internal fun layoutRect(n: Int, b: Double, h: Double, c0: Double, db: Double): Layout? {
        if (n < 4 || n % 2 != 0) return null
        val ab = PI * db * db / 4.0
        var bestNb = 0
        var bestNh = 0
        var bestScore = Double.MAX_VALUE
        for (nb in 2..16) {
            for (nh in 2..16) {
                if (2 * (nb + nh) - 4 != n) continue
                val clearB = (b - 2 * c0) / (nb - 1) - db
                val clearH = (h - 2 * c0) / (nh - 1) - db
                if (clearB < minClear(db) || clearH < minClear(db)) continue
                val score = abs(nb.toDouble() / nh - b / h)
                if (score < bestScore) { bestScore = score; bestNb = nb; bestNh = nh }
            }
        }
        if (bestNb == 0) return null

        fun rows(depth: Double, across: Int, along: Int): List<Bar> {
            val list = ArrayList<Bar>()
            list.add(Bar(c0, across * ab))
            for (k in 1..along - 2) list.add(Bar(c0 + k * (depth - 2 * c0) / (along - 1), 2 * ab))
            list.add(Bar(depth - c0, across * ab))
            return list
        }
        // bending about X: depth h, bars across the b-face on the top/bottom rows
        return Layout(rows(h, bestNb, bestNh), rows(b, bestNh, bestNb), bestNb, bestNh)
    }

    internal fun layoutCircle(n: Int, d: Double, c0: Double, db: Double): Layout? {
        if (n < 4) return null
        val ab = PI * db * db / 4.0
        val rs = d / 2.0 - c0
        val clear = 2.0 * rs * sin(PI / n) - db
        if (clear < minClear(db)) return null
        val bars = (0 until n).map { i -> Bar(d / 2.0 - rs * cos(2.0 * PI * i / n), ab) }
        return Layout(bars, bars, n, n)
    }

    // ---- Interaction diagram (one axis) ------------------------------------------------------------------

    internal fun diagram(
        circular: Boolean, depth: Double, width: Double, bars: List<Bar>,
        fc: Double, fy: Double, spiral: Boolean
    ): List<PM> {
        val ag = if (circular) PI * depth * depth / 4.0 else depth * width
        val ast = bars.sumOf { it.area }
        val b1 = beta1(fc)
        val dt = bars.maxOf { it.y }
        val epsY = fy / ES
        val phiC = if (spiral) 0.75 else 0.65
        val po = 0.85 * fc * (ag - ast) + fy * ast
        val pnMax = (if (spiral) 0.85 else 0.80) * po
        val list = ArrayList<PM>()
        val steps = 200
        val cMax = depth * 6.0
        val cMin = dt * 0.02
        for (i in 0..steps) {
            val c = cMax * (cMin / cMax).pow(i.toDouble() / steps)
            list.add(pointAt(circular, depth, width, bars, fc, fy, spiral, c, b1, epsY, dt, phiC * pnMax))
        }
        return list
    }

    internal fun pointAt(
        circular: Boolean, depth: Double, width: Double, bars: List<Bar>,
        fc: Double, fy: Double, spiral: Boolean, c: Double,
        b1: Double = beta1(fc), epsY: Double = fy / ES,
        dt: Double = bars.maxOf { it.y }, phiPnCap: Double = Double.MAX_VALUE
    ): PM {
        val a = min(b1 * c, depth)
        val ac = compressionArea(circular, depth, width, a)
        val yc = compressionCentroid(circular, depth, a)
        val cc = 0.85 * fc * ac
        var p = cc
        var m = cc * (depth / 2.0 - yc)
        for (bar in bars) {
            val eps = ECU * (c - bar.y) / c
            val fs = (ES * eps).coerceIn(-fy, fy)
            val f = bar.area * (fs - (if (bar.y <= a) 0.85 * fc else 0.0))
            p += f
            m += f * (depth / 2.0 - bar.y)
        }
        val epsT = ECU * (dt - c) / c
        val phi = phiOf(epsT, epsY, spiral)
        return PM(p, m, phi, min(phi * p, phiPnCap), phi * m)
    }

    /** Largest design moment (N·mm) the curve can carry at axial load [puN], or null if Pu is outside the curve. */
    internal fun phiMnAt(pts: List<PM>, puN: Double): Double? {
        var best: Double? = null
        for (i in 0 until pts.size - 1) {
            val p1 = pts[i].phiPn
            val p2 = pts[i + 1].phiPn
            if ((p1 - puN) * (p2 - puN) <= 0.0 && p1 != p2) {
                val t = (puN - p1) / (p2 - p1)
                val m = pts[i].phiMn + t * (pts[i + 1].phiMn - pts[i].phiMn)
                if (best == null || m > best) best = m
            }
        }
        return best
    }

    /** Nominal axial capacity (N) where the diagram crosses the load line of eccentricity [e] (mm). */
    internal fun pnAtEcc(pts: List<PM>, e: Double, po: Double): Double {
        for (i in 0 until pts.size - 1) {
            if (pts[i].pn <= 0.0) break
            val g1 = pts[i].mn - e * pts[i].pn
            val g2 = pts[i + 1].mn - e * pts[i + 1].pn
            if (g1 <= 0.0 && g2 > 0.0) {
                val t = -g1 / (g2 - g1)
                return max(pts[i].pn + t * (pts[i + 1].pn - pts[i].pn), 1.0)
            }
        }
        return po
    }

    // ---- Slenderness (ACI 6.2.5, 6.6.4) --------------------------------------------------------------------

    private fun slenderness(
        inp: ColumnInput, name: String, depth: Double, ig: Double, k: Double,
        muKnM: Double, m1m2: Double, puKn: Double, betaDns: Double, ec: Double
    ): AxisSlenderness {
        val r = if (inp.isCircular) 0.25 * depth else 0.3 * depth
        val lu = inp.heightM * 1000.0
        val kluR = k * lu / r
        val limit = if (inp.braced) min(34.0 + 12.0 * m1m2, 40.0) else 22.0
        val slender = kluR > limit
        val mMin = puKn * (15.0 + 0.03 * depth) / 1000.0
        if (!slender) {
            return AxisSlenderness(name, kluR, limit, false, Double.POSITIVE_INFINITY, 1.0, 1.0, muKnM, muKnM, false)
        }
        val eiN = 0.4 * ec * ig / (1.0 + betaDns)
        val pcN = PI * PI * eiN / (k * lu).pow(2)
        val puN = puKn * 1000.0
        val unstable = puN >= 0.75 * pcN
        val m2 = max(muKnM, mMin)
        val cm = if (!inp.braced || muKnM < mMin) 1.0 else max(0.6 - 0.4 * m1m2, 0.4)
        val delta = when {
            unstable -> Double.POSITIVE_INFINITY
            inp.braced -> max(cm / (1.0 - puN / (0.75 * pcN)), 1.0)
            else -> 1.0 / (1.0 - puN / (0.75 * pcN))
        }
        return AxisSlenderness(name, kluR, limit, true, pcN / 1000.0, cm, delta, m2, delta * m2, unstable)
    }

    // ---- Capacity evaluation for a chosen bar count ----------------------------------------------------------

    private class Eval(
        val n: Int, val layout: Layout, val ast: Double, val ag: Double, val po: Double, val phiPnMax: Double,
        val ratio: Double, val method: String, val phiMnX: Double?, val phiMnY: Double?,
        val ptsX: List<PM>, val ptsY: List<PM>, val mx: Double, val my: Double
    )

    private fun evaluate(
        inp: ColumnInput, n: Int, puKn: Double, mxKnM: Double, myKnM: Double,
        mMinX: Double, mMinY: Double, c0: Double
    ): Eval? {
        val circular = inp.isCircular
        val b = inp.bMm
        val h = inp.hMm
        val db = inp.barDiaMm
        val layout = (if (circular) layoutCircle(n, b, c0, db) else layoutRect(n, b, h, c0, db)) ?: return null
        val ab = PI * db * db / 4.0
        val ast = n * ab
        val ag = if (circular) PI * b * b / 4.0 else b * h
        val spiral = inp.ties == ColumnTies.SPIRAL
        val fc = inp.fcMpa
        val fy = inp.fyMpa
        val po = 0.85 * fc * (ag - ast) + fy * ast
        val phiC = if (spiral) 0.75 else 0.65
        val pnMax = (if (spiral) 0.85 else 0.80) * po
        val phiPnMax = phiC * pnMax

        val ptsX = diagram(circular, if (circular) b else h, b, layout.barsX, fc, fy, spiral)
        val ptsY = diagram(circular, b, if (circular) b else h, layout.barsY, fc, fy, spiral)

        val puN = puKn * 1000.0
        val cases = if (inp.minEccentricity) listOf(max(mxKnM, mMinX) to myKnM, mxKnM to max(myKnM, mMinY))
        else listOf(mxKnM to myKnM)

        var worstRatio = -1.0
        var worstMx = mxKnM
        var worstMy = myKnM
        var method = ""
        for ((mx, my) in cases) {
            val mxN = mx * 1e6
            val myN = my * 1e6
            val ratio: Double
            if (puN >= 0.1 * fc * ag) {
                method = "Bresler reciprocal-load method"
                val pnx = pnAtEcc(ptsX, max(mxN / puN, 1.0), po)
                val pny = pnAtEcc(ptsY, max(myN / puN, 1.0), po)
                val inv = 1.0 / pnx + 1.0 / pny - 1.0 / po
                val pni = if (inv > 0.0) 1.0 / inv else po
                ratio = puN / (phiC * min(pni, pnMax))
            } else {
                method = "Linear interaction (Pu < 0.1 f'c Ag)"
                val cx = phiMnAt(ptsX, puN)
                val cy = phiMnAt(ptsY, puN)
                ratio = if (cx == null || cy == null || cx <= 0.0 || cy <= 0.0) 99.0 else mxN / cx + myN / cy
            }
            if (ratio > worstRatio) {
                worstRatio = ratio; worstMx = mx; worstMy = my
            }
        }
        val phiMnX = phiMnAt(ptsX, puN)?.div(1e6)
        val phiMnY = phiMnAt(ptsY, puN)?.div(1e6)
        return Eval(n, layout, ast, ag, po, phiPnMax, worstRatio, method, phiMnX, phiMnY, ptsX, ptsY, worstMx, worstMy)
    }

    // ---- Public entry point -------------------------------------------------------------------------------------

    fun design(inp: ColumnInput): ColumnDesign {
        inp.validate()?.let { throw IllegalArgumentException(it) }
        if (!inp.autoBarDia) return designFixed(inp)
        // Lightest passing design over the usual bar sizes; if none passes, the one with the lowest ratio.
        var bestOk: ColumnDesign? = null
        var bestAny: ColumnDesign? = null
        for (dia in listOf(14.0, 16.0, 18.0, 20.0, 22.0, 25.0, 28.0, 32.0)) {
            val trial = inp.copy(barDiaMm = dia, barCount = 0, autoBarDia = false)
            if (trial.validate() != null) continue
            val d = runCatching { designFixed(trial) }.getOrNull() ?: continue
            if (bestAny == null || d.ratio < bestAny.ratio) bestAny = d
            if (d.ok && (bestOk == null || d.asMm2 < bestOk.asMm2 - 1.0 ||
                    (abs(d.asMm2 - bestOk.asMm2) <= 1.0 && d.bars < bestOk.bars))
            ) bestOk = d
        }
        return bestOk ?: bestAny ?: throw IllegalStateException("No feasible bar arrangement fits — enlarge the section")
    }

    private fun designFixed(inp: ColumnInput): ColumnDesign {
        val gD = inp.deadFactor
        val gL = inp.liveFactor
        val puKn = gD * inp.pdKn + gL * inp.plKn
        val muxIn = abs(gD * inp.mxDKnM + gL * inp.mxLKnM)
        val muyIn = abs(gD * inp.myDKnM + gL * inp.myLKnM)
        val circular = inp.isCircular
        val b = inp.bMm
        val h = inp.hMm
        val ec = 4700.0 * sqrt(inp.fcMpa)
        val betaDns = gD * inp.pdKn / puKn

        val depthX = if (circular) b else h
        val depthY = b
        val igX = if (circular) PI * b.pow(4) / 64.0 else b * h.pow(3) / 12.0
        val igY = if (circular) PI * b.pow(4) / 64.0 else h * b.pow(3) / 12.0
        val ax = slenderness(inp, "X (about x-axis, depth ${depthX.toInt()} mm)", depthX, igX, inp.kx, muxIn, inp.m1m2x, puKn, betaDns, ec)
        val ay = slenderness(inp, "Y (about y-axis, depth ${depthY.toInt()} mm)", depthY, igY, inp.ky, muyIn, inp.m1m2y, puKn, betaDns, ec)

        val mMinX = puKn * (15.0 + 0.03 * depthX) / 1000.0
        val mMinY = puKn * (15.0 + 0.03 * depthY) / 1000.0
        val c0 = inp.coverMm + inp.tieDiaMm + inp.barDiaMm / 2.0
        val ab = PI * inp.barDiaMm * inp.barDiaMm / 4.0
        val ag = if (circular) PI * b * b / 4.0 else b * h
        val spiral = inp.ties == ColumnTies.SPIRAL

        val notes = ArrayList<String>()
        val warnings = ArrayList<String>()
        var ok = true

        val unstable = ax.unstable || ay.unstable
        if (unstable) {
            ok = false
            warnings.add("Pu exceeds 0.75·Pc (elastic buckling load) — the column is too slender; enlarge the section or reduce the height")
        }
        if (max(ax.kluOverR, ay.kluOverR) > 100.0) {
            ok = false
            warnings.add("kℓu/r > 100: the simplified moment-magnification method is not permitted (ACI 6.2.5) — use a second-order analysis")
        }
        if (!inp.braced) notes.add("Sway frame: δs is estimated for this column alone with Pc from your k factor — verify against a frame (story) analysis.")

        val mxEff = if (unstable) muxIn else ax.designMomentKnM
        val myEff = if (unstable) muyIn else ay.designMomentKnM

        // ---- choose bars ----
        val candidates: List<Int> = when {
            inp.barCount > 0 -> listOf(inp.barCount)
            circular -> ((if (spiral) 6 else 4)..40).toList()
            else -> (4..40 step 2).toList()
        }
        var chosen: Eval? = null
        var fallback: Eval? = null
        var hitRhoLimit = false
        for (n in candidates) {
            val rho = n * ab / ag
            if (inp.barCount == 0 && rho < 0.01) continue
            if (rho > 0.08) { hitRhoLimit = true; break }
            val ev = evaluate(inp, n, puKn, mxEff, myEff, mMinX, mMinY, c0) ?: continue
            if (fallback == null || ev.ratio < fallback.ratio) fallback = ev
            if (ev.ratio <= 1.0) { chosen = ev; break }
        }
        val ev = chosen ?: fallback
            ?: throw IllegalStateException("No feasible bar arrangement fits — enlarge the section or use smaller bars")
        if (chosen == null) {
            ok = false
            warnings.add(
                if (inp.barCount > 0) "The ${inp.barCount} bars given are not enough (capacity ratio ${"%.2f".format(ev.ratio)})"
                else if (hitRhoLimit) "Even 8% steel is not enough (ratio ${"%.2f".format(ev.ratio)}) — enlarge the section or raise f'c"
                else "With Φ${inp.barDiaMm.toInt()} bars at most ${ev.n} fit (${"%.1f".format(ev.ast / ag * 100)}%, ratio ${"%.2f".format(ev.ratio)}) — use larger bars or a bigger section"
            )
        }

        val rho = ev.ast / ag
        if (rho < 0.01) { ok = false; warnings.add("Steel ratio ${"%.2f".format(rho * 100)}% is below the 1% minimum (ACI 10.6.1.1)") }
        if (rho > 0.04) warnings.add("Steel ratio ${"%.1f".format(rho * 100)}% — above 4% laps and congestion become difficult")

        // ---- ties / spiral ----
        val db = inp.barDiaMm
        val dt = inp.tieDiaMm
        val minTie = if (db <= 32.0) 10.0 else 12.0
        if (dt < minTie) { ok = false; warnings.add("Ties must be at least Φ${minTie.toInt()} for Φ${db.toInt()} bars (ACI 25.7.2.2)") }
        val fc = inp.fcMpa
        val fyt = inp.fytMpa
        val tiesText: String
        if (spiral) {
            val dc = b - 2.0 * inp.coverMm
            val ach = PI * dc * dc / 4.0
            val rhoS = max(0.45 * (ag / ach - 1.0) * fc / fyt, 0.12 * fc / fyt)
            val asp = PI * dt * dt / 4.0
            val sCalc = 4.0 * asp * (dc - dt) / (dc * dc * rhoS)
            val sMax = 75.0 + dt
            val sMin = 25.0 + dt
            val s = floor(min(sCalc, sMax) / 5.0) * 5.0
            if (s < sMin) { ok = false; warnings.add("Spiral pitch would be under ${sMin.toInt()} mm — use a larger spiral bar") }
            tiesText = "Spiral Φ${dt.toInt()} @ ${s.toInt()} mm pitch (ρs ≥ ${"%.4f".format(rhoS)})"
        } else {
            val sMax = min(16.0 * db, min(48.0 * dt, min(b, h)))
            val s = max(floor(sMax / 25.0) * 25.0, 50.0)
            tiesText = if (circular) "Circular ties Φ${dt.toInt()} @ ${s.toInt()} mm" else "Ties Φ${dt.toInt()} @ ${s.toInt()} mm"
            if (!circular && (ev.layout.nb >= 4 || ev.layout.nh >= 4 ||
                    (b - 2 * c0) / (ev.layout.nb - 1) - db > 150.0 || (h - 2 * c0) / (ev.layout.nh - 1) - db > 150.0)
            ) notes.add("Add cross-ties so that no bar is more than 150 mm clear from a laterally supported bar (ACI 25.7.2.3).")
        }
        notes.add("Ties/spiral shown are minimum detailing; closer spacing is needed near beam–column joints and for seismic zones.")

        // ---- description ----
        val type = when {
            muxIn < 1e-6 && muyIn < 1e-6 -> "Axially loaded (minimum eccentricity)"
            muyIn < 1e-6 -> "Uniaxial bending about X"
            muxIn < 1e-6 -> "Uniaxial bending about Y"
            else -> "Biaxial bending"
        }
        val arrangement = if (circular) "${ev.n}Φ${db.toInt()} evenly spaced"
        else "${ev.n}Φ${db.toInt()} (${ev.layout.nb} along b, ${ev.layout.nh} along h)"
        notes.add(
            "Short/slender: X ${if (ax.slender) "slender δns=" + "%.2f".format(ax.delta) else "short"}, " +
                "Y ${if (ay.slender) "slender δns=" + "%.2f".format(ay.delta) else "short"}."
        )

        fun curve(pts: List<PM>): InteractionCurve {
            val v = pts.filter { it.phiPn >= 0.0 }
            return InteractionCurve(
                DoubleArray(v.size + 1) { if (it == 0) 0.0 else v[it - 1].phiMn / 1e6 },
                DoubleArray(v.size + 1) { if (it == 0) ev.phiPnMax / 1000.0 else v[it - 1].phiPn / 1000.0 }
            )
        }

        return ColumnDesign(
            typeLabel = type, puKn = puKn, muxKnM = ev.mx, muyKnM = ev.my,
            axisX = ax, axisY = ay, bars = ev.n, barDiaMm = db.toInt(), arrangement = arrangement,
            rho = rho, asMm2 = ev.ast, tiesText = tiesText, phiPnMaxKn = ev.phiPnMax / 1000.0,
            phiMnxKnM = ev.phiMnX, phiMnyKnM = ev.phiMnY, ratio = ev.ratio, method = ev.method,
            ok = ok && ev.ratio <= 1.0, notes = notes, warnings = warnings,
            curveX = curve(ev.ptsX), curveY = curve(ev.ptsY),
            demandX = ev.mx to puKn, demandY = ev.my to puKn
        )
    }
}
