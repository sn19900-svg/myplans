package com.myprojects.cad.domain.structural

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Result of the elastic analysis. Moment sign: sagging (+). Shear sign: upward force on the left of a cut (+).
 * Arrays [xs], [vMax]… form the ULTIMATE envelope including live-load pattern loading (ACI 318-19 6.4.3).
 */
class BeamAnalysis(
    val input: BeamInput,
    val spanStartX: List<Double>,
    val supportsX: List<Double>,
    val xs: DoubleArray,
    val vMax: DoubleArray,
    val vMin: DoubleArray,
    val mMax: DoubleArray,
    val mMin: DoubleArray,
    val reactions: List<SupportReaction>,
    val spanPositiveKnM: List<Double>,
    val spanPositiveAtX: List<Double>,
    val supportNegativeKnM: List<Double>,
    val deflXs: DoubleArray,
    /** Downward deflection (mm) from dead load, gross section. */
    val deflDeadMm: DoubleArray,
    /** Downward deflection (mm) from live load, worst pattern, gross section. */
    val deflLiveMm: DoubleArray,
    val ecMpa: Double,
    val eiKnM2: Double,
    private val serviceMoment: (Double) -> Double
) {
    fun serviceMomentAt(x: Double): Double = serviceMoment(x)

    /** Largest |V| of the ultimate envelope at x (linear interpolation between grid points; conservative). */
    fun shearEnvelopeAbsAt(x: Double): Double = max(abs(interp(vMax, x)), abs(interp(vMin, x)))

    private fun interp(arr: DoubleArray, x: Double): Double {
        if (x <= xs[0]) return arr[0]
        if (x >= xs[xs.size - 1]) return arr[arr.size - 1]
        var lo = 0
        var hi = xs.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (xs[mid] <= x) lo = mid else hi = mid
        }
        val dx = xs[hi] - xs[lo]
        if (dx < 1e-12) return arr[lo]
        return arr[lo] + (x - xs[lo]) / dx * (arr[hi] - arr[lo])
    }
}

/**
 * Direct stiffness (Euler–Bernoulli) solver for single-span and continuous beams, plus a closed-form
 * statics evaluator for shear/moment. Live loads are solved span by span and superposed, which gives the
 * EXACT pattern-loading envelope at every section: positive contributions are added for maximum effects,
 * negative ones for minimum effects.
 */
object BeamSolver {
    private const val EPS = 1e-9

    private class PL(val x: Double, val p: Double)
    private class LoadSet(val w: DoubleArray, val pts: List<PL>)

    private class Statics(
        val loads: LoadSet,
        val spanStart: DoubleArray,
        val reactionX: DoubleArray,
        val reactionV: DoubleArray,
        val fixedMoments: List<Pair<Double, Double>>,
        val nodeDisp: DoubleArray
    ) {
        fun v(x: Double, right: Boolean): Double {
            var s = 0.0
            for (i in reactionX.indices) {
                val rx = reactionX[i]
                if (if (right) rx <= x + EPS else rx < x - EPS) s += reactionV[i]
            }
            for (p in loads.pts) {
                if (if (right) p.x <= x + EPS else p.x < x - EPS) s -= p.p
            }
            for (j in loads.w.indices) {
                val a = spanStart[j]
                val t = (x - a).coerceIn(0.0, spanStart[j + 1] - a)
                s -= loads.w[j] * t
            }
            return s
        }

        /** Sagging moment at x. [right] selects the section just right (true) or just left (false) of x. */
        fun m(x: Double, right: Boolean = true): Double {
            var s = 0.0
            for ((fx, fm) in fixedMoments) {
                if (if (right) fx <= x + EPS else fx < x - EPS) s -= fm
            }
            for (i in reactionX.indices) if (reactionX[i] <= x + EPS) s += reactionV[i] * (x - reactionX[i])
            for (p in loads.pts) if (p.x <= x + EPS) s -= p.p * (x - p.x)
            for (j in loads.w.indices) {
                val a = spanStart[j]
                val t = (x - a).coerceIn(0.0, spanStart[j + 1] - a)
                s -= loads.w[j] * t * (x - a - t / 2.0)
            }
            return s
        }
    }

    fun analyze(input: BeamInput): BeamAnalysis {
        input.validate()?.let { throw IllegalArgumentException(it) }
        val n = input.spansM.size
        val spanStart = DoubleArray(n + 1)
        for (i in 0 until n) spanStart[i + 1] = spanStart[i] + input.spansM[i]
        val total = spanStart[n]
        val leftCond = input.leftCondition
        val rightCond = input.rightCondition
        val supportsX: List<Double> = spanStart.toList().filterIndexed { i, _ ->
            !(i == 0 && leftCond == EndCondition.FREE) && !(i == n && rightCond == EndCondition.FREE)
        }

        val bM = input.bMm / 1000.0
        val hM = input.hMm / 1000.0
        val ecMpa = 4700.0 * sqrt(input.fcMpa)
        val ei = ecMpa * 1000.0 * (bM * hM * hM * hM / 12.0) // kN·m²
        val sw = input.selfWeightKnPerM

        val deadSet = LoadSet(
            DoubleArray(n) { sw + input.deadKnPerM[it] },
            input.pointLoads.filter { it.deadKn > 0.0 }.map { PL(spanStart[it.spanIndex] + it.posM, it.deadKn) }
        )
        val liveSets = (0 until n).map { j ->
            LoadSet(
                DoubleArray(n) { if (it == j) input.liveKnPerM[j] else 0.0 },
                input.pointLoads.filter { it.spanIndex == j && it.liveKn > 0.0 }
                    .map { PL(spanStart[j] + it.posM, it.liveKn) }
            )
        }

        // ---- FEM mesh: nodes at supports, point loads, and 8 sub-elements per segment ----
        val events = (supportsX + spanStart.toList() + (deadSet.pts + liveSets.flatMap { it.pts }).map { it.x })
            .sorted().fold(ArrayList<Double>()) { acc, v -> if (acc.isEmpty() || v - acc.last() > 1e-7) acc.add(v); acc }
        val nodeX = ArrayList<Double>()
        for (k in 0 until events.size - 1) {
            val a = events[k]
            val b = events[k + 1]
            for (s in 0 until FEM_SUB) nodeX.add(a + (b - a) * s / FEM_SUB)
        }
        nodeX.add(events.last())
        val xsNode = nodeX.toDoubleArray()
        val nNodes = xsNode.size
        val nd = 2 * nNodes
        val k = Array(nd) { DoubleArray(nd) }
        for (e in 0 until nNodes - 1) {
            val l = xsNode[e + 1] - xsNode[e]
            val c = ei / (l * l * l)
            val ke = arrayOf(
                doubleArrayOf(12.0, 6.0 * l, -12.0, 6.0 * l),
                doubleArrayOf(6.0 * l, 4.0 * l * l, -6.0 * l, 2.0 * l * l),
                doubleArrayOf(-12.0, -6.0 * l, 12.0, -6.0 * l),
                doubleArrayOf(6.0 * l, 2.0 * l * l, -6.0 * l, 4.0 * l * l)
            )
            val base = 2 * e
            for (i in 0 until 4) for (j in 0 until 4) k[base + i][base + j] += c * ke[i][j]
        }
        fun nodeIndex(x: Double): Int {
            var best = 0
            for (i in xsNode.indices) if (abs(xsNode[i] - x) < abs(xsNode[best] - x)) best = i
            return best
        }
        val supportNodes = supportsX.map { nodeIndex(it) }
        val constrained = ArrayList<Int>()
        for (s in supportNodes) constrained.add(2 * s)
        val fixedNodes = ArrayList<Int>()
        if (leftCond == EndCondition.FIXED) fixedNodes.add(nodeIndex(0.0))
        if (rightCond == EndCondition.FIXED) fixedNodes.add(nodeIndex(total))
        for (fnode in fixedNodes) constrained.add(2 * fnode + 1) // rotation restrained
        val constrainedSet = constrained.toSet()
        val free = (0 until nd).filter { it !in constrainedSet }

        fun spanOfX(x: Double): Int {
            for (j in 0 until n) if (x <= spanStart[j + 1] + EPS) return j
            return n - 1
        }

        fun solve(ls: LoadSet): Statics {
            val f = DoubleArray(nd)
            for (e in 0 until nNodes - 1) {
                val l = xsNode[e + 1] - xsNode[e]
                val w = ls.w[spanOfX((xsNode[e] + xsNode[e + 1]) / 2.0)]
                if (w != 0.0) {
                    f[2 * e] += -w * l / 2.0
                    f[2 * e + 1] += -w * l * l / 12.0
                    f[2 * e + 2] += -w * l / 2.0
                    f[2 * e + 3] += w * l * l / 12.0
                }
            }
            for (p in ls.pts) f[2 * nodeIndex(p.x)] -= p.p

            val m = free.size
            val a = Array(m) { r -> DoubleArray(m) { c -> k[free[r]][free[c]] } }
            val rhs = DoubleArray(m) { f[free[it]] }
            val uf = gauss(a, rhs)
            val u = DoubleArray(nd)
            for (i in 0 until m) u[free[i]] = uf[i]

            fun reaction(dof: Int): Double {
                var s = 0.0
                for (j in 0 until nd) s += k[dof][j] * u[j]
                return s - f[dof]
            }
            val rv = DoubleArray(supportsX.size) { reaction(2 * supportNodes[it]) }
            val fixedM = fixedNodes.map { xsNode[it] to reaction(2 * it + 1) }
            val disp = DoubleArray(nNodes) { -u[2 * it] * 1000.0 } // downward mm
            return Statics(ls, spanStart, supportsX.toDoubleArray(), rv, fixedM, disp)
        }

        val dead = solve(deadSet)
        val lives = liveSets.map { solve(it) }
        val gD = input.deadFactor
        val gL = input.liveFactor

        // ---- Ultimate envelope on a fine grid (each event appears twice to show shear jumps) ----
        val step = max(total / 400.0, 0.005)
        val gx = ArrayList<Double>()
        val gSide = ArrayList<Int>() // 0 = start of segment (right side), 1 = interior, 2 = end (left side)
        for (kk in 0 until events.size - 1) {
            val a = events[kk]
            val b = events[kk + 1]
            val m = max(2, ceil((b - a) / step).toInt())
            for (s in 0..m) {
                gx.add(a + (b - a) * s / m)
                gSide.add(if (s == 0) 0 else if (s == m) 2 else 1)
            }
        }
        val cnt = gx.size
        val xs = DoubleArray(cnt) { gx[it] }
        val vMax = DoubleArray(cnt)
        val vMin = DoubleArray(cnt)
        val mMax = DoubleArray(cnt)
        val mMin = DoubleArray(cnt)
        for (i in 0 until cnt) {
            val x = xs[i]
            val right = gSide[i] != 2
            val vd = dead.v(x, right)
            var vp = 0.0
            var vn = 0.0
            for (c in lives) {
                val v = c.v(x, right)
                if (v > 0) vp += v else vn += v
            }
            vMax[i] = max(gD * vd + gL * vp, 1.4 * vd)
            vMin[i] = min(gD * vd + gL * vn, 1.4 * vd)

            val md = dead.m(x, right)
            var mp = 0.0
            var mn = 0.0
            for (c in lives) {
                val mm = c.m(x, right)
                if (mm > 0) mp += mm else mn += mm
            }
            mMax[i] = max(gD * md + gL * mp, 1.4 * md)
            mMin[i] = min(gD * md + gL * mn, 1.4 * md)
        }

        // ---- Span / support extremes ----
        val spanPos = ArrayList<Double>()
        val spanPosAt = ArrayList<Double>()
        for (j in 0 until n) {
            var best = 0.0
            var at = (spanStart[j] + spanStart[j + 1]) / 2.0
            for (i in 0 until cnt) {
                if (xs[i] >= spanStart[j] - EPS && xs[i] <= spanStart[j + 1] + EPS && mMax[i] > best) {
                    best = mMax[i]; at = xs[i]
                }
            }
            spanPos.add(best); spanPosAt.add(at)
        }
        val supNeg = supportsX.map { sx ->
            var worst = 0.0
            for (i in 0 until cnt) if (abs(xs[i] - sx) < 1e-7 && mMin[i] < worst) worst = mMin[i]
            -worst
        }

        // ---- Reactions ----
        val reactions = supportsX.indices.map { i ->
            val rd = dead.reactionV[i]
            var rlSum = 0.0
            var rlPos = 0.0
            for (c in lives) {
                rlSum += c.reactionV[i]
                if (c.reactionV[i] > 0) rlPos += c.reactionV[i]
            }
            SupportReaction(
                label = "${'A' + i}", xM = supportsX[i], deadKn = rd, liveKn = rlSum,
                ultimateMaxKn = max(gD * rd + gL * rlPos, 1.4 * rd), liveMaxKn = rlPos
            )
        }

        // ---- Deflections (gross section; designer scales by Ig/Ie) ----
        val deflDead = DoubleArray(nNodes) { dead.nodeDisp[it] }
        val deflLive = DoubleArray(nNodes) { i ->
            var s = 0.0
            for (c in lives) if (c.nodeDisp[i] > 0) s += c.nodeDisp[i]
            s
        }

        return BeamAnalysis(
            input = input, spanStartX = spanStart.toList(), supportsX = supportsX,
            xs = xs, vMax = vMax, vMin = vMin, mMax = mMax, mMin = mMin,
            reactions = reactions, spanPositiveKnM = spanPos, spanPositiveAtX = spanPosAt,
            supportNegativeKnM = supNeg,
            deflXs = xsNode, deflDeadMm = deflDead, deflLiveMm = deflLive,
            ecMpa = ecMpa, eiKnM2 = ei,
            serviceMoment = { x ->
                val r = x < total - 1e-9
                dead.m(x, r) + lives.sumOf { it.m(x, r) }
            }
        )
    }

    private const val FEM_SUB = 8

    private fun gauss(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        for (col in 0 until n) {
            var piv = col
            var best = abs(a[col][col])
            for (r in col + 1 until n) {
                val v = abs(a[r][col])
                if (v > best) { best = v; piv = r }
            }
            if (best < 1e-12) throw IllegalStateException("The beam is unstable (check supports)")
            if (piv != col) {
                val t = a[piv]; a[piv] = a[col]; a[col] = t
                val tb = b[piv]; b[piv] = b[col]; b[col] = tb
            }
            val inv = 1.0 / a[col][col]
            for (r in col + 1 until n) {
                val f = a[r][col] * inv
                if (f != 0.0) {
                    for (c in col until n) a[r][c] -= f * a[col][c]
                    b[r] -= f * b[col]
                }
            }
        }
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var s = b[i]
            for (j in i + 1 until n) s -= a[i][j] * x[j]
            x[i] = s / a[i][i]
        }
        return x
    }
}
