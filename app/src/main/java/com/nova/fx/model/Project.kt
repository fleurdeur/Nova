package com.nova.fx.model

import android.graphics.PointF
import android.net.Uri
import java.util.concurrent.CopyOnWriteArrayList

enum class BlendMode {
    NORMAL,
    ADD,
    MULTIPLY,
    SCREEN,
    OVERLAY
}

class Transform {
    val x = AnimatedFloat()
    val y = AnimatedFloat()
    val scaleX = AnimatedFloat(1f)
    val scaleY = AnimatedFloat(1f)
    val rotation = AnimatedFloat()               // degrees
    val rotX = AnimatedFloat()                   // 3D tilt
    val rotY = AnimatedFloat()
    val anchorX = AnimatedFloat()
    val anchorY = AnimatedFloat()
}

sealed interface Source {
    data class Solid(val r: Float, val g: Float, val b: Float) : Source
    class Video(val uri: Uri) : Source
    class Raster(val bitmap: android.graphics.Bitmap, val photo: Boolean = false) : Source {
        var glId = 0
        var gen = -1
    }
    class Texture(var glId: Int = 0, val width: Int, val height: Int) : Source
    class Particles(
        var type: Int = 0,             // 0=Bokeh, 1=Sparks, 2=Fireflies, 3=Snow, 4=Rain, 5=Stars
        var count: Int = 120,
        var speed: Float = 1.0f,
        var size: Float = 1.0f,
        var colorR: Float = 1.0f,
        var colorG: Float = 0.85f,
        var colorB: Float = 0.4f
    ) : Source
}

class Layer(
    val id: Int,
    var source: Source,
    var width: Float,
    var height: Float,
    var inMs: Long = 0,
    var outMs: Long = Long.MAX_VALUE,
    var blend: BlendMode = BlendMode.NORMAL,
    var trimMs: Long = 0
) {
    var name = ""
    var threeD = false
    var locked = false
    var visible = true

    // Mask & Rotoscoping (0 none, 1 rectangle, 2 ellipse, 3 bezier rotoscope path)
    var maskType = 0
    var maskInvert = false
    val maskX = AnimatedFloat(0.5f)
    val maskY = AnimatedFloat(0.5f)
    val maskW = AnimatedFloat(0.5f)
    val maskH = AnimatedFloat(0.5f)
    val maskFeather = AnimatedFloat(0.02f)
    val maskPath = ArrayList<PointF>()  // Normalized coordinates (0..1) for freehand rotoscope

    // Color Grading & 3D LUTs
    var lutId = 0                       // 0=None, 1=TealOrange, 2=Portra, 3=Cyberpunk, 4=Vintage, 5=Noir, 6=Clean, 7=Golden, 8=Moody, 9=Custom
    var lutIntensity = 1.0f
    var lutCustomPath: String? = null
    val lift = AnimatedFloat(0f)        // Shadows lift
    val gamma = AnimatedFloat(1f)       // Midtones
    val gain = AnimatedFloat(1f)        // Highlights gain

    // Audio
    var audioUri: Uri? = null
    var timeMode = 0                    // 0 nearest, 1 frame blend, 2 optical flow
    var volume = 1f
    var fadeInMs = 0L
    var fadeOutMs = 0L

    // Time speed / reverse / AE time remap
    var speed = 1f
    var reversed = false
    var srcDurMs = 0L
    val remap = AnimatedFloat(0f)

    // Properties table
    val e = Array(FX.size) { AnimatedFloat(0f) }
    val rgbSplit = AnimatedFloat(0f)
    val vignette = AnimatedFloat(0f)
    val posterize = AnimatedFloat(0f)
    val grain = AnimatedFloat(0f)
    val brightness = AnimatedFloat(0f)
    val contrast = AnimatedFloat(1f)
    val saturation = AnimatedFloat(1f)
    val transform = Transform()
    val opacity = AnimatedFloat(1f)
    var parent: Layer? = null

    fun props(): Map<String, AnimatedFloat> {
        val m = LinkedHashMap<String, AnimatedFloat>()
        val t = transform
        m["x"] = t.x; m["y"] = t.y; m["sx"] = t.scaleX; m["sy"] = t.scaleY
        m["rot"] = t.rotation; m["rx"] = t.rotX; m["ry"] = t.rotY
        m["ax"] = t.anchorX; m["ay"] = t.anchorY
        m["op"] = opacity; m["br"] = brightness; m["ct"] = contrast; m["sa"] = saturation
        m["rs"] = rgbSplit; m["vg"] = vignette; m["pz"] = posterize; m["gr"] = grain
        m["remap"] = remap; m["mx"] = maskX; m["my"] = maskY; m["mw"] = maskW; m["mh"] = maskH; m["mf"] = maskFeather
        m["lift"] = lift; m["gamma"] = gamma; m["gain"] = gain
        for (i in e.indices) m["e$i"] = e[i]
        return m
    }

    fun animatedProps() = listOf(
        transform.x, transform.y, transform.scaleX, transform.scaleY,
        transform.rotation, transform.rotX, transform.rotY,
        opacity, maskX, maskY, maskW, maskH, maskFeather,
        brightness, contrast, saturation, rgbSplit, vignette,
        lift, gamma, gain
    )

    fun srcMs(t: Long): Long {
        if (remap.isAnimated) return maxOf(0L, (remap.valueAt(t) * 1000).toLong())
        val local = ((t - inMs) * speed).toLong()
        return maxOf(0L, if (!reversed) trimMs + local else trimMs + ((outMs - inMs) * speed).toLong() - local)
    }

    fun seedRemap() {
        if (remap.isAnimated) return
        val a = srcMs(inMs) / 1000f
        val b = srcMs(outMs) / 1000f
        remap.set(Keyframe(inMs, a, Interp.LINEAR))
        remap.set(Keyframe(outMs, b, Interp.LINEAR))
    }
}

class Project(
    var width: Int = 1280,
    var height: Int = 720,
    var fps: Int = 30,
    var durationMs: Long = 5000
) {
    val layers = CopyOnWriteArrayList<Layer>()
}
