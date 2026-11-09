package com.myprojects.cad.data.dxf

import com.myprojects.cad.core.geometry.Vec2
import com.myprojects.cad.domain.model.ArcEntity
import com.myprojects.cad.domain.model.CadEntity
import com.myprojects.cad.domain.model.CircleEntity
import com.myprojects.cad.domain.model.LineEntity
import com.myprojects.cad.domain.model.PolylineEntity
import com.myprojects.cad.domain.model.TextEntity
import java.nio.charset.Charset

data class DxfImportResult(
    val entities: List<CadEntity>,
    val skipped: Map<String, Int>,
    val warnings: List<String>,
    val unitFactor: Double,
    val unitsDetected: Boolean
)

/**
 * ASCII DXF reader (any AutoCAD version). Supports LINE, LWPOLYLINE, POLYLINE/VERTEX, CIRCLE, ARC, TEXT, MTEXT.
 * Other entity types are counted and reported, not silently dropped.
 */
object DxfReader {

    private class Rec(val type: String) {
        val groups = ArrayList<Pair<Int, String>>()
        fun s(code: Int): String? = groups.firstOrNull { it.first == code }?.second
        fun d(code: Int): Double? = s(code)?.trim()?.toDoubleOrNull()
    }

    fun read(
        bytes: ByteArray,
        unitFactorOverride: Double? = null,
        fallbackUnitFactor: Double = 1.0
    ): DxfImportResult {
        val head = String(bytes, 0, minOf(bytes.size, 24), Charsets.ISO_8859_1)
        require(!head.startsWith("AutoCAD Binary DXF")) { "Binary DXF is not supported. Save it as ASCII DXF." }

        var pairs = parsePairs(String(bytes, Charsets.ISO_8859_1))
        val charset = detectCharset(pairs)
        if (charset != Charsets.ISO_8859_1) pairs = parsePairs(String(bytes, charset))

        val insUnits = headerValue(pairs, "\$INSUNITS")?.toIntOrNull()
        val detected = insUnits?.let { unitToMeters(it) }
        val factor = unitFactorOverride ?: detected ?: fallbackUnitFactor

        val records = entityRecords(pairs)
        val result = ArrayList<CadEntity>()
        val skipped = LinkedHashMap<String, Int>()
        val warnings = ArrayList<String>()
        var bulgeCount = 0

        fun skip(type: String) {
            skipped[type] = (skipped[type] ?: 0) + 1
        }

        var idx = 0
        while (idx < records.size) {
            val r = records[idx]
            val layer = r.s(8)?.trim().orEmpty().ifEmpty { "0" }
            when (r.type) {
                "LINE" -> {
                    val x1 = r.d(10); val y1 = r.d(20); val x2 = r.d(11); val y2 = r.d(21)
                    if (x1 != null && y1 != null && x2 != null && y2 != null) {
                        result.add(LineEntity(start = Vec2(x1 * factor, y1 * factor), end = Vec2(x2 * factor, y2 * factor), layer = layer))
                    } else skip("LINE (invalid)")
                }
                "LWPOLYLINE" -> {
                    val flags = r.d(70)?.toInt() ?: 0
                    val pts = ArrayList<Vec2>()
                    var px: Double? = null
                    for ((c, v) in r.groups) {
                        when (c) {
                            10 -> px = v.trim().toDoubleOrNull()
                            20 -> {
                                val y = v.trim().toDoubleOrNull()
                                val x = px
                                if (x != null && y != null) pts.add(Vec2(x * factor, y * factor))
                                px = null
                            }
                            42 -> if ((v.trim().toDoubleOrNull() ?: 0.0) != 0.0) bulgeCount++
                        }
                    }
                    if (pts.size >= 2) {
                        result.add(PolylineEntity(points = pts, closed = (flags and 1) != 0, layer = layer))
                    } else skip("LWPOLYLINE (invalid)")
                }
                "POLYLINE" -> {
                    val flags = r.d(70)?.toInt() ?: 0
                    val verts = ArrayList<Vec2>()
                    var j = idx + 1
                    while (j < records.size && records[j].type != "SEQEND") {
                        val v = records[j]
                        if (v.type == "VERTEX") {
                            val x = v.d(10); val y = v.d(20)
                            val vflags = v.d(70)?.toInt() ?: 0
                            if (x != null && y != null && (vflags and 16) == 0) verts.add(Vec2(x * factor, y * factor))
                            if ((v.d(42) ?: 0.0) != 0.0) bulgeCount++
                        }
                        j++
                    }
                    idx = j
                    if ((flags and (16 or 64)) != 0) skip("POLYLINE (3D mesh)")
                    else if (verts.size >= 2) result.add(PolylineEntity(points = verts, closed = (flags and 1) != 0, layer = layer))
                    else skip("POLYLINE (invalid)")
                }
                "CIRCLE" -> {
                    val x = r.d(10); val y = r.d(20); val rad = r.d(40)
                    if (x != null && y != null && rad != null && rad > 0.0) {
                        result.add(CircleEntity(center = Vec2(x * factor, y * factor), radius = rad * factor, layer = layer))
                    } else skip("CIRCLE (invalid)")
                }
                "ARC" -> {
                    val x = r.d(10); val y = r.d(20); val rad = r.d(40)
                    val a1 = r.d(50); val a2 = r.d(51)
                    if (x != null && y != null && rad != null && rad > 0.0 && a1 != null && a2 != null) {
                        result.add(ArcEntity(center = Vec2(x * factor, y * factor), radius = rad * factor, startDeg = a1, endDeg = a2, layer = layer))
                    } else skip("ARC (invalid)")
                }
                "TEXT" -> {
                    val x = r.d(10); val y = r.d(20); val raw = r.s(1)
                    if (x != null && y != null && !raw.isNullOrBlank()) {
                        result.add(
                            TextEntity(
                                position = Vec2(x * factor, y * factor),
                                text = decodeText(raw),
                                heightM = (r.d(40) ?: 0.25 / factor) * factor,
                                rotationDeg = r.d(50) ?: 0.0,
                                layer = layer
                            )
                        )
                    } else skip("TEXT (invalid)")
                }
                "MTEXT" -> {
                    val x = r.d(10); val y = r.d(20)
                    val body = r.groups.filter { it.first == 3 }.joinToString("") { it.second } + (r.s(1) ?: "")
                    if (x != null && y != null && body.isNotBlank()) {
                        result.add(
                            TextEntity(
                                position = Vec2(x * factor, y * factor),
                                text = cleanMText(body),
                                heightM = (r.d(40) ?: 0.25 / factor) * factor,
                                rotationDeg = r.d(50) ?: 0.0,
                                layer = layer
                            )
                        )
                    } else skip("MTEXT (invalid)")
                }
                "SEQEND", "VERTEX" -> Unit
                else -> skip(r.type)
            }
            idx++
        }

        if (bulgeCount > 0) warnings.add("$bulgeCount curved polyline segments were imported as straight lines")
        if (insUnits == null || detected == null) warnings.add("File has no drawing units; assumed ${unitName(factor)}")
        return DxfImportResult(result, skipped, warnings, factor, detected != null)
    }

    // ---- parsing helpers ---------------------------------------------------------------------------

    private fun parsePairs(text: String): List<Pair<Int, String>> {
        val lines = text.split('\n')
        val out = ArrayList<Pair<Int, String>>(lines.size / 2)
        var i = 0
        while (i + 1 < lines.size) {
            val code = lines[i].trim().toIntOrNull()
            if (code == null) {
                i++
                continue
            }
            out.add(code to lines[i + 1].trimEnd('\r'))
            i += 2
        }
        return out
    }

    private fun headerValue(pairs: List<Pair<Int, String>>, name: String): String? {
        for (i in pairs.indices) {
            if (pairs[i].first == 9 && pairs[i].second.trim() == name) return pairs.getOrNull(i + 1)?.second?.trim()
        }
        return null
    }

    private fun detectCharset(pairs: List<Pair<Int, String>>): Charset {
        val ver = headerValue(pairs, "\$ACADVER")
        if (ver != null && ver >= "AC1021") return Charsets.UTF_8
        val cp = headerValue(pairs, "\$DWGCODEPAGE")
        if (cp != null && cp.uppercase().startsWith("ANSI_")) {
            return try {
                Charset.forName("windows-" + cp.substring(5))
            } catch (e: Exception) {
                Charsets.ISO_8859_1
            }
        }
        return Charsets.ISO_8859_1
    }

    private fun entityRecords(pairs: List<Pair<Int, String>>): List<Rec> {
        val records = ArrayList<Rec>()
        var section = ""
        var cur: Rec? = null
        var i = 0
        loop@ while (i < pairs.size) {
            val (code, value) = pairs[i]
            if (code == 0) {
                val type = value.trim()
                if (type == "SECTION") {
                    section = pairs.getOrNull(i + 1)?.takeIf { it.first == 2 }?.second?.trim() ?: ""
                    cur = null
                    i += 2
                    continue@loop
                } else if (type == "ENDSEC") {
                    section = ""
                    cur = null
                } else if (type == "EOF") {
                    break@loop
                } else if (section == "ENTITIES") {
                    val rec = Rec(type)
                    records.add(rec)
                    cur = rec
                } else {
                    cur = null
                }
            } else {
                cur?.groups?.add(code to value)
            }
            i++
        }
        return records
    }

    private fun unitToMeters(insUnits: Int): Double? = when (insUnits) {
        1 -> 0.0254
        2 -> 0.3048
        4 -> 0.001
        5 -> 0.01
        6 -> 1.0
        7 -> 1000.0
        10 -> 0.9144
        else -> null
    }

    private fun unitName(factor: Double): String = when (factor) {
        1.0 -> "meters"
        0.01 -> "centimeters"
        0.001 -> "millimeters"
        else -> "units × $factor m"
    }

    private val UNICODE_ESC = Regex("\\\\U\\+([0-9A-Fa-f]{4})")
    private val MTEXT_CODES = Regex("\\\\[A-Za-z][^;\\\\]*;")

    internal fun decodeText(s: String): String {
        val u = UNICODE_ESC.replace(s) { m -> m.groupValues[1].toInt(16).toChar().toString() }
        return u.replace("%%d", "°").replace("%%D", "°")
            .replace("%%p", "±").replace("%%P", "±")
            .replace("%%c", "Ø").replace("%%C", "Ø")
    }

    private fun cleanMText(s: String): String =
        decodeText(s).replace("\\P", " ").replace(MTEXT_CODES, "").replace("{", "").replace("}", "").trim()
}
