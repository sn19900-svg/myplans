package com.myprojects.cad.domain.model

import com.myprojects.cad.core.geometry.Vec2
import kotlin.math.abs

data class ChainSeg(val entityId: String, val a: Vec2, val b: Vec2) {
    val length: Double get() = a.distanceTo(b)
}

/** An ordered run of connected straight segments — the plan view of one beam (single or continuous). */
data class Chain(val segs: List<ChainSeg>) {
    val entityIds: List<String> get() = segs.map { it.entityId }.distinct()
    val spansM: List<Double> get() = segs.map { it.length }

    /** Joint points: start of the first segment, then the end of every segment (n + 1 points). */
    val points: List<Vec2> get() = if (segs.isEmpty()) emptyList() else listOf(segs.first().a) + segs.map { it.b }

    /** True when consecutive segments deviate by less than 2° (a physically straight beam). */
    val straight: Boolean
        get() {
            for (i in 0 until segs.size - 1) {
                val d1 = (segs[i].b - segs[i].a).normalized()
                val d2 = (segs[i + 1].b - segs[i + 1].a).normalized()
                if (abs(d1.cross(d2)) > 0.035) return false
            }
            return true
        }
}

object ChainBuilder {

    /** Groups the straight elements (lines, walls, polyline segments) into chains by matching end points. */
    fun build(entities: List<CadEntity>, tol: Double = 0.02): List<Chain> {
        val segs = ArrayList<ChainSeg>()
        for (e in entities) {
            when (e) {
                is Wall -> segs.add(ChainSeg(e.id, e.start, e.end))
                is LineEntity -> segs.add(ChainSeg(e.id, e.start, e.end))
                is PolylineEntity -> e.segments().forEach { segs.add(ChainSeg(e.id, it.a, it.b)) }
                else -> Unit
            }
        }
        val valid = segs.filter { it.length > 1e-6 }
        val used = BooleanArray(valid.size)
        val chains = ArrayList<Chain>()
        for (i in valid.indices) {
            if (used[i]) continue
            used[i] = true
            val deque = ArrayDeque<ChainSeg>()
            deque.addLast(valid[i])

            var progress = true
            while (progress) {
                progress = false
                val tail = deque.last().b
                for (j in valid.indices) {
                    if (used[j]) continue
                    val s = valid[j]
                    if (s.a.distanceTo(tail) <= tol) {
                        deque.addLast(s); used[j] = true; progress = true; break
                    }
                    if (s.b.distanceTo(tail) <= tol) {
                        deque.addLast(ChainSeg(s.entityId, s.b, s.a)); used[j] = true; progress = true; break
                    }
                }
            }
            progress = true
            while (progress) {
                progress = false
                val head = deque.first().a
                for (j in valid.indices) {
                    if (used[j]) continue
                    val s = valid[j]
                    if (s.b.distanceTo(head) <= tol) {
                        deque.addFirst(s); used[j] = true; progress = true; break
                    }
                    if (s.a.distanceTo(head) <= tol) {
                        deque.addFirst(ChainSeg(s.entityId, s.b, s.a)); used[j] = true; progress = true; break
                    }
                }
            }
            chains.add(Chain(deque.toList()))
        }
        return chains
    }

    /** Ids of every linear element connected (through shared end points) to the seed elements. */
    fun connectedIds(all: List<CadEntity>, seed: Set<String>, tol: Double = 0.02): Set<String> {
        fun ends(e: CadEntity): List<Vec2> = when (e) {
            is Wall -> listOf(e.start, e.end)
            is LineEntity -> listOf(e.start, e.end)
            is PolylineEntity -> e.points
            else -> emptyList()
        }
        val linear = all.filter { ends(it).isNotEmpty() }
        val result = HashSet<String>(seed)
        val queue = ArrayDeque<CadEntity>()
        linear.filter { it.id in seed }.forEach { queue.addLast(it) }
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val curEnds = ends(cur)
            for (other in linear) {
                if (other.id in result) continue
                val oe = ends(other)
                if (curEnds.any { a -> oe.any { b -> a.distanceTo(b) <= tol } }) {
                    result.add(other.id)
                    queue.addLast(other)
                }
            }
        }
        return result
    }
}
