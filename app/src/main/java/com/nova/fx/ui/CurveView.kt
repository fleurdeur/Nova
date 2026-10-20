package com.nova.fx.ui

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * Alight Motion / Keyflow style interactive Bezier Curve Editor.
 * Renders cubic bezier easing with draggable P1 and P2 tangent handles,
 * grid lines, overshoot support (-0.5 .. 1.5), and live coordinate display.
 */
class CurveView(c: Context) : View(c) {
    private val dp = resources.displayMetrics.density
    private val pad = 28 * dp
    private val h = floatArrayOf(0.42f, 0f, 0.58f, 1f) // outX, outY, inX, inY
    private var drag = -1

    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00DF82.toInt() // Alight Motion Emerald Green curve
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * dp
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF35394A.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1f * dp
    }
    private val armPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00DF82.toInt() // Alight Motion Green handle arms
        style = Paint.Style.STROKE
        strokeWidth = 2f * dp
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00DF82.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f * dp
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8E92A4.toInt()
        textSize = 10 * dp
    }

    var onCurveChanged: ((FloatArray) -> Unit)? = null

    fun handles() = h.copyOf()

    fun set(a: Float, b: Float, c2: Float, d: Float) {
        h[0] = a.coerceIn(0f, 1f)
        h[1] = b.coerceIn(-0.5f, 1.5f)
        h[2] = c2.coerceIn(0f, 1f)
        h[3] = d.coerceIn(-0.5f, 1.5f)
        invalidate()
        onCurveChanged?.invoke(handles())
    }

    private fun px(x: Float) = pad + x * (width - 2 * pad)
    private fun py(y: Float) = height - pad - (y + 0.5f) / 2f * (height - 2 * pad)

    override fun onMeasure(w: Int, hh: Int) {
        val s = minOf(MeasureSpec.getSize(w), (280 * dp).toInt())
        setMeasuredDimension(s, s)
    }

    override fun onDraw(cv: Canvas) {
        cv.drawColor(0xFF14151B.toInt())

        // Reference 0..1 bounding box and baseline
        val left = px(0f); val right = px(1f)
        val bottom = py(0f); val top = py(1f)

        // Grid lines
        for (i in 1..3) {
            val f = i * 0.25f
            cv.drawLine(px(f), top, px(f), bottom, guidePaint)
            cv.drawLine(left, py(f), right, py(f), guidePaint)
        }

        // Bounding frame
        cv.drawRect(left, top, right, bottom, guidePaint)
        // Linear diagonal reference
        cv.drawLine(left, bottom, right, top, guidePaint)

        // Tangent arms
        cv.drawLine(left, bottom, px(h[0]), py(h[1]), armPaint)
        cv.drawLine(right, top, px(h[2]), py(h[3]), armPaint)

        // Bezier curve
        val path = Path().apply {
            moveTo(left, bottom)
            cubicTo(px(h[0]), py(h[1]), px(h[2]), py(h[3]), right, top)
        }
        cv.drawPath(path, curvePaint)

        // Draggable P1 & P2 Handles
        cv.drawCircle(px(h[0]), py(h[1]), 8 * dp, handleFill)
        cv.drawCircle(px(h[0]), py(h[1]), 8 * dp, handleStroke)

        cv.drawCircle(px(h[2]), py(h[3]), 8 * dp, handleFill)
        cv.drawCircle(px(h[2]), py(h[3]), 8 * dp, handleStroke)

        // Coordinates label
        val label = "P1: (%.2f, %.2f)  •  P2: (%.2f, %.2f)".format(h[0], h[1], h[2], h[3])
        cv.drawText(label, pad, height - 8 * dp, textPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val d0 = hypot((e.x - px(h[0])).toDouble(), (e.y - py(h[1])).toDouble())
                val d1 = hypot((e.x - px(h[2])).toDouble(), (e.y - py(h[3])).toDouble())
                drag = if (minOf(d0, d1) < 48 * dp) {
                    if (d0 <= d1) 0 else 1
                } else -1
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (drag >= 0) {
                    val nx = ((e.x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
                    val ny = (((height - pad - e.y) / (height - 2 * pad)) * 2f - 0.5f).coerceIn(-0.5f, 1.5f)
                    h[drag * 2] = nx
                    h[drag * 2 + 1] = ny
                    invalidate()
                    onCurveChanged?.invoke(handles())
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drag = -1
            }
        }
        return true
    }
}
