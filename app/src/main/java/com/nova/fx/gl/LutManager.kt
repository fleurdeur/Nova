package com.nova.fx.gl

import android.graphics.Color
import android.opengl.GLES30 as GL
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/**
 * High-performance 3D LUT (Look-Up Table) manager for OpenGL ES 3.0.
 * Allocates and caches real 3D hardware textures (GL_TEXTURE_3D) for cinematic color grading,
 * and parses industry-standard .CUBE files and mathematical LUT profiles.
 */
class LutManager {
    private val lutTextures = HashMap<Int, Int>()
    private val lutSize = 16 // 16x16x16 3D color cube = 4096 voxels (instant upload, crystal smooth with trilinear filter)

    fun getLutTexture(lutId: Int, customFile: File? = null): Int {
        if (lutId <= 0) return 0
        lutTextures[lutId]?.let { return it }

        val tid = if (lutId == 9 && customFile != null && customFile.exists()) {
            loadCubeFile(customFile)
        } else {
            generateBuiltInLut(lutId)
        }
        lutTextures[lutId] = tid
        return tid
    }

    private fun generateBuiltInLut(id: Int): Int {
        val size = lutSize
        val buf = ByteBuffer.allocateDirect(size * size * size * 4).order(ByteOrder.nativeOrder())

        for (b in 0 until size) {
            val fb = b / (size - 1f)
            for (g in 0 until size) {
                val fg = g / (size - 1f)
                for (r in 0 until size) {
                    val fr = r / (size - 1f)
                    val (or, og, ob) = gradeColor(id, fr, fg, fb)
                    buf.put((or.coerceIn(0f, 1f) * 255).toInt().toByte())
                    buf.put((og.coerceIn(0f, 1f) * 255).toInt().toByte())
                    buf.put((ob.coerceIn(0f, 1f) * 255).toInt().toByte())
                    buf.put(255.toByte())
                }
            }
        }
        buf.position(0)
        return upload3DTexture(size, size, size, buf)
    }

    private fun gradeColor(id: Int, r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val luma = 0.299f * r + 0.587f * g + 0.114f * b
        return when (id) {
            1 -> {
                // Teal & Orange (Blockbuster)
                val shadows = 1f - luma
                val highlights = luma
                val or = r + highlights * 0.15f - shadows * 0.08f
                val og = g + highlights * 0.05f + shadows * 0.05f
                val ob = b - highlights * 0.15f + shadows * 0.22f
                Triple(or * 1.08f, og * 1.02f, ob * 0.98f)
            }
            2 -> {
                // Kodak Portra 400 (Warm golden film)
                val or = r * 1.06f + 0.02f
                val og = g * 0.99f + 0.01f
                val ob = b * 0.92f
                val con = (or - 0.5f) * 1.08f + 0.5f
                Triple(con, og, ob)
            }
            3 -> {
                // Cyberpunk Neon (Cyan-Magenta)
                val or = if (r > luma) r * 1.25f else r * 0.8f
                val og = g * 0.88f
                val ob = if (b > luma) b * 1.35f else b * 1.15f
                Triple(or, og, ob)
            }
            4 -> {
                // 70s Vintage Film
                val or = r * 1.12f + 0.04f
                val og = g * 1.04f + 0.02f
                val ob = b * 0.85f + 0.05f
                Triple(or, og, ob)
            }
            5 -> {
                // Noir Monolith (Crisp high-contrast B&W)
                val con = ((luma - 0.5f) * 1.35f + 0.5f).coerceIn(0f, 1f)
                Triple(con, con, con)
            }
            6 -> {
                // Commercial Clean
                val or = ((r - 0.5f) * 1.12f + 0.5f).coerceIn(0f, 1f)
                val og = ((g - 0.5f) * 1.12f + 0.5f).coerceIn(0f, 1f)
                val ob = ((b - 0.5f) * 1.12f + 0.5f).coerceIn(0f, 1f)
                Triple(or * 1.02f, og * 1.02f, ob * 1.02f)
            }
            7 -> {
                // Golden Hour Sunset
                val or = r * 1.2f + 0.05f
                val og = g * 1.06f + 0.02f
                val ob = b * 0.82f - 0.02f
                Triple(or, og, ob)
            }
            8 -> {
                // Urban Moody (Desaturated greens, crushed deep shadows)
                val or = r * 1.05f
                val og = g * 0.82f
                val ob = b * 0.95f
                val s = if (luma < 0.35f) luma * 0.85f else luma
                Triple(or * 0.9f + s * 0.1f, og * 0.9f + s * 0.1f, ob * 0.9f + s * 0.1f)
            }
            else -> Triple(r, g, b)
        }
    }

    private fun loadCubeFile(f: File): Int {
        return runCatching {
            var size = 0
            val values = ArrayList<FloatArray>()
            f.forEachLine { raw ->
                val line = raw.trim()
                if (!line.startsWith("#") && line.isNotEmpty()) {
                    if (line.startsWith("LUT_3D_SIZE")) {
                        size = line.substringAfter("LUT_3D_SIZE").trim().toInt()
                    } else if (size > 0) {
                        val parts = line.split("\\s+".toRegex()).mapNotNull { it.toFloatOrNull() }
                        if (parts.size >= 3) {
                            values.add(floatArrayOf(parts[0], parts[1], parts[2]))
                        }
                    }
                }
            }
            if (size > 0 && values.size >= size * size * size) {
                val buf = ByteBuffer.allocateDirect(size * size * size * 4).order(ByteOrder.nativeOrder())
                for (v in values.take(size * size * size)) {
                    buf.put((v[0].coerceIn(0f, 1f) * 255).toInt().toByte())
                    buf.put((v[1].coerceIn(0f, 1f) * 255).toInt().toByte())
                    buf.put((v[2].coerceIn(0f, 1f) * 255).toInt().toByte())
                    buf.put(255.toByte())
                }
                buf.position(0)
                upload3DTexture(size, size, size, buf)
            } else {
                generateBuiltInLut(1)
            }
        }.getOrElse { generateBuiltInLut(1) }
    }

    private fun upload3DTexture(w: Int, h: Int, d: Int, buf: ByteBuffer): Int {
        val ids = IntArray(1)
        GL.glGenTextures(1, ids, 0)
        val tid = ids[0]
        GL.glBindTexture(GL.GL_TEXTURE_3D, tid)
        GL.glTexParameteri(GL.GL_TEXTURE_3D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_3D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_3D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(GL.GL_TEXTURE_3D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(GL.GL_TEXTURE_3D, GL.GL_TEXTURE_WRAP_R, GL.GL_CLAMP_TO_EDGE)
        GL.glTexImage3D(GL.GL_TEXTURE_3D, 0, GL.GL_RGBA8, w, h, d, 0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, buf)
        GL.glBindTexture(GL.GL_TEXTURE_3D, 0)
        return tid
    }

    fun release() {
        val arr = lutTextures.values.toIntArray()
        if (arr.isNotEmpty()) {
            GL.glDeleteTextures(arr.size, arr, 0)
        }
        lutTextures.clear()
    }
}
