package com.sengine.engine.render

import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * Thin, allocation-free wrapper over OpenGL ES 2.0 with everything the 2D renderer needs:
 * programs, streaming vertex buffers, framebuffers, texture upload and state helpers.
 *
 * Everything in this file is 2D: no depth buffer, no cube maps, no 3D matrices.
 */
object GL {
    const val TAG = "SEngine"

    /** Compiles and links a program; returns 0 and logs on failure. */
    fun compile(vs: String, fs: String, label: String = "program"): Int {
        val v = shader(GLES20.GL_VERTEX_SHADER, vs, "$label.vert")
        val f = shader(GLES20.GL_FRAGMENT_SHADER, fs, "$label.frag")
        if (v == 0 || f == 0) return 0
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val st = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, st, 0)
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        if (st[0] == 0) {
            Log.e(TAG, "Link error in $label: " + GLES20.glGetProgramInfoLog(p))
            GLES20.glDeleteProgram(p)
            return 0
        }
        return p
    }

    /** Compiles and links, returning the program plus an error message (for the shader editor). */
    fun tryCompile(vs: String, fs: String): Pair<Int, String?> {
        val v = shader(GLES20.GL_VERTEX_SHADER, vs, "vs")
        if (v == 0) return 0 to shaderLog
        val f = shader(GLES20.GL_FRAGMENT_SHADER, fs, "fs")
        if (f == 0) return 0 to shaderLog
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val st = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, st, 0)
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        if (st[0] == 0) {
            val e = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            return 0 to "link: $e"
        }
        return p to null
    }

    var shaderLog = ""

    private fun shader(type: Int, src: String, label: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val st = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, st, 0)
        if (st[0] == 0) {
            shaderLog = "$label: ${GLES20.glGetShaderInfoLog(s)}"
            Log.e(TAG, "Shader error $label: ${GLES20.glGetShaderInfoLog(s)}")
            GLES20.glDeleteShader(s)
            return 0
        }
        return s
    }

    /** Uniform location cache: string lookups are expensive on Android. */
    private val uniforms = HashMap<Int, HashMap<String, Int>>()

    fun loc(program: Int, name: String): Int {
        val map = uniforms.getOrPut(program) { HashMap() }
        return map.getOrPut(name) { GLES20.glGetUniformLocation(program, name) }
    }

    fun attrib(program: Int, name: String): Int = GLES20.glGetAttribLocation(program, name)

    fun floatBuffer(floats: Int): FloatBuffer =
        ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun shortBuffer(shorts: Int): java.nio.ShortBuffer =
        ByteBuffer.allocateDirect(shorts * 2).order(ByteOrder.nativeOrder()).asShortBuffer()

    fun intBuffer(ints: Int): IntBuffer =
        ByteBuffer.allocateDirect(ints * 4).order(ByteOrder.nativeOrder()).asIntBuffer()

    // ------------------------------------------------------------------ state

    fun blendPremultiplied() {
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    fun blendAlpha() {
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    fun blendAdditive() {
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
    }

    fun blendMultiply() {
        GLES20.glBlendFunc(GLES20.GL_DST_COLOR, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    fun checkError(where: String) {
        val e = GLES20.glGetError()
        if (e != GLES20.GL_NO_ERROR) Log.w(TAG, "GL error 0x${Integer.toHexString(e)} at $where")
    }

    fun colorChannels(c: Int): FloatArray = floatArrayOf(
        ((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f, ((c ushr 24) and 0xFF) / 255f
    )

    fun r(c: Int) = ((c shr 16) and 0xFF) / 255f
    fun g(c: Int) = ((c shr 8) and 0xFF) / 255f
    fun b(c: Int) = (c and 0xFF) / 255f
    fun a(c: Int) = ((c ushr 24) and 0xFF) / 255f

    // ------------------------------------------------------------------ textures

    /** Creates an empty RGBA texture (used by framebuffers and the white/1x1 helper). */
    fun createTexture(width: Int, height: Int, linear: Boolean = true, clamp: Boolean = true): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, maxOf(1, width), maxOf(1, height), 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        val filter = if (linear) GLES20.GL_LINEAR else GLES20.GL_NEAREST
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
        val wrap = if (clamp) GLES20.GL_CLAMP_TO_EDGE else GLES20.GL_REPEAT
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, wrap)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, wrap)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    fun uploadBitmap(bitmap: android.graphics.Bitmap, nearest: Boolean = false, mipmaps: Boolean = false): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        val filter = if (nearest) GLES20.GL_NEAREST else GLES20.GL_LINEAR
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        if (mipmaps) {
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, if (nearest) GLES20.GL_NEAREST_MIPMAP_NEAREST else GLES20.GL_LINEAR_MIPMAP_LINEAR)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        } else {
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    fun deleteTexture(id: Int) {
        if (id != 0) GLES20.glDeleteTextures(1, intArrayOf(id), 0)
    }

    // ------------------------------------------------------------------ framebuffers

    /** Off-screen render target used for the light map, post processing and editor previews. */
    class Target(var width: Int, var height: Int) {
        var texture = 0; private set
        var fbo = 0; private set
        var depth = 0; private set

        init { create() }

        private fun create() {
            texture = createTexture(width, height, linear = true)
            val ids = IntArray(1)
            GLES20.glGenFramebuffers(1, ids, 0)
            fbo = ids[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0
            )
            checkError("Target.create")
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }

        fun resize(w: Int, h: Int) {
            if (w == width && h == height) return
            release()
            width = maxOf(1, w); height = maxOf(1, h)
            create()
        }

        fun bind() {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glViewport(0, 0, width, height)
        }

        fun release() {
            if (fbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            deleteTexture(texture)
            fbo = 0; texture = 0
        }
    }

    fun bindScreen(width: Int, height: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, width, height)
    }

    /** Uploads raw RGBA bytes (used by texture atlases and procedural tile sheets). */
    fun uploadPixels(width: Int, height: Int, pixels: IntArray, nearest: Boolean = true): Int {
        val buf = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        for (p in pixels) {
            buf.put(((p shr 16) and 0xFF).toByte())
            buf.put(((p shr 8) and 0xFF).toByte())
            buf.put((p and 0xFF).toByte())
            buf.put(((p ushr 24) and 0xFF).toByte())
        }
        buf.position(0)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        val filter = if (nearest) GLES20.GL_NEAREST else GLES20.GL_LINEAR
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return ids[0]
    }
}
