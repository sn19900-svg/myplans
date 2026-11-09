package com.myprojects.cad.data.dxf

import com.myprojects.cad.core.geometry.Geo
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
import java.util.Locale
import kotlin.math.abs

/** Drawing unit written to the DXF. The app itself always works in meters. */
enum class DxfUnit(val label: String, val perMeter: Double, val insUnits: Int) {
    METERS("m", 1.0, 6),
    CENTIMETERS("cm", 100.0, 5),
    MILLIMETERS("mm", 1000.0, 4)
}

/**
 * ASCII DXF writer (AutoCAD R12 / AC1009 — opens everywhere: AutoCAD, BricsCAD, LibreCAD, DraftSight…).
 * Walls become closed polylines on layer WALLS; dimensions are exploded into lines + text on layer DIM.
 * Non-ASCII text is written with \U+XXXX escapes so Arabic text survives.
 */
object DxfWriter {

    fun write(entities: List<CadEntity>, unit: DxfUnit): String {
        val out = Out(StringBuilder(), unit.perMeter)
        val layers = (entities.map { safeLayer(it.layer) } + "0").toSortedSet()

        out.g(0, "SECTION"); out.g(2, "HEADER")
        out.g(9, "\$ACADVER"); out.g(1, "AC1009")
        out.g(9, "\$INSUNITS"); out.g(70, unit.insUnits.toString())
        entities.worldBounds()?.let { b ->
            out.g(9, "\$EXTMIN"); out.pt(10, Vec2(b.minX, b.minY))
            out.g(9, "\$EXTMAX"); out.pt(10, Vec2(b.maxX, b.maxY))
        }
        out.g(0, "ENDSEC")

        out.g(0, "SECTION"); out.g(2, "TABLES")
        out.g(0, "TABLE"); out.g(2, "LTYPE"); out.g(70, "1")
        out.g(0, "LTYPE"); out.g(2, "CONTINUOUS"); out.g(70, "0"); out.g(3, "Solid line")
        out.g(72, "65"); out.g(73, "0"); out.g(40, "0.0")
        out.g(0, "ENDTAB")
        out.g(0, "TABLE"); out.g(2, "LAYER"); out.g(70, layers.size.toString())
        for (name in layers) {
            out.g(0, "LAYER"); out.g(2, name); out.g(70, "0"); out.g(62, layerColor(name).toString())
            out.g(6, "CONTINUOUS")
        }
        out.g(0, "ENDTAB")
        out.g(0, "ENDSEC")

        out.g(0, "SECTION"); out.g(2, "ENTITIES")
        for (e in entities) writeEntity(out, e)
        out.g(0, "ENDSEC")
        out.g(0, "EOF")
        return out.sb.toString()
    }

    private fun writeEntity(o: Out, e: CadEntity) {
        val layer = safeLayer(e.layer)
        when (e) {
            is Wall -> o.polyline(layer, e.corners(), true)
            is LineEntity -> o.line(layer, e.start, e.end)
            is PolylineEntity -> o.polyline(layer, e.points, e.closed)
            is CircleEntity -> {
                o.g(0, "CIRCLE"); o.g(8, layer); o.pt(10, e.center); o.n(40, e.radius * o.k)
            }
            is ArcEntity -> {
                o.g(0, "ARC"); o.g(8, layer); o.pt(10, e.center); o.n(40, e.radius * o.k)
                o.n(50, Geo.normDeg(e.startDeg)); o.n(51, Geo.normDeg(e.endDeg))
            }
            is TextEntity -> o.text(layer, e.position, e.heightM, e.text, e.rotationDeg)
            is DimensionEntity -> {
                val g = e.geometry()
                val dir = (g.p2 - g.p1).normalized()
                val n = dir.perpLeft()
                o.line(layer, g.p1, g.e1)
                o.line(layer, g.p2, g.e2)
                o.line(layer, g.d1, g.d2)
                val t = (dir + n).normalized() * 0.07
                o.line(layer, g.d1 - t, g.d1 + t)
                o.line(layer, g.d2 - t, g.d2 + t)
                var angle = g.angleDeg
                if (angle > 90.0) angle -= 180.0 else if (angle < -90.0) angle += 180.0
                val sign = if (e.offset >= 0.0) 1.0 else -1.0
                val mid = g.d1.midpoint(g.d2) + n * (0.06 * sign)
                o.text(layer, mid, 0.12, String.format(Locale.US, "%.2f", g.length), angle)
            }
        }
    }

    private class Out(val sb: StringBuilder, val k: Double) {
        fun g(code: Int, value: String) {
            sb.append(code).append('\n').append(value).append('\n')
        }

        fun n(code: Int, v: Double) = g(code, fmt(v))

        fun pt(code: Int, p: Vec2) {
            n(code, p.x * k); n(code + 10, p.y * k); n(code + 20, 0.0)
        }

        fun line(layer: String, a: Vec2, b: Vec2) {
            g(0, "LINE"); g(8, layer); pt(10, a); pt(11, b)
        }

        fun polyline(layer: String, pts: List<Vec2>, closed: Boolean) {
            g(0, "POLYLINE"); g(8, layer); g(66, "1")
            n(10, 0.0); n(20, 0.0); n(30, 0.0)
            g(70, if (closed) "1" else "0")
            for (p in pts) {
                g(0, "VERTEX"); g(8, layer); pt(10, p)
            }
            g(0, "SEQEND"); g(8, layer)
        }

        fun text(layer: String, pos: Vec2, heightM: Double, text: String, rotationDeg: Double) {
            g(0, "TEXT"); g(8, layer); pt(10, pos); n(40, heightM * k)
            g(1, escape(text))
            if (abs(rotationDeg) > 1e-9) n(50, Geo.normDeg(rotationDeg))
        }
    }

    private fun fmt(v: Double): String {
        val x = if (abs(v) < 5e-7) 0.0 else v
        return String.format(Locale.US, "%.6f", x)
    }

    /** ASCII only; everything else as \U+XXXX (understood by every AutoCAD version). */
    internal fun escape(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            when {
                ch == '\n' || ch == '\r' -> sb.append(' ')
                ch.code in 32..126 -> sb.append(ch)
                else -> sb.append(String.format(Locale.US, "\\U+%04X", ch.code))
            }
        }
        return sb.toString()
    }

    private fun safeLayer(name: String): String {
        val cleaned = name.map { c ->
            if (c.code in 33..126 && c !in "<>/\\\":;?*|=`") c else '_'
        }.joinToString("")
        return cleaned.ifBlank { "0" }
    }

    private fun layerColor(name: String): Int = when (name) {
        "0" -> 7
        "WALLS" -> 5
        "DIM" -> 3
        "TEXT" -> 2
        else -> 7
    }
}
