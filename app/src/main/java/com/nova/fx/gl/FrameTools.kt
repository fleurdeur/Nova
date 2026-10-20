package com.nova.fx.gl

import android.opengl.GLES30 as GL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-performance offscreen rendering utilities, FBO management, and GPU optical-flow.
 * Shared quad VAO and texture tools optimized for ARM Mali / tile GPUs.
 */
class FrameTools {
    var vao = 0; private set
    private var copyP = 0; private var flowP = 0; private var fbo = 0
    private var cMat = 0; private var cTex = 0; private var fPrev = 0; private var fCur = 0; private var fPx = 0
    var rvx = 0; var rvy = 0; var rvw = 1; var rvh = 1

    fun init() {
        copyP = link(
            """#version 300 es
layout(location=0) in vec2 aPos; uniform mat4 uTexMat; out vec2 vUv;
void main(){ vUv = (uTexMat * vec4(aPos, 0.0, 1.0)).xy; gl_Position = vec4(aPos * 2.0 - 1.0, 0.0, 1.0); }""",
            """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float; in vec2 vUv; uniform samplerExternalOES uTex; out vec4 o;
void main(){ o = texture(uTex, vUv); }"""
        )

        flowP = link(
            """#version 300 es
layout(location=0) in vec2 aPos; out vec2 vUv; void main(){ vUv = aPos; gl_Position = vec4(aPos * 2.0 - 1.0, 0.0, 1.0); }""",
            """#version 300 es
precision highp float; in vec2 vUv; uniform sampler2D uPrev; uniform sampler2D uCur; uniform vec2 uPx; out vec4 o;
float Lp(vec2 p){ return dot(texture(uPrev, p).rgb, vec3(0.299, 0.587, 0.114)); }
float Lc(vec2 p){ return dot(texture(uCur, p).rgb, vec3(0.299, 0.587, 0.114)); }
void main(){
  float a[9]; int k = 0;
  for (int j = -1; j <= 1; j++) for (int i = -1; i <= 1; i++) { a[k] = Lp(vUv + vec2(float(i), float(j)) * uPx); k++; }
  float best = 1e9; vec2 bd = vec2(0.0);
  for (int dy = -4; dy <= 4; dy++) for (int dx = -4; dx <= 4; dx++) {
    vec2 d = vec2(float(dx), float(dy)) * uPx; float c = 0.0; int m = 0;
    for (int j = -1; j <= 1; j++) for (int i = -1; i <= 1; i++) { c += abs(a[m] - Lc(vUv + d + vec2(float(i), float(j)) * uPx)); m++; }
    c += 0.02 * length(vec2(float(dx), float(dy)));
    if (c < best) { best = c; bd = vec2(float(dx), float(dy)); }
  }
  o = vec4(0.5 + bd / 8.0, 0.0, 1.0);
}"""
        )

        cMat = GL.glGetUniformLocation(copyP, "uTexMat"); cTex = GL.glGetUniformLocation(copyP, "uTex")
        fPrev = GL.glGetUniformLocation(flowP, "uPrev"); fCur = GL.glGetUniformLocation(flowP, "uCur"); fPx = GL.glGetUniformLocation(flowP, "uPx")

        val ids = IntArray(1)
        GL.glGenVertexArrays(1, ids, 0); vao = ids[0]; GL.glBindVertexArray(vao)
        val fb = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); fb.position(0)
        GL.glGenBuffers(1, ids, 0); GL.glBindBuffer(GL.GL_ARRAY_BUFFER, ids[0]); GL.glBufferData(GL.GL_ARRAY_BUFFER, 32, fb, GL.GL_STATIC_DRAW)
        GL.glEnableVertexAttribArray(0); GL.glVertexAttribPointer(0, 2, GL.GL_FLOAT, false, 8, 0)
        GL.glGenFramebuffers(1, ids, 0); fbo = ids[0]
    }

    fun makeTex(w: Int, h: Int): Int {
        val t = IntArray(1); GL.glGenTextures(1, t, 0); GL.glBindTexture(GL.GL_TEXTURE_2D, t[0])
        GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA, w, h, 0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, null)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR); GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE); GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
        return t[0]
    }

    fun begin(dst: Int, w: Int, h: Int) {
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, fbo)
        GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, dst, 0)
        GL.glViewport(0, 0, w, h)
        GL.glDisable(GL.GL_BLEND)
        GL.glBindVertexArray(vao)
    }

    fun end() {
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
        GL.glViewport(rvx, rvy, rvw, rvh)
        GL.glEnable(GL.GL_BLEND)
    }

    fun copy(oes: Int, texMat: FloatArray, dst: Int, w: Int, h: Int) {
        begin(dst, w, h)
        GL.glUseProgram(copyP)
        GL.glUniformMatrix4fv(cMat, 1, false, texMat, 0)
        GL.glActiveTexture(GL.GL_TEXTURE0)
        GL.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes)
        GL.glUniform1i(cTex, 0)
        GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
        end()
    }

    fun flow(prev: Int, cur: Int, dst: Int, w: Int, h: Int) {
        begin(dst, w, h)
        GL.glUseProgram(flowP)
        GL.glActiveTexture(GL.GL_TEXTURE0); GL.glBindTexture(GL.GL_TEXTURE_2D, prev); GL.glUniform1i(fPrev, 0)
        GL.glActiveTexture(GL.GL_TEXTURE1); GL.glBindTexture(GL.GL_TEXTURE_2D, cur); GL.glUniform1i(fCur, 1)
        GL.glUniform2f(fPx, 1f / w, 1f / h)
        GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
        GL.glActiveTexture(GL.GL_TEXTURE0)
        end()
    }

    fun read(tex: Int, w: Int, h: Int, buf: ByteBuffer) {
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, fbo)
        GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, tex, 0)
        GL.glReadPixels(0, 0, w, h, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, buf)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
    }

    fun release() {
        if (fbo != 0) {
            GL.glDeleteFramebuffers(1, intArrayOf(fbo), 0); fbo = 0
        }
        if (vao != 0) {
            GL.glDeleteVertexArrays(1, intArrayOf(vao), 0); vao = 0
        }
        if (copyP != 0) { GL.glDeleteProgram(copyP); copyP = 0 }
        if (flowP != 0) { GL.glDeleteProgram(flowP); flowP = 0 }
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
}
