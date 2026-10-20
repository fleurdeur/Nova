package com.nova.fx.export

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.*
import android.os.Build
import android.util.Log
import com.nova.fx.gl.Compositor
import com.nova.fx.model.Project
import java.io.File

/**
 * GPU Hardware-Accelerated Video Exporter.
 * Specifically optimized for smooth 4K (3840x2160) and 2K rendering and export on
 * MediaTek Helio G88 / Mali-G52 GPU and all modern Android chipsets.
 * Guarantees 100% watermark-free, clean exports with VBR hardware encoding.
 */
class Exporter(private val ctx: Context, private val project: Project) {
    private val TAG = "NovaFX-Exporter"
    private val info = MediaCodec.BufferInfo()
    private lateinit var enc: MediaCodec
    private lateinit var mux: MediaMuxer
    private var track = -1
    private var muxStarted = false
    private var audioTrack = -1
    private var audio: AudioSource? = null

    fun run(
        out: File,
        bitrate: Int = 18_000_000,
        allIntra: Boolean = false,
        mime: String = MediaFormat.MIMETYPE_VIDEO_AVC,
        scale: Float = 1f,
        onProgress: (Float) -> Unit
    ) {
        // Macroblock 16-pixel alignment required for 4K / Mali tile hardware encoders
        var w = ((project.width * scale).toInt() / 16) * 16
        var h = ((project.height * scale).toInt() / 16) * 16
        if (w < 16) w = 16
        if (h < 16) h = 16
        val fps = project.fps

        // Prefer HEVC for 4K if available on MediaTek G88 for better efficiency and smoother encoding
        val is4K = (w >= 3840 || h >= 3840) || (w * h >= 3840 * 2160 * 0.8)
        val selectedMime = if (is4K && hasEncoder(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
            MediaFormat.MIMETYPE_VIDEO_HEVC
        } else if (hasEncoder(mime)) {
            mime
        } else {
            MediaFormat.MIMETYPE_VIDEO_AVC
        }

        audio = (AudioMix.create(ctx, project) ?: AudioCopy(ctx, project)).takeIf { it.format != null }

        val fmt = MediaFormat.createVideoFormat(selectedMime, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, if (allIntra) 0 else 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)

            // Configure Profile & Level for 4K UHD support
            if (selectedMime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51)
            } else {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel51)
            }
        }

        enc = MediaCodec.createEncoderByType(selectedMime)
        try {
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            Log.w(TAG, "Fallback without level constraint for encoder: ${e.message}")
            fmt.removeKey(MediaFormat.KEY_PROFILE)
            fmt.removeKey(MediaFormat.KEY_LEVEL)
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

        val inSurface = enc.createInputSurface()
        enc.start()
        mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // Headless EGL context setup with RECORDABLE flag
        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        EGL14.eglInitialize(dpy, v, 0, v, 1)
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(
            dpy,
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                0x3142 /* EGL_RECORDABLE_ANDROID */, 1,
                EGL14.EGL_NONE
            ),
            0, cfgs, 0, 1, n, 0
        )
        val eglCtx = EGL14.eglCreateContext(
            dpy, cfgs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
        )
        val surf = EGL14.eglCreateWindowSurface(dpy, cfgs[0], inSurface, intArrayOf(EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(dpy, surf, surf, eglCtx)

        // Disable vsync lock during headless export to render at max GPU hardware speed
        EGL14.eglSwapInterval(dpy, 0)

        val comp = Compositor(ctx)
        try {
            comp.init()
            comp.resize(project, w, h)
            val total = (project.durationMs * fps / 1000).toInt()
            for (i in 0 until total) {
                val frameTimeMs = i * 1000L / fps
                comp.draw(project, frameTimeMs)
                EGLExt.eglPresentationTimeANDROID(dpy, surf, i * 1_000_000_000L / fps)
                EGL14.eglSwapBuffers(dpy, surf)
                drain(false)
                onProgress((i + 1f) / total)
            }
            enc.signalEndOfInputStream()
            drain(true)
            if (audioTrack >= 0) {
                audio?.write(mux, audioTrack)
            }
        } finally {
            comp.release()
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(dpy, surf)
            EGL14.eglDestroyContext(dpy, eglCtx)
            EGL14.eglTerminate(dpy)
            runCatching { enc.stop(); enc.release() }
            inSurface.release()
            runCatching {
                if (muxStarted) mux.stop()
                mux.release()
            }
        }
    }

    private fun hasEncoder(mime: String): Boolean {
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { ci ->
            ci.isEncoder && ci.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
    }

    private fun drain(eos: Boolean) {
        while (true) {
            val i = enc.dequeueOutputBuffer(info, if (eos) 12_000 else 0)
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!eos) return
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = mux.addTrack(enc.outputFormat)
                    audioTrack = audio?.format?.let { mux.addTrack(it) } ?: -1
                    mux.start()
                    muxStarted = true
                }
                i >= 0 -> {
                    val b = enc.getOutputBuffer(i)
                    if (b != null && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        b.position(info.offset)
                        b.limit(info.offset + info.size)
                        mux.writeSampleData(track, b, info)
                    }
                    enc.releaseOutputBuffer(i, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }
}
