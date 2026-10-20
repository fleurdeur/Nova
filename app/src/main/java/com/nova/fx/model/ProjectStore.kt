package com.nova.fx.model

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Projects live in filesDir/projects/<id>.json with a tiny <id>.meta beside it so lists load instantly. */
object ProjectStore {
    class Meta(val id: String, val name: String, val w: Int, val h: Int, val fps: Int, val layers: Int, val modified: Long)
    private fun dir(c: Context) = File(c.filesDir, "projects").apply { mkdirs() }
    fun file(c: Context, id: String) = File(dir(c), "$id.json")
    private fun mf(c: Context, id: String) = File(dir(c), "$id.meta")

    fun meta(c: Context, id: String): Meta? = runCatching {
        val o = JSONObject(mf(c, id).readText())
        Meta(id, o.getString("name"), o.getInt("w"), o.getInt("h"), o.getInt("fps"), o.getInt("layers"), o.getLong("t"))
    }.getOrNull()

    fun list(c: Context): List<Meta> = (dir(c).listFiles { f -> f.name.endsWith(".meta") } ?: emptyArray())
        .mapNotNull { meta(c, it.name.removeSuffix(".meta")) }.sortedByDescending { it.modified }

    private fun writeMeta(c: Context, id: String, name: String, w: Int, h: Int, fps: Int, layers: Int) =
        mf(c, id).writeText(JSONObject().put("name", name).put("w", w).put("h", h).put("fps", fps).put("layers", layers).put("t", System.currentTimeMillis()).toString())

    fun saveMeta(c: Context, id: String, name: String, p: Project) = writeMeta(c, id, name, p.width, p.height, p.fps, p.layers.size)
    private fun newId() = java.lang.Long.toString(System.currentTimeMillis(), 36)

    fun create(c: Context, name: String, w: Int, h: Int, fps: Int): String {
        val id = newId()
        ProjectIO.save(Project(w, h, fps), file(c, id))
        writeMeta(c, id, name, w, h, fps, 0)
        return id
    }

    fun createWithPreset(c: Context, name: String, w: Int, h: Int, fps: Int, setup: (Project) -> Unit): String {
        val id = newId()
        val p = Project(w, h, fps)
        setup(p)
        ProjectIO.save(p, file(c, id))
        writeMeta(c, id, name, w, h, fps, p.layers.size)
        return id
    }

    fun rename(c: Context, id: String, name: String) {
        meta(c, id)?.let { writeMeta(c, id, name, it.w, it.h, it.fps, it.layers) }
    }

    fun duplicate(c: Context, id: String): String? {
        val m = meta(c, id) ?: return null
        val nid = newId() + "c"
        file(c, id).copyTo(file(c, nid), true)
        writeMeta(c, nid, m.name + " copy", m.w, m.h, m.fps, m.layers)
        return nid
    }

    fun delete(c: Context, id: String) {
        file(c, id).delete()
        mf(c, id).delete()
    }

    fun nextName(c: Context) = "Project ${list(c).size + 1}"
}
