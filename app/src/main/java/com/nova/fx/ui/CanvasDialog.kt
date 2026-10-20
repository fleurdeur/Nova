package com.nova.fx.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*

/**
 * Alight Motion Signature "New Project" / Canvas Setup Dialog.
 * Accurate visual aspect ratio selector cards (16:9, 9:16, 4:5, 1:1, 4:3, 21:9),
 * resolution tier buttons (4K UHD 2160p, 2K QHD 1440p, 1080p FHD, 720p HD),
 * frame rates (60, 30, 24, 12 fps), background color swatches, and prominent Alight Motion green CTA.
 */
object CanvasDialog {
    data class Ratio(val label: String, val w: Int, val h: Int, val desc: String)

    val ratios = listOf(
        Ratio("16:9", 16, 9, "Landscape / YouTube"),
        Ratio("9:16", 9, 16, "Portrait / Reels / TikTok"),
        Ratio("4:5", 4, 5, "Feed / Instagram"),
        Ratio("1:1", 1, 1, "Square"),
        Ratio("4:3", 4, 3, "Classic Standard"),
        Ratio("21:9", 21, 9, "Cinema Scope")
    )

    private val resos = listOf(
        "2160p 4K UHD" to 2160,
        "1440p 2K QHD" to 1440,
        "1080p FHD" to 1080,
        "720p HD" to 720,
        "480p SD" to 480
    )

    private val fpsList = listOf(60, 30, 24, 12)

    private val bgColors = listOf(
        "Black" to 0xFF000000.toInt(),
        "Dark Grey" to 0xFF191C24.toInt(),
        "Light Grey" to 0xFFD1D5DB.toInt(),
        "White" to 0xFFFFFFFF.toInt(),
        "Chroma Green" to 0xFF00FF00.toInt(),
        "Transparent" to 0x00000000
    )

    private fun even(v: Int) = (v.coerceIn(64, 4096) / 2) * 2

    fun show(
        ctx: Context,
        w0: Int,
        h0: Int,
        fps0: Int,
        okLabel: String = "CREATE PROJECT",
        onOk: (Int, Int, Int) -> Unit
    ) {
        show(ctx, w0, h0, fps0, okLabel, "Project") { w, h, f, _ -> onOk(w, h, f) }
    }

    fun show(
        ctx: Context,
        w0: Int,
        h0: Int,
        fps0: Int,
        okLabel: String = "CREATE PROJECT",
        defaultName: String = "Project",
        onOk: (Int, Int, Int, String) -> Unit
    ) {
        var currentW = w0
        var currentH = h0
        var currentFps = fps0
        var selectedRatio = ratios.firstOrNull { it.w * h0 == it.h * w0 } ?: ratios[0]
        var selectedRes = minOf(w0, h0)

        val d = AlertDialog.Builder(ctx).create()
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.CARD)
            setPadding(Ui.dp(ctx, 20f), Ui.dp(ctx, 20f), Ui.dp(ctx, 20f), Ui.dp(ctx, 20f))
        }

        // Header: "New Project"
        val headerRow = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.text(ctx, "New Project", 20f, Color.WHITE, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            val closeBtn = Ui.text(ctx, "✕", 18f, Ui.MUTED).apply {
                setPadding(Ui.dp(ctx, 8f), Ui.dp(ctx, 4f), Ui.dp(ctx, 8f), Ui.dp(ctx, 4f))
                setOnClickListener { d.dismiss() }
            }
            addView(closeBtn)
        }
        root.addView(headerRow)

        // Project Name Input
        val nameInput = EditText(ctx).apply {
            setText(defaultName)
            setTextColor(Color.WHITE)
            textSize = 15f
            setHintTextColor(Ui.MUTED)
            hint = "Project Name"
            background = Ui.stroke(ctx, Ui.CHIP, Ui.CARD_BORDER, 1f, 10f)
            setPadding(Ui.dp(ctx, 14f), Ui.dp(ctx, 10f), Ui.dp(ctx, 14f), Ui.dp(ctx, 10f))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, Ui.dp(ctx, 14f), 0, Ui.dp(ctx, 12f))
            }
        }
        root.addView(nameInput)

        // Aspect Ratio Section
        root.addView(Ui.text(ctx, "ASPECT RATIO", 11.5f, Ui.MUTED, bold = true).apply {
            setPadding(0, Ui.dp(ctx, 4f), 0, Ui.dp(ctx, 6f))
        })

        val ratioContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, Ui.dp(ctx, 10f))
        }
        val ratioScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(ratioContainer)
        }
        root.addView(ratioScroll)

        // Resolution Section
        root.addView(Ui.text(ctx, "RESOLUTION", 11.5f, Ui.MUTED, bold = true).apply {
            setPadding(0, Ui.dp(ctx, 4f), 0, Ui.dp(ctx, 6f))
        })
        val resContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, Ui.dp(ctx, 10f))
        }
        val resScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(resContainer)
        }
        root.addView(resScroll)

        // Frame Rate Section
        root.addView(Ui.text(ctx, "FRAME RATE", 11.5f, Ui.MUTED, bold = true).apply {
            setPadding(0, Ui.dp(ctx, 4f), 0, Ui.dp(ctx, 6f))
        })
        val fpsContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, Ui.dp(ctx, 10f))
        }
        root.addView(fpsContainer)

        // Background Color Swatches
        root.addView(Ui.text(ctx, "BACKGROUND", 11.5f, Ui.MUTED, bold = true).apply {
            setPadding(0, Ui.dp(ctx, 4f), 0, Ui.dp(ctx, 6f))
        })
        val bgContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, Ui.dp(ctx, 14f))
        }
        root.addView(bgContainer)

        // Dimensions Summary Tag
        val summaryBadge = TextView(ctx).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Ui.stroke(ctx, Ui.CHIP, Ui.CARD_BORDER, 1f, 8f)
            setPadding(Ui.dp(ctx, 10f), Ui.dp(ctx, 8f), Ui.dp(ctx, 10f), Ui.dp(ctx, 8f))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, 0, 0, Ui.dp(ctx, 16f))
            }
        }
        root.addView(summaryBadge)

        // Alight Motion CTA Button
        val createBtn = Ui.primaryButton(ctx, okLabel) {
            val finalName = nameInput.text.toString().trim().ifEmpty { defaultName }
            d.dismiss()
            onOk(currentW, currentH, currentFps, finalName)
        }
        root.addView(createBtn)

        fun updateDimensions() {
            val rw = selectedRatio.w
            val rh = selectedRatio.h
            val base = selectedRes
            if (rw >= rh) {
                currentH = even(base)
                currentW = even(base * rw / rh)
            } else {
                currentW = even(base)
                currentH = even(base * rh / rw)
            }
            val is4k = currentW >= 3840 || currentH >= 3840
            val hwLabel = if (is4k) "⚡ 4K UHD • Mali-G52 Accelerated" else "✓ Hardware Rendered"
            summaryBadge.text = "${selectedRatio.label} (${currentW} × ${currentH})  •  $currentFps fps  •  $hwLabel"
        }

        // Populate Aspect Ratio Cards
        fun refreshRatioViews() {
            ratioContainer.removeAllViews()
            for (r in ratios) {
                val isSel = r == selectedRatio
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = if (isSel) {
                        Ui.stroke(ctx, 0x1A00DF82.toInt(), Ui.ACCENT_GREEN, 2f, 12f)
                    } else {
                        Ui.stroke(ctx, Ui.CHIP, Ui.CARD_BORDER, 1f, 12f)
                    }
                    setPadding(Ui.dp(ctx, 14f), Ui.dp(ctx, 12f), Ui.dp(ctx, 14f), Ui.dp(ctx, 12f))
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                        setMargins(0, 0, Ui.dp(ctx, 8f), 0)
                    }

                    // Wireframe aspect ratio box
                    val boxW = if (r.w >= r.h) 36f else (36f * r.w / r.h).coerceAtLeast(18f)
                    val boxH = if (r.h >= r.w) 36f else (36f * r.h / r.w).coerceAtLeast(18f)
                    val wireframe = View(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, boxW), Ui.dp(ctx, boxH)).apply {
                            gravity = Gravity.CENTER_HORIZONTAL
                            setMargins(0, 0, 0, Ui.dp(ctx, 6f))
                        }
                        background = Ui.stroke(
                            ctx,
                            if (isSel) 0x3300DF82.toInt() else 0x1AFFFFFF.toInt(),
                            if (isSel) Ui.ACCENT_GREEN else Ui.MUTED,
                            1.5f,
                            4f
                        )
                    }
                    addView(wireframe)

                    val lbl = Ui.text(ctx, r.label, 13f, if (isSel) Ui.ACCENT_GREEN else Color.WHITE, bold = true).apply {
                        gravity = Gravity.CENTER
                    }
                    addView(lbl)

                    setOnClickListener {
                        selectedRatio = r
                        refreshRatioViews()
                        updateDimensions()
                    }
                }
                ratioContainer.addView(card)
            }
        }

        // Populate Resolution Chips
        fun refreshResViews() {
            resContainer.removeAllViews()
            for ((label, valPx) in resos) {
                val isSel = valPx == selectedRes
                val chip = Ui.chip(ctx, label, selected = isSel) {
                    selectedRes = valPx
                    refreshResViews()
                    updateDimensions()
                }
                resContainer.addView(chip)
            }
        }

        // Populate FPS Chips
        fun refreshFpsViews() {
            fpsContainer.removeAllViews()
            for (fps in fpsList) {
                val isSel = fps == currentFps
                val chip = Ui.chip(ctx, "$fps fps", selected = isSel) {
                    currentFps = fps
                    refreshFpsViews()
                    updateDimensions()
                }
                fpsContainer.addView(chip)
            }
        }

        // Populate Background Swatches
        var selBg = 0
        fun refreshBgViews() {
            bgContainer.removeAllViews()
            for ((idx, bgItem) in bgColors.withIndex()) {
                val (name, color) = bgItem
                val isSel = idx == selBg
                val dot = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    val circle = View(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, 32f), Ui.dp(ctx, 32f))
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(if (color == 0) 0xFF333333.toInt() else color)
                            if (isSel) {
                                setStroke(Ui.dp(ctx, 3f), Ui.ACCENT_GREEN)
                            } else {
                                setStroke(Ui.dp(ctx, 1f), 0x55FFFFFF.toInt())
                            }
                        }
                    }
                    addView(circle)
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                        setMargins(0, 0, Ui.dp(ctx, 12f), 0)
                    }
                    setOnClickListener {
                        selBg = idx
                        refreshBgViews()
                    }
                }
                bgContainer.addView(dot)
            }
        }

        refreshRatioViews()
        refreshResViews()
        refreshFpsViews()
        refreshBgViews()
        updateDimensions()

        d.setView(ScrollView(ctx).apply { addView(root) })
        d.show()
    }
}
