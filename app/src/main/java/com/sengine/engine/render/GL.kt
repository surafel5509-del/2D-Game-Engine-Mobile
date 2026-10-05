package com.sengine.engine.render

import android.opengl.GLES20
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object GL {
    /** Compiles and links; returns program id (0 on failure) and the error log. */
    fun tryCompile(vs: String, fs: String): Pair<Int, String?> {
        val v = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER)
        GLES20.glShaderSource(v, vs); GLES20.glCompileShader(v)
        val st = IntArray(1)
        GLES20.glGetShaderiv(v, GLES20.GL_COMPILE_STATUS, st, 0)
        if (st[0] == 0) { val e = GLES20.glGetShaderInfoLog(v); GLES20.glDeleteShader(v); return 0 to "vertex: $e" }
        val f = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER)
        GLES20.glShaderSource(f, fs); GLES20.glCompileShader(f)
        GLES20.glGetShaderiv(f, GLES20.GL_COMPILE_STATUS, st, 0)
        if (st[0] == 0) { val e = GLES20.glGetShaderInfoLog(f); GLES20.glDeleteShader(v); GLES20.glDeleteShader(f); return 0 to e }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f); GLES20.glLinkProgram(p)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, st, 0)
        GLES20.glDeleteShader(v); GLES20.glDeleteShader(f)
        if (st[0] == 0) { val e = GLES20.glGetProgramInfoLog(p); GLES20.glDeleteProgram(p); return 0 to "link: $e" }
        return p to null
    }

    fun compile(vs: String, fs: String): Int {
        val v = shader(GLES20.GL_VERTEX_SHADER, vs)
        val f = shader(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val st = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, st, 0)
        if (st[0] == 0) Log.e("SEngine", "Link error: " + GLES20.glGetProgramInfoLog(p))
        return p
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val st = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, st, 0)
        if (st[0] == 0) Log.e("SEngine", "Shader error: " + GLES20.glGetShaderInfoLog(s))
        return s
    }

    fun floatBuffer(n: Int): FloatBuffer =
        ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun r(c: Int) = ((c shr 16) and 0xFF) / 255f
    fun g(c: Int) = ((c shr 8) and 0xFF) / 255f
    fun b(c: Int) = (c and 0xFF) / 255f
    fun a(c: Int) = ((c ushr 24) and 0xFF) / 255f
}
