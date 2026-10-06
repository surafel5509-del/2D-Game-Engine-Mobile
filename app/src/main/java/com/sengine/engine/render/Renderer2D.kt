package com.sengine.engine.render

import android.opengl.GLES20
import com.sengine.engine.core.SpriteRenderer
import com.sengine.engine.fx.ParticleSystem2D
import com.sengine.engine.fx.Trail2D
import com.sengine.engine.math.Affine
import com.sengine.engine.math.Colors
import com.sengine.engine.math.M
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/** Render statistics shown in the profiler overlay. */
class RenderStats {
    var drawCalls = 0
    var quadCount = 0
    var vertexCount = 0
    var textureSwaps = 0
    var batchCount = 0
    var sprites = 0
    var particles = 0
    var glyphs = 0
    var shapes = 0
    var tileQuads = 0
    var lights = 0
    var shadowQuads = 0
    var culled = 0
    var cpuMs = 0f

    fun reset() {
        drawCalls = 0; quadCount = 0; vertexCount = 0; textureSwaps = 0; batchCount = 0
        sprites = 0; particles = 0; glyphs = 0; shapes = 0; tileQuads = 0; lights = 0
        shadowQuads = 0; culled = 0
    }

    override fun toString() =
        "calls=$drawCalls quads=$quadCount sprites=$sprites tiles=$tileQuads particles=$particles glyphs=$glyphs shapes=$shapes"
}

/**
 * The batched 2D renderer.
 *
 * Two primitive families cover everything a 2D game draws:
 *  - **quad batch** - sprites, glyphs, particles, trails, tile chunks: position + uv + packed
 *    RGBA colour, uploaded with `glBufferData` and drawn with one `glDrawElements`,
 *  - **shape batch** - rounded rectangles, circles, capsules and gizmos whose SDF parameters
 *    travel per vertex, so hundreds of different shapes still batch into a single draw call.
 *
 * Draw calls are minimised by sorting submissions, flushing only on texture/program/blend/
 * material changes and using 16-bit index buffers limited to [MAX_QUADS] quads. Strictly 2D:
 * no depth buffer, no perspective projection and no 3D lighting or mesh pipeline.
 */
class Renderer2D {

    lateinit var shaders: ShaderLibrary
    lateinit var materials: MaterialLibrary
    lateinit var textures: TextureCache
    lateinit var fonts: FontCache

    val stats = RenderStats()

    var totalFrames = 0L
        private set
    var frameMilliseconds = 0f
        private set

    private lateinit var quadVerts: FloatBuffer
    private lateinit var quadColors: ByteBuffer
    private lateinit var quadIndices: ShortBuffer
    private var quadIndexCount = 0
    private var quadVertCount = 0

    private lateinit var shapeVerts: FloatBuffer
    private lateinit var shapeColors: ByteBuffer
    private lateinit var shapeIndices: ShortBuffer
    private var shapeIndexCount = 0
    private var shapeVertCount = 0

    private var vboQuadPos = 0
    private var vboQuadColor = 0
    private var vboShapePos = 0
    private var vboShapeColor = 0

    private var currentProgram: Program? = null
    private var currentTexture = 0
    private var currentMaterialKey: String? = null
    private var currentBlend = -1
    private var cpuStart = 0L

    /** Current projection matrix (2D orthographic). */
    val projection = FloatArray(16)

    var screenWidth = 1f
        private set
    var screenHeight = 1f
        private set
    var time = 0f
    /** Screen pixels per world unit - drives pixel snapping and font rasterisation size. */
    var pixelsPerUnit = 100f

    /** Temporary translation applied to submitted geometry (local-space particle systems). */
    private var offsetX = 0f
    private var offsetY = 0f
    private var offsetStack = 0

    companion object {
        /** Maximum quads per batch: 8192 * 4 = 32768 vertices, which fits in 16-bit indices. */
        const val MAX_QUADS = 8192
        private const val FLOATS_PER_VERTEX = 4
        private const val SHAPE_FLOATS_PER_VERTEX = 8

        const val BLEND_ALPHA = 0
        const val BLEND_ADDITIVE = 1
        const val BLEND_MULTIPLY = 2
        const val BLEND_PREMULTIPLIED = 3
        const val BLEND_NONE = 4
    }

    fun init(shaders: ShaderLibrary, materials: MaterialLibrary, textures: TextureCache, fonts: FontCache) {
        this.shaders = shaders
        this.materials = materials
        this.textures = textures
        this.fonts = fonts

        quadVerts = GL.floatBuffer(MAX_QUADS * 4 * FLOATS_PER_VERTEX)
        quadColors = ByteBuffer.allocateDirect(MAX_QUADS * 4 * 4).order(java.nio.ByteOrder.nativeOrder())
        quadIndices = GL.shortBuffer(MAX_QUADS * 6)
        shapeVerts = GL.floatBuffer(MAX_QUADS * 4 * SHAPE_FLOATS_PER_VERTEX)
        shapeColors = ByteBuffer.allocateDirect(MAX_QUADS * 4 * 4).order(java.nio.ByteOrder.nativeOrder())
        shapeIndices = GL.shortBuffer(MAX_QUADS * 6)
        fillIndices(quadIndices)
        fillIndices(shapeIndices)

        val ids = IntArray(2)
        GLES20.glGenBuffers(2, ids, 0)
        vboQuadPos = ids[0]; vboShapePos = ids[1]
        GLES20.glGenBuffers(2, ids, 0)
        vboQuadColor = ids[0]; vboShapeColor = ids[1]

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_BLEND)
    }

    private fun fillIndices(buffer: ShortBuffer) {
        for (q in 0 until MAX_QUADS) {
            val v = q * 4
            buffer.put(v.toShort()); buffer.put((v + 1).toShort()); buffer.put((v + 2).toShort())
            buffer.put((v + 2).toShort()); buffer.put((v + 3).toShort()); buffer.put(v.toShort())
        }
        buffer.position(0)
    }

    /** Called on surface size changes / orientation switches. */
    fun resize(width: Int, height: Int) {
        screenWidth = width.coerceAtLeast(1).toFloat()
        screenHeight = height.coerceAtLeast(1).toFloat()
    }

    fun release() {
        if (vboQuadPos != 0) GLES20.glDeleteBuffers(2, intArrayOf(vboQuadPos, vboShapePos), 0)
        if (vboQuadColor != 0) GLES20.glDeleteBuffers(2, intArrayOf(vboQuadColor, vboShapeColor), 0)
        vboQuadPos = 0; vboShapePos = 0; vboQuadColor = 0; vboShapeColor = 0
    }

    // ------------------------------------------------------------------ frame setup

    fun beginFrame(fbWidth: Int, fbHeight: Int, clearColor: Int) {
        stats.reset()
        totalFrames++
        cpuStart = System.nanoTime()
        screenWidth = fbWidth.toFloat()
        screenHeight = fbHeight.toFloat()
        GLES20.glViewport(0, 0, fbWidth, fbHeight)
        GLES20.glClearColor(GL.r(clearColor), GL.g(clearColor), GL.b(clearColor), GL.a(clearColor))
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        currentBlend = -1
        setBlend(BLEND_PREMULTIPLIED)
        currentProgram = null
        currentTexture = 0
        currentMaterialKey = null
    }

    fun endFrame() {
        flush()
        frameMilliseconds = ((System.nanoTime() - cpuStart) / 1_000_000.0).toFloat()
        stats.cpuMs = frameMilliseconds
    }

    fun setWorldProjection(cx: Float, cy: Float, halfW: Float, halfH: Float) {
        setOrtho(projection, cx - halfW, cx + halfW, cy - halfH, cy + halfH)
    }

    /** Screen-space projection with the origin at the top-left and +Y pointing down. */
    fun setScreenProjection(width: Float, height: Float) {
        setOrtho(projection, 0f, width, height, 0f)
    }

    fun setOrtho(out: FloatArray, left: Float, right: Float, bottom: Float, top: Float) {
        out[0] = 2f / (right - left); out[1] = 0f; out[2] = 0f; out[3] = 0f
        out[4] = 0f; out[5] = 2f / (top - bottom); out[6] = 0f; out[7] = 0f
        out[8] = 0f; out[9] = 0f; out[10] = -1f; out[11] = 0f
        out[12] = -(right + left) / (right - left)
        out[13] = -(top + bottom) / (top - bottom)
        out[14] = 0f; out[15] = 1f
    }

    /** Changes the blend mode; flushes first because blending is applied per draw call. */
    fun setBlend(mode: Int) {
        if (mode == currentBlend) return
        if (quadIndexCount > 0 || shapeIndexCount > 0) flush()
        currentBlend = mode
        when (mode) {
            BLEND_ALPHA -> GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            BLEND_PREMULTIPLIED -> GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            BLEND_ADDITIVE -> GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
            BLEND_MULTIPLY -> GLES20.glBlendFunc(GLES20.GL_DST_COLOR, GLES20.GL_ZERO)
            BLEND_NONE -> GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ZERO)
        }
    }

    // ------------------------------------------------------------------ quad batch

    private fun ensureQuadSpace() {
        if (quadVertCount + 4 > MAX_QUADS * 4) flush()
    }

    /**
     * Adds a rotated quad. The quad is centred on ([cx], [cy]) and the pivot offsets it inside its
     * own box, exactly like a sprite pivot.
     */
    fun quad(
        cx: Float, cy: Float, width: Float, height: Float,
        rotationDeg: Float, pivotX: Float, pivotY: Float,
        u0: Float, v0: Float, u1: Float, v1: Float,
        color: Int, tex: Tex?, program: Program? = null,
        flipX: Boolean = false, flipY: Boolean = false, materialKey: String? = null,
        blend: Int = BLEND_PREMULTIPLIED
    ) {
        if (width <= 0f || height <= 0f) return
        val prog = program ?: shaders.sprite ?: return
        val texId = tex?.id ?: textures.white().id
        if (blend != currentBlend) setBlend(blend)
        if (prog !== currentProgram || texId != currentTexture || materialKey != currentMaterialKey) {
            flush()
            if (prog !== currentProgram && currentTexture != 0) stats.textureSwaps++
            currentProgram = prog
            currentTexture = texId
            currentMaterialKey = materialKey
            prog.use()
            GLES20.glUniformMatrix4fv(prog.uMVP, 1, false, projection, 0)
            if (prog.uTex >= 0) GLES20.glUniform1i(prog.uTex, 0)
            if (prog.uTexel >= 0) GLES20.glUniform2f(prog.uTexel, 1f / maxOf(1, tex?.w ?: 1), 1f / maxOf(1, tex?.h ?: 1))
            if (prog.uTime >= 0) GLES20.glUniform1f(prog.uTime, time)
        }
        ensureQuadSpace()
        val hw = width * 0.5f
        val hh = height * 0.5f
        val ox = -pivotX * width
        val oy = -pivotY * height
        val cos = Math.cos(Math.toRadians(rotationDeg.toDouble())).toFloat()
        val sin = Math.sin(Math.toRadians(rotationDeg.toDouble())).toFloat()
        val ax = if (flipX) u1 else u0
        val bx = if (flipX) u0 else u1
        val ay = if (flipY) v0 else v1
        val by = if (flipY) v1 else v0
        putQuadVertex(cx, cy, ox, oy, cos, sin, ax, ay, color)
        putQuadVertex(cx, cy, ox + width, oy, cos, sin, bx, ay, color)
        putQuadVertex(cx, cy, ox + width, oy + height, cos, sin, bx, by, color)
        putQuadVertex(cx, cy, ox, oy + height, cos, sin, ax, by, color)
        quadIndexCount += 6
        quadVertCount += 4
        stats.quadCount++
    }

    /** Pushes a translation applied to all subsequently submitted vertices. */
    fun pushOffset(x: Float, y: Float) {
        offsetX += x
        offsetY += y
        offsetStack++
    }

    fun popOffset() {
        if (offsetStack > 0) {
            offsetStack--
            offsetX = 0f
            offsetY = 0f
        }
    }

    private fun putQuadVertex(cx0: Float, cy0: Float, lx: Float, ly: Float, cos: Float, sin: Float, u: Float, v: Float, color: Int) {
        val cx = cx0 + offsetX
        val cy = cy0 + offsetY
        quadVerts.put(cx + lx * cos - ly * sin)
        quadVerts.put(cy + lx * sin + ly * cos)
        quadVerts.put(u)
        quadVerts.put(v)
        quadColors.put((color shr 16).toByte())
        quadColors.put((color shr 8).toByte())
        quadColors.put(color.toByte())
        quadColors.put((color ushr 24).toByte())
    }

    /** Draws one [SpriteRenderer] using its computed world transform. */
    fun sprite(
        sprite: SpriteRenderer, world: Affine, region: TexRegion?, materialKey: String?, program: Program?,
        extraColor: Int = Colors.WHITE, alpha: Float = 1f, blend: Int = BLEND_PREMULTIPLIED
    ) {
        val w = sprite.width * world.scaleX
        val h = sprite.height * world.scaleY
        var cx = world.tx
        var cy = world.ty
        if (sprite.pixelSnap && pixelsPerUnit > 0.01f) {
            cx = Math.round(cx * pixelsPerUnit) / pixelsPerUnit
            cy = Math.round(cy * pixelsPerUnit) / pixelsPerUnit
        }
        val u0 = if (sprite.useFrameUv) sprite.uvU0 else region?.u0 ?: 0f
        val v0 = if (sprite.useFrameUv) sprite.uvV0 else region?.v0 ?: 0f
        val u1 = if (sprite.useFrameUv) sprite.uvU1 else region?.u1 ?: 1f
        val vEnd = if (sprite.useFrameUv) sprite.uvV1 else region?.v1 ?: 0f
        var color = Colors.multiply(sprite.color, extraColor)
        if (alpha < 1f) color = Colors.withAlpha(color, Colors.alpha(color).toFloat() / 255f * alpha)
        quad(cx, cy, w, h, world.rotationDeg, sprite.pivotX, sprite.pivotY, u0, v0, u1, vEnd, color, region?.tex, program, sprite.flipX, sprite.flipY, materialKey, blend)
        stats.sprites++
    }

    /** Raw textured quad in the current projection space. */
    fun texturedRect(x: Float, y: Float, w: Float, h: Float, region: TexRegion?, color: Int, blend: Int = BLEND_PREMULTIPLIED) {
        quad(
            x + w * 0.5f, y + h * 0.5f, w, h, 0f, 0.5f, 0.5f,
            region?.u0 ?: 0f, region?.v0 ?: 0f, region?.u1 ?: 1f, region?.v1 ?: 0f,
            color, region?.tex, null, false, false, null, blend
        )
    }

    // ------------------------------------------------------------------ shape batch

    private fun ensureShapeSpace() {
        if (shapeVertCount + 4 > MAX_QUADS * 4) flush()
    }

    /**
     * Rounded rectangle / circle / capsule. Corner radius is clamped to half the box, so
     * `radius = h/2` gives a capsule and `radius = w/2 = h/2` gives a circle.
     */
    fun shape(
        x: Float, y: Float, w: Float, h: Float, cornerRadius: Float, color: Int,
        borderWidth: Float = 0f, borderColor: Int = 0, rotation: Float = 0f, innerGlow: Float = 0f
    ) {
        if (w <= 0f || h <= 0f) return
        val prog = shaders.shape ?: return
        setBlend(BLEND_PREMULTIPLIED)
        if (prog !== currentProgram) {
            flush()
            currentProgram = prog
            currentTexture = 0
            currentMaterialKey = null
            prog.use()
            GLES20.glUniformMatrix4fv(prog.uMVP, 1, false, projection, 0)
        }
        if (prog.uBorderColor >= 0) GLES20.glUniform4f(prog.uBorderColor, GL.r(borderColor), GL.g(borderColor), GL.b(borderColor), GL.a(borderColor))
        if (prog.uInnerGlow >= 0) GLES20.glUniform1f(prog.uInnerGlow, innerGlow)
        ensureShapeSpace()
        val cx = x + w * 0.5f
        val cy = y + h * 0.5f
        val cos = Math.cos(Math.toRadians(rotation.toDouble())).toFloat()
        val sin = Math.sin(Math.toRadians(rotation.toDouble())).toFloat()
        val hw = w * 0.5f
        val hh = h * 0.5f
        val border = if (borderWidth > 0f && Colors.alpha(borderColor) > 0) borderWidth else 0f
        putShapeVertex(cx, cy, -hw, -hh, cos, sin, 0f, 0f, color, w, h, cornerRadius, border)
        putShapeVertex(cx, cy, hw, -hh, cos, sin, 1f, 0f, color, w, h, cornerRadius, border)
        putShapeVertex(cx, cy, hw, hh, cos, sin, 1f, 1f, color, w, h, cornerRadius, border)
        putShapeVertex(cx, cy, -hw, hh, cos, sin, 0f, 1f, color, w, h, cornerRadius, border)
        shapeIndexCount += 6
        shapeVertCount += 4
        stats.shapes++
        stats.quadCount++
    }

    private fun putShapeVertex(
        cx0: Float, cy0: Float, lx: Float, ly: Float, cos: Float, sin: Float, u: Float, v: Float,
        color: Int, w: Float, h: Float, radius: Float, border: Float
    ) {
        val cx = cx0 + offsetX
        val cy = cy0 + offsetY
        shapeVerts.put(cx + lx * cos - ly * sin)
        shapeVerts.put(cy + lx * sin + ly * cos)
        shapeVerts.put(u)
        shapeVerts.put(v)
        shapeVerts.put(w)
        shapeVerts.put(h)
        shapeVerts.put(radius)
        shapeVerts.put(border)
        shapeColors.put((color shr 16).toByte())
        shapeColors.put((color shr 8).toByte())
        shapeColors.put(color.toByte())
        shapeColors.put((color ushr 24).toByte())
    }

    fun circle(cx: Float, cy: Float, radius: Float, color: Int, borderWidth: Float = 0f, borderColor: Int = 0) {
        shape(cx - radius, cy - radius, radius * 2f, radius * 2f, radius, color, borderWidth, borderColor)
    }

    /** Anti-aliased line drawn as a rectangle rotated around its start point. */
    fun line(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, color: Int) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = M.dist(x0, y0, x1, y1)
        if (len <= 0.0001f || width <= 0f) return
        val prog = shaders.shape ?: return
        setBlend(BLEND_PREMULTIPLIED)
        if (prog !== currentProgram) {
            flush()
            currentProgram = prog
            currentTexture = 0
            currentMaterialKey = null
            prog.use()
            GLES20.glUniformMatrix4fv(prog.uMVP, 1, false, projection, 0)
        }
        if (prog.uBorderColor >= 0) GLES20.glUniform4f(prog.uBorderColor, 0f, 0f, 0f, 0f)
        if (prog.uInnerGlow >= 0) GLES20.glUniform1f(prog.uInnerGlow, 0f)
        ensureShapeSpace()
        val angle = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
        val cos = Math.cos(Math.toRadians(angle.toDouble())).toFloat()
        val sin = Math.sin(Math.toRadians(angle.toDouble())).toFloat()
        val hw = len * 0.5f
        val hh = width * 0.5f
        // the SDF parameter is per-pixel size; a line is a capsule of the correct length
        putShapeVertex(x0 + dx * 0.5f, y0 + dy * 0.5f, -hw, -hh, cos, sin, 0f, 0f, color, len, width, hh, 0f)
        putShapeVertex(x0 + dx * 0.5f, y0 + dy * 0.5f, hw, -hh, cos, sin, 1f, 0f, color, len, width, hh, 0f)
        putShapeVertex(x0 + dx * 0.5f, y0 + dy * 0.5f, hw, hh, cos, sin, 1f, 1f, color, len, width, hh, 0f)
        putShapeVertex(x0 + dx * 0.5f, y0 + dy * 0.5f, -hw, hh, cos, sin, 0f, 1f, color, len, width, hh, 0f)
        shapeIndexCount += 6
        shapeVertCount += 4
        stats.shapes++
    }

    /** Polyline (open or closed); used by trails, path previews and the tilemap brush. */
    fun polyline(points: FloatArray, width: Float, color: Int, closed: Boolean = false) {
        val n = points.size / 2
        if (n < 2) return
        for (i in 0 until n - 1) line(points[i * 2], points[i * 2 + 1], points[i * 2 + 2], points[i * 2 + 3], width, color)
        if (closed && n > 2) line(points[(n - 1) * 2], points[(n - 1) * 2 + 1], points[0], points[1], width, color)
    }

    /** Filled convex polygon (physics debug, editor polygon tool, UI custom shapes). */
    fun convexPolygon(points: FloatArray, fillColor: Int, borderColor: Int = 0, borderWidth: Float = 0f) {
        val n = points.size / 2
        if (n < 3) return
        var cx = 0f; var cy = 0f
        for (i in 0 until n) { cx += points[i * 2]; cy += points[i * 2 + 1] }
        cx /= n; cy /= n
        for (i in 0 until n) {
            val j = (i + 1) % n
            triangle(
                cx, cy,
                points[i * 2], points[i * 2 + 1],
                points[j * 2], points[j * 2 + 1], fillColor
            )
        }
        if (borderWidth > 0f && Colors.alpha(borderColor) > 0) {
            for (i in 0 until n) {
                val j = (i + 1) % n
                line(points[i * 2], points[i * 2 + 1], points[j * 2], points[j * 2 + 1], borderWidth, borderColor)
            }
        }
    }

    /** A single flat-shaded triangle (the 4th vertex duplicates the first to reuse quad indices). */
    fun triangle(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, color: Int) {
        val prog = shaders.shape ?: return
        setBlend(BLEND_PREMULTIPLIED)
        if (prog !== currentProgram) {
            flush()
            currentProgram = prog
            currentTexture = 0
            currentMaterialKey = null
            prog.use()
            GLES20.glUniformMatrix4fv(prog.uMVP, 1, false, projection, 0)
        }
        if (prog.uBorderColor >= 0) GLES20.glUniform4f(prog.uBorderColor, 0f, 0f, 0f, 0f)
        if (prog.uInnerGlow >= 0) GLES20.glUniform1f(prog.uInnerGlow, 0f)
        ensureShapeSpace()
        putShapeVertex(x0, y0, 0f, 0f, 1f, 0f, 0f, 0f, color, 0.001f, 0.001f, 0f, 0f)
        putShapeVertex(x1, y1, 0f, 0f, 1f, 0f, 1f, 0f, color, 0.001f, 0.001f, 0f, 0f)
        putShapeVertex(x2, y2, 0f, 0f, 1f, 0f, 0f, 1f, color, 0.001f, 0.001f, 0f, 0f)
        putShapeVertex(x0, y0, 0f, 0f, 1f, 0f, 1f, 1f, color, 0.001f, 0.001f, 0f, 0f)
        shapeIndexCount += 6
        shapeVertCount += 4
        stats.quadCount++
    }

    // ------------------------------------------------------------------ text

    /**
     * Draws text. [size] is the line height in the current projection space; glyph atlases are
     * baked at the screen resolution the caller is drawing at, so text stays sharp when the
     * camera zooms.
     */
    fun text(
        text: String, x: Float, y: Float, size: Float,
        color: Int, align: Int = 0, font: String = "", bold: Boolean = false,
        wrapWidth: Float = 0f, letterSpacing: Float = 0f, lineSpacing: Float = 1.15f,
        outlineColor: Int = 0, outlineWidth: Float = 0f, alpha: Float = 1f, blend: Int = BLEND_PREMULTIPLIED
    ): Float {
        if (text.isEmpty()) return 0f
        val bakePx = (size * pixelsPerUnit).coerceIn(12f, 160f).toInt()
        val atlas = fonts.atlas(font, bakePx, bold)
        val layout = fonts.layout(atlas, text, size, letterSpacing, lineSpacing, wrapWidth, align)
        val prog = shaders.text ?: return 0f
        setBlend(blend)
        if (prog !== currentProgram || atlas.tex.id != currentTexture) {
            flush()
            if (prog !== currentProgram && currentTexture != 0) stats.textureSwaps++
            currentProgram = prog
            currentTexture = atlas.tex.id
            currentMaterialKey = null
            prog.use()
            GLES20.glUniformMatrix4fv(prog.uMVP, 1, false, projection, 0)
            if (prog.uTex >= 0) GLES20.glUniform1i(prog.uTex, 0)
        }
        if (prog.uOutlineColor >= 0) GLES20.glUniform4f(prog.uOutlineColor, GL.r(outlineColor), GL.g(outlineColor), GL.b(outlineColor), GL.a(outlineColor))
        if (prog.uOutlineWidth >= 0) GLES20.glUniform1f(prog.uOutlineWidth, if (outlineWidth > 0f) maxOf(1f, outlineWidth * pixelsPerUnit) else 0f)
        val c = if (alpha < 1f) Colors.withAlpha(color, Colors.alpha(color).toFloat() / 255f * alpha) else color
        for (q in layout.quads) {
            val g = q.glyph
            val w = g.region.pw * q.scale
            val h = g.region.ph * q.scale
            quad(
                x + q.x + w * 0.5f, y + q.y + h * 0.5f, w, h, 0f, 0.5f, 0.5f,
                g.region.u0, g.region.v0, g.region.u1, g.region.v1, c, atlas.tex, null, false, false, null, blend
            )
            stats.glyphs++
        }
        return layout.width
    }

    fun measureText(text: String, font: String = "", bold: Boolean = false): Float =
        fonts.atlas(font, 48, bold).measure(text)

    // ------------------------------------------------------------------ particles & trails

    /** Draws every live particle of [system] into the current batch. */
    fun particles(system: ParticleSystem2D, texture: TexRegion?, additive: Boolean, softness: Float = 0.35f) {
        if (system.count <= 0) return
        val prog = shaders.particle ?: return
        val blend = if (additive) BLEND_ADDITIVE else BLEND_PREMULTIPLIED
        setBlend(blend)
        val texId = texture?.tex?.id ?: textures.white().id
        if (prog !== currentProgram || texId != currentTexture) {
            flush()
            if (prog !== currentProgram && currentTexture != 0) stats.textureSwaps++
            currentProgram = prog
            currentTexture = texId
            currentMaterialKey = null
            prog.use()
            GLES20.glUniformMatrix4fv(prog.uMVP, 1, false, projection, 0)
            if (prog.uTex >= 0) GLES20.glUniform1i(prog.uTex, 0)
            if (prog.uUseTexture >= 0) GLES20.glUniform1f(prog.uUseTexture, if (texture != null) 1f else 0f)
            if (prog.uSoftness >= 0) GLES20.glUniform1f(prog.uSoftness, softness)
        }
        val capacity = system.capacity()
        for (i in 0 until capacity) {
            if (!system.isAlive(i)) continue
            val size = system.sizeAt(i)
            if (size <= 0.0001f) continue
            val alpha = system.alphaAt(i)
            if (alpha <= 0.004f) continue
            val c = Colors.withAlpha(system.colorAt(i), Colors.alpha(system.colorAt(i)).toFloat() / 255f * alpha)
            quad(
                system.xAt(i), system.yAt(i), size, size, system.rotationAt(i), 0.5f, 0.5f,
                texture?.u0 ?: 0f, texture?.v0 ?: 0f, texture?.u1 ?: 1f, texture?.v1 ?: 0f,
                c, texture?.tex, null, false, false, null, blend
            )
            stats.particles++
        }
    }

    /** Draws a ribbon trail from its stored points, offset by the owner's transform. */
    fun trail(trail: Trail2D, originX: Float, originY: Float) {
        if (trail.count < 2) return
        val prog = shaders.shape ?: return
        val blend = if (trail.additive) BLEND_ADDITIVE else BLEND_PREMULTIPLIED
        setBlend(blend)
        if (prog !== currentProgram) {
            flush()
            currentProgram = prog
            currentTexture = 0
            currentMaterialKey = null
            prog.use()
            GLES20.glUniformMatrix4fv(prog.uMVP, 1, false, projection, 0)
        }
        if (prog.uBorderColor >= 0) GLES20.glUniform4f(prog.uBorderColor, 0f, 0f, 0f, 0f)
        if (prog.uInnerGlow >= 0) GLES20.glUniform1f(prog.uInnerGlow, 0f)
        val n = trail.count
        var prevX = originX + trail.pointX(0)
        var prevY = originY + trail.pointY(0)
        var prevW = trail.width
        var prevC = trail.endColor
        for (i in 1 until n) {
            val t = i.toFloat() / maxOf(1, n - 1)
            val x = originX + trail.pointX(i)
            val y = originY + trail.pointY(i)
            val w = M.lerp(trail.width, trail.endWidth, t)
            val c = Colors.lerpArgb(trail.endColor, trail.color, t)
            ribbonSegment(prevX, prevY, prevW, prevC, x, y, w, c)
            prevX = x; prevY = y; prevW = w; prevC = c
        }
    }

    private fun ribbonSegment(x0: Float, y0: Float, w0: Float, c0: Int, x1: Float, y1: Float, w1: Float, c1: Int) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = M.dist(x0, y0, x1, y1).coerceAtLeast(0.0001f)
        val nx = -dy / len
        val ny = dx / len
        ensureShapeSpace()
        putShapeVertex(x0 + nx * w0 * 0.5f, y0 + ny * w0 * 0.5f, 0f, 0f, 1f, 0f, 0f, 0f, c0, 0.001f, 0.001f, 0f, 0f)
        putShapeVertex(x1 + nx * w1 * 0.5f, y1 + ny * w1 * 0.5f, 0f, 0f, 1f, 0f, 1f, 0f, c1, 0.001f, 0.001f, 0f, 0f)
        putShapeVertex(x1 - nx * w1 * 0.5f, y1 - ny * w1 * 0.5f, 0f, 0f, 1f, 0f, 0f, 1f, c1, 0.001f, 0.001f, 0f, 0f)
        putShapeVertex(x0 - nx * w0 * 0.5f, y0 - ny * w0 * 0.5f, 0f, 0f, 1f, 0f, 1f, 1f, c0, 0.001f, 0.001f, 0f, 0f)
        shapeIndexCount += 6
        shapeVertCount += 4
        stats.shapes++
    }

    // ------------------------------------------------------------------ flushing

    fun flush() {
        if (quadIndexCount > 0) {
            val prog = currentProgram ?: shaders.sprite
            if (prog != null) {
                prog.use()
                GLES20.glUniformMatrix4fv(prog.uMVP, 1, false, projection, 0)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, currentTexture)
                quadVerts.position(0)
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboQuadPos)
                GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quadVertCount * FLOATS_PER_VERTEX * 4, quadVerts, GLES20.GL_DYNAMIC_DRAW)
                bindAttrib(prog.aPos, 2, 16, 0)
                bindAttrib(prog.aUV, 2, 16, 8)
                quadColors.position(0)
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboQuadColor)
                GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quadVertCount * 4, quadColors, GLES20.GL_DYNAMIC_DRAW)
                bindColorAttrib(prog.aColor)
                GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
                quadIndices.position(0)
                GLES20.glDrawElements(GLES20.GL_TRIANGLES, quadIndexCount, GLES20.GL_UNSIGNED_SHORT, quadIndices)
                stats.drawCalls++
                stats.batchCount++
                stats.vertexCount += quadVertCount
            }
            quadIndexCount = 0
            quadVertCount = 0
        }
        if (shapeIndexCount > 0) {
            val prog = shaders.shape
            if (prog != null) {
                prog.use()
                GLES20.glUniformMatrix4fv(prog.uMVP, 1, false, projection, 0)
                shapeVerts.position(0)
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboShapePos)
                GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, shapeVertCount * SHAPE_FLOATS_PER_VERTEX * 4, shapeVerts, GLES20.GL_DYNAMIC_DRAW)
                val stride = 32
                bindAttrib(prog.aPos, 2, stride, 0)
                bindAttrib(prog.aUV, 2, stride, 8)
                bindAttrib(prog.aParam, 4, stride, 16)
                shapeColors.position(0)
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboShapeColor)
                GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, shapeVertCount * 4, shapeColors, GLES20.GL_DYNAMIC_DRAW)
                bindColorAttrib(prog.aColor)
                GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
                shapeIndices.position(0)
                GLES20.glDrawElements(GLES20.GL_TRIANGLES, shapeIndexCount, GLES20.GL_UNSIGNED_SHORT, shapeIndices)
                stats.drawCalls++
                stats.batchCount++
                stats.vertexCount += shapeVertCount
            }
            shapeIndexCount = 0
            shapeVertCount = 0
        }
        currentProgram = null
        currentTexture = 0
        currentMaterialKey = null
    }

    private fun bindAttrib(location: Int, size: Int, stride: Int, offset: Int) {
        if (location < 0) return
        GLES20.glEnableVertexAttribArray(location)
        GLES20.glVertexAttribPointer(location, size, GLES20.GL_FLOAT, false, stride, offset)
    }

    private fun bindColorAttrib(location: Int) {
        if (location < 0) return
        GLES20.glEnableVertexAttribArray(location)
        GLES20.glVertexAttribPointer(location, 4, GLES20.GL_UNSIGNED_BYTE, true, 0, 0)
    }

    /** Full-screen blit with an optional program and tint (light map composite, post effects). */
    fun blit(texture: Int, program: Program?, color: FloatArray = TINT_WHITE) {
        val prog = program ?: shaders.blit ?: return
        flush()
        currentBlend = BLEND_NONE
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ZERO)
        prog.use()
        GLES20.glUniformMatrix4fv(prog.uMVP, 1, false, IDENTITY, 0)
        if (prog.uTex >= 0) GLES20.glUniform1i(prog.uTex, 0)
        if (prog.uColor >= 0) GLES20.glUniform4f(prog.uColor, color[0], color[1], color[2], color[3])
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        fullQuadPos.position(0)
        fullQuadUv.position(0)
        if (prog.aPos >= 0) {
            GLES20.glEnableVertexAttribArray(prog.aPos)
            GLES20.glVertexAttribPointer(prog.aPos, 2, GLES20.GL_FLOAT, false, 0, fullQuadPos)
        }
        if (prog.aUV >= 0) {
            GLES20.glEnableVertexAttribArray(prog.aUV)
            GLES20.glVertexAttribPointer(prog.aUV, 2, GLES20.GL_FLOAT, false, 0, fullQuadUv)
        }
        if (prog.aColor >= 0) GLES20.glDisableVertexAttribArray(prog.aColor)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        stats.drawCalls++
        currentProgram = null
        currentTexture = 0
        currentBlend = BLEND_PREMULTIPLIED
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    private val fullQuadPos: FloatBuffer = GL.floatBuffer(12).apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f, 1f)); position(0)
    }
    private val fullQuadUv: FloatBuffer = GL.floatBuffer(12).apply {
        put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f, 1f)); position(0)
    }

    /** Screen (pixel) coordinates -> world coordinates using the current world projection. */
    fun screenToWorld(x: Float, y: Float, cx: Float, cy: Float, halfW: Float, halfH: Float): FloatArray =
        floatArrayOf(
            cx + (x / screenWidth * 2f - 1f) * halfW,
            cy + (1f - y / screenHeight * 2f) * halfH
        )

    override fun toString() = "Renderer2D(${stats})"
}

/** Column-major identity for full-screen quads. */
val IDENTITY = floatArrayOf(
    1f, 0f, 0f, 0f,
    0f, 1f, 0f, 0f,
    0f, 0f, 1f, 0f,
    0f, 0f, 0f, 1f
)
val TINT_WHITE = floatArrayOf(1f, 1f, 1f, 1f)
