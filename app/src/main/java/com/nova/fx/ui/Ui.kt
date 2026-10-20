package com.nova.fx.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.nova.fx.Prefs

/**
 * Modern dark-mode UI kit inspired by Alight Motion, Keyflow, and Aurea.
 * Lightweight, zero-dependency, GPU/view-friendly layout primitives.
 */
object Ui {
    val BG = 0xFF0B0C10.toInt()
    val PANEL = 0xFF14151B.toInt()
    val CARD = 0xFF1C1E26.toInt()
    val CHIP = 0xFF252834.toInt()
    val CHIP_ACTIVE = 0xFF35394A.toInt()
    val MUTED = 0xFF8E92A4.toInt()
    val ACCENT_CYAN = 0xFF00E5FF.toInt()
    val ACCENT_VIOLET = 0xFF7C4DFF.toInt()
    val SUCCESS_GREEN = 0xFF00E676.toInt()

    private val palette = intArrayOf(
        0xFF7C4DFF.toInt(), // Vivid Violet
        0xFF00E5FF.toInt(), // Neon Cyan
        0xFFFF5252.toInt(), // Coral Red
        0xFFFFB300.toInt(), // Amber
        0xFF00E676.toInt()  // Emerald
    )
    val paletteNames = listOf("Violet", "Cyan", "Coral", "Amber", "Emerald")

    fun accent() = palette[Prefs.accent.coerceIn(0, palette.size - 1)]
    fun dp(c: Context, v: Float) = (v * c.resources.displayMetrics.density).toInt()

    fun round(c: Context, color: Int, r: Float = 12f) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = r * c.resources.displayMetrics.density
    }

    fun stroke(c: Context, color: Int, strokeColor: Int, strokeDp: Float = 1f, r: Float = 12f) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = r * c.resources.displayMetrics.density
        setStroke((strokeDp * c.resources.displayMetrics.density).toInt(), strokeColor)
    }

    fun text(c: Context, s: String, size: Float = 14f, color: Int = Color.WHITE, bold: Boolean = false) = TextView(c).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    fun chip(c: Context, s: String, selected: Boolean = false, onClick: (TextView) -> Unit) = TextView(c).apply {
        text = s
        textSize = 12.5f
        setTextColor(if (selected) Color.WHITE else Color.WHITE)
        gravity = Gravity.CENTER
        background = if (selected) round(c, accent(), 10f) else round(c, CHIP, 10f)
        setPadding(dp(c, 12f), dp(c, 8f), dp(c, 12f), dp(c, 8f))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
            setMargins(dp(c, 3f), dp(c, 4f), dp(c, 3f), dp(c, 4f))
        }
        setOnClickListener {
            if (Prefs.haptics) it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick(this)
        }
    }

    fun badge(c: Context, text: String, color: Int = ACCENT_CYAN) = TextView(c).apply {
        this.text = text
        textSize = 10f
        setTextColor(color)
        setTypeface(typeface, Typeface.BOLD)
        background = stroke(c, 0x2200E5FF.toInt(), color, 1f, 6f)
        setPadding(dp(c, 6f), dp(c, 2f), dp(c, 6f), dp(c, 2f))
        gravity = Gravity.CENTER
    }

    fun card(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = round(c, CARD, 14f)
        setPadding(dp(c, 14f), dp(c, 12f), dp(c, 14f), dp(c, 12f))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, dp(c, 6f), 0, dp(c, 6f))
        }
    }
}
