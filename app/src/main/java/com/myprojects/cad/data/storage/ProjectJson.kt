package com.myprojects.cad.data.storage

import com.myprojects.cad.core.geometry.Vec2
import com.myprojects.cad.domain.model.ArcEntity
import com.myprojects.cad.domain.model.CadEntity
import com.myprojects.cad.domain.model.CadProject
import com.myprojects.cad.domain.model.CircleEntity
import com.myprojects.cad.domain.model.DimensionEntity
import com.myprojects.cad.domain.model.Layers
import com.myprojects.cad.domain.model.LineEntity
import com.myprojects.cad.domain.model.PolylineEntity
import com.myprojects.cad.domain.model.ProjectInfo
import com.myprojects.cad.domain.model.TextEntity
import com.myprojects.cad.domain.model.Wall
import com.myprojects.cad.domain.structural.BeamInput
import com.myprojects.cad.domain.structural.BeamPointLoad
import com.myprojects.cad.domain.structural.BeamType
import com.myprojects.cad.domain.structural.EndCondition
import com.myprojects.cad.domain.structural.StudyRecord
import com.myprojects.cad.domain.structural.StudySummary
import org.json.JSONArray
import org.json.JSONObject

/** Versioned JSON project format. Unknown entity types are skipped so newer files still open. */
object ProjectJson {
    private const val VERSION = 1

    fun encode(p: CadProject): String {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("id", p.id)
        root.put("name", p.name)
        root.put("createdAt", p.createdAt)
        root.put("modifiedAt", p.modifiedAt)
        val arr = JSONArray()
        for (e in p.entities) arr.put(encodeEntity(e))
        root.put("entities", arr)
        val studies = JSONArray()
        for (st in p.studies) studies.put(encodeStudy(st))
        root.put("studies", studies)
        return root.toString()
    }

    fun decode(text: String): CadProject {
        val root = JSONObject(text)
        val arr = root.optJSONArray("entities") ?: JSONArray()
        val list = ArrayList<CadEntity>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            decodeEntity(o)?.let { list.add(it) }
        }
        val sArr = root.optJSONArray("studies") ?: JSONArray()
        val studyList = ArrayList<StudyRecord>()
        for (i in 0 until sArr.length()) {
            val o = sArr.optJSONObject(i) ?: continue
            runCatching { decodeStudy(o) }.getOrNull()?.let { studyList.add(it) }
        }
        return CadProject(
            id = root.getString("id"),
            name = root.optString("name", "Project"),
            entities = list,
            studies = studyList,
            createdAt = root.optLong("createdAt", 0L),
            modifiedAt = root.optLong("modifiedAt", 0L)
        )
    }

    fun info(text: String): ProjectInfo {
        val root = JSONObject(text)
        return ProjectInfo(
            id = root.getString("id"),
            name = root.optString("name", "Project"),
            modifiedAt = root.optLong("modifiedAt", 0L),
            entityCount = root.optJSONArray("entities")?.length() ?: 0
        )
    }

    private fun pt(v: Vec2) = JSONArray().put(v.x).put(v.y)
    private fun readPt(a: JSONArray) = Vec2(a.getDouble(0), a.getDouble(1))

    private fun encodeEntity(e: CadEntity): JSONObject {
        val o = JSONObject()
        o.put("id", e.id)
        o.put("layer", e.layer)
        o.put("name", e.name)
        when (e) {
            is Wall -> o.put("t", "wall").put("a", pt(e.start)).put("b", pt(e.end)).put("th", e.thicknessM)
            is LineEntity -> o.put("t", "line").put("a", pt(e.start)).put("b", pt(e.end))
            is PolylineEntity -> {
                val pts = JSONArray()
                e.points.forEach { pts.put(pt(it)) }
                o.put("t", "poly").put("pts", pts).put("closed", e.closed)
            }
            is CircleEntity -> o.put("t", "circle").put("c", pt(e.center)).put("r", e.radius)
            is ArcEntity -> o.put("t", "arc").put("c", pt(e.center)).put("r", e.radius)
                .put("s", e.startDeg).put("e", e.endDeg)
            is TextEntity -> o.put("t", "text").put("p", pt(e.position)).put("text", e.text)
                .put("h", e.heightM).put("rot", e.rotationDeg)
            is DimensionEntity -> o.put("t", "dim").put("a", pt(e.p1)).put("b", pt(e.p2)).put("off", e.offset)
        }
        return o
    }

    private fun decodeEntity(o: JSONObject): CadEntity? {
        val id = o.getString("id")
        val layer = o.optString("layer", Layers.DEFAULT)
        val name = o.optString("name", "")
        return when (o.optString("t")) {
            "wall" -> Wall(id, readPt(o.getJSONArray("a")), readPt(o.getJSONArray("b")), o.getDouble("th"), layer, name)
            "line" -> LineEntity(id, readPt(o.getJSONArray("a")), readPt(o.getJSONArray("b")), layer, name)
            "poly" -> {
                val arr = o.getJSONArray("pts")
                val pts = (0 until arr.length()).map { readPt(arr.getJSONArray(it)) }
                PolylineEntity(id, pts, o.optBoolean("closed", false), layer, name)
            }
            "circle" -> CircleEntity(id, readPt(o.getJSONArray("c")), o.getDouble("r"), layer, name)
            "arc" -> ArcEntity(id, readPt(o.getJSONArray("c")), o.getDouble("r"), o.getDouble("s"), o.getDouble("e"), layer, name)
            "text" -> TextEntity(
                id, readPt(o.getJSONArray("p")), o.getString("text"),
                o.optDouble("h", 0.25), o.optDouble("rot", 0.0), layer, name
            )
            "dim" -> DimensionEntity(id, readPt(o.getJSONArray("a")), readPt(o.getJSONArray("b")), o.getDouble("off"), layer, name)
            else -> null
        }
    }

    // ---- Beam studies -----------------------------------------------------------------------------------

    private fun doubles(list: List<Double>) = JSONArray().also { a -> list.forEach { a.put(it) } }
    private fun readDoubles(a: JSONArray) = (0 until a.length()).map { a.getDouble(it) }

    private fun encodeInput(i: BeamInput): JSONObject {
        val o = JSONObject()
        o.put("type", i.type.name)
        o.put("spans", doubles(i.spansM))
        o.put("dead", doubles(i.deadKnPerM))
        o.put("live", doubles(i.liveKnPerM))
        val pts = JSONArray()
        i.pointLoads.forEach { p ->
            pts.put(JSONObject().put("s", p.spanIndex).put("x", p.posM).put("d", p.deadKn).put("l", p.liveKn))
        }
        o.put("points", pts)
        o.put("b", i.bMm).put("h", i.hMm).put("fc", i.fcMpa).put("fy", i.fyMpa).put("fyt", i.fytMpa)
        o.put("cover", i.coverMm).put("stirrup", i.stirrupDiaMm).put("bar", i.barDiaMm)
        o.put("support", i.supportWidthMm).put("sw", i.includeSelfWeight)
        o.put("gD", i.deadFactor).put("gL", i.liveFactor)
        o.put("left", i.leftEnd.name).put("right", i.rightEnd.name)
        return o
    }

    private fun decodeInput(o: JSONObject): BeamInput {
        val pts = o.optJSONArray("points") ?: JSONArray()
        val points = (0 until pts.length()).map {
            val p = pts.getJSONObject(it)
            BeamPointLoad(p.getInt("s"), p.getDouble("x"), p.getDouble("d"), p.getDouble("l"))
        }
        return BeamInput(
            type = BeamType.valueOf(o.getString("type")),
            spansM = readDoubles(o.getJSONArray("spans")),
            deadKnPerM = readDoubles(o.getJSONArray("dead")),
            liveKnPerM = readDoubles(o.getJSONArray("live")),
            pointLoads = points,
            bMm = o.getDouble("b"), hMm = o.getDouble("h"),
            fcMpa = o.getDouble("fc"), fyMpa = o.getDouble("fy"), fytMpa = o.getDouble("fyt"),
            coverMm = o.getDouble("cover"), stirrupDiaMm = o.getDouble("stirrup"), barDiaMm = o.getDouble("bar"),
            supportWidthMm = o.getDouble("support"), includeSelfWeight = o.getBoolean("sw"),
            deadFactor = o.getDouble("gD"), liveFactor = o.getDouble("gL"),
            leftEnd = EndCondition.valueOf(o.optString("left", "PINNED")),
            rightEnd = EndCondition.valueOf(o.optString("right", "PINNED"))
        )
    }

    private fun encodeStudy(s: StudyRecord): JSONObject {
        val o = JSONObject()
        o.put("id", s.id).put("name", s.name).put("modifiedAt", s.modifiedAt)
        val ids = JSONArray()
        s.entityIds.forEach { ids.put(it) }
        o.put("entities", ids)
        o.put("input", encodeInput(s.input))
        s.summary?.let { m ->
            o.put(
                "summary",
                JSONObject().put("section", m.section).put("bottom", m.bottom).put("top", m.top)
                    .put("stirrups", m.stirrups).put("ok", m.ok)
                    .put("mu", m.maxMomentKnM).put("vu", m.maxShearKn)
            )
        }
        return o
    }

    private fun decodeStudy(o: JSONObject): StudyRecord {
        val ids = o.getJSONArray("entities")
        val sm = o.optJSONObject("summary")
        return StudyRecord(
            id = o.getString("id"),
            entityIds = (0 until ids.length()).map { ids.getString(it) },
            name = o.getString("name"),
            input = decodeInput(o.getJSONObject("input")),
            summary = sm?.let {
                StudySummary(
                    it.getString("section"), it.getString("bottom"), it.getString("top"),
                    it.getString("stirrups"), it.getBoolean("ok"),
                    it.optDouble("mu", 0.0), it.optDouble("vu", 0.0)
                )
            },
            modifiedAt = o.optLong("modifiedAt", 0L)
        )
    }
}
