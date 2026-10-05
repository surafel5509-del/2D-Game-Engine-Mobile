package com.sengine.engine.render

import android.opengl.GLES20
import com.sengine.engine.math.Affine
import com.sengine.engine.math.Mat4
import java.nio.FloatBuffer

/** Immediate-mode drawing of shaped / textured quads (optionally with custom shaders) and coloured 3D lines. */
class Renderer2D {
    lateinit var shaders: ShaderLibrary
    private var lineProg = 0
    private lateinit var quad: FloatBuffer
    private var lineBuf: FloatBuffer = GL.floatBuffer(7 * 4096)
    private var lineData = FloatArray(7 * 4096)
    private var lineCount = 0
    private var lPos = 0; private var lColor = 0; private var lMVP = 0

    val viewProj = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val tmp = Affine()
    private val defaultUv = floatArrayOf(0f, 1f, 1f, 0f)

    var drawCalls = 0
    var time = 0f
    var resW = 1f
    var resH = 1f

    fun init(shaders: ShaderLibrary) {
        this.shaders = shaders
        lineProg = GL.compile(LINE_VS, LINE_FS)
        lPos = GLES20.glGetAttribLocation(lineProg, "aPos")
        lColor = GLES20.glGetAttribLocation(lineProg, "aColor")
        lMVP = GLES20.glGetUniformLocation(lineProg, "uMVP")
        quad = GL.floatBuffer(8)
        quad.put(floatArrayOf(-0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f)).position(0)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
    }

    fun begin(view: View2D) { view.matrix(viewProj) }

    fun begin(m: FloatArray) { System.arraycopy(m, 0, viewProj, 0, 16) }

    /**
     * Draw a unit quad transformed by [m].
     * shape: 0 rect, 1 circle, 2 triangle, 3 ring
     */
    fun quad(m: Affine, color: Int, shape: Int, tex: Tex?, aaPixels: Float, flipX: Boolean = false, flipY: Boolean = false,
             uv: FloatArray? = null, program: SpriteProgram? = null, param: Float = 1f) {
        m.toMat4(model)
        quadModel(model, color, shape, tex, aaPixels, flipX, flipY, uv, program, param)
    }

    /** Draw a unit quad (XY plane) with a full 4x4 model matrix. */
    fun quadModel(modelM: FloatArray, color: Int, shape: Int, tex: Tex?, aaPixels: Float, flipX: Boolean = false, flipY: Boolean = false,
                  uv: FloatArray? = null, program: SpriteProgram? = null, param: Float = 1f) {
        val p = program ?: shaders.defaultSprite
        GLES20.glUseProgram(p.id)
        Mat4.mul(mvp, viewProj, modelM)
        GLES20.glUniformMatrix4fv(p.uMVP, 1, false, mvp, 0)
        GLES20.glUniform4f(p.uColor, GL.r(color), GL.g(color), GL.b(color), GL.a(color))
        GLES20.glUniform1f(p.uShape, shape.toFloat())
        GLES20.glUniform1f(p.uAA, aaPixels.coerceAtLeast(1f))
        val q = uv ?: defaultUv
        val u0 = if (flipX) q[2] else q[0]
        val u1 = if (flipX) q[0] else q[2]
        val v0 = if (flipY) q[3] else q[1]
        val v1 = if (flipY) q[1] else q[3]
        GLES20.glUniform4f(p.uUV, u0, v0, u1, v1)
        if (p.uTime >= 0) GLES20.glUniform1f(p.uTime, time)
        if (p.uParam >= 0) GLES20.glUniform1f(p.uParam, param)
        if (p.uResolution >= 0) GLES20.glUniform2f(p.uResolution, resW, resH)
        if (tex != null) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex.id)
            GLES20.glUniform1i(p.uTex, 0)
            GLES20.glUniform1f(p.uUseTex, 1f)
        } else GLES20.glUniform1f(p.uUseTex, 0f)
        quad.position(0)
        GLES20.glEnableVertexAttribArray(p.aPos)
        GLES20.glVertexAttribPointer(p.aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(p.aPos)
        drawCalls++
    }

    /** Axis-aligned helper. */
    fun rect(cx: Float, cy: Float, w: Float, h: Float, color: Int, shape: Int, ppu: Float, tex: Tex? = null) {
        tmp.a = w; tmp.b = 0f; tmp.c = 0f; tmp.d = h; tmp.tx = cx; tmp.ty = cy
        quad(tmp, color, shape, tex, minOf(w, h) * ppu)
    }

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int) = line3(x1, y1, 0f, x2, y2, 0f, color)

    fun line3(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float, color: Int) {
        if ((lineCount + 2) * 7 > lineData.size) {
            lineData = lineData.copyOf(lineData.size * 2)
            lineBuf = GL.floatBuffer(lineData.size)
        }
        val r = GL.r(color); val g = GL.g(color); val b = GL.b(color); val a = GL.a(color)
        var i = lineCount * 7
        lineData[i++] = x1; lineData[i++] = y1; lineData[i++] = z1; lineData[i++] = r; lineData[i++] = g; lineData[i++] = b; lineData[i++] = a
        lineData[i++] = x2; lineData[i++] = y2; lineData[i++] = z2; lineData[i++] = r; lineData[i++] = g; lineData[i++] = b; lineData[i] = a
        lineCount += 2
    }

    fun circleLines(cx: Float, cy: Float, r: Float, color: Int, seg: Int = 40) {
        var px = cx + r
        var py = cy
        for (i in 1..seg) {
            val a = i * Math.PI * 2 / seg
            val nx = cx + (Math.cos(a) * r).toFloat()
            val ny = cy + (Math.sin(a) * r).toFloat()
            line(px, py, nx, ny, color)
            px = nx; py = ny
        }
    }

    /** Circle in 3D around an axis (0 = X, 1 = Y, 2 = Z). */
    fun circle3(cx: Float, cy: Float, cz: Float, r: Float, axis: Int, color: Int, seg: Int = 48) {
        var prev: FloatArray? = null
        for (i in 0..seg) {
            val a = i * Math.PI * 2 / seg
            val c = (Math.cos(a) * r).toFloat(); val s = (Math.sin(a) * r).toFloat()
            val p = when (axis) {
                0 -> floatArrayOf(cx, cy + c, cz + s)
                1 -> floatArrayOf(cx + c, cy, cz + s)
                else -> floatArrayOf(cx + c, cy + s, cz)
            }
            prev?.let { line3(it[0], it[1], it[2], p[0], p[1], p[2], color) }
            prev = p
        }
    }

    /** Wire box of the local unit cube [min,max] transformed by a 4x4 matrix. */
    fun wireBox(m: FloatArray, min: FloatArray, max: FloatArray, color: Int) {
        val c = Array(8) { i ->
            Mat4.point(m, if (i and 1 == 0) min[0] else max[0], if (i and 2 == 0) min[1] else max[1], if (i and 4 == 0) min[2] else max[2])
        }
        val edges = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 0, 2, 1, 3, 4, 6, 5, 7, 0, 4, 1, 5, 2, 6, 3, 7)
        var k = 0
        while (k < edges.size) {
            val a = c[edges[k]]; val b = c[edges[k + 1]]
            line3(a[0], a[1], a[2], b[0], b[1], b[2], color)
            k += 2
        }
    }

    fun obb(m: Affine, hw: Float, hh: Float, color: Int) {
        val xs = floatArrayOf(-hw, hw, hw, -hw)
        val ys = floatArrayOf(-hh, -hh, hh, hh)
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            line(m.mapX(xs[i], ys[i]), m.mapY(xs[i], ys[i]), m.mapX(xs[j], ys[j]), m.mapY(xs[j], ys[j]), color)
        }
    }

    fun flushLines(width: Float = 1f) {
        if (lineCount == 0) return
        GLES20.glUseProgram(lineProg)
        GLES20.glUniformMatrix4fv(lMVP, 1, false, viewProj, 0)
        lineBuf.position(0)
        lineBuf.put(lineData, 0, lineCount * 7)
        lineBuf.position(0)
        GLES20.glEnableVertexAttribArray(lPos)
        GLES20.glEnableVertexAttribArray(lColor)
        GLES20.glVertexAttribPointer(lPos, 3, GLES20.GL_FLOAT, false, 28, lineBuf)
        lineBuf.position(3)
        GLES20.glVertexAttribPointer(lColor, 4, GLES20.GL_FLOAT, false, 28, lineBuf)
        GLES20.glLineWidth(width)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, lineCount)
        GLES20.glDisableVertexAttribArray(lPos)
        GLES20.glDisableVertexAttribArray(lColor)
        lineCount = 0
        drawCalls++
    }

    companion object {
        const val LINE_VS = """
uniform mat4 uMVP;
attribute vec3 aPos;
attribute vec4 aColor;
varying vec4 vColor;
void main() { vColor = aColor; gl_Position = uMVP * vec4(aPos, 1.0); }
"""
        const val LINE_FS = """
precision mediump float;
varying vec4 vColor;
void main() { gl_FragColor = vColor; }
"""
    }
}
