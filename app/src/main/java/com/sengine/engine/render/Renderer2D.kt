package com.sengine.engine.render

import android.opengl.GLES20
import com.sengine.engine.math.Affine
import java.nio.FloatBuffer
import kotlin.math.round

/**
 * OpenGL ES 2D renderer. Sprites are transformed on the CPU into a streaming
 * vertex buffer and submitted in stable, texture-compatible batches. Draw order
 * is preserved (no global texture sort, which would break alpha compositing).
 */
class Renderer2D {
    private var spriteProg = 0
    private var lineProg = 0
    private var lineBuf: FloatBuffer = GL.floatBuffer(6 * INITIAL_LINES)
    private var lineData = FloatArray(6 * INITIAL_LINES)
    private var lineCount = 0

    private var spriteBuf: FloatBuffer = GL.floatBuffer(MAX_VERTICES * STRIDE)
    private var spriteVertexCount = 0
    private var batchTexture: Tex? = null
    private var batchTextureId = Int.MIN_VALUE
    private var pixelStep = 0f

    private var sPos = 0; private var sUV = 0; private var sColor = 0
    private var sLocal = 0; private var sShape = 0; private var sAA = 0
    private var sMVP = 0; private var sTex = 0; private var sUseTex = 0
    private var lPos = 0; private var lColor = 0; private var lMVP = 0

    val viewProj = FloatArray(16)
    private val tmp = Affine()

    @Volatile var drawCalls = 0
        private set
    @Volatile var spritesSubmitted = 0
        private set
    @Volatile var textureBinds = 0
        private set

    fun init() {
        spriteProg = GL.compile(SPRITE_VS, SPRITE_FS)
        lineProg = GL.compile(LINE_VS, LINE_FS)
        sPos = GLES20.glGetAttribLocation(spriteProg, "aPos")
        sUV = GLES20.glGetAttribLocation(spriteProg, "aUV")
        sColor = GLES20.glGetAttribLocation(spriteProg, "aColor")
        sLocal = GLES20.glGetAttribLocation(spriteProg, "aLocal")
        sShape = GLES20.glGetAttribLocation(spriteProg, "aShape")
        sAA = GLES20.glGetAttribLocation(spriteProg, "aAA")
        sMVP = GLES20.glGetUniformLocation(spriteProg, "uMVP")
        sTex = GLES20.glGetUniformLocation(spriteProg, "uTex")
        sUseTex = GLES20.glGetUniformLocation(spriteProg, "uUseTex")
        lPos = GLES20.glGetAttribLocation(lineProg, "aPos")
        lColor = GLES20.glGetAttribLocation(lineProg, "aColor")
        lMVP = GLES20.glGetUniformLocation(lineProg, "uMVP")
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
    }

    fun begin(view: View2D, pixelPerfect: Boolean = false) {
        flush()
        view.matrix(viewProj)
        pixelStep = if (pixelPerfect) 1f / view.pixelsPerUnit.coerceAtLeast(0.0001f) else 0f
        drawCalls = 0
        spritesSubmitted = 0
        textureBinds = 0
    }

    /** Flush queued geometry before crossing an ordering boundary. */
    fun flush() {
        flushSprites()
        flushLines()
    }

    /** Draw a unit quad transformed by [m]. Shape: 0 rect, 1 circle, 2 triangle, 3 ring. */
    fun quad(
        m: Affine,
        color: Int,
        shape: Int,
        tex: Tex?,
        aaPixels: Float,
        flipX: Boolean = false,
        flipY: Boolean = false,
        uvRect: FloatArray? = null,
    ) {
        if (lineCount > 0) flushLines()
        val textureId = tex?.id ?: 0
        if (spriteVertexCount > 0 && (textureId != batchTextureId || spriteVertexCount + 6 > MAX_VERTICES)) flushSprites()
        if (spriteVertexCount == 0) {
            batchTexture = tex
            batchTextureId = textureId
        }

        val uv = uvRect
        val u0 = (uv?.getOrNull(0) ?: 0f).coerceIn(0f, 1f)
        val v0 = (uv?.getOrNull(1) ?: 0f).coerceIn(0f, 1f)
        val maxW = (1f - u0).coerceAtLeast(0.001f)
        val maxH = (1f - v0).coerceAtLeast(0.001f)
        val uw = (uv?.getOrNull(2) ?: 1f).coerceAtLeast(0.001f).coerceAtMost(maxW)
        val vh = (uv?.getOrNull(3) ?: 1f).coerceAtLeast(0.001f).coerceAtMost(maxH)
        val leftU = if (flipX) u0 + uw else u0
        val rightU = if (flipX) u0 else u0 + uw
        val bottomV = if (flipY) 1f - v0 else 1f - v0 - vh
        val topV = if (flipY) 1f - v0 - vh else 1f - v0
        val red = GL.r(color); val green = GL.g(color); val blue = GL.b(color); val alpha = GL.a(color)
        val aa = aaPixels.coerceAtLeast(1f)

        // Triangle-list winding is 0,1,2 / 2,1,3; texture runs stay ordered.
        emitVertex(m.mapX(-0.5f, -0.5f), m.mapY(-0.5f, -0.5f), leftU, bottomV, red, green, blue, alpha, -0.5f, -0.5f, shape.toFloat(), aa)
        emitVertex(m.mapX(0.5f, -0.5f), m.mapY(0.5f, -0.5f), rightU, bottomV, red, green, blue, alpha, 0.5f, -0.5f, shape.toFloat(), aa)
        emitVertex(m.mapX(-0.5f, 0.5f), m.mapY(-0.5f, 0.5f), leftU, topV, red, green, blue, alpha, -0.5f, 0.5f, shape.toFloat(), aa)
        emitVertex(m.mapX(-0.5f, 0.5f), m.mapY(-0.5f, 0.5f), leftU, topV, red, green, blue, alpha, -0.5f, 0.5f, shape.toFloat(), aa)
        emitVertex(m.mapX(0.5f, -0.5f), m.mapY(0.5f, -0.5f), rightU, bottomV, red, green, blue, alpha, 0.5f, -0.5f, shape.toFloat(), aa)
        emitVertex(m.mapX(0.5f, 0.5f), m.mapY(0.5f, 0.5f), rightU, topV, red, green, blue, alpha, 0.5f, 0.5f, shape.toFloat(), aa)
        spritesSubmitted++
    }

    private fun emitVertex(
        x0: Float, y0: Float, u: Float, v: Float,
        r: Float, g: Float, b: Float, a: Float,
        localX: Float, localY: Float, shape: Float, aa: Float,
    ) {
        val x = if (pixelStep > 0f) round(x0 / pixelStep) * pixelStep else x0
        val y = if (pixelStep > 0f) round(y0 / pixelStep) * pixelStep else y0
        spriteBuf.put(x).put(y).put(u).put(v)
            .put(r).put(g).put(b).put(a)
            .put(localX).put(localY).put(shape).put(aa)
        spriteVertexCount++
    }

    /** Axis-aligned helper. */
    fun rect(cx: Float, cy: Float, w: Float, h: Float, color: Int, shape: Int, ppu: Float) {
        tmp.a = w; tmp.b = 0f; tmp.c = 0f; tmp.d = h; tmp.tx = cx; tmp.ty = cy
        quad(tmp, color, shape, null, minOf(w, h) * ppu)
    }

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int) {
        if (lineCount == 0) flushSprites()
        if ((lineCount + 2) * 6 > lineData.size) {
            lineData = lineData.copyOf(lineData.size * 2)
            lineBuf = GL.floatBuffer(lineData.size)
        }
        val red = GL.r(color); val green = GL.g(color); val blue = GL.b(color); val alpha = GL.a(color)
        var i = lineCount * 6
        lineData[i++] = x1; lineData[i++] = y1; lineData[i++] = red; lineData[i++] = green; lineData[i++] = blue; lineData[i++] = alpha
        lineData[i++] = x2; lineData[i++] = y2; lineData[i++] = red; lineData[i++] = green; lineData[i++] = blue; lineData[i] = alpha
        lineCount += 2
    }

    fun circleLines(cx: Float, cy: Float, radius: Float, color: Int, segments: Int = 40) {
        var px = cx + radius
        var py = cy
        for (i in 1..segments) {
            val angle = i * Math.PI * 2 / segments
            val nx = cx + (kotlin.math.cos(angle) * radius).toFloat()
            val ny = cy + (kotlin.math.sin(angle) * radius).toFloat()
            line(px, py, nx, ny, color)
            px = nx; py = ny
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

    fun flushSprites() {
        if (spriteVertexCount == 0) return
        GLES20.glUseProgram(spriteProg)
        GLES20.glUniformMatrix4fv(sMVP, 1, false, viewProj, 0)
        if (batchTexture != null) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, batchTexture!!.id)
            GLES20.glUniform1i(sTex, 0)
            GLES20.glUniform1f(sUseTex, 1f)
            textureBinds++
        } else GLES20.glUniform1f(sUseTex, 0f)

        spriteBuf.position(0)
        val strideBytes = STRIDE * 4
        GLES20.glEnableVertexAttribArray(sPos)
        GLES20.glEnableVertexAttribArray(sUV)
        GLES20.glEnableVertexAttribArray(sColor)
        GLES20.glEnableVertexAttribArray(sLocal)
        GLES20.glEnableVertexAttribArray(sShape)
        GLES20.glEnableVertexAttribArray(sAA)
        GLES20.glVertexAttribPointer(sPos, 2, GLES20.GL_FLOAT, false, strideBytes, spriteBuf)
        spriteBuf.position(2)
        GLES20.glVertexAttribPointer(sUV, 2, GLES20.GL_FLOAT, false, strideBytes, spriteBuf)
        spriteBuf.position(4)
        GLES20.glVertexAttribPointer(sColor, 4, GLES20.GL_FLOAT, false, strideBytes, spriteBuf)
        spriteBuf.position(8)
        GLES20.glVertexAttribPointer(sLocal, 2, GLES20.GL_FLOAT, false, strideBytes, spriteBuf)
        spriteBuf.position(10)
        GLES20.glVertexAttribPointer(sShape, 1, GLES20.GL_FLOAT, false, strideBytes, spriteBuf)
        spriteBuf.position(11)
        GLES20.glVertexAttribPointer(sAA, 1, GLES20.GL_FLOAT, false, strideBytes, spriteBuf)
        spriteBuf.position(0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, spriteVertexCount)
        GLES20.glDisableVertexAttribArray(sPos)
        GLES20.glDisableVertexAttribArray(sUV)
        GLES20.glDisableVertexAttribArray(sColor)
        GLES20.glDisableVertexAttribArray(sLocal)
        GLES20.glDisableVertexAttribArray(sShape)
        GLES20.glDisableVertexAttribArray(sAA)
        drawCalls++
        spriteVertexCount = 0
        batchTexture = null
        batchTextureId = Int.MIN_VALUE
        spriteBuf.clear()
    }

    fun flushLines(width: Float = 1f) {
        if (lineCount == 0) return
        flushSprites()
        GLES20.glUseProgram(lineProg)
        GLES20.glUniformMatrix4fv(lMVP, 1, false, viewProj, 0)
        lineBuf.position(0)
        lineBuf.put(lineData, 0, lineCount * 6)
        lineBuf.position(0)
        GLES20.glEnableVertexAttribArray(lPos)
        GLES20.glEnableVertexAttribArray(lColor)
        GLES20.glVertexAttribPointer(lPos, 2, GLES20.GL_FLOAT, false, 24, lineBuf)
        lineBuf.position(2)
        GLES20.glVertexAttribPointer(lColor, 4, GLES20.GL_FLOAT, false, 24, lineBuf)
        lineBuf.position(0)
        GLES20.glLineWidth(width)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, lineCount)
        GLES20.glDisableVertexAttribArray(lPos)
        GLES20.glDisableVertexAttribArray(lColor)
        drawCalls++
        lineCount = 0
    }

    companion object {
        private const val STRIDE = 12
        private const val MAX_VERTICES = 2048 * 6
        private const val INITIAL_LINES = 2048

        const val SPRITE_VS = """
uniform mat4 uMVP;
attribute vec2 aPos;
attribute vec2 aUV;
attribute vec4 aColor;
attribute vec2 aLocal;
attribute float aShape;
attribute float aAA;
varying vec2 vLocal;
varying vec2 vUV;
varying vec4 vColor;
varying float vShape;
varying float vAA;
void main() {
  vLocal = aLocal;
  vUV = aUV;
  vColor = aColor;
  vShape = aShape;
  vAA = aAA;
  gl_Position = uMVP * vec4(aPos, 0.0, 1.0);
}
"""
        const val SPRITE_FS = """
precision mediump float;
varying vec2 vLocal;
varying vec2 vUV;
varying vec4 vColor;
varying float vShape;
varying float vAA;
uniform sampler2D uTex;
uniform float uUseTex;
void main() {
  float a = 1.0;
  if (vShape > 0.5 && vShape < 1.5) {
    a = clamp((0.5 - length(vLocal)) * vAA, 0.0, 1.0);
  } else if (vShape > 1.5 && vShape < 2.5) {
    float w = (0.5 - vLocal.y) * 0.5;
    float e = min(w - abs(vLocal.x), vLocal.y + 0.5);
    a = clamp(e * vAA, 0.0, 1.0);
  } else if (vShape > 2.5) {
    float d = length(vLocal);
    a = clamp((0.5 - d) * vAA, 0.0, 1.0) * clamp((d - 0.40) * vAA, 0.0, 1.0);
  }
  vec4 c = vColor;
  if (uUseTex > 0.5) c *= texture2D(uTex, vUV);
  gl_FragColor = vec4(c.rgb, c.a * a);
}
"""
        const val LINE_VS = """
uniform mat4 uMVP;
attribute vec2 aPos;
attribute vec4 aColor;
varying vec4 vColor;
void main() { vColor = aColor; gl_Position = uMVP * vec4(aPos, 0.0, 1.0); }
"""
        const val LINE_FS = """
precision mediump float;
varying vec4 vColor;
void main() { gl_FragColor = vColor; }
"""
    }
}
