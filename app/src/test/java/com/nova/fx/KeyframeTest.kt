package com.nova.fx

import com.nova.fx.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyframeTest {
    @Test
    fun linearMidpoint() {
        val a = AnimatedFloat()
        a.set(Keyframe(0, 0f, Interp.LINEAR))
        a.set(Keyframe(1000, 10f, Interp.LINEAR))
        assertEquals(5f, a.valueAt(500), 1e-3f)
    }

    @Test
    fun bezierEndpointsAndSymmetry() {
        assertEquals(0f, AnimatedFloat.bezier(0f, .42f, 0f, .58f, 1f), 1e-3f)
        assertEquals(1f, AnimatedFloat.bezier(1f, .42f, 0f, .58f, 1f), 1e-3f)
        assertEquals(.5f, AnimatedFloat.bezier(.5f, .42f, 0f, .58f, 1f), 1e-2f)
    }

    @Test
    fun holdAndClamp() {
        val a = AnimatedFloat()
        a.set(Keyframe(0, 1f, Interp.HOLD))
        a.set(Keyframe(100, 9f))
        assertEquals(1f, a.valueAt(50), 0f)
        assertEquals(9f, a.valueAt(500), 0f)
    }

    @Test
    fun keyframeNavigation() {
        val a = AnimatedFloat()
        a.set(Keyframe(100, 2f))
        a.set(Keyframe(500, 8f))
        a.set(Keyframe(900, 12f))

        assertTrue(a.hasKeyAt(500))
        assertEquals(100L, a.prevKeyTime(500))
        assertEquals(900L, a.nextKeyTime(500))
    }
}
