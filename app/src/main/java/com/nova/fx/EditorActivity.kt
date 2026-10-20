package com.nova.fx

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.*
import com.nova.fx.export.Exporter
import com.nova.fx.export.Tracker
import com.nova.fx.gl.Compositor
import com.nova.fx.model.*
import com.nova.fx.ui.CanvasDialog
import com.nova.fx.ui.CurveView
import com.nova.fx.ui.TimelineView
import com.nova.fx.ui.Ui
import java.io.File
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

class EditorState {
    val project = Project()
    @Volatile var posMs = 0.0
    @Volatile var playing = false
    @Volatile var exporting = false
    @Volatile var selected: Layer? = null
    @Volatile var autoKey = false
    @Volatile var trackPick = false
}

private class Prop(
    val name: String,
    val min: Float,
    val max: Float,
    val get: (Long) -> Float,
    val set: (Long, Float, Boolean) -> Unit
)

class EditorActivity : Activity() {
    private val st = EditorState()
    private lateinit var glView: GLSurfaceView
    private lateinit var stage: FrameLayout
    private lateinit var timeline: TimelineView
    private lateinit var renderer: EditorRenderer
    private lateinit var titleView: TextView
    private lateinit var timecodeView: TextView
    private lateinit var keyframeBtn: TextView
    private lateinit var playBtn: TextView
    private lateinit var resBadge: TextView

    private lateinit var pid: String
    private var pname = "Project"
    private var nextId = 1
    private var thermalCut = false
    private var lastRender = 0L
    private var frames = 0
    private val ui = Handler(Looper.getMainLooper())

    private fun d(v: Float) = Ui.dp(this, v)
    private fun now() = st.posMs.toLong()
    private fun gl(f: () -> Unit) {
        glView.queueEvent { f() }
        glView.requestRender()
    }

    private val autosaver = object : Runnable {
        override fun run() {
            save()
            if (Prefs.autosaveMin > 0) ui.postDelayed(this, Prefs.autosaveMin * 60_000L)
        }
    }

    private val tick = object : Choreographer.FrameCallback {
        override fun doFrame(n: Long) {
            if (!st.playing) return
            val fps = if (Prefs.previewFpsCap == 0) st.project.fps else minOf(st.project.fps, Prefs.previewFpsCap)
            if (n - lastRender >= 1_000_000_000L / fps - 2_000_000L) {
                lastRender = n
                glView.requestRender()
                timeline.invalidate()
                updateTimecode()
            }
            if (Prefs.thermalGuard && ++frames % 120 == 0) checkThermal()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        Prefs.init(this)

        pid = intent.getStringExtra("pid") ?: run { finish(); return }
        pname = ProjectStore.meta(this, pid)?.name ?: "Project"
        val f = ProjectStore.file(this, pid)
        if (f.exists()) runCatching {
            val ld = ProjectIO.load(f)
            val p = st.project
            p.width = ld.w
            p.height = ld.h
            p.fps = ld.fps
            p.durationMs = ld.dur
            p.layers.addAll(ld.layers)
            nextId = (ld.layers.maxOfOrNull { it.id } ?: 0) + 1
        }
        if (Prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        renderer = EditorRenderer(this, st)
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 0, 0)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            setOnTouchListener { _, e -> gizmo(e); true }
        }
        stage = FrameLayout(this).apply {
            setBackgroundColor(0xFF090A0D.toInt())
            addView(glView, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutPreview() }
        }

        timeline = TimelineView(this, st) {
            glView.requestRender()
            updateTimecode()
            updateKeyframeStatus()
        }

        buildUi()
    }

    private fun buildUi() {
        // Top Navigation Bar
        titleView = Ui.text(this, pname, 15f, bold = true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { renameDialog() }
        }
        resBadge = Ui.badge(this, "${st.project.width}p ${st.project.fps}fps", Ui.ACCENT_CYAN).apply {
            setOnClickListener { canvasDialog() }
        }
        val noWatermarkBadge = Ui.badge(this, "No Watermark", Ui.SUCCESS_GREEN)

        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.PANEL)
            setPadding(d(8f), d(6f), d(8f), d(6f))
            addView(Ui.chip(this@EditorActivity, "‹ Back") { finishSave() })
            addView(LinearLayout(this@EditorActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(titleView)
                val badgeRow = LinearLayout(this@EditorActivity).apply {
                    gravity = Gravity.CENTER
                    addView(resBadge)
                    val space = View(this@EditorActivity).apply { layoutParams = LinearLayout.LayoutParams(d(6f), 1) }
                    addView(space)
                    addView(noWatermarkBadge)
                }
                addView(badgeRow)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Ui.chip(this@EditorActivity, "Canvas") { canvasDialog() })
            addView(Ui.chip(this@EditorActivity, "Export", selected = true) { v -> exportDialog(v) })
        }

        // Transport & Keyframe Quick Control Bar (Alight Motion / Keyflow style)
        timecodeView = Ui.text(this, "00:00:00.00", 12f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(8f), 0, d(8f), 0)
        }
        playBtn = Ui.chip(this, "▶ Play") { v ->
            if (!st.exporting) {
                st.playing = !st.playing
                v.text = if (st.playing) "❚❚ Pause" else "▶ Play"
                if (st.playing) {
                    lastRender = 0
                    Choreographer.getInstance().postFrameCallback(tick)
                }
            }
        }
        keyframeBtn = Ui.chip(this, "◇ Key") { toggleCurrentKeyframe() }

        val transportBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.PANEL)
            setPadding(d(6f), d(4f), d(6f), d(4f))
            addView(timecodeView)
            addView(Ui.chip(this@EditorActivity, "⏮") { jumpPrevKeyframe() })
            addView(keyframeBtn)
            addView(Ui.chip(this@EditorActivity, "⏭") { jumpNextKeyframe() })
            addView(View(this@EditorActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(Ui.chip(this@EditorActivity, "◀ 1f") { stepFrame(-1) })
            addView(playBtn)
            addView(Ui.chip(this@EditorActivity, "1f ▶") { stepFrame(1) })
            addView(Ui.chip(this@EditorActivity, "✂ Split") { splitLayerAtPlayhead() })
            addView(Ui.chip(this@EditorActivity, "📋 Dupe") { duplicateSelectedLayer() })
        }

        // Studio Action Shelf Tabs
        val bar = LinearLayout(this).apply {
            setPadding(d(4f), d(4f), d(4f), d(4f))
        }
        fun add(label: String, f: (TextView) -> Unit) = bar.addView(Ui.chip(this, label, false, f))
        fun mark(v: TextView, on: Boolean) {
            v.background = Ui.round(this, if (on) Ui.accent() else Ui.CHIP, 10f)
        }

        add("＋ Add") { addLayerDialog() }
        add("Transform") { transformDialog() }
        add("📈 Curves & Keys") { curveDialog() }
        add("🎨 Color & LUTs") { colorGradingDialog() }
        add("✨ Effects & AE") { effectsDialog() }
        add("Particles") { particlesDialog() }
        add("Mask / Roto") { maskDialog() }
        add("🎯 Track") { startMotionTracking() }
        add("Speed / Slow-mo") { v ->
            st.selected?.let { l ->
                l.timeMode = (l.timeMode + 1) % 3
                v.text = "Slow-mo: " + arrayOf("Off", "Blend", "Flow (Optical)")[l.timeMode]
                glView.requestRender()
            }
        }
        add("Reverse") { v ->
            st.selected?.let { l ->
                l.reversed = !l.reversed
                mark(v, l.reversed)
                glView.requestRender()
            }
        }
        add("Bake Proxy") { bake() }
        add("3D Tilt") { v ->
            st.selected?.let { l ->
                l.threeD = !l.threeD
                mark(v, l.threeD)
                glView.requestRender()
            }
        }
        add("◆ Auto-Key") { v ->
            st.autoKey = !st.autoKey
            mark(v, st.autoKey)
        }
        add("Blend") { v ->
            st.selected?.let { l ->
                val m = BlendMode.values()
                l.blend = m[(l.blend.ordinal + 1) % m.size]
                v.text = "Blend: ${l.blend}"
                glView.requestRender()
            }
        }
        add("🗑 Delete") { deleteSelectedLayer() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            addView(top)
            addView(stage, LinearLayout.LayoutParams(-1, 0, 1.25f))
            addView(transportBar)
            addView(timeline, LinearLayout.LayoutParams(-1, d(145f)))
            addView(HorizontalScrollView(this@EditorActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(bar)
            })
        }
        setContentView(root)
        updateTimecode()
    }

    private fun updateTimecode() {
        val totalMs = now()
        val sec = (totalMs / 1000) % 60
        val min = (totalMs / 60000) % 60
        val hr = totalMs / 3600000
        val frames = ((totalMs % 1000) * st.project.fps / 1000).toInt()
        timecodeView.text = "%02d:%02d:%02d.%02d".format(hr, min, sec, frames)
    }

    private fun updateKeyframeStatus() {
        val l = st.selected
        if (l != null && l.animatedProps().any { it.hasKeyAt(now()) }) {
            keyframeBtn.text = "◆ Key"
            keyframeBtn.setTextColor(Ui.ACCENT_CYAN)
        } else {
            keyframeBtn.text = "◇ Key"
            keyframeBtn.setTextColor(Color.WHITE)
        }
    }

    private fun stepFrame(delta: Int) {
        val frameMs = 1000.0 / st.project.fps
        st.posMs = (st.posMs + delta * frameMs).coerceIn(0.0, st.project.durationMs.toDouble())
        glView.requestRender()
        timeline.invalidate()
        updateTimecode()
        updateKeyframeStatus()
    }

    private fun toggleCurrentKeyframe() {
        val l = st.selected ?: return
        val t = now()
        val hasKey = l.animatedProps().any { it.hasKeyAt(t) }
        gl {
            if (hasKey) {
                l.animatedProps().forEach { it.removeAt(t) }
            } else {
                // Keyframe primary transform properties
                l.transform.x.set(Keyframe(t, l.transform.x.valueAt(t)))
                l.transform.y.set(Keyframe(t, l.transform.y.valueAt(t)))
                l.transform.scaleX.set(Keyframe(t, l.transform.scaleX.valueAt(t)))
                l.transform.scaleY.set(Keyframe(t, l.transform.scaleY.valueAt(t)))
                l.transform.rotation.set(Keyframe(t, l.transform.rotation.valueAt(t)))
                l.opacity.set(Keyframe(t, l.opacity.valueAt(t)))
            }
        }
        updateKeyframeStatus()
        timeline.invalidate()
    }

    private fun jumpPrevKeyframe() {
        val l = st.selected ?: return
        val t = now()
        val prev = l.animatedProps().mapNotNull { it.prevKeyTime(t) }.maxOrNull()
        if (prev != null) {
            st.posMs = prev.toDouble()
            glView.requestRender()
            timeline.invalidate()
            updateTimecode()
            updateKeyframeStatus()
        }
    }

    private fun jumpNextKeyframe() {
        val l = st.selected ?: return
        val t = now()
        val next = l.animatedProps().mapNotNull { it.nextKeyTime(t) }.minOrNull()
        if (next != null) {
            st.posMs = next.toDouble()
            glView.requestRender()
            timeline.invalidate()
            updateTimecode()
            updateKeyframeStatus()
        }
    }

    private fun splitLayerAtPlayhead() {
        val l = st.selected ?: return
        val t = now()
        if (t <= l.inMs + 100 || t >= l.outMs - 100) {
            Toast.makeText(this, "Move playhead inside the layer to split", Toast.LENGTH_SHORT).show()
            return
        }
        val origOut = l.outMs
        l.outMs = t
        val newLayer = Layer(nextId++, l.source, l.width, l.height, inMs = t, outMs = origOut, blend = l.blend).apply {
            name = "${l.name} (Split)"
            trimMs = l.trimMs + (t - l.inMs)
            speed = l.speed
            reversed = l.reversed
            threeD = l.threeD
            audioUri = l.audioUri
            lutId = l.lutId
            lutIntensity = l.lutIntensity
            transform.x.base = l.transform.x.valueAt(t)
            transform.y.base = l.transform.y.valueAt(t)
            transform.scaleX.base = l.transform.scaleX.valueAt(t)
            transform.scaleY.base = l.transform.scaleY.valueAt(t)
            transform.rotation.base = l.transform.rotation.valueAt(t)
        }
        gl { st.project.layers.add(newLayer) }
        st.selected = newLayer
        timeline.invalidate()
        Toast.makeText(this, "Layer split at ${t}ms", Toast.LENGTH_SHORT).show()
    }

    private fun duplicateSelectedLayer() {
        val l = st.selected ?: return
        val dupe = Layer(nextId++, l.source, l.width, l.height, inMs = l.inMs, outMs = l.outMs, blend = l.blend).apply {
            name = "${l.name} (Copy)"
            trimMs = l.trimMs
            speed = l.speed
            reversed = l.reversed
            threeD = l.threeD
            audioUri = l.audioUri
            lutId = l.lutId
            lutIntensity = l.lutIntensity
            transform.x.base = l.transform.x.valueAt(now()) + 30f
            transform.y.base = l.transform.y.valueAt(now()) + 30f
            transform.scaleX.base = l.transform.scaleX.valueAt(now())
            transform.scaleY.base = l.transform.scaleY.valueAt(now())
            transform.rotation.base = l.transform.rotation.valueAt(now())
        }
        gl { st.project.layers.add(dupe) }
        st.selected = dupe
        timeline.invalidate()
        Toast.makeText(this, "Layer duplicated", Toast.LENGTH_SHORT).show()
    }

    private fun deleteSelectedLayer() {
        st.selected?.let { l ->
            st.selected = null
            gl {
                st.project.layers.remove(l)
                renderer.comp.drop(l.id)
            }
            timeline.invalidate()
        }
    }

    private fun layoutPreview() {
        val sw = stage.width
        val sh = stage.height
        if (sw == 0 || sh == 0) return
        val p = st.project
        val fit = min(sw / p.width.toFloat(), sh / p.height.toFloat())
        val fw = maxOf(2, (p.width * fit).toInt())
        val fh = maxOf(2, (p.height * fit).toInt())
        val lp = glView.layoutParams as FrameLayout.LayoutParams
        if (lp.width != fw || lp.height != fh) {
            lp.width = fw
            lp.height = fh
            glView.layoutParams = lp
        }
        var cap = Prefs.previewRes
        if (thermalCut) cap = if (cap == 0) 360 else min(cap, 360)
        var q = if (cap == 0) 1f else min(1f, cap / min(p.width, p.height).toFloat())
        q = min(q, fit)
        glView.holder.setFixedSize(maxOf(2, (p.width * q).toInt()), maxOf(2, (p.height * q).toInt()))
    }

    private fun checkThermal() {
        if (Build.VERSION.SDK_INT < 29) return
        val ts = (getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
        if (!thermalCut && ts >= PowerManager.THERMAL_STATUS_MODERATE) {
            thermalCut = true
            layoutPreview()
            Toast.makeText(this, "Mali GPU temperature: preview resolution balanced.", Toast.LENGTH_SHORT).show()
        } else if (thermalCut && ts <= PowerManager.THERMAL_STATUS_NONE) {
            thermalCut = false
            layoutPreview()
        }
    }

    // On-canvas interactive gizmo touch interaction
    private var lx = 0f
    private var ly = 0f
    private var pd = 0f
    private var pa = 0f

    private fun gizmo(e: MotionEvent) {
        val view = glView.width / st.project.width.toFloat()
        if (st.trackPick) {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) startTrack(e.x / view, e.y / view)
            return
        }
        val l = st.selected
        if (l == null || st.playing) return

        fun dist() = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
        fun ang() = Math.toDegrees(atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lx = e.x
                ly = e.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                pd = dist()
                pa = ang()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val k = if (e.actionIndex == 0) 1 else 0
                lx = e.getX(k)
                ly = e.getY(k)
            }
            MotionEvent.ACTION_MOVE -> {
                val t = now()
                val tr = l.transform
                val key = st.autoKey
                if (e.pointerCount == 1) {
                    val dx = (e.x - lx) / view
                    val dy = (e.y - ly) / view
                    lx = e.x
                    ly = e.y
                    gl {
                        tr.x.put(t, tr.x.valueAt(t) + dx, key)
                        tr.y.put(t, tr.y.valueAt(t) + dy, key)
                    }
                } else if (e.pointerCount >= 2) {
                    val dd = dist()
                    val a = ang()
                    val f = if (pd > 0) dd / pd else 1f
                    val da = a - pa
                    pd = dd
                    pa = a
                    gl {
                        tr.scaleX.put(t, tr.scaleX.valueAt(t) * f, key)
                        tr.scaleY.put(t, tr.scaleY.valueAt(t) * f, key)
                        tr.rotation.put(t, tr.rotation.valueAt(t) + da, key)
                    }
                }
            }
        }
    }

    private fun addLayer(l: Layer) {
        st.selected = l
        gl { st.project.layers += l }
        timeline.invalidate()
    }

    private fun addLayerDialog() {
        val options = arrayOf(
            "🎬 Video Clip",
            "🖼 Photo / Graphic",
            "✍ Text Overlay",
            "📐 Shape (Rect, Star, Heart...)",
            "✨ Particle Generator (Bokeh, Sparks...)",
            "🎨 Solid / Color Adjustment Layer"
        )
        AlertDialog.Builder(this)
            .setTitle("Add Layer")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("video/*").addCategory(Intent.CATEGORY_OPENABLE), 1)
                    1 -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), 2)
                    2 -> textDialog()
                    3 -> shapeDialog()
                    4 -> particlesDialog()
                    5 -> addLayer(LayerFactory.solid(nextId++, 0.1f, 0.1f, 0.15f, st.project, "Adjustment Layer"))
                }
            }.show()
    }

    private fun shapeDialog() {
        val items = arrayOf("Rectangle", "Rounded Rect", "Circle / Ellipse", "Star", "Triangle", "Heart")
        AlertDialog.Builder(this)
            .setTitle("Add Vector Shape")
            .setItems(items) { _, i ->
                addLayer(LayerFactory.shape(nextId++, i, st.project))
            }.show()
    }

    private fun particlesDialog() {
        val types = arrayOf("Floating Bokeh Dust", "Golden Sparks / Embers", "Fireflies Glow", "Blizzard Snow", "Raindrops", "Starfield Warp")
        AlertDialog.Builder(this)
            .setTitle("Particle Generator")
            .setItems(types) { _, i ->
                addLayer(LayerFactory.particles(nextId++, i, st.project))
            }.show()
    }

    private fun textDialog() {
        val et = EditText(this).apply {
            hint = "Enter motion graphic text..."
            setTextColor(Color.WHITE)
        }
        AlertDialog.Builder(this)
            .setTitle("Text Overlay")
            .setView(et)
            .setPositiveButton("Add") { _, _ ->
                val s = et.text.toString()
                if (s.isNotBlank()) addLayer(LayerFactory.text(nextId++, s, st.project))
            }.setNegativeButton("Cancel", null)
            .show()
    }

    private fun p(n: String, lo: Float, hi: Float, a: AnimatedFloat) =
        Prop(n, lo, hi, { a.valueAt(it) }, { t, v, k -> a.put(t, v, k) })

    private fun transformDialog() {
        val l = st.selected ?: return
        val tr = l.transform
        val ps = ArrayList<Prop>()
        ps += p("Opacity", 0f, 1f, l.opacity)
        ps += Prop("Scale", 0.05f, 4f, { tr.scaleX.valueAt(it) }, { t, v, k -> tr.scaleX.put(t, v, k); tr.scaleY.put(t, v, k) })
        ps += p("Rotation (Z)", -360f, 360f, tr.rotation)
        ps += p("Rotate X (3D)", -180f, 180f, tr.rotX)
        ps += p("Rotate Y (3D)", -180f, 180f, tr.rotY)
        ps += p("Anchor X", 0f, l.width, tr.anchorX)
        ps += p("Anchor Y", 0f, l.height, tr.anchorY)
        showProps("Transform: " + l.name.ifEmpty { "Layer ${l.id}" }, ps)
    }

    private fun colorGradingDialog() {
        val l = st.selected ?: return
        val options = arrayOf(
            "LUT: Teal & Orange",
            "LUT: Kodak Portra 400",
            "LUT: Cyberpunk Neon",
            "LUT: 70s Vintage Film",
            "LUT: Noir Monolith (B&W)",
            "LUT: Commercial Clean",
            "LUT: Golden Hour Sunset",
            "LUT: Urban Moody",
            "LUT: None (Reset)",
            "Lift / Gamma / Gain Sliders",
            "Temperature & Tint Sliders"
        )
        AlertDialog.Builder(this)
            .setTitle("Cinematic 3D LUTs & Color Grading")
            .setItems(options) { _, which ->
                when (which) {
                    in 0..7 -> {
                        gl {
                            l.lutId = which + 1
                            l.lutIntensity = 1.0f
                        }
                        Toast.makeText(this, "Applied ${options[which]}", Toast.LENGTH_SHORT).show()
                    }
                    8 -> gl { l.lutId = 0 }
                    9 -> showProps("Lift, Gamma, Gain", listOf(
                        p("Shadows Lift", -0.5f, 0.5f, l.lift),
                        p("Midtones Gamma", 0.2f, 2.5f, l.gamma),
                        p("Highlights Gain", 0.2f, 2.5f, l.gain),
                        p("Exposure EV", -2f, 2f, l.e[21])
                    ))
                    10 -> showProps("Color Temp & Balance", listOf(
                        p("Temperature (Kelvin)", -1f, 1f, l.e[3]),
                        p("Tint (Green - Magenta)", -1f, 1f, l.e[25]),
                        p("Saturation", 0f, 2f, l.saturation),
                        p("Contrast", 0f, 2f, l.contrast)
                    ))
                }
            }.show()
    }

    private fun effectsDialog() {
        val l = st.selected ?: return
        showProps("Effects & After Effects", FX.mapIndexed { i, f -> p(f.name, f.min, f.max, l.e[i]) })
    }

    private fun maskDialog() {
        val l = st.selected ?: return
        AlertDialog.Builder(this)
            .setTitle("Rotoscoping & Masking")
            .setItems(arrayOf("None", "Rectangle Mask", "Ellipse Mask", "Toggle Invert", "Adjust Mask Bounds…")) { _, i ->
                when (i) {
                    0, 1, 2 -> gl { l.maskType = i }
                    3 -> gl { l.maskInvert = !l.maskInvert }
                    else -> showProps(
                        "Mask Bounds",
                        listOf(
                            p("Center X", 0f, 1f, l.maskX),
                            p("Center Y", 0f, 1f, l.maskY),
                            p("Width", 0.02f, 1.5f, l.maskW),
                            p("Height", 0.02f, 1.5f, l.maskH),
                            p("Feather", 0f, 0.3f, l.maskFeather)
                        )
                    )
                }
            }.show()
    }

    private fun curveDialog() {
        val l = st.selected ?: return
        val cv = CurveView(this)
        val row = LinearLayout(this).apply {
            setPadding(0, d(4f), 0, d(8f))
        }

        val presets = listOf(
            "Linear" to floatArrayOf(0f, 0f, 1f, 1f),
            "Ease" to floatArrayOf(0.42f, 0f, 0.58f, 1f),
            "In" to floatArrayOf(0.42f, 0f, 1f, 1f),
            "Out" to floatArrayOf(0f, 0f, 0.58f, 1f),
            "Bounce" to floatArrayOf(0.34f, 1.56f, 0.64f, 1f),
            "Snappy" to floatArrayOf(0.05f, 0.9f, 0.1f, 1f)
        )
        for ((n, v) in presets) {
            row.addView(Button(this).apply {
                text = n
                textSize = 10f
                setOnClickListener { cv.set(v[0], v[1], v[2], v[3]) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(12f), d(8f), d(12f), d(8f))
            addView(Ui.text(this@EditorActivity, "Alight Motion / Keyflow Bezier Easing", 13f, Ui.ACCENT_CYAN, bold = true))
            addView(row)
            addView(cv)
        }

        AlertDialog.Builder(this)
            .setTitle("Keyframe Transition Curve")
            .setView(box)
            .setPositiveButton("Apply Curve") { _, _ ->
                val h = cv.handles()
                gl {
                    l.animatedProps().forEach { it.applyEase(h[0], h[1], h[2], h[3]) }
                }
                timeline.invalidate()
                Toast.makeText(this, "Bezier curve applied to keyframes", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showProps(title: String, props: List<Prop>) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(16f), d(4f), d(16f), d(8f))
        }
        for (pr in props) {
            val valText = Ui.text(this, "%.2f".format(pr.get(now())), 11f, Ui.MUTED)
            val lblRow = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(Ui.text(this@EditorActivity, pr.name, 13f, Color.WHITE, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                addView(valText)
            }
            box.addView(lblRow)
            box.addView(SeekBar(this).apply {
                max = 1000
                progress = (((pr.get(now()) - pr.min) / (pr.max - pr.min)) * 1000).toInt().coerceIn(0, 1000)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, v: Int, user: Boolean) {
                        if (!user) return
                        val value = pr.min + v / 1000f * (pr.max - pr.min)
                        val t = now()
                        val k = st.autoKey
                        gl { pr.set(t, value, k) }
                        valText.text = "%.2f".format(value)
                        timeline.invalidate()
                        updateKeyframeStatus()
                    }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
            })
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Done", null)
            .show()
    }

    private fun startMotionTracking() {
        val l = st.selected
        if (l == null || st.project.layers.none { it.source is Source.Video }) {
            Toast.makeText(this, "Select the overlay layer to track, with a video in project.", Toast.LENGTH_LONG).show()
        } else {
            st.trackPick = true
            Toast.makeText(this, "Tap the feature point to follow on video preview.", Toast.LENGTH_LONG).show()
        }
    }

    private fun startTrack(px: Float, py: Float) {
        st.trackPick = false
        val over = st.selected ?: return
        val vid = st.project.layers.firstOrNull { it.source is Source.Video } ?: return
        if (st.exporting) return
        st.exporting = true
        st.playing = false

        val t0 = now()
        val tr = vid.transform
        val u0 = ((px - tr.x.valueAt(t0)) / tr.scaleX.valueAt(t0) + tr.anchorX.valueAt(t0)) / vid.width
        val vq = ((py - tr.y.valueAt(t0)) / tr.scaleY.valueAt(t0) + tr.anchorY.valueAt(t0)) / vid.height
        val ox = over.transform.x.valueAt(t0) - px
        val oy = over.transform.y.valueAt(t0) - py
        val end = minOf(vid.outMs, st.project.durationMs)
        val uri = (vid.source as Source.Video).uri

        Toast.makeText(this, "Tracking motion…", Toast.LENGTH_SHORT).show()
        Thread {
            val res = runCatching { Tracker(applicationContext, uri).track(t0, end, st.project.fps, u0, 1f - vq) { ms -> vid.srcMs(ms) } }
            st.exporting = false
            res.onSuccess { pts ->
                gl {
                    for (r in pts) {
                        val t = r[0].toLong()
                        val vt = vid.transform
                        val tx = vt.x.valueAt(t) + (r[1] * vid.width - vt.anchorX.valueAt(t)) * vt.scaleX.valueAt(t)
                        val ty = vt.y.valueAt(t) + ((1f - r[2]) * vid.height - vt.anchorY.valueAt(t)) * vt.scaleY.valueAt(t)
                        over.transform.x.set(Keyframe(t, tx + ox, Interp.LINEAR))
                        over.transform.y.set(Keyframe(t, ty + oy, Interp.LINEAR))
                    }
                }
            }
            runOnUiThread {
                Toast.makeText(this, if (res.isSuccess) "Tracked ${res.getOrNull()?.size ?: 0} frames successfully." else "Tracking failed: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                timeline.invalidate()
            }
        }.start()
    }

    private fun bake() {
        val l = st.selected ?: return
        val src = l.source as? Source.Video ?: return
        if (st.exporting) return
        if (!l.reversed || l.remap.isAnimated) {
            Toast.makeText(this, "Bake: turn Reverse on first (without time remap).", Toast.LENGTH_LONG).show()
            return
        }
        st.playing = false
        st.exporting = true
        val segMs = ((l.outMs - l.inMs) * l.speed).toLong()
        val pj = st.project
        val tmp = Project(pj.width, pj.height, pj.fps, segMs)
        tmp.layers += Layer(-1, src, l.width, l.height, 0, segMs, trimMs = l.trimMs).apply {
            reversed = true
            transform.anchorX.base = l.width / 2
            transform.anchorY.base = l.height / 2
            transform.x.base = pj.width / 2f
            transform.y.base = pj.height / 2f
        }
        val out = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "proxy_${l.id}_${System.currentTimeMillis()}.mp4")
        Toast.makeText(this, "Baking fast reverse proxy…", Toast.LENGTH_SHORT).show()
        Thread {
            val ok = runCatching { Exporter(applicationContext, tmp).run(out, 16_000_000, true) {} }
            if (ok.isSuccess) gl {
                renderer.comp.drop(l.id)
                l.source = Source.Video(Uri.fromFile(out))
                l.trimMs = 0
                l.reversed = false
                l.audioUri = null
                l.srcDurMs = segMs
            }
            st.exporting = false
            runOnUiThread {
                Toast.makeText(this, if (ok.isSuccess) "Proxy baked. Reverse is now fast and smooth." else "Bake error: ${ok.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                timeline.invalidate()
            }
        }.start()
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        val uri = data?.data ?: return
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        if (req == 1) importVideo(uri) else if (req == 2) importPhoto(uri)
    }

    private fun importVideo(uri: Uri) {
        val r = MediaMetadataRetriever().apply { setDataSource(this@EditorActivity, uri) }
        fun k(key: Int) = r.extractMetadata(key)?.toLongOrNull() ?: 0L
        var w = k(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toFloat()
        var h = k(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toFloat()
        val rot = k(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
        val dur = k(MediaMetadataRetriever.METADATA_KEY_DURATION)
        r.release()

        if (rot == 90L || rot == 270L) {
            val t = w; w = h; h = t
        }
        val p = st.project
        val s = min(p.width / w, p.height / h)
        val layer = Layer(nextId++, Source.Video(uri), w * s, h * s, 0, dur).apply {
            name = "Video Clip"
            srcDurMs = dur
            audioUri = uri
            transform.anchorX.base = w * s / 2
            transform.anchorY.base = h * s / 2
            transform.x.base = p.width / 2f
            transform.y.base = p.height / 2f
        }
        if (p.layers.isEmpty() || dur > p.durationMs) p.durationMs = dur
        addLayer(layer)
    }

    private fun importPhoto(uri: Uri) {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        var ss = 1
        while (maxOf(opts.outWidth, opts.outHeight) / ss > 2048) ss *= 2
        var bmp = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = ss })
        } ?: return
        val rot = contentResolver.openInputStream(uri)?.use { s ->
            when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                6 -> 90f
                3 -> 180f
                8 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (rot != 0f) {
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(rot) }, true)
        }
        val p = st.project
        val s = min(p.width / bmp.width.toFloat(), p.height / bmp.height.toFloat())
        val l = Layer(nextId++, Source.Raster(bmp, true), bmp.width * s, bmp.height * s).apply {
            name = "Photo Graphic"
            transform.anchorX.base = width / 2
            transform.anchorY.base = height / 2
            transform.x.base = p.width / 2f
            transform.y.base = p.height / 2f
            outMs = maxOf(p.durationMs, 5000)
        }
        addLayer(l)
    }

    private fun save() {
        runCatching {
            ProjectIO.save(st.project, ProjectStore.file(this, pid))
            ProjectStore.saveMeta(this, pid, pname, st.project)
        }
    }

    private fun finishSave() {
        save()
        finish()
    }

    private fun renameDialog() {
        val et = EditText(this).apply { setText(pname); setTextColor(Color.WHITE) }
        AlertDialog.Builder(this)
            .setTitle("Rename Project")
            .setView(et)
            .setPositiveButton("OK") { _, _ ->
                if (et.text.isNotBlank()) {
                    pname = et.text.toString().trim()
                    titleView.text = pname
                    save()
                }
            }.show()
    }

    private fun canvasDialog() {
        val p = st.project
        CanvasDialog.show(this, p.width, p.height, p.fps, "Apply Canvas") { w, h, f ->
            p.width = w
            p.height = h
            p.fps = f
            resBadge.text = "${w}p ${f}fps"
            layoutPreview()
            gl { renderer.comp.resize(p, renderer.sw, renderer.sh) }
            Toast.makeText(this, "Canvas updated: $w × $h @ $f fps", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportDialog(v: TextView) {
        if (st.exporting || st.project.layers.isEmpty()) {
            Toast.makeText(this, "Nothing to export yet, or an export job is currently running.", Toast.LENGTH_SHORT).show()
            return
        }
        val p = st.project
        val is4K = (p.width >= 3840 || p.height >= 3840)
        val msg = "Resolution: ${p.width} × ${p.height} · ${p.fps} fps\n" +
                "Codec: ${if (Prefs.exportCodec == 1 || is4K) "HEVC (H.265 Hardware)" else "H.264"}\n" +
                "Target Bitrate: ${Prefs.exportMbps()} Mbps (VBR Hardware Accelerated)\n" +
                "Watermark: None (100% Free & Clean Export)"

        val options = arrayOf(
            "Export at Current Canvas (${p.width}×${p.height})",
            "Export 4K UHD (3840×2160)",
            "Export 2K QHD (2560×1440)",
            "Export 1080p FHD (1920×1080)"
        )

        AlertDialog.Builder(this)
            .setTitle("GPU Video Export (Watermark-Free)")
            .setMessage(msg)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> export(v, 1.0f)
                    1 -> {
                        val scale = 3840f / maxOf(p.width, p.height)
                        export(v, scale)
                    }
                    2 -> {
                        val scale = 2560f / maxOf(p.width, p.height)
                        export(v, scale)
                    }
                    3 -> {
                        val scale = 1920f / maxOf(p.width, p.height)
                        export(v, scale)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun export(v: TextView, scale: Float) {
        st.playing = false
        st.exporting = true
        save()
        val out = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "${pname.replace(Regex("[^A-Za-z0-9_-]"), "_")}_${System.currentTimeMillis()}.mp4")
        var last = -1
        val mime = if (Prefs.exportCodec == 1) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val bitrate = Prefs.exportMbps() * 1_000_000

        Thread {
            val res = runCatching {
                Exporter(applicationContext, st.project).run(out, bitrate, false, mime, scale) { f ->
                    val pc = (f * 100).toInt()
                    if (pc != last) {
                        last = pc
                        runOnUiThread { v.text = "Export $pc%" }
                    }
                }
            }
            if (res.isSuccess) runCatching { publish(out) }
            st.exporting = false
            runOnUiThread {
                v.text = "Export"
                Toast.makeText(
                    this,
                    if (res.isSuccess) "Export complete: Saved to Movies/NovaFX without watermark." else "Export failed: ${res.exceptionOrNull()?.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    private fun publish(f: File) {
        if (Build.VERSION.SDK_INT < 29) return
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/NovaFX")
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: return
        contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
    }

    override fun onPause() {
        super.onPause()
        st.playing = false
        ui.removeCallbacks(autosaver)
        save()
        glView.onPause()
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        if (Prefs.autosaveMin > 0) ui.postDelayed(autosaver, Prefs.autosaveMin * 60_000L)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        finishSave()
    }
}

class EditorRenderer(ctx: Context, private val st: EditorState) : GLSurfaceView.Renderer {
    val comp = Compositor(ctx.applicationContext)
    private var last = 0L
    @Volatile var sw = 1
    @Volatile var sh = 1

    override fun onSurfaceCreated(g: GL10?, c: EGLConfig?) {
        comp.release()
        comp.init()
    }

    override fun onSurfaceChanged(g: GL10?, w: Int, h: Int) {
        sw = w
        sh = h
        comp.resize(st.project, w, h)
    }

    override fun onDrawFrame(g: GL10?) {
        comp.lite = Prefs.liteEffects
        val n = System.nanoTime()
        if (st.playing) {
            if (last != 0L) {
                st.posMs += (n - last) / 1e6
                if (st.posMs >= st.project.durationMs) st.posMs = 0.0
            }
            last = n
        } else {
            last = 0
        }
        comp.draw(st.project, st.posMs.toLong())
    }
}
