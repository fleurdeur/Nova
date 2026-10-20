package com.nova.fx.model

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Project Serialization (JSON).
 * Supports video URIs, raster bitmaps (PNG/JPEG base64), shapes, text, particles,
 * and keyframed properties.
 */
object ProjectIO {
    private fun af(a: AnimatedFloat) = JSONObject().put("b", a.base.toDouble()).put("k", JSONArray().also { arr ->
        for (k in a.keyList()) {
            arr.put(
                JSONArray().put(k.timeMs).put(k.value.toDouble()).put(k.interp.ordinal)
                    .put(k.outX.toDouble()).put(k.outY.toDouble()).put(k.inX.toDouble()).put(k.inY.toDouble())
            )
        }
    })

    private fun loadAf(a: AnimatedFloat, o: JSONObject) {
        a.base = o.optDouble("b", a.base.toDouble()).toFloat()
        val arr = o.optJSONArray("k") ?: return
        for (i in 0 until arr.length()) {
            val r = arr.getJSONArray(i)
            val interpOrd = r.optInt(2, 0)
            val interp = if (interpOrd in Interp.values().indices) Interp.values()[interpOrd] else Interp.BEZIER
            a.set(
                Keyframe(
                    r.getLong(0),
                    r.getDouble(1).toFloat(),
                    interp,
                    r.optDouble(3, 0.42).toFloat(),
                    r.optDouble(4, 0.0).toFloat(),
                    r.optDouble(5, 0.58).toFloat(),
                    r.optDouble(6, 1.0).toFloat()
                )
            )
        }
    }

    fun save(p: Project, f: File) {
        val layers = JSONArray()
        for (l in p.layers) {
            val o = JSONObject().put("id", l.id).put("name", l.name).put("w", l.width.toDouble()).put("h", l.height.toDouble())
                .put("in", l.inMs).put("out", minOf(l.outMs, 3_600_000L)).put("blend", l.blend.ordinal).put("trim", l.trimMs).put("3d", l.threeD)
                .put("speed", l.speed.toDouble()).put("rev", l.reversed).put("srcDur", l.srcDurMs).put("tm", l.timeMode)
                .put("mt", l.maskType).put("mi", l.maskInvert).put("vol", l.volume.toDouble()).put("fi", l.fadeInMs).put("fo", l.fadeOutMs)
                .put("lutId", l.lutId).put("lutInt", l.lutIntensity.toDouble()).put("locked", l.locked).put("visible", l.visible)
            l.audioUri?.let { o.put("audio", it.toString()) }

            if (l.maskPath.isNotEmpty()) {
                val mpArr = JSONArray()
                for (pt in l.maskPath) {
                    mpArr.put(JSONArray().put(pt.x.toDouble()).put(pt.y.toDouble()))
                }
                o.put("mp", mpArr)
            }

            when (val s = l.source) {
                is Source.Video -> o.put("src", "v").put("uri", s.uri.toString())
                is Source.Solid -> o.put("src", "s").put("rgb", JSONArray().put(s.r.toDouble()).put(s.g.toDouble()).put(s.b.toDouble()))
                is Source.Particles -> o.put("src", "p").put("ptype", s.type).put("pcount", s.count).put("pspeed", s.speed.toDouble())
                    .put("psize", s.size.toDouble()).put("prgb", JSONArray().put(s.colorR.toDouble()).put(s.colorG.toDouble()).put(s.colorB.toDouble()))
                is Source.Raster -> {
                    val bos = ByteArrayOutputStream()
                    if (s.photo) s.bitmap.compress(Bitmap.CompressFormat.JPEG, 88, bos) else s.bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)
                    o.put("src", "r").put("photo", s.photo).put("png", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP))
                }
                is Source.Texture -> o.put("src", "none")
            }
            val pr = JSONObject()
            for ((k, a) in l.props()) pr.put(k, af(a))
            o.put("p", pr)
            if (o.getString("src") != "none") layers.put(o)
        }
        f.writeText(JSONObject().put("v", 2).put("dur", p.durationMs).put("w", p.width).put("h", p.height).put("fps", p.fps).put("layers", layers).toString())
    }

    class Loaded(val dur: Long, val w: Int, val h: Int, val fps: Int, val layers: List<Layer>)

    fun load(f: File): Loaded {
        val root = JSONObject(f.readText())
        val out = ArrayList<Layer>()
        val arr = root.getJSONArray("layers")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val src: Source = when (o.getString("src")) {
                "v" -> Source.Video(Uri.parse(o.getString("uri")))
                "s" -> {
                    val c = o.getJSONArray("rgb")
                    Source.Solid(c.getDouble(0).toFloat(), c.getDouble(1).toFloat(), c.getDouble(2).toFloat())
                }
                "p" -> {
                    val cr = o.optJSONArray("prgb")
                    Source.Particles(
                        type = o.optInt("ptype", 0),
                        count = o.optInt("pcount", 120),
                        speed = o.optDouble("pspeed", 1.0).toFloat(),
                        size = o.optDouble("psize", 1.0).toFloat(),
                        colorR = cr?.optDouble(0, 1.0)?.toFloat() ?: 1.0f,
                        colorG = cr?.optDouble(1, 0.85)?.toFloat() ?: 0.85f,
                        colorB = cr?.optDouble(2, 0.4)?.toFloat() ?: 0.4f
                    )
                }
                "r" -> {
                    val b = Base64.decode(o.getString("png"), Base64.DEFAULT)
                    Source.Raster(BitmapFactory.decodeByteArray(b, 0, b.size), o.optBoolean("photo"))
                }
                else -> continue
            }
            val blendOrd = o.optInt("blend", 0)
            val blendMode = if (blendOrd in BlendMode.values().indices) BlendMode.values()[blendOrd] else BlendMode.NORMAL
            val l = Layer(
                o.getInt("id"), src, o.getDouble("w").toFloat(), o.getDouble("h").toFloat(), o.getLong("in"), o.getLong("out"),
                blendMode, o.getLong("trim")
            )
            l.name = o.optString("name")
            l.threeD = o.optBoolean("3d")
            l.speed = o.optDouble("speed", 1.0).toFloat()
            l.reversed = o.optBoolean("rev")
            l.srcDurMs = o.optLong("srcDur")
            l.timeMode = o.optInt("tm")
            l.maskType = o.optInt("mt")
            l.maskInvert = o.optBoolean("mi")
            l.volume = o.optDouble("vol", 1.0).toFloat()
            l.fadeInMs = o.optLong("fi")
            l.fadeOutMs = o.optLong("fo")
            l.lutId = o.optInt("lutId", 0)
            l.lutIntensity = o.optDouble("lutInt", 1.0).toFloat()
            l.locked = o.optBoolean("locked", false)
            l.visible = o.optBoolean("visible", true)

            if (o.has("audio")) l.audioUri = Uri.parse(o.getString("audio"))
            val mpArr = o.optJSONArray("mp")
            if (mpArr != null) {
                for (j in 0 until mpArr.length()) {
                    val ptArr = mpArr.getJSONArray(j)
                    l.maskPath.add(PointF(ptArr.getDouble(0).toFloat(), ptArr.getDouble(1).toFloat()))
                }
            }

            val pr = o.optJSONObject("p")
            if (pr != null) {
                for ((k, a) in l.props()) {
                    pr.optJSONObject(k)?.let { loadAf(a, it) }
                }
            }
            out += l
        }
        return Loaded(root.getLong("dur"), root.optInt("w", 1280), root.optInt("h", 720), root.optInt("fps", 30), out)
    }
}
