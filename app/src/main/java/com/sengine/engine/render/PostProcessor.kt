package com.sengine.engine.render

import android.opengl.GLES20
import java.nio.FloatBuffer

/** Renders the frame into an off-screen target, then draws it to the screen through a post effect. */
class PostProcessor {
    private var fbo = 0
    private var colorTex = 0
    private var depthRb = 0
    private var w = 0
    private var h = 0
    private lateinit var quad: FloatBuffer
    var active = false; private set

    fun init() {
        fbo = 0; colorTex = 0; depthRb = 0; w = 0; h = 0
        quad = GL.floatBuffer(8)
        quad.put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0)
    }

    private fun ensure(width: Int, height: Int): Boolean {
        if (fbo != 0 && width == w && height == h) return true
        release()
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0); colorTex = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colorTex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glGenRenderbuffers(1, ids, 0); depthRb = ids[0]
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, depthRb)
        GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16, width, height)
        GLES20.glGenFramebuffers(1, ids, 0); fbo = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, colorTex, 0)
        GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_RENDERBUFFER, depthRb)
        val ok = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        w = width; h = height
        if (!ok) { release(); return false }
        return true
    }

    fun release() {
        if (fbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        if (colorTex != 0) GLES20.glDeleteTextures(1, intArrayOf(colorTex), 0)
        if (depthRb != 0) GLES20.glDeleteRenderbuffers(1, intArrayOf(depthRb), 0)
        fbo = 0; colorTex = 0; depthRb = 0; w = 0; h = 0
    }

    /** Redirects rendering to the off-screen target. Returns false if unsupported. */
    fun begin(width: Int, height: Int): Boolean {
        active = ensure(width, height)
        if (active) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glViewport(0, 0, width, height)
        }
        return active
    }

    fun end(p: PostProgram, time: Float, intensity: Float): Int {
        if (!active) return 0
        active = false
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(p.id)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colorTex)
        GLES20.glUniform1i(p.uTex, 0)
        if (p.uTime >= 0) GLES20.glUniform1f(p.uTime, time)
        if (p.uParam >= 0) GLES20.glUniform1f(p.uParam, intensity)
        if (p.uResolution >= 0) GLES20.glUniform2f(p.uResolution, w.toFloat(), h.toFloat())
        quad.position(0)
        GLES20.glEnableVertexAttribArray(p.aPos)
        GLES20.glVertexAttribPointer(p.aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(p.aPos)
        GLES20.glEnable(GLES20.GL_BLEND)
        return 1
    }
}
