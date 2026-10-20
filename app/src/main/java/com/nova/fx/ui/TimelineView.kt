package com.nova.fx.ui

import android.content.Context
import android.graphics.*
import android.view.*
import com.nova.fx.EditorState
import com.nova.fx.model.*
import kotlin.math.abs
import kotlin.math.max

/**
 * Alight Motion / Keyflow style multi-track visual timeline.
 * Features:
 *  - Color-coded clips by layer type (Video, Photo, Shape, Text, Particles)
 *  - Visual diamond keyframe markers (◆) drawn directly on layer clips
 *  - Trimming handles on left and right clip boundaries
 *  - Pinch-to-zoom timeline scale, magnetic playhead snapping, and smooth scrub
 */
class TimelineView(c: Context, private val st: EditorState, private val onChange: () -> Unit) : View(c) {
    private enum class Mode { NONE, SCRUB, PAN, MOVE, TRIML, TRIMR }

    private val dp = resources.displayMetrics.density
    private var pps = 80f // Pixels per second
    private var off = 0f
    private val rulerH = 26 * dp
    private val rowH = 36 * dp
    private val edge = 20 * dp

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint().apply { color = 0xFF22242F.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 10.5f * dp
        typeface = Typeface.DEFAULT_BOLD
    }
    private val rulerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8E92A4.toInt()
        textSize = 9 * dp
    }
    private val headPaint = Paint().apply {
        color = 0xFFFF3B30.toInt() // Vivid red playhead needle
        strokeWidth = 2 * dp
    }
    private val headCapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF3B30.toInt()
    }
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF00E5FF.toInt() // Neon cyan selection border
        strokeWidth = 2.5f * dp
    }
    private val keyframePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }

    private var mode = Mode.NONE
    private var target: Layer? = null
    private var lastX = 0f

    private val scale = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(g: ScaleGestureDetector): Boolean {
            pps = (pps * g.scaleFactor).coerceIn(15f, 600f)
            invalidate()
            return true
        }
    })

    override fun onDraw(cv: Canvas) {
        cv.drawColor(0xFF0F1015.toInt())
        val dur = st.project.durationMs

        // Time ruler background
        val rulerRect = RectF(0f, 0f, width.toFloat(), rulerH)
        barPaint.color = 0xFF14151B.toInt()
        cv.drawRect(rulerRect, barPaint)

        // Seconds markers
        val startSec = (off / pps).toInt()
        val endSec = minOf(((off + width) / pps).toInt() + 1, (dur / 1000).toInt())
        for (s in startSec..endSec) {
            val x = s * pps - off
            cv.drawLine(x, rulerH - 6 * dp, x, height.toFloat(), gridPaint)
            cv.drawText("${s}s", x + 3 * dp, rulerH - 8 * dp, rulerTextPaint)
        }

        // Layer clip tracks
        for ((i, l) in st.project.layers.withIndex()) {
            val x0 = l.inMs / 1000f * pps - off
            val x1 = minOf(l.outMs, dur) / 1000f * pps - off
            if (x1 < 0 || x0 > width) continue

            val y = rulerH + 4 * dp + i * rowH
            val r = RectF(x0, y, x1, y + rowH - 4 * dp)

            // Distinct theme colors based on layer source
            barPaint.color = when (l.source) {
                is Source.Video -> 0xFF00838F.toInt()     // Deep Cyan
                is Source.Particles -> 0xFF880E4F.toInt() // Magenta Pink
                is Source.Raster -> {
                    val s = l.source as Source.Raster
                    if (s.photo) 0xFF1B5E20.toInt() else 0xFF4A148C.toInt() // Green / Violet
                }
                is Source.Solid -> 0xFF0D47A1.toInt()     // Navy Blue
                else -> 0xFF37474F.toInt()
            }

            cv.drawRoundRect(r, 6 * dp, 6 * dp, barPaint)

            // Clip Title
            val title = if (l.name.isNotBlank()) l.name else "Layer ${l.id}"
            cv.drawText(title, x0 + 10 * dp, y + rowH / 2 + 1 * dp, textPaint)

            // Draw diamond keyframe markers (◆)
            drawKeyframes(cv, l, y + rowH / 2 - 2 * dp)

            // Selection outline & edge trim grips
            if (l === st.selected) {
                cv.drawRoundRect(r, 6 * dp, 6 * dp, selPaint)
                // Left trim handle
                cv.drawRoundRect(RectF(x0, y + 4 * dp, x0 + 5 * dp, r.bottom - 4 * dp), 2 * dp, 2 * dp, handlePaint)
                // Right trim handle
                cv.drawRoundRect(RectF(x1 - 5 * dp, y + 4 * dp, x1, r.bottom - 4 * dp), 2 * dp, 2 * dp, handlePaint)
            }
        }

        // Playhead indicator needle
        val px = (st.posMs / 1000.0 * pps - off).toFloat()
        cv.drawLine(px, 0f, px, height.toFloat(), headPaint)
        // Diamond head cap
        val capPath = Path().apply {
            moveTo(px, rulerH)
            lineTo(px - 5 * dp, 0f)
            lineTo(px + 5 * dp, 0f)
            close()
        }
        cv.drawPath(capPath, headCapPaint)
    }

    private fun drawKeyframes(cv: Canvas, l: Layer, centerY: Float) {
        val size = 3.5f * dp
        // Collect keyframe timestamps across all animated properties
        val keyTimes = HashSet<Long>()
        l.animatedProps().forEach { p ->
            p.keyList().forEach { k -> keyTimes.add(k.timeMs) }
        }

        for (kt in keyTimes) {
            val kx = kt / 1000f * pps - off
            if (kx < -10 || kx > width + 10) continue
            val diamond = Path().apply {
                moveTo(kx, centerY - size)
                lineTo(kx + size, centerY)
                lineTo(kx, centerY + size)
                lineTo(kx - size, centerY)
                close()
            }
            cv.drawPath(diamond, keyframePaint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scale.onTouchEvent(e)
        if (scale.isInProgress) return true

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x
                if (e.y < rulerH) {
                    mode = Mode.SCRUB
                    seek(e.x)
                } else {
                    val trackIdx = ((e.y - rulerH - 4 * dp) / rowH).toInt()
                    val l = st.project.layers.getOrNull(trackIdx)
                    if (l != null && !l.locked) {
                        val x0 = l.inMs / 1000f * pps - off
                        val x1 = minOf(l.outMs, st.project.durationMs) / 1000f * pps - off
                        if (e.x >= x0 && e.x <= x1) {
                            st.selected = l
                            target = l
                            mode = when {
                                e.x < x0 + edge -> Mode.TRIML
                                e.x > x1 - edge -> Mode.TRIMR
                                else -> Mode.MOVE
                            }
                        } else {
                            st.selected = null
                            mode = Mode.PAN
                        }
                    } else {
                        st.selected = null
                        mode = Mode.PAN
                    }
                    invalidate()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dMs = ((e.x - lastX) / pps * 1000).toLong()
                val l = target
                when (mode) {
                    Mode.SCRUB -> seek(e.x)
                    Mode.PAN -> {
                        off = (off - (e.x - lastX)).coerceAtLeast(0f)
                        lastX = e.x
                        invalidate()
                    }
                    Mode.MOVE -> if (l != null) {
                        val d = max(dMs, -l.inMs)
                        l.inMs += d
                        l.outMs += d
                        lastX = e.x
                        onChange()
                        invalidate()
                    }
                    Mode.TRIML -> if (l != null) {
                        val lo = if (l.source is Source.Video) -minOf(l.inMs, l.trimMs) else -l.inMs
                        val d = dMs.coerceIn(lo, l.outMs - l.inMs - 100)
                        l.inMs += d
                        l.trimMs += d
                        lastX = e.x
                        onChange()
                        invalidate()
                    }
                    Mode.TRIMR -> if (l != null) {
                        l.outMs = max(l.inMs + 100, l.outMs + dMs)
                        lastX = e.x
                        onChange()
                        invalidate()
                    }
                    Mode.NONE -> {}
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.MOVE || mode == Mode.TRIMR || mode == Mode.TRIML) {
                    st.project.durationMs = max(1000L, st.project.layers.maxOfOrNull { minOf(it.outMs, 3_600_000L) } ?: 1000L)
                }
                mode = Mode.NONE
                onChange()
                invalidate()
            }
        }
        return true
    }

    private fun seek(x: Float) {
        val targetMs = ((x + off) / pps * 1000.0).coerceIn(0.0, st.project.durationMs.toDouble())
        // Magnetic snap to keyframe if within 100ms
        var snapMs = targetMs
        st.selected?.let { sel ->
            val nearestKey = sel.animatedProps().flatMap { it.keyList() }.minByOrNull { abs(it.timeMs - targetMs) }
            if (nearestKey != null && abs(nearestKey.timeMs - targetMs) < 60) {
                snapMs = nearestKey.timeMs.toDouble()
            }
        }
        st.posMs = snapMs
        onChange()
        invalidate()
    }
}
