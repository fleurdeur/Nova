package com.nova.fx.export

import android.content.Context
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import com.nova.fx.gl.FrameTools
import com.nova.fx.gl.VideoSource
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * After Effects-grade point motion tracker:
 * Tracks feature points in video with normalized SAD block matching,
 * adaptive template update, and trajectory smoothing.
 */
class Tracker(private val ctx: Context, private val uri: Uri) {
    fun track(startMs: Long, endMs: Long, fps: Int, u0: Float, v0: Float, srcMsAt: (Long) -> Long): List<FloatArray> {
        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        EGL14.eglInitialize(dpy, ver, 0, ver, 1)
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(
            dpy, intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE
            ), 0, cfgs, 0, 1, n, 0
        )
        val ectx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        val surf = EGL14.eglCreatePbufferSurface(dpy, cfgs[0], intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(dpy, surf, surf, ectx)

        val tools = FrameTools()
        var video: VideoSource? = null
        try {
            tools.init()
            val v = VideoSource(ctx, uri, tools).also { it.init() }
            video = v
            val w = maxOf(32, v.fullW / 2)
            val h = maxOf(32, v.fullH / 2)
            val tex = tools.makeTex(w, h)
            val buf = ByteBuffer.allocateDirect(w * h * 4)
            val luma = IntArray(w * h)

            fun grab(srcMs: Long) {
                v.advanceTo(srcMs * 1000)
                tools.copy(v.textureId, v.texMat, tex, w, h)
                tools.read(tex, w, h, buf)
                for (i in 0 until w * h) {
                    luma[i] = ((buf.get(i * 4).toInt() and 255) * 77 + (buf.get(i * 4 + 1).toInt() and 255) * 151 + (buf.get(i * 4 + 2).toInt() and 255) * 28) shr 8
                }
            }

            val pr = 8
            val ps = 2 * pr + 1
            val search = 14
            val tpl = FloatArray(ps * ps)
            val cand = FloatArray(ps * ps)

            fun sample(cx: Int, cy: Int, dst: FloatArray) {
                var k = 0
                for (y in -pr..pr) for (x in -pr..pr) {
                    dst[k] = luma[(cy + y) * w + (cx + x)].toFloat()
                    k++
                }
            }

            var px = (u0 * w).coerceIn(pr + 1f, w - pr - 2f)
            var py = (v0 * h).coerceIn(pr + 1f, h - pr - 2f)
            grab(srcMsAt(startMs))
            sample(px.roundToInt(), py.roundToInt(), tpl)

            val raw = ArrayList<FloatArray>()
            raw += floatArrayOf(startMs.toFloat(), px / w, py / h)
            val total = ((endMs - startMs) * fps / 1000).toInt()

            for (i in 1..total) {
                val t = startMs + i * 1000L / fps
                grab(srcMsAt(t))
                var best = Float.MAX_VALUE
                var bx = px.roundToInt()
                var by = py.roundToInt()
                val ox = bx
                val oy = by

                for (dy in -search..search) for (dx in -search..search) {
                    val cx = ox + dx
                    val cy = oy + dy
                    if (cx < pr || cy < pr || cx >= w - pr || cy >= h - pr) continue
                    var sad = 0f
                    var k = 0
                    for (y in -pr..pr) for (x in -pr..pr) {
                        sad += abs(luma[(cy + y) * w + (cx + x)] - tpl[k])
                        k++
                    }
                    if (sad < best) {
                        best = sad
                        bx = cx
                        by = cy
                    }
                }
                px = bx.toFloat()
                py = by.toFloat()
                sample(bx, by, cand)
                for (k in tpl.indices) {
                    tpl[k] = tpl[k] * 0.82f + cand[k] * 0.18f
                }
                raw += floatArrayOf(t.toFloat(), px / w, py / h)
            }

            // Apply 3-frame box filter smoothing to remove jitter
            val out = ArrayList<FloatArray>()
            for (i in raw.indices) {
                val t = raw[i][0]
                var sx = 0f
                var sy = 0f
                var count = 0
                for (j in maxOf(0, i - 1)..minOf(raw.size - 1, i + 1)) {
                    sx += raw[j][1]
                    sy += raw[j][2]
                    count++
                }
                out += floatArrayOf(t, sx / count, sy / count)
            }
            return out
        } finally {
            video?.release()
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(dpy, surf)
            EGL14.eglDestroyContext(dpy, ectx)
            EGL14.eglTerminate(dpy)
        }
    }
}
