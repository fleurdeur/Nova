package com.nova.fx

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.*
import com.nova.fx.model.Presets
import com.nova.fx.model.ProjectStore
import com.nova.fx.ui.CanvasDialog
import com.nova.fx.ui.Ui

/**
 * Launcher & Hub: Home, Projects, Presets & LUTs, and Hardware Performance Settings.
 * Optimized for MediaTek Helio G88 / Mali-G52 GPU with 4K rendering profiles.
 */
class HomeActivity : Activity() {
    private lateinit var body: FrameLayout
    private val tabs = ArrayList<TextView>()
    private var cur = 0
    private fun d(v: Float) = Ui.dp(this, v)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        Prefs.init(this)

        val bar = LinearLayout(this).apply {
            setBackgroundColor(Ui.PANEL)
            setPadding(0, d(4f), 0, d(6f))
        }
        for ((i, n) in listOf("Home", "Projects", "Presets & LUTs", "Settings").withIndex()) {
            val t = Ui.text(this, n, 13f, Ui.MUTED, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, d(12f), 0, d(12f))
                setOnClickListener { show(i) }
            }
            tabs += t
            bar.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        }

        body = FrameLayout(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(bar)
        }
        setContentView(root)
        show(0)
    }

    override fun onResume() {
        super.onResume()
        show(cur)
    }

    private fun show(i: Int) {
        cur = i
        tabs.forEachIndexed { k, t ->
            t.setTextColor(if (k == i) Ui.accent() else Ui.MUTED)
        }
        val col = when (i) {
            0 -> home()
            1 -> projects()
            2 -> presets()
            else -> settings()
        }
        body.removeAllViews()
        body.addView(ScrollView(this).apply { addView(col) })
    }

    private fun page() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(d(18f), d(20f), d(18f), d(24f))
    }

    private fun heading(p: LinearLayout, t: String) = p.addView(
        Ui.text(this, t, 12f, Ui.ACCENT_CYAN, true).apply {
            setPadding(0, d(18f), 0, d(6f))
        }
    )

    private fun open(id: String) = startActivity(Intent(this, EditorActivity::class.java).putExtra("pid", id))
    private fun create(w: Int, h: Int, f: Int) {
        open(ProjectStore.create(this, ProjectStore.nextName(this), w, h, f))
    }

    private fun ratioDims(rw: Int, rh: Int, base: Int = 1920): Pair<Int, Int> {
        fun ev(v: Int) = (v / 2) * 2
        return if (rw >= rh) Pair(ev(base), ev(base * rh / rw)) else Pair(ev(base * rw / rh), ev(base))
    }

    // ---------------- Home Screen ----------------
    private fun home(): View {
        val p = page()

        // Hero Header
        p.addView(Ui.text(this, "NovaFX Studio", 28f, Color.WHITE, bold = true))
        p.addView(Ui.text(this, "GPU-Accelerated 4K Video & Motion Graphics Editor", 13f, Ui.MUTED))

        // Hardware Chipset Badge
        val chipBadge = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, d(6f), 0, d(12f))
            addView(Ui.badge(this@HomeActivity, "⚡ Mali-G52 GPU Accelerated", Ui.ACCENT_CYAN))
            val sp = View(this@HomeActivity).apply { layoutParams = LinearLayout.LayoutParams(d(8f), 1) }
            addView(sp)
            addView(Ui.badge(this@HomeActivity, "✓ Free • No Watermark", Ui.SUCCESS_GREEN))
        }
        p.addView(chipBadge)

        // New Project Button
        val nc = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = Ui.round(this@HomeActivity, Ui.accent(), 16f)
            setPadding(0, d(20f), 0, d(20f))
            addView(Ui.text(this@HomeActivity, "＋  New 4K Project", 17f, Color.WHITE, bold = true))
            setOnClickListener {
                CanvasDialog.show(this@HomeActivity, Prefs.defW, Prefs.defH, Prefs.defFps, "Create") { w, h, f ->
                    create(w, h, f)
                }
            }
        }
        nc.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(6f) }
        p.addView(nc)

        // Quick Start Aspect Ratios
        heading(p, "QUICK START RATIOS")
        val ratios = listOf(
            "16:9 YouTube / 4K" to (16 to 9),
            "9:16 Shorts / Reels" to (9 to 16),
            "1:1 Square" to (1 to 1),
            "4:5 Feed Post" to (4 to 5),
            "21:9 UltraWide Cinema" to (21 to 9)
        )
        p.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(this@HomeActivity).apply {
                for ((n, r) in ratios) {
                    addView(Ui.chip(this@HomeActivity, n) {
                        val (w, h) = ratioDims(r.first, r.second, 1920)
                        create(w, h, Prefs.defFps)
                    })
                }
            })
        })

        // Recent Projects
        heading(p, "RECENT PROJECTS")
        val recent = ProjectStore.list(this).take(3)
        if (recent.isEmpty()) {
            p.addView(Ui.card(this).apply {
                addView(Ui.text(this@HomeActivity, "No projects yet. Tap 'New 4K Project' above to start editing.", 13f, Ui.MUTED))
            })
        } else {
            recent.forEach { p.addView(projectCard(it)) }
        }

        // Hardware Diagnostics
        heading(p, "HARDWARE ACCELERATION")
        p.addView(Ui.card(this).apply {
            addView(Ui.text(this@HomeActivity, "Device: ${Build.MANUFACTURER} ${Build.MODEL}", 13f, Color.WHITE, bold = true))
            addView(Ui.text(this@HomeActivity, "Chipset: ${Build.HARDWARE} (Optimized for Mali-G52 / Helio G88)", 12f, Ui.MUTED))
            addView(Ui.text(this@HomeActivity, "Graphics: OpenGL ES 3.0 • Direct OES 2-Pass Pipeline", 12f, Ui.ACCENT_CYAN))
            addView(Ui.text(this@HomeActivity, "Export Engine: Hardware VBR H.265 / H.264 up to 4K UHD", 12f, Ui.SUCCESS_GREEN))
        })

        return p
    }

    // ---------------- Projects Screen ----------------
    private fun projectCard(m: ProjectStore.Meta): View = Ui.card(this).apply {
        val titleRow = LinearLayout(this@HomeActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.text(this@HomeActivity, m.name, 16f, Color.WHITE, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            if (m.w >= 3840 || m.h >= 3840) {
                addView(Ui.badge(this@HomeActivity, "4K UHD", Ui.ACCENT_CYAN))
            } else if (m.w >= 2560 || m.h >= 2560) {
                addView(Ui.badge(this@HomeActivity, "2K QHD", Ui.ACCENT_CYAN))
            } else {
                addView(Ui.badge(this@HomeActivity, "${m.w}×${m.h}", Ui.MUTED))
            }
        }
        addView(titleRow)
        addView(Ui.text(
            this@HomeActivity,
            "${m.fps} fps · ${m.layers} layers · Modified ${DateFormat.format("MMM d, HH:mm", m.modified)}",
            12f,
            Ui.MUTED
        ))
        setOnClickListener { open(m.id) }
        setOnLongClickListener { projectMenu(m); true }
    }

    private fun projects(): View {
        val p = page()
        p.addView(Ui.text(this, "Projects", 26f, Color.WHITE, bold = true))
        p.addView(Ui.text(this, "Tap to open, long-press for options (duplicate, rename, delete).", 13f, Ui.MUTED))
        val all = ProjectStore.list(this)
        if (all.isEmpty()) {
            p.addView(Ui.card(this).apply {
                addView(Ui.text(this@HomeActivity, "No projects yet. Create your first project from the Home tab.", 13f, Ui.MUTED))
            })
        } else {
            all.forEach { p.addView(projectCard(it)) }
        }
        return p
    }

    private fun projectMenu(m: ProjectStore.Meta) {
        AlertDialog.Builder(this)
            .setTitle(m.name)
            .setItems(arrayOf("Rename", "Duplicate", "Delete")) { _, i ->
                when (i) {
                    0 -> {
                        val et = EditText(this).apply { setText(m.name); setTextColor(Color.WHITE) }
                        AlertDialog.Builder(this)
                            .setTitle("Rename")
                            .setView(et)
                            .setPositiveButton("OK") { _, _ ->
                                if (et.text.isNotBlank()) {
                                    ProjectStore.rename(this, m.id, et.text.toString().trim())
                                    show(cur)
                                }
                            }.show()
                    }
                    1 -> {
                        ProjectStore.duplicate(this, m.id)
                        show(cur)
                    }
                    else -> AlertDialog.Builder(this)
                        .setTitle("Delete \"${m.name}\"?")
                        .setMessage("This will delete the project file permanently.")
                        .setPositiveButton("Delete") { _, _ ->
                            ProjectStore.delete(this, m.id)
                            show(cur)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }.show()
    }

    // ---------------- Presets & 3D LUTs Screen ----------------
    private fun presets(): View {
        val p = page()
        p.addView(Ui.text(this, "Presets & 3D LUTs", 26f, Color.WHITE, bold = true))
        p.addView(Ui.text(this, "Cinematic color grades and After Effects motion animation transitions.", 13f, Ui.MUTED))

        val favs = Prefs.favs()
        fun entry(name: String, desc: String): View = Ui.card(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val col = LinearLayout(this@HomeActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.text(this@HomeActivity, name, 15f, Color.WHITE, bold = true))
                addView(Ui.text(this@HomeActivity, desc, 12f, Ui.MUTED))
            }
            addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Ui.text(this@HomeActivity, if (name in favs) "★" else "☆", 22f, Ui.accent()).apply {
                setPadding(d(10f), 0, d(4f), 0)
                setOnClickListener {
                    Prefs.toggleFav(name)
                    show(2)
                }
            })
        }

        heading(p, "CINEMATIC 3D LUTS & LOOKS")
        Presets.looks.forEach { p.addView(entry(it.name, it.desc)) }

        heading(p, "AFTER EFFECTS & ALIGHT MOTION ANIMATIONS")
        Presets.anims.forEach { p.addView(entry(it.name, it.desc)) }

        return p
    }

    // ---------------- Settings Screen ----------------
    private fun row(title: String, value: String, onClick: () -> Unit): View = Ui.card(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val col = LinearLayout(this@HomeActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(this@HomeActivity, title, 15f, Color.WHITE, bold = true))
            addView(Ui.text(this@HomeActivity, value, 12f, Ui.MUTED))
        }
        addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        addView(Ui.text(this@HomeActivity, "›", 22f, Ui.MUTED))
        setOnClickListener { onClick() }
    }

    private fun settings(): View {
        val p = page()
        p.addView(Ui.text(this, "Settings", 26f, Color.WHITE, bold = true))
        p.addView(Ui.text(this, "Hardware tuning for MediaTek Helio G88 / Mali-G52 GPU.", 13f, Ui.MUTED))

        heading(p, "PERFORMANCE PROFILE")
        val profDesc = when (Prefs.exportQuality) {
            0 -> "Battery Saver (Low GPU power, 360p preview)"
            1 -> "Balanced (Recommended for Mali-G52 / Helio G88)"
            else -> "Ultra 4K Production (Full resolution preview & 4K export)"
        }
        p.addView(row("Optimization Profile", profDesc) {
            AlertDialog.Builder(this)
                .setTitle("Select GPU Profile")
                .setItems(arrayOf(
                    "Battery Saver",
                    "Balanced (Mali-G52 / Helio G88 Recommended)",
                    "Ultra 4K Production"
                )) { _, idx ->
                    Prefs.applyProfile(idx)
                    show(cur)
                }.show()
        })

        heading(p, "4K VIDEO & EXPORT")
        p.addView(row("Export Codec", if (Prefs.exportCodec == 1) "HEVC (H.265 Hardware - Fast & Efficient)" else "AVC (H.264)") {
            AlertDialog.Builder(this)
                .setTitle("Hardware Encoder Codec")
                .setItems(arrayOf("H.264 (AVC)", "HEVC (H.265 Hardware)")) { _, idx ->
                    Prefs.exportCodec = idx
                    show(cur)
                }.show()
        })

        p.addView(row("Target Bitrate", "${Prefs.exportMbps()} Mbps") {
            val rates = arrayOf("12 Mbps (Fast 1080p)", "24 Mbps (High Quality)", "35 Mbps (4K Standard)", "45 Mbps (4K Ultra Cinematic)", "60 Mbps (Mastering)")
            val vals = arrayOf(12, 24, 35, 45, 60)
            AlertDialog.Builder(this)
                .setTitle("Target Video Bitrate")
                .setItems(rates) { _, idx ->
                    Prefs.exportQuality = 3
                    Prefs.exportCustomMbps = vals[idx]
                    show(cur)
                }.show()
        })

        heading(p, "WATERMARK STATUS")
        p.addView(Ui.card(this).apply {
            addView(Ui.text(this@HomeActivity, "✓ Watermark-Free Guarantee", 15f, Ui.SUCCESS_GREEN, bold = true))
            addView(Ui.text(this@HomeActivity, "NovaFX exports 100% clean, unwatermarked videos free of charge.", 12f, Ui.MUTED))
        })

        heading(p, "GENERAL")
        p.addView(row("Theme Accent Color", Ui.paletteNames[Prefs.accent]) {
            AlertDialog.Builder(this)
                .setTitle("Theme Accent")
                .setItems(Ui.paletteNames.toTypedArray()) { _, idx ->
                    Prefs.accent = idx
                    show(cur)
                }.show()
        })

        p.addView(row("Haptic Feedback", if (Prefs.haptics) "Enabled" else "Disabled") {
            Prefs.haptics = !Prefs.haptics
            show(cur)
        })

        return p
    }
}
