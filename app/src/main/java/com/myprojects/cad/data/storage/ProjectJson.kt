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
        return CadProject(
            id = root.getString("id"),
            name = root.optString("name", "Project"),
            entities = list,
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
        return when (o.optString("t")) {
            "wall" -> Wall(id, readPt(o.getJSONArray("a")), readPt(o.getJSONArray("b")), o.getDouble("th"), layer)
            "line" -> LineEntity(id, readPt(o.getJSONArray("a")), readPt(o.getJSONArray("b")), layer)
            "poly" -> {
                val arr = o.getJSONArray("pts")
                val pts = (0 until arr.length()).map { readPt(arr.getJSONArray(it)) }
                PolylineEntity(id, pts, o.optBoolean("closed", false), layer)
            }
            "circle" -> CircleEntity(id, readPt(o.getJSONArray("c")), o.getDouble("r"), layer)
            "arc" -> ArcEntity(id, readPt(o.getJSONArray("c")), o.getDouble("r"), o.getDouble("s"), o.getDouble("e"), layer)
            "text" -> TextEntity(
                id, readPt(o.getJSONArray("p")), o.getString("text"),
                o.optDouble("h", 0.25), o.optDouble("rot", 0.0), layer
            )
            "dim" -> DimensionEntity(id, readPt(o.getJSONArray("a")), readPt(o.getJSONArray("b")), o.getDouble("off"), layer)
            else -> null
        }
    }
}
