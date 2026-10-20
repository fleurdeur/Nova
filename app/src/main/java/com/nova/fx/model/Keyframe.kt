package com.nova.fx.model

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

enum class Interp {
    LINEAR,
    HOLD,
    BEZIER,
    EASE_IN,
    EASE_OUT,
    EASE_IN_OUT,
    BOUNCE,
    ELASTIC,
    OVERSHOOT
}

/**
 * Keyframe representation with full Bezier curve handles (CSS cubic-bezier parameters)
 * and rich easing transitions (Alight Motion / Keyflow / Aurea style).
 */
data class Keyframe(
    val timeMs: Long,
    val value: Float,
    val interp: Interp = Interp.BEZIER,
    val outX: Float = 0.42f,
    val outY: Float = 0.0f,
    val inX: Float = 0.58f,
    val inY: Float = 1.0f
)

/**
 * Allocation-free animated property supporting multi-point keyframe interpolation,
 * custom Bezier easing, and keyframe navigation.
 */
class AnimatedFloat(var base: Float = 0f) {
    private val keys = CopyOnWriteArrayList<Keyframe>()
    private var hint = 0

    fun set(k: Keyframe) {
        val i = keys.indexOfFirst { it.timeMs >= k.timeMs }
        when {
            i < 0 -> keys.add(k)
            keys[i].timeMs == k.timeMs -> keys[i] = k
            else -> keys.add(i, k)
        }
    }

    fun removeAt(timeMs: Long, toleranceMs: Long = 50): Boolean {
        val key = keys.firstOrNull { abs(it.timeMs - timeMs) <= toleranceMs }
        return if (key != null) keys.remove(key) else false
    }

    fun hasKeyAt(timeMs: Long, toleranceMs: Long = 50): Boolean {
        return keys.any { abs(it.timeMs - timeMs) <= toleranceMs }
    }

    fun prevKeyTime(timeMs: Long): Long? {
        return keys.filter { it.timeMs < timeMs - 30 }.maxOfOrNull { it.timeMs }
    }

    fun nextKeyTime(timeMs: Long): Long? {
        return keys.filter { it.timeMs > timeMs + 30 }.minOfOrNull { it.timeMs }
    }

    val isAnimated get() = keys.isNotEmpty()
    fun keyList(): List<Keyframe> = keys.toList()
    fun clear() = keys.clear()

    /** Apply custom Bezier handles to all keyframes */
    fun applyEase(ox: Float, oy: Float, ix: Float, iy: Float) {
        keys.replaceAll { it.copy(interp = Interp.BEZIER, outX = ox, outY = oy, inX = ix, inY = iy) }
    }

    /** Apply an interpolation preset to all keyframes */
    fun applyInterp(interp: Interp) {
        val (ox, oy, ix, iy) = when (interp) {
            Interp.EASE_IN -> floatArrayOf(0.42f, 0f, 1f, 1f)
            Interp.EASE_OUT -> floatArrayOf(0f, 0f, 0.58f, 1f)
            Interp.EASE_IN_OUT -> floatArrayOf(0.42f, 0f, 0.58f, 1f)
            Interp.OVERSHOOT -> floatArrayOf(0.34f, 1.56f, 0.64f, 1f)
            else -> floatArrayOf(0.25f, 0.1f, 0.25f, 1f)
        }
        keys.replaceAll { it.copy(interp = interp, outX = ox, outY = oy, inX = ix, inY = iy) }
    }

    /** Auto-key edit: writes a keyframe at [t] if animated or requested, else changes base */
    fun put(t: Long, v: Float, autoKey: Boolean) {
        if (isAnimated || autoKey) {
            set(Keyframe(t, v))
        } else {
            base = v
        }
    }

    fun valueAt(t: Long): Float {
        val n = keys.size
        if (n == 0) return base
        if (t <= keys[0].timeMs) return keys[0].value
        if (t >= keys[n - 1].timeMs) return keys[n - 1].value

        var i = if (hint in 0 until n - 1 && keys[hint].timeMs <= t) hint else 0
        while (i < n - 1 && keys[i + 1].timeMs <= t) i++
        hint = i
        val a = keys[i]
        val b = keys[i + 1]
        val span = (b.timeMs - a.timeMs).toFloat()
        if (span <= 0f) return a.value
        val u = ((t - a.timeMs).toFloat() / span).coerceIn(0f, 1f)

        val e = when (a.interp) {
            Interp.HOLD -> return a.value
            Interp.LINEAR -> u
            Interp.BEZIER -> bezier(u, a.outX, a.outY, a.inX, a.inY)
            Interp.EASE_IN -> u * u * u
            Interp.EASE_OUT -> 1f - (1f - u).pow(3)
            Interp.EASE_IN_OUT -> if (u < 0.5f) 4f * u * u * u else 1f - (-2f * u + 2f).pow(3) / 2f
            Interp.BOUNCE -> bounceEase(u)
            Interp.ELASTIC -> elasticEase(u)
            Interp.OVERSHOOT -> overshootEase(u)
        }
        return a.value + (b.value - a.value) * e
    }

    companion object {
        fun bezier(u: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
            fun c(a: Float, b: Float, s: Float) = ((1 - 3 * b + 3 * a) * s + (3 * b - 6 * a)) * s * s + 3 * a * s
            fun cx(s: Float) = c(x1, x2, s)
            fun cy(s: Float) = c(y1, y2, s)
            fun dx(s: Float) = 3 * (1 - 3 * x2 + 3 * x1) * s * s + 2 * (3 * x2 - 6 * x1) * s + 3 * x1
            var s = u
            repeat(5) {
                val err = cx(s) - u
                if (abs(err) < 1e-4f) return cy(s)
                val d = dx(s)
                if (abs(d) < 1e-6f) return@repeat
                s -= err / d
            }
            var lo = 0f
            var hi = 1f
            s = u
            repeat(16) {
                if (cx(s) < u) lo = s else hi = s
                s = (lo + hi) / 2
            }
            return cy(s)
        }

        private fun bounceEase(t: Float): Float {
            var n1 = 7.5625f
            var d1 = 2.75f
            return when {
                t < 1 / d1 -> n1 * t * t
                t < 2 / d1 -> {
                    val p = t - 1.5f / d1
                    n1 * p * p + 0.75f
                }
                t < 2.5 / d1 -> {
                    val p = t - 2.25f / d1
                    n1 * p * p + 0.9375f
                }
                else -> {
                    val p = t - 2.625f / d1
                    n1 * p * p + 0.984375f
                }
            }
        }

        private fun elasticEase(t: Float): Float {
            if (t == 0f) return 0f
            if (t == 1f) return 1f
            val c4 = (2f * Math.PI / 3f).toFloat()
            return 2.0.pow((-10 * t).toDouble()).toFloat() * sin((t * 10f - 0.75f) * c4) + 1f
        }

        private fun overshootEase(t: Float): Float {
            val s = 1.70158f
            val p = t - 1f
            return p * p * ((s + 1) * p + s) + 1f
        }
    }
}
