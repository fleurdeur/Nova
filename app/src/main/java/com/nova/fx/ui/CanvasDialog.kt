package com.nova.fx.ui

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.*

/**
 * Canvas & Frame Rate Dialog supporting 4K UHD (3840x2160), 2K QHD, 1080p,
 * standard aspect ratios (16:9, 9:16 Shorts/Reels, 1:1, 4:5, 21:9), and 60fps/30fps.
 */
object CanvasDialog {
    private val ratios = listOf(
        "16:9 (Landscape)" to (16 to 9),
        "9:16 (Reels/TikTok)" to (9 to 16),
        "1:1 (Square)" to (1 to 1),
        "4:5 (Portrait)" to (4 to 5),
        "21:9 (Cinema)" to (21 to 9),
        "4:3 (Classic)" to (4 to 3)
    )
    private val resos = listOf(
        "4K UHD (2160p)" to 2160,
        "2K QHD (1440p)" to 1440,
        "1080p (FHD)" to 1080,
        "720p (HD)" to 720,
        "480p (SD)" to 480
    )
    private val fpsList = listOf(24, 30, 60)

    private fun even(v: Int) = (v.coerceIn(64, 4096) / 2) * 2

    fun show(ctx: Context, w0: Int, h0: Int, fps0: Int, okLabel: String, onOk: (Int, Int, Int) -> Unit) {
        fun num(et: EditText, d: Int) = et.text.toString().toIntOrNull() ?: d
        fun numEt(v: Int) = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(v.toString())
            setTextColor(-1)
            minEms = 4
        }
        val wEt = numEt(w0)
        val hEt = numEt(h0)
        val fEt = numEt(fps0)
        val summary = Ui.text(ctx, "", 13f, Ui.MUTED)
        val note = Ui.text(ctx, "", 12f, Ui.ACCENT_CYAN)
        var rw = w0
        var rh = h0

        fun refresh() {
            val w = num(wEt, w0)
            val h = num(hEt, h0)
            val f = num(fEt, fps0)
            val is4K = (w >= 3840 || h >= 3840) || (w * h >= 3840 * 2160 * 0.8)
            summary.text = "$w × $h  @  $f fps"
            note.text = if (is4K) {
                "⚡ 4K UHD Mode: GPU hardware acceleration and HEVC encoding enabled for Mali-G52 / MediaTek G88."
            } else {
                "✓ Hardware accelerated preview & export (Mali-G52 optimized)."
            }
        }

        fun setDims(a: Int, b: Int, short: Int) {
            val w = if (a >= b) even(short * a / b) else even(short)
            val h = if (a >= b) even(short) else even(short * b / a)
            wEt.setText(w.toString())
            hEt.setText(h.toString())
            refresh()
        }

        val watcher = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { refresh() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        wEt.addTextChangedListener(watcher)
        hEt.addTextChangedListener(watcher)
        fEt.addTextChangedListener(watcher)

        fun rowOf(items: List<View>) = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(ctx).apply { items.forEach { addView(it) } })
        }

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 18f), Ui.dp(ctx, 6f), Ui.dp(ctx, 18f), 0)
        }
        box.addView(Ui.text(ctx, "Aspect Ratio", 13f, Ui.MUTED, true))
        box.addView(rowOf(ratios.map { (n, r) ->
            Ui.chip(ctx, n) {
                rw = r.first
                rh = r.second
                setDims(rw, rh, minOf(num(wEt, w0), num(hEt, h0)))
            }
        }))
        box.addView(Ui.text(ctx, "Resolution Tier", 13f, Ui.MUTED, true).apply { setPadding(0, Ui.dp(ctx, 8f), 0, 0) })
        box.addView(rowOf(resos.map { (n, v) ->
            Ui.chip(ctx, n) { setDims(rw, rh, v) }
        }))
        box.addView(Ui.text(ctx, "Custom Dimensions (px)", 13f, Ui.MUTED, true).apply { setPadding(0, Ui.dp(ctx, 8f), 0, 0) })
        box.addView(LinearLayout(ctx).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(wEt)
            addView(Ui.text(ctx, "  ×  ", 16f))
            addView(hEt)
        })
        box.addView(Ui.text(ctx, "Frame Rate (fps)", 13f, Ui.MUTED, true).apply { setPadding(0, Ui.dp(ctx, 8f), 0, 0) })
        box.addView(rowOf(fpsList.map { f ->
            Ui.chip(ctx, "$f fps") { fEt.setText(f.toString()) }
        }))
        box.addView(summary.apply { setPadding(0, Ui.dp(ctx, 8f), 0, 0) })
        box.addView(note.apply { setPadding(0, Ui.dp(ctx, 4f), 0, Ui.dp(ctx, 8f)) })
        refresh()

        AlertDialog.Builder(ctx)
            .setTitle("Canvas & 4K Settings")
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton(okLabel) { _, _ ->
                onOk(even(num(wEt, w0)), even(num(hEt, h0)), num(fEt, fps0).coerceIn(1, 120))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
