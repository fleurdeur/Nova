package com.nova.fx.model

import android.graphics.*
import kotlin.math.*

object LayerFactory {
    private val palette = intArrayOf(
        0xFF7C4DFF.toInt(),
        0xFF00E5FF.toInt(),
        0xFFFF5252.toInt(),
        0xFF26A69A.toInt(),
        0xFFFFB300.toInt(),
        0xFFE040FB.toInt()
    )

    fun text(id: Int, s: String, p: Project): Layer {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 96f
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(8f, 0f, 4f, 0x88000000.toInt())
        }
        val fm = paint.fontMetrics
        val w = min(ceil(paint.measureText(s)).toInt() + 24, 2048)
        val h = ceil(fm.descent - fm.ascent).toInt() + 24
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawText(s, 12f, 12f - fm.ascent, paint)
        return place(Layer(id, Source.Raster(bmp), w.toFloat(), h.toFloat()).also { it.name = s.take(16) }, p)
    }

    /** kind: 0 rectangle, 1 rounded, 2 ellipse, 3 star, 4 triangle, 5 heart */
    fun shape(id: Int, kind: Int, p: Project): Layer {
        val w = 400
        val h = if (kind == 0) 260 else 400
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette[id % palette.size] }
        val c = Canvas(bmp)
        val r = RectF(2f, 2f, w - 2f, h - 2f)
        when (kind) {
            0 -> c.drawRect(r, paint)
            1 -> c.drawRoundRect(r, 60f, 60f, paint)
            2 -> c.drawOval(r, paint)
            3 -> {
                val path = Path()
                val R = w / 2f * 0.95f
                for (i in 0 until 10) {
                    val rad = if (i % 2 == 0) R else R * 0.4f
                    val a = Math.toRadians(-90.0 + 36.0 * i)
                    val x = w / 2f + (rad * cos(a)).toFloat()
                    val y = h / 2f + (rad * sin(a)).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                c.drawPath(path, paint)
            }
            4 -> {
                val path = Path().apply {
                    moveTo(w / 2f, 4f)
                    lineTo(w - 4f, h - 4f)
                    lineTo(4f, h - 4f)
                    close()
                }
                c.drawPath(path, paint)
            }
            else -> {
                // Heart shape
                val path = Path().apply {
                    moveTo(w / 2f, h / 4f)
                    cubicTo(w / 2f, 0f, 0f, 0f, 0f, h * 0.4f)
                    cubicTo(0f, h * 0.65f, w * 0.35f, h * 0.85f, w / 2f, h.toFloat() - 4f)
                    cubicTo(w * 0.65f, h * 0.85f, w.toFloat(), h * 0.65f, w.toFloat(), h * 0.4f)
                    cubicTo(w.toFloat(), 0f, w / 2f, 0f, w / 2f, h / 4f)
                    close()
                }
                c.drawPath(path, paint)
            }
        }
        val names = arrayOf("Rectangle", "Rounded Rect", "Circle", "Star", "Triangle", "Heart")
        return place(Layer(id, Source.Raster(bmp), w.toFloat(), h.toFloat()).also { it.name = names.getOrElse(kind) { "Shape" } }, p)
    }

    /** After Effects particle generator layer */
    fun particles(id: Int, type: Int, p: Project): Layer {
        val names = arrayOf("Bokeh Dust", "Golden Sparks", "Fireflies Glow", "Blizzard Snow", "Raindrops", "Starfield Warp")
        val (cr, cg, cb) = when (type) {
            1 -> Triple(1.0f, 0.7f, 0.2f)   // Sparks (Golden)
            2 -> Triple(0.4f, 1.0f, 0.4f)   // Fireflies (Lime Green)
            3 -> Triple(0.9f, 0.95f, 1.0f)  // Snow (Crisp White/Ice)
            4 -> Triple(0.6f, 0.8f, 1.0f)   // Rain (Cyan blue)
            5 -> Triple(1.0f, 0.95f, 0.9f)  // Stars
            else -> Triple(1.0f, 0.85f, 0.5f) // Bokeh
        }
        val src = Source.Particles(
            type = type,
            count = if (type == 4) 200 else 120,
            speed = 1.0f,
            size = 1.0f,
            colorR = cr,
            colorG = cg,
            colorB = cb
        )
        val l = Layer(id, src, p.width.toFloat(), p.height.toFloat()).apply {
            name = names.getOrElse(type) { "Particles" }
            blend = BlendMode.ADD
        }
        return place(l, p)
    }

    /** Solid Color / Adjustment Layer */
    fun solid(id: Int, r: Float, g: Float, b: Float, p: Project, name: String = "Solid"): Layer {
        return place(Layer(id, Source.Solid(r, g, b), p.width.toFloat(), p.height.toFloat()).also { it.name = name }, p)
    }

    private fun place(l: Layer, p: Project): Layer {
        l.transform.anchorX.base = l.width / 2f
        l.transform.anchorY.base = l.height / 2f
        l.transform.x.base = p.width / 2f
        l.transform.y.base = p.height / 2f
        l.outMs = maxOf(p.durationMs, 5000)
        return l
    }
}
