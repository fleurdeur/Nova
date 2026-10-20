package com.nova.fx.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES30 as GL
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.nova.fx.Prefs

/**
 * Hardware-accelerated video decoder with direct external-OES texture streaming.
 * Uses a dedicated background HandlerThread for zero-latency frame notifications,
 * eliminating main-thread locking and playback stuttering.
 */
class VideoSource(private val ctx: Context, private val uri: Uri, private val tools: FrameTools) {
    val texMat = FloatArray(16)
    var textureId = 0; private set
    var fullW = 2; private set
    var fullH = 2; private set
    var smallW = 8; private set
    var smallH = 8; private set
    var prevFull = 0; private set
    var curFull = 0; private set
    var flowTex = 0; private set
    private var prevSmall = 0
    private var curSmall = 0
    var mix = 1f; private set

    private lateinit var st: SurfaceTexture
    private lateinit var surface: Surface
    private val ex = MediaExtractor()
    private lateinit var dec: MediaCodec
    private val lock = Object()
    private var frameReady = false
    private val info = MediaCodec.BufferInfo()
    private var inputDone = false
    private var outputDone = false
    private var curPts = Long.MIN_VALUE
    private var ringPrev = Long.MIN_VALUE
    private var ringCur = Long.MIN_VALUE
    private var ringCount = 0
    private var ringReady = false

    // Dedicated background thread for frame callbacks (fixes UI thread contention)
    private var frameThread: HandlerThread? = null

    fun init() {
        val t = IntArray(1)
        GL.glGenTextures(1, t, 0)
        textureId = t[0]
        val tgt = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GL.glBindTexture(tgt, textureId)
        GL.glTexParameteri(tgt, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(tgt, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(tgt, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(tgt, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)

        frameThread = HandlerThread("NovaFX-Decoder-${System.identityHashCode(this)}").apply { start() }
        st = SurfaceTexture(textureId)
        st.setOnFrameAvailableListener(
            {
                synchronized(lock) {
                    frameReady = true
                    lock.notifyAll()
                }
            },
            Handler(frameThread!!.looper)
        )
        surface = Surface(st)

        ex.setDataSource(ctx, uri, null)
        val idx = (0 until ex.trackCount).first {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        }
        ex.selectTrack(idx)
        val f = ex.getTrackFormat(idx)
        var w = f.getInteger(MediaFormat.KEY_WIDTH)
        var h = f.getInteger(MediaFormat.KEY_HEIGHT)
        val rot = if (f.containsKey(MediaFormat.KEY_ROTATION)) f.getInteger(MediaFormat.KEY_ROTATION) else 0
        if (rot == 90 || rot == 270) {
            val tmp = w; w = h; h = tmp
        }

        val fs = minOf(1f, 1920f / maxOf(w, h))
        val ss = Prefs.flowRes.toFloat() / maxOf(w, h)
        fullW = maxOf(2, (w * fs).toInt())
        fullH = maxOf(2, (h * fs).toInt())
        smallW = maxOf(8, (w * ss).toInt())
        smallH = maxOf(8, (h * ss).toInt())

        val mime = f.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_VIDEO_AVC
        dec = MediaCodec.createDecoderByType(mime)
        dec.configure(f, surface, null, 0)
        dec.start()
    }

    private fun seek(tUs: Long) {
        ex.seekTo(tUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        runCatching { dec.flush() }
        inputDone = false
        outputDone = false
        curPts = Long.MIN_VALUE
        ringCount = 0
        synchronized(lock) { frameReady = false }
    }

    /** Feed + drain one buffer with reduced latency. */
    private fun step(renderFrom: Long): Long {
        if (!inputDone) {
            val i = dec.dequeueInputBuffer(0)
            if (i >= 0) {
                val inputBuf = dec.getInputBuffer(i)
                if (inputBuf != null) {
                    val n = ex.readSampleData(inputBuf, 0)
                    if (n < 0) {
                        dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }
        }

        val o = dec.dequeueOutputBuffer(info, 3000)
        if (o < 0) return -1L
        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
        val render = info.size > 0 && info.presentationTimeUs >= renderFrom
        dec.releaseOutputBuffer(o, render)
        var r = -1L
        if (render) {
            if (awaitFrame()) {
                st.updateTexImage()
                st.getTransformMatrix(texMat)
                r = info.presentationTimeUs
            }
        }
        if (eos) outputDone = true
        return r
    }

    fun advanceTo(tUs: Long, backward: Boolean = false) {
        val tol = 16_000L
        if ((curPts != Long.MIN_VALUE && (tUs < curPts - tol * 6 || tUs > curPts + 1_200_000)) ||
            (curPts == Long.MIN_VALUE && tUs > 1_000_000) ||
            (backward && curPts != Long.MIN_VALUE && tUs < curPts - 8_000)
        ) {
            seek(tUs)
        }
        var attempts = 0
        while (curPts < tUs - tol && !outputDone && attempts++ < 60) {
            val r = step(tUs - tol)
            if (r >= 0) curPts = r
        }
    }

    private fun ensureRing() {
        if (ringReady) return
        prevFull = tools.makeTex(fullW, fullH)
        curFull = tools.makeTex(fullW, fullH)
        prevSmall = tools.makeTex(smallW, smallH)
        curSmall = tools.makeTex(smallW, smallH)
        flowTex = tools.makeTex(smallW, smallH)
        ringReady = true
    }

    private fun push(pts: Long, flow: Boolean) {
        var t = prevFull; prevFull = curFull; curFull = t
        t = prevSmall; prevSmall = curSmall; curSmall = t
        ringPrev = ringCur; ringCur = pts; ringCount++
        tools.copy(textureId, texMat, curFull, fullW, fullH)
        tools.copy(textureId, texMat, curSmall, smallW, smallH)
        if (flow && ringCount >= 2) {
            tools.flow(prevSmall, curSmall, flowTex, smallW, smallH)
        }
    }

    fun advanceBlend(tUs: Long, flow: Boolean) {
        ensureRing()
        if ((ringCount >= 1 && tUs > ringCur + 1_200_000) || (ringCount == 0 && tUs > 1_000_000)) seek(tUs)
        var attempts = 0
        while (!outputDone && attempts++ < 40) {
            if (ringCount >= 2 && ringPrev <= tUs && tUs < ringCur) break
            if (ringCount == 1 && tUs < ringCur) break
            if (ringCount >= 1 && tUs < ringPrev) { seek(tUs); continue }
            val r = step(tUs - 80_000)
            if (r >= 0) {
                push(r, flow)
                curPts = r
            }
        }
        mix = if (ringCount >= 2) ((tUs - ringPrev).toFloat() / (ringCur - ringPrev).toFloat()).coerceIn(0f, 1f) else 1f
    }

    private fun awaitFrame(): Boolean = synchronized(lock) {
        val end = SystemClock.uptimeMillis() + 80
        while (!frameReady) {
            val w = end - SystemClock.uptimeMillis()
            if (w <= 0) return false
            try { lock.wait(w) } catch (_: InterruptedException) { return false }
        }
        frameReady = false
        return true
    }

    fun release() {
        runCatching {
            dec.stop()
            dec.release()
        }
        runCatching { ex.release() }
        surface.release()
        st.release()
        frameThread?.quitSafely()
        frameThread = null
        if (ringReady) {
            GL.glDeleteTextures(5, intArrayOf(prevFull, curFull, prevSmall, curSmall, flowTex), 0)
            ringReady = false
        }
        if (textureId != 0) {
            GL.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
    }
}
