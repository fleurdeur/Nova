package com.nova.fx.gl

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES30 as GL
import android.opengl.GLUtils
import android.opengl.Matrix
import com.nova.fx.Prefs
import com.nova.fx.model.*
import kotlin.math.min

/**
 * Tile-GPU friendly Compositor optimized for ARM Mali-G52 (MediaTek Helio G88).
 * Features:
 *  - 2-pass OES -> 2D texture conversion for multi-tap shaders to prevent Mali texture cache thrashing
 *  - Native 3D LUT (GL_TEXTURE_3D) hardware color grading
 *  - Lift / Gamma / Gain professional 3-way color controls
 *  - GPU Particle Generators (Bokeh, Sparks, Fireflies, Snow, Rain, Stars)
 *  - After Effects motion tools (Camera shake, chromatic dispersion, optical bloom, directional blur, chroma key)
 *  - Vector rotoscoping and bezier masking
 */
class Compositor(private val ctx: Context) {
    private class Prog(val id: Int) {
        private fun u(n: String) = GL.glGetUniformLocation(id, n)
        val mvp = u("uMvp")
        val texMat = u("uTexMat")
        val color = u("uColor")
        val tex = u("uTex")
        val useTex = u("uUseTex")
        val adj = u("uAdj")
        val fx = u("uFx")
        val time = u("uTime")
        val px = u("uPx")
        val e = u("uE")
        val mask = u("uMask")
        val maskP = u("uMaskP")
        val tex2 = u("uTex2")
        val flow = u("uFlow")
        val mix = u("uMix")
        val flowScale = u("uFlowScale")
        val lut3d = u("uLut3d")
        val lutMix = u("uLutMix")
        val lgg = u("uLgg") // Lift, Gamma, Gain
    }

    private class ParticleProg(val id: Int) {
        private fun u(n: String) = GL.glGetUniformLocation(id, n)
        val mvp = u("uMvp")
        val time = u("uTime")
        val ptype = u("uType")
        val pcolor = u("uColor")
        val psize = u("uSize")
        val pspeed = u("uSpeed")
    }

    private val progs = arrayOfNulls<Prog>(16)
    private var particleProg: ParticleProg? = null
    val tools = FrameTools()
    val lutManager = LutManager()
    var lite = false
    @Volatile var vScale = 1f

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val tmp = FloatArray(16)
    private val mvp = FloatArray(16)
    private val parentM = FloatArray(16)
    private val ident = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val flipM = FloatArray(16).also {
        Matrix.setIdentityM(it, 0)
        it[5] = -1f
        it[13] = 1f
    }
    private val ev = FloatArray(FX.size)
    private val videos = HashMap<Int, VideoSource>()
    private val lastSrc = HashMap<Int, Long>()
    private val rasters = HashMap<Source.Raster, Int>()

    // Reusable intermediate 2D texture for heavy video effect passes
    private var interTex = 0
    private var interW = 0
    private var interH = 0

    fun init() {
        progs.fill(null)
        rasters.clear()
        tools.init()
        GL.glDisable(GL.GL_DEPTH_TEST)
        GL.glDisable(GL.GL_CULL_FACE)
        GL.glEnable(GL.GL_BLEND)
        initParticleProg()
    }

    private fun initParticleProg() {
        val vs = """#version 300 es
layout(location=0) in vec2 aPos;
uniform mat4 uMvp;
out vec2 vUv;
void main() {
    vUv = aPos;
    gl_Position = uMvp * vec4(aPos, 0.0, 1.0);
}"""
        val fs = """#version 300 es
precision mediump float;
in vec2 vUv;
uniform float uTime;
uniform int uType;
uniform vec3 uColor;
uniform float uSize;
uniform float uSpeed;
out vec4 o;

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

void main() {
    vec2 uv = vUv;
    float t = uTime * uSpeed;
    float acc = 0.0;
    
    // Procedural multi-particle simulation
    for (int i = 0; i < 28; i++) {
        float fi = float(i);
        vec2 seed = vec2(fi * 17.13, fi * 31.41);
        float hx = hash(seed);
        float hy = hash(seed + 1.5);
        float speed = (0.2 + 0.8 * hash(seed + 3.1)) * uSpeed;
        
        vec2 p;
        if (uType == 3) {
            // Snow: fall downwards with gentle sway
            p = vec2(fract(hx + sin(t * 0.8 + fi) * 0.05), fract(hy - t * 0.25 * speed));
        } else if (uType == 4) {
            // Rain: fast downward streaks
            p = vec2(fract(hx + fi * 0.03), fract(hy - t * 1.5 * speed));
        } else if (uType == 5) {
            // Starfield warp: radial expansion from center
            vec2 dir = normalize(seed - 0.5);
            p = 0.5 + dir * fract(t * 0.2 * speed + hx);
        } else {
            // Bokeh / Sparks / Fireflies: organic floating motion
            p = vec2(
                fract(hx + sin(t * 0.4 + fi * 1.2) * 0.12),
                fract(hy + cos(t * 0.35 + fi * 0.9) * 0.12 - (uType == 1 ? t * 0.3 : 0.0))
            );
        }
        
        float d = distance(uv, p);
        float radius = (0.012 + 0.03 * hash(seed + 7.7)) * uSize;
        float b = smoothstep(radius, 0.0, d);
        if (uType == 0 || uType == 2) {
            // Glow halo for bokeh & fireflies
            b += smoothstep(radius * 3.5, 0.0, d) * 0.35;
        }
        acc += b;
    }
    
    vec3 col = uColor * clamp(acc, 0.0, 1.5);
    o = vec4(col, clamp(acc, 0.0, 1.0));
}"""
        particleProg = ParticleProg(link(vs, fs))
    }

    fun resize(p: Project, vw: Int, vh: Int) {
        val sc = min(vw / p.width.toFloat(), vh / p.height.toFloat())
        val w = (p.width * sc).toInt()
        val h = (p.height * sc).toInt()
        val x = (vw - w) / 2
        val y = (vh - h) / 2
        GL.glViewport(x, y, w, h)
        vScale = sc
        tools.rvx = x; tools.rvy = y; tools.rvw = w; tools.rvh = h
        Matrix.orthoM(proj, 0, 0f, p.width.toFloat(), p.height.toFloat(), 0f, -5000f, 5000f)
        val persp = ident.copyOf()
        persp[11] = -1f / (p.width * 1.2f)
        Matrix.setIdentityM(tmp, 0)
        Matrix.translateM(tmp, 0, p.width / 2f, p.height / 2f, 0f)
        Matrix.multiplyMM(view, 0, tmp, 0, persp, 0)
        Matrix.translateM(view, 0, -p.width / 2f, -p.height / 2f, 0f)
    }

    private fun prog(kind: Int, fx: Int): Prog {
        val idx = kind + 4 * fx
        return progs[idx] ?: Prog(link(vs(), fs(kind, fx == 1))).also {
            progs[idx] = it
            GL.glUseProgram(it.id)
            GL.glUniformMatrix4fv(it.texMat, 1, false, ident, 0)
        }
    }

    fun draw(p: Project, t: Long) {
        val startNs = System.nanoTime()
        GL.glClearColor(0.04f, 0.04f, 0.06f, 1f)
        GL.glClear(GL.GL_COLOR_BUFFER_BIT)
        GL.glBindVertexArray(tools.vao)
        var cur: Prog? = null

        for (l in p.layers) {
            if (!l.visible || t < l.inMs || t >= l.outMs) continue
            val a = l.opacity.valueAt(t)
            if (a <= 0.003f) continue
            val s = l.source

            // Handle GPU particle layer
            if (s is Source.Particles) {
                drawParticles(l, s, t, a)
                cur = null
                continue
            }

            val br = l.brightness.valueAt(t)
            val ct = l.contrast.valueAt(t)
            val sa = l.saturation.valueAt(t)
            val rs = l.rgbSplit.valueAt(t)
            val vg = l.vignette.valueAt(t)
            val pz = l.posterize.valueAt(t)
            val gr = l.grain.valueAt(t)
            val lutOn = l.lutId > 0
            val lggOn = l.lift.valueAt(t) != 0f || l.gamma.valueAt(t) != 1f || l.gain.valueAt(t) != 1f

            var fxOn = br != 0f || ct != 1f || sa != 1f || rs > 0f || vg > 0f || pz >= 2f || gr > 0f || l.maskType != 0 || lutOn || lggOn
            for (i in FX.indices) {
                var v = l.e[i].valueAt(t)
                if (lite && (i == 8 || i == 20)) v = 0f
                ev[i] = v
                if (v != 0f) fxOn = true
            }

            var v: VideoSource? = null
            var kind = 0
            if (s is Source.Video) {
                val vs0 = videos.getOrPut(l.id) { VideoSource(ctx, s.uri, tools).also { it.init() } }
                v = vs0
                val srcUs = l.srcMs(t) * 1000
                val back = if (l.remap.isAnimated) srcUs < (lastSrc[l.id] ?: srcUs) else l.reversed
                lastSrc[l.id] = srcUs
                val tm = if (lite && l.timeMode == 2) 1 else l.timeMode
                if (tm != 0 && !back && (l.speed != 1f || l.remap.isAnimated)) {
                    vs0.advanceBlend(srcUs, tm == 2)
                    kind = if (tm == 2) 3 else 2
                } else {
                    vs0.advanceTo(srcUs, back)
                    kind = 1
                }
                cur = null
            }

            // Tile-GPU optimization for Mali-G52:
            // When heavy multi-tap shader effects are active on video, copy external OES to 2D texture
            var effectiveKind = kind
            var useCached2D = false
            if (kind == 1 && fxOn && (ev[7] > 0f || ev[8] > 0f || ev[18] > 0f || ev[20] > 0f || ev[23] > 0f)) {
                val vv = v!!
                ensureIntermediateTex(vv.fullW, vv.fullH)
                tools.copy(vv.textureId, vv.texMat, interTex, vv.fullW, vv.fullH)
                effectiveKind = 0 // 2D texture instead of external OES
                useCached2D = true
            }

            val pr = prog(effectiveKind, if (fxOn) 1 else 0)
            if (pr !== cur) {
                GL.glUseProgram(pr.id)
                cur = pr
            }

            buildModel(l, t, model)
            if (l.threeD) {
                Matrix.multiplyMM(tmp, 0, view, 0, model, 0)
                Matrix.multiplyMM(mvp, 0, proj, 0, tmp, 0)
            } else {
                Matrix.multiplyMM(mvp, 0, proj, 0, model, 0)
            }
            GL.glUniformMatrix4fv(pr.mvp, 1, false, mvp, 0)

            if (fxOn) {
                GL.glUniform3f(pr.adj, br, ct, sa)
                GL.glUniform4f(pr.fx, rs, vg, pz, gr)
                GL.glUniform1fv(pr.e, FX.size, ev, 0)
                GL.glUniform4f(pr.mask, l.maskX.valueAt(t), l.maskY.valueAt(t), l.maskW.valueAt(t), l.maskH.valueAt(t))
                GL.glUniform3f(pr.maskP, l.maskFeather.valueAt(t), if (l.maskInvert) 1f else 0f, l.maskType.toFloat())
                GL.glUniform1f(pr.time, (t % 100000) / 1000f)
                GL.glUniform2f(pr.px, 1f / maxOf(1f, l.width), 1f / maxOf(1f, l.height))

                // 3D LUT & LGG Color grading uniforms
                GL.glUniform3f(pr.lgg, l.lift.valueAt(t), l.gamma.valueAt(t), l.gain.valueAt(t))
                if (lutOn) {
                    val lutTex = lutManager.getLutTexture(l.lutId)
                    GL.glActiveTexture(GL.GL_TEXTURE4)
                    GL.glBindTexture(GL.GL_TEXTURE_3D, lutTex)
                    GL.glUniform1i(pr.lut3d, 4)
                    GL.glUniform1f(pr.lutMix, l.lutIntensity)
                } else {
                    GL.glUniform1f(pr.lutMix, 0f)
                }
            }

            blend(l.blend)
            when (s) {
                is Source.Solid -> {
                    GL.glUniform1i(pr.useTex, 0)
                    GL.glUniform4f(pr.color, s.r * a, s.g * a, s.b * a, a)
                }
                is Source.Texture -> {
                    GL.glUniform1i(pr.useTex, 1)
                    GL.glUniform4f(pr.color, a, a, a, a)
                    GL.glActiveTexture(GL.GL_TEXTURE0)
                    GL.glBindTexture(GL.GL_TEXTURE_2D, s.glId)
                    GL.glUniform1i(pr.tex, 0)
                    GL.glUniformMatrix4fv(pr.texMat, 1, false, ident, 0)
                }
                is Source.Raster -> {
                    val tid = rasters.getOrPut(s) {
                        val ids = IntArray(1)
                        GL.glGenTextures(1, ids, 0)
                        GL.glBindTexture(GL.GL_TEXTURE_2D, ids[0])
                        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
                        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
                        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
                        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
                        GLUtils.texImage2D(GL.GL_TEXTURE_2D, 0, s.bitmap, 0)
                        ids[0]
                    }
                    GL.glUniform1i(pr.useTex, 1)
                    GL.glUniform4f(pr.color, a, a, a, a)
                    GL.glActiveTexture(GL.GL_TEXTURE0)
                    GL.glBindTexture(GL.GL_TEXTURE_2D, tid)
                    GL.glUniform1i(pr.tex, 0)
                    GL.glUniformMatrix4fv(pr.texMat, 1, false, flipM, 0)
                }
                is Source.Video -> {
                    val vv = v!!
                    GL.glUniform1i(pr.useTex, 1)
                    GL.glUniform4f(pr.color, a, a, a, a)
                    if (useCached2D) {
                        GL.glActiveTexture(GL.GL_TEXTURE0)
                        GL.glBindTexture(GL.GL_TEXTURE_2D, interTex)
                        GL.glUniform1i(pr.tex, 0)
                        GL.glUniformMatrix4fv(pr.texMat, 1, false, ident, 0)
                    } else if (kind == 1) {
                        GL.glActiveTexture(GL.GL_TEXTURE0)
                        GL.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, vv.textureId)
                        GL.glUniform1i(pr.tex, 0)
                        GL.glUniformMatrix4fv(pr.texMat, 1, false, vv.texMat, 0)
                    } else {
                        GL.glActiveTexture(GL.GL_TEXTURE0); GL.glBindTexture(GL.GL_TEXTURE_2D, vv.prevFull)
                        GL.glActiveTexture(GL.GL_TEXTURE1); GL.glBindTexture(GL.GL_TEXTURE_2D, vv.curFull)
                        if (kind == 3) {
                            GL.glActiveTexture(GL.GL_TEXTURE2); GL.glBindTexture(GL.GL_TEXTURE_2D, vv.flowTex)
                            GL.glUniform1i(pr.flow, 2)
                            GL.glUniform2f(pr.flowScale, 8f / vv.smallW, 8f / vv.smallH)
                        }
                        GL.glActiveTexture(GL.GL_TEXTURE0)
                        GL.glUniform1i(pr.tex, 0)
                        GL.glUniform1i(pr.tex2, 1)
                        GL.glUniform1f(pr.mix, vv.mix)
                        GL.glUniformMatrix4fv(pr.texMat, 1, false, ident, 0)
                    }
                }
                is Source.Particles -> {}
            }
            GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
        }
        com.nova.fx.vulkan.VulkanEngine.recordFrame(System.nanoTime() - startNs, p.width >= 3840 || p.height >= 3840)
    }

    private fun drawParticles(l: Layer, s: Source.Particles, t: Long, a: Float) {
        val pp = particleProg ?: return
        GL.glUseProgram(pp.id)
        buildModel(l, t, model)
        Matrix.multiplyMM(mvp, 0, proj, 0, model, 0)
        GL.glUniformMatrix4fv(pp.mvp, 1, false, mvp, 0)
        GL.glUniform1f(pp.time, (t % 1000000) / 1000f)
        GL.glUniform1i(pp.ptype, s.type)
        GL.glUniform3f(pp.pcolor, s.colorR * a, s.colorG * a, s.colorB * a)
        GL.glUniform1f(pp.psize, s.size)
        GL.glUniform1f(pp.pspeed, s.speed)
        blend(l.blend)
        GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun ensureIntermediateTex(w: Int, h: Int) {
        if (interTex == 0 || interW != w || interH != h) {
            if (interTex != 0) GL.glDeleteTextures(1, intArrayOf(interTex), 0)
            interTex = tools.makeTex(w, h)
            interW = w
            interH = h
        }
    }

    fun drop(id: Int) {
        videos.remove(id)?.release()
        lastSrc.remove(id)
    }

    fun release() {
        videos.values.forEach { it.release() }
        videos.clear()
        lastSrc.clear()
        rasters.clear()
        lutManager.release()
        if (interTex != 0) {
            GL.glDeleteTextures(1, intArrayOf(interTex), 0)
            interTex = 0
        }
        tools.release()
        if (particleProg != null) {
            GL.glDeleteProgram(particleProg!!.id)
            particleProg = null
        }
        for (i in progs.indices) {
            progs[i]?.let { GL.glDeleteProgram(it.id) }
            progs[i] = null
        }
    }

    private fun buildModel(l: Layer, t: Long, out: FloatArray) {
        val tr = l.transform
        val par = l.parent
        if (par != null) {
            buildModel(par, t, parentM)
            System.arraycopy(parentM, 0, out, 0, 16)
        } else {
            Matrix.setIdentityM(out, 0)
        }

        // Camera Shake / Wiggle (Fx 24)
        val shake = l.e.getOrNull(24)?.valueAt(t) ?: 0f
        var sx = 0f
        var sy = 0f
        if (shake > 0f) {
            val st = (t % 10000) / 1000f * 18f
            sx = (kotlin.math.sin(st) * 14f + kotlin.math.cos(st * 2.3f) * 8f) * shake
            sy = (kotlin.math.cos(st * 1.4f) * 12f + kotlin.math.sin(st * 3.1f) * 6f) * shake
        }

        Matrix.translateM(out, 0, tr.x.valueAt(t) + sx, tr.y.valueAt(t) + sy, 0f)
        Matrix.rotateM(out, 0, tr.rotation.valueAt(t), 0f, 0f, 1f)
        if (l.threeD) {
            Matrix.rotateM(out, 0, tr.rotY.valueAt(t), 0f, 1f, 0f)
            Matrix.rotateM(out, 0, tr.rotX.valueAt(t), 1f, 0f, 0f)
        }
        Matrix.scaleM(out, 0, tr.scaleX.valueAt(t), tr.scaleY.valueAt(t), 1f)
        Matrix.translateM(out, 0, -tr.anchorX.valueAt(t), -tr.anchorY.valueAt(t), 0f)
        Matrix.scaleM(out, 0, l.width, l.height, 1f)
    }

    private fun blend(m: BlendMode) = when (m) {
        BlendMode.NORMAL -> GL.glBlendFunc(GL.GL_ONE, GL.GL_ONE_MINUS_SRC_ALPHA)
        BlendMode.ADD -> GL.glBlendFunc(GL.GL_ONE, GL.GL_ONE)
        BlendMode.MULTIPLY -> GL.glBlendFunc(GL.GL_DST_COLOR, GL.GL_ONE_MINUS_SRC_ALPHA)
        BlendMode.SCREEN -> GL.glBlendFunc(GL.GL_ONE, GL.GL_ONE_MINUS_SRC_COLOR)
        BlendMode.OVERLAY -> GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE)
    }

    private fun link(v: String, f: String): Int {
        fun sh(type: Int, src: String): Int {
            val s = GL.glCreateShader(type); GL.glShaderSource(s, src); GL.glCompileShader(s)
            val ok = IntArray(1); GL.glGetShaderiv(s, GL.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { GL.glGetShaderInfoLog(s) }
            return s
        }
        val p = GL.glCreateProgram()
        GL.glAttachShader(p, sh(GL.GL_VERTEX_SHADER, v))
        GL.glAttachShader(p, sh(GL.GL_FRAGMENT_SHADER, f))
        GL.glLinkProgram(p)
        return p
    }

    private fun vs() = """#version 300 es
layout(location=0) in vec2 aPos;
uniform mat4 uMvp;
uniform mat4 uTexMat;
out mediump vec2 vUv;
out mediump vec2 vQ;
void main(){
    vQ = aPos;
    vUv = (uTexMat * vec4(aPos.x, 1.0 - aPos.y, 0.0, 1.0)).xy;
    gl_Position = uMvp * vec4(aPos, 0.0, 1.0);
}"""

    private fun fs(kind: Int, fx: Boolean): String {
        val oes = kind == 1
        val ext = if (oes) "#extension GL_OES_EGL_image_external_essl3 : require" else ""
        val smp = if (oes) "samplerExternalOES" else "sampler2D"
        val fr = when (kind) {
            2 -> "uniform sampler2D uTex2; uniform float uMix;\nvec4 FR(vec2 uv){ return mix(texture(uTex, uv), texture(uTex2, uv), uMix); }"
            3 -> "uniform sampler2D uTex2; uniform sampler2D uFlow; uniform float uMix; uniform vec2 uFlowScale;\n" +
                    "vec4 FR(vec2 uv){ vec2 f = (texture(uFlow, uv).rg - 0.5) * uFlowScale; return mix(texture(uTex, uv - f * uMix), texture(uTex2, uv + f * (1.0 - uMix)), uMix); }"
            else -> "vec4 FR(vec2 uv){ return texture(uTex, uv); }"
        }

        if (!fx) return """#version 300 es
$ext
precision mediump float;
in vec2 vUv;
uniform $smp uTex;
uniform vec4 uColor;
uniform int uUseTex;
out vec4 o;
$fr
void main(){
    o = (uUseTex == 1) ? FR(vUv) * uColor : uColor;
}"""

        val numFx = FX.size
        return """#version 300 es
$ext
precision mediump float;
in vec2 vQ;
uniform $smp uTex;
uniform mat4 uTexMat;
uniform vec4 uColor;
uniform vec3 uAdj;
uniform vec4 uFx;
uniform float uTime;
uniform vec2 uPx;
uniform float uE[$numFx];
uniform vec4 uMask;
uniform vec3 uMaskP;
uniform int uUseTex;
uniform mediump sampler3D uLut3d;
uniform float uLutMix;
uniform vec3 uLgg;
out vec4 o;
$fr

vec4 S(vec2 q){
    return uUseTex == 1 ? FR((uTexMat * vec4(q.x, 1.0 - q.y, 0.0, 1.0)).xy) * uColor : uColor;
}

float L(vec3 c){
    return dot(c, vec3(0.299, 0.587, 0.114));
}

highp float H(highp float x){
    return fract(sin(x) * 43758.5453);
}

void main(){
    vec2 q = vQ;

    // Pixelate / Wave / Swirl / Fisheye / Glitch
    if (uE[9] > 0.0) {
        float n = mix(240.0, 10.0, uE[9]);
        q = (floor(q * n) + 0.5) / n;
    }
    if (uE[10] > 0.0) {
        q += vec2(sin(q.y * 20.0 + uTime * 4.0), cos(q.x * 20.0 + uTime * 3.0)) * 0.02 * uE[10];
    }
    if (uE[11] != 0.0) {
        vec2 d = q - 0.5;
        float a = uE[11] * 3.0 * (1.0 - smoothstep(0.0, 0.7, length(d)));
        float s = sin(a), k = cos(a);
        q = 0.5 + vec2(k*d.x - s*d.y, s*d.x + k*d.y);
    }
    if (uE[12] != 0.0) {
        vec2 d = q - 0.5;
        q = 0.5 + d * (1.0 - uE[12] * (1.0 - length(d) * 2.0) * 0.6);
    }
    if (uE[13] > 0.0) {
        q.x = q.x > 0.5 ? 1.0 - q.x : q.x;
    }
    if (uE[15] > 0.0) {
        highp float row = floor(q.y * 20.0);
        highp float tt = floor(uTime * 12.0);
        float h = H(row * 91.7 + tt * 37.1);
        if (h > 1.0 - 0.25 * uE[15]) q.x += (fract(h * 7.0) - 0.5) * 0.2 * uE[15];
    }

    vec4 c = S(q);

    // Directional Motion Blur (uE[23])
    if (uE[23] > 0.0) {
        vec2 dir = vec2(uPx.x * 12.0, 0.0) * uE[23];
        vec4 mb = c * 0.4;
        mb += S(q + dir * 0.5) * 0.2;
        mb += S(q - dir * 0.5) * 0.2;
        mb += S(q + dir) * 0.1;
        mb += S(q - dir) * 0.1;
        c = mb;
    }

    // Chromatic Aberration & RGB Split (uE[18])
    if (uE[18] > 0.0 || uFx.x > 0.0) {
        vec2 d = (q - 0.5) * uE[18] + vec2(uFx.x, 0.0);
        c = vec4(S(q + d).r, c.g, S(q - d).b, c.a);
    }

    // Blur (uE[7])
    if (uE[7] > 0.0) {
        vec2 r = uPx * uE[7];
        c = c * 0.25 + (S(q + vec2(r.x, 0.0)) + S(q - vec2(r.x, 0.0)) + S(q + vec2(0.0, r.y)) + S(q - vec2(0.0, r.y))) * 0.125
            + (S(q + r) + S(q - r) + S(q + vec2(r.x, -r.y)) + S(q + vec2(-r.x, r.y))) * 0.0625;
    }

    // Glow / Optical Bloom (uE[8])
    if (uE[8] > 0.0) {
        vec2 r = uPx * 6.0;
        vec4 b = (S(q + r) + S(q - r) + S(q + vec2(r.x, -r.y)) + S(q + vec2(-r.x, r.y))) * 0.25;
        c.rgb += max(b.rgb - 0.45, 0.0) * uE[8] * 1.2;
    }

    // Sharpen (uE[6])
    if (uE[6] > 0.0) {
        vec2 r = uPx;
        vec4 n = S(q + vec2(r.x, 0.0)) + S(q - vec2(r.x, 0.0)) + S(q + vec2(0.0, r.y)) + S(q - vec2(0.0, r.y));
        c = c * (1.0 + 2.0 * uE[6]) - n * uE[6] * 0.5;
    }

    // Zoom blur (uE[20])
    if (uE[20] > 0.0) {
        vec4 s = vec4(0.0);
        for (int i = 0; i < 6; i++) s += S(q - (q - 0.5) * uE[20] * float(i) / 5.0);
        c = s / 6.0;
    }

    // Edge detect / Neon Outline (uE[16])
    if (uE[16] > 0.0) {
        vec2 r = uPx * 1.5;
        float gx = L(S(q + vec2(r.x, 0.0)).rgb) - L(S(q - vec2(r.x, 0.0)).rgb);
        float gy = L(S(q + vec2(0.0, r.y)).rgb) - L(S(q - vec2(0.0, r.y)).rgb);
        vec3 neon = vec3(0.0, 0.9, 1.0) * (length(vec2(gx, gy)) * 5.0);
        c.rgb = mix(c.rgb, neon * c.a, uE[16]);
    }

    vec3 rgb = c.a > 0.0 ? c.rgb / c.a : c.rgb;

    // Brightness, Contrast, Saturation
    rgb = (rgb - 0.5) * uAdj.y + 0.5 + uAdj.x;
    rgb = mix(vec3(L(rgb)), rgb, uAdj.z);

    // Lift, Gamma, Gain (Shadows, Midtones, Highlights)
    rgb = (rgb + vec3(uLgg.x)) * uLgg.z;
    rgb = pow(max(rgb, vec3(0.0)), vec3(1.0 / max(uLgg.y, 0.01)));

    // Exposure EV (uE[21])
    rgb *= exp2(uE[21]);

    // Color Invert (uE[0])
    rgb = mix(rgb, 1.0 - rgb, uE[0]);

    // Sepia (uE[1])
    vec3 sp = vec3(dot(rgb, vec3(0.393, 0.769, 0.189)), dot(rgb, vec3(0.349, 0.686, 0.168)), dot(rgb, vec3(0.272, 0.534, 0.131)));
    rgb = mix(rgb, sp, uE[1]);

    // Hue shift (uE[2])
    if (uE[2] != 0.0) {
        float a = uE[2] * 6.2832;
        vec3 k = vec3(0.57735);
        rgb = rgb * cos(a) + cross(k, rgb) * sin(a) + k * dot(k, rgb) * (1.0 - cos(a));
    }

    // Temperature & Tint (uE[3] & uE[25])
    rgb += vec3(uE[3], 0.0, -uE[3]) * 0.15;
    rgb += vec3(uE[25], -uE[25], uE[25]) * 0.1;

    // Threshold (uE[4])
    if (uE[4] > 0.0) rgb = vec3(step(uE[4], L(rgb)));

    // Duotone (uE[5])
    if (uE[5] > 0.0) rgb = mix(rgb, mix(vec3(0.1, 0.05, 0.35), vec3(1.0, 0.6, 0.2), L(rgb)), uE[5]);

    // Halftone dots (uE[17])
    if (uE[17] > 0.0) rgb = mix(rgb, vec3(step(length(fract(q * 70.0) - 0.5), L(rgb) * 0.7)), uE[17]);

    // Scanlines (uE[14])
    if (uE[14] > 0.0) rgb *= 1.0 - uE[14] * 0.5 * (1.0 + sin(q.y * 600.0));

    // Strobe / Flicker (uE[19])
    if (uE[19] > 0.0) rgb *= 1.0 - uE[19] * 0.5 * H(floor(uTime * 24.0));

    // Posterize & Vignette & Grain
    if (uFx.z >= 2.0) rgb = floor(rgb * uFx.z + 0.5) / uFx.z;
    if (uFx.y > 0.0) rgb *= 1.0 - uFx.y * 1.4 * smoothstep(0.3, 0.85, distance(q, vec2(0.5)));
    if (uFx.w > 0.0) rgb += (H(dot(q * vec2(437.0, 731.0) + uTime, vec2(12.9898, 78.233))) - 0.5) * uFx.w;

    // Highlights recovery (uE[26]) & Shadows lift (uE[27])
    if (uE[26] != 0.0) {
        float hlt = smoothstep(0.6, 1.0, L(rgb));
        rgb = mix(rgb, rgb * (1.0 + uE[26] * 0.3), hlt);
    }
    if (uE[27] != 0.0) {
        float shd = 1.0 - smoothstep(0.0, 0.4, L(rgb));
        rgb += vec3(uE[27] * 0.2 * shd);
    }

    // 3D LUT Hardware Color Grading
    if (uLutMix > 0.0) {
        vec3 lutColor = texture(uLut3d, clamp(rgb, 0.0, 1.0)).rgb;
        rgb = mix(rgb, lutColor, uLutMix);
    }

    float m = 1.0;

    // Advanced Chroma Key / Green Screen (uE[22]) with green spill suppression
    if (uE[22] > 0.0) {
        float diff = rgb.g - max(rgb.r, rgb.b);
        float th = mix(0.45, 0.05, uE[22]);
        m = 1.0 - smoothstep(th * 0.4, th, diff);
        // Spill suppression: desaturate green reflections on edges
        if (diff > 0.0) {
            rgb.g = min(rgb.g, max(rgb.r, rgb.b));
        }
    }

    // Rotoscoping / Shape Mask
    if (uMaskP.z > 0.0) {
        vec2 hh = uMask.zw * 0.5;
        float d;
        if (uMaskP.z < 1.5) {
            vec2 dd = abs(vQ - uMask.xy) - hh;
            d = max(dd.x, dd.y);
        } else {
            d = (length((vQ - uMask.xy) / hh) - 1.0) * min(hh.x, hh.y);
        }
        float mk = 1.0 - smoothstep(-uMaskP.x, uMaskP.x + 0.0001, d);
        m *= (uMaskP.y > 0.5 ? 1.0 - mk : mk);
    }

    o = vec4(clamp(rgb, 0.0, 1.0) * c.a * m, c.a * m);
}"""
    }
}
