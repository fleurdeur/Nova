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
 * Alight Motion Signature UI Design System.
 * Authentic deep charcoal dark theme (#11141A), Alight Motion Emerald Green (#00DF82),
 * sleek card containers (#1E222D), rounded aspect ratio wireframes, and smooth haptic feedback.
 */
object Ui {
    val BG = 0xFF11141A.toInt()          // Alight Motion deep charcoal background
    val PANEL = 0xFF181B23.toInt()       // Navigation / Top bar panel
    val CARD = 0xFF1E222D.toInt()        // Card container
    val CARD_BORDER = 0xFF2A303E.toInt() // Subtle border
    val CHIP = 0xFF252936.toInt()        // Inactive chip background
    val CHIP_ACTIVE = 0xFF00DF82.toInt() // Active chip
    val MUTED = 0xFF8E95A5.toInt()       // Muted secondary text
    val MUTED_DARK = 0xFF585E70.toInt()  // Subtle helper text
    val ACCENT_GREEN = 0xFF00DF82.toInt() // Signature Alight Motion Emerald Green
    val ACCENT_CYAN = 0xFF00E5FF.toInt()  // Secondary Cyan
    val ACCENT_YELLOW = 0xFFFFD600.toInt()// Accent Amber/Yellow
    val ACCENT_VIOLET = 0xFF9D65FF.toInt()
    val SUCCESS_GREEN = 0xFF00DF82.toInt()

    private val palette = intArrayOf(
        0xFF00DF82.toInt(), // Alight Motion Emerald Green (Default)
        0xFF00E5FF.toInt(), // Electric Cyan
        0xFF9D65FF.toInt(), // Motion Violet
        0xFFFF5252.toInt(), // Coral
        0xFFFFD600.toInt()  // Amber
    )
    val paletteNames = listOf("Alight Motion Green", "Electric Cyan", "Motion Violet", "Coral", "Amber")

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

    fun gradient(c: Context, startColor: Int, endColor: Int, r: Float = 16f) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(startColor, endColor)
    ).apply {
        cornerRadius = r * c.resources.displayMetrics.density
    }

    fun text(c: Context, s: String, size: Float = 14f, color: Int = Color.WHITE, bold: Boolean = false) = TextView(c).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    fun chip(c: Context, s: String, selected: Boolean = false, onClick: (TextView) -> Unit) = TextView(c).apply {
        text = s
        textSize = 12f
        setTextColor(if (selected) 0xFF081710.toInt() else Color.WHITE)
        gravity = Gravity.CENTER
        background = if (selected) round(c, ACCENT_GREEN, 12f) else stroke(c, CHIP, CARD_BORDER, 1f, 12f)
        if (selected) setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(c, 13f), dp(c, 8f), dp(c, 13f), dp(c, 8f))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
            setMargins(dp(c, 3f), dp(c, 4f), dp(c, 3f), dp(c, 4f))
        }
        setOnClickListener {
            if (Prefs.haptics) it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick(this)
        }
    }

    fun primaryButton(c: Context, text: String, onClick: () -> Unit) = TextView(c).apply {
        this.text = text
        textSize = 15f
        setTextColor(0xFF091811.toInt())
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        background = round(c, ACCENT_GREEN, 14f)
        setPadding(dp(c, 16f), dp(c, 14f), dp(c, 16f), dp(c, 14f))
        minimumHeight = dp(c, 48f)
        setOnClickListener {
            if (Prefs.haptics) it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        }
    }

    fun badge(c: Context, text: String, color: Int = ACCENT_GREEN) = TextView(c).apply {
        this.text = text
        textSize = 10f
        setTextColor(color)
        setTypeface(typeface, Typeface.BOLD)
        background = stroke(c, 0x1A000000 or (color and 0x00FFFFFF), color, 1f, 8f)
        setPadding(dp(c, 7f), dp(c, 3f), dp(c, 7f), dp(c, 3f))
        gravity = Gravity.CENTER
    }

    fun card(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = stroke(c, CARD, CARD_BORDER, 1f, 16f)
        setPadding(dp(c, 16f), dp(c, 14f), dp(c, 16f), dp(c, 14f))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, dp(c, 6f), 0, dp(c, 6f))
        }
    }
}
