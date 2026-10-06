package com.sengine.engine.render

import android.opengl.GLES20
import com.sengine.engine.lighting.LightSystem
import com.sengine.engine.math.Colors
import com.sengine.engine.math.Rect2

/**
 * Renders the 2D light map.
 *
 * The light map is a small off-screen target (its resolution is controlled by
 * `AmbientLight.lightmapScale`, so low-end devices can render it at 0.25x) containing:
 *  1. the ambient colour as a clear,
 *  2. every visible light as an additive radial/cone gradient quad,
 *  3. every 2D shadow as a black quad that punches light out of the map (soft edges).
 *
 * The scene is then lit by multiplying the light map over it (`GL_DST_COLOR`), which gives real
 * 2D lighting with zero per-pixel cost on the sprite pass - the mobile-friendly approach.
 */
class Lighting2D {

    var target: GL.Target? = null
        private set

    /** Light map resolution scale (1 = full resolution). */
    var scale = 1f

    var enabled = true
    /** Draw light gizmos (editor only). */
    var debugGizmos = false

    private val stats = HashMap<String, Int>()

    fun ensureTarget(width: Int, height: Int) {
        val w = (width * scale).toInt().coerceAtLeast(16)
        val h = (height * scale).toInt().coerceAtLeast(16)
        val t = target
        if (t == null) target = GL.Target(w, h) else t.resize(w, h)
    }

    fun release() {
        target?.release()
        target = null
    }

    /**
     * Renders the light map for [system] into [target]. [view] is the world-space rectangle that
     * the camera currently shows, so lights and shadows are positioned in light map space.
     */
    fun render(
        renderer: Renderer2D, system: LightSystem, view: Rect2, clearColor: Int, ambientColor: Int,
        ambientIntensity: Float
    ) {
        val t = target ?: return
        if (!enabled) return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, t.fbo)
        GLES20.glViewport(0, 0, t.width, t.height)
        val amb = Colors.scale(ambientColor, ambientIntensity.coerceIn(0f, 4f), 1f)
        GLES20.glClearColor(GL.r(amb), GL.g(amb), GL.b(amb), 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        renderer.setOrtho(renderer.projection, view.left, view.right, view.bottom, view.top)
        renderer.setBlend(Renderer2D.BLEND_ADDITIVE)

        val lightProg = renderer.shaders.light
        if (lightProg != null) {
            for (l in system.lights) {
                val quadSize = l.radius * 2f
                val directionX = Math.cos(Math.toRadians(l.direction.toDouble())).toFloat()
                val directionY = -Math.sin(Math.toRadians(l.direction.toDouble())).toFloat()
                val coneCos = if (l.kind == 2) Math.cos(Math.toRadians(l.coneAngle * 0.5f.toDouble())).toFloat() else -2f
                // program uniforms: set through the material library path would reset them, so bind
                // the light program directly and set its own uniforms
                renderer.flush()
                renderer.setBlend(Renderer2D.BLEND_ADDITIVE)
                lightProg.use()
                GLES20.glUniformMatrix4fv(lightProg.uMVP, 1, false, renderer.projection, 0)
                if (lightProg.uRadius >= 0) GLES20.glUniform1f(lightProg.uRadius, l.radius)
                if (lightProg.uInner >= 0) GLES20.glUniform1f(lightProg.uInner, l.innerRadius / maxOf(0.0001f, l.radius))
                if (lightProg.uFalloff >= 0) GLES20.glUniform1f(lightProg.uFalloff, l.falloff)
                if (lightProg.uConeCos >= 0) GLES20.glUniform1f(lightProg.uConeCos, coneCos)
                if (lightProg.uDirection >= 0) GLES20.glUniform2f(lightProg.uDirection, directionX, directionY)
                if (lightProg.uLightPos >= 0) GLES20.glUniform2f(lightProg.uLightPos, l.x, l.y)
                val c = Colors.scale(l.color, l.intensity, 1f)
                renderer.quad(
                    l.x, l.y, quadSize, quadSize, 0f, 0.5f, 0.5f, 0f, 1f, 1f, 0f,
                    c, null, lightProg, false, false, null, Renderer2D.BLEND_ADDITIVE
                )
            }
            stats["lights"] = system.lights.size
        }

        // shadows: multiply the light map down where occluders block the light
        val shadows = system.shadows
        if (shadows.isNotEmpty()) {
            renderer.flush()
            renderer.setBlend(Renderer2D.BLEND_MULTIPLY)
            val shapeProg = renderer.shaders.shape
            if (shapeProg != null) {
                shapeProg.use()
                GLES20.glUniformMatrix4fv(shapeProg.uMVP, 1, false, renderer.projection, 0)
                if (shapeProg.uBorderColor >= 0) GLES20.glUniform4f(shapeProg.uBorderColor, 0f, 0f, 0f, 0f)
                if (shapeProg.uInnerGlow >= 0) GLES20.glUniform1f(shapeProg.uInnerGlow, 0f)
            }
            for (s in shadows) {
                // black quad over the light map; blend multiply keeps the ambient term visible
                renderer.convexPolygon(
                    floatArrayOf(s.x0, s.y0, s.x1, s.y1, s.x2, s.y2, s.x3, s.y3),
                    Colors.rgba(8, 10, 14, 255)
                )
            }
            stats["shadowQuads"] = shadows.size
            renderer.flush()
        }
        renderer.setBlend(Renderer2D.BLEND_PREMULTIPLIED)

        if (debugGizmos) {
            renderer.setBlend(Renderer2D.BLEND_ALPHA)
            for (l in system.lights) {
                renderer.circle(l.x, l.y, l.radius, 0x33FFCC00)
                renderer.circle(l.x, l.y, 0.08f, 0xFFFFCC00.toInt())
                renderer.line(l.x, l.y, l.x + Math.cos(Math.toRadians(l.direction.toDouble())).toFloat() * l.radius * 0.4f,
                    l.y - Math.sin(Math.toRadians(l.direction.toDouble())).toFloat() * l.radius * 0.4f, 2f, 0xFFFFCC00.toInt())
            }
            renderer.setBlend(Renderer2D.BLEND_PREMULTIPLIED)
        }
        renderer.flush()
        renderer.stats.lights = system.lights.size
        renderer.stats.shadowQuads = shadows.size
    }

    /** Multiplies the light map over the already-rendered scene. */
    fun composite(renderer: Renderer2D) {
        val t = target ?: return
        if (!enabled) return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        renderer.setScreenProjection(renderer.screenWidth, renderer.screenHeight)
        // Multiply blend is not directly available through the blit path, so set it explicitly.
        renderer.flush()
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_DST_COLOR, GLES20.GL_ZERO)
        renderer.blit(t.texture, renderer.shaders.blit)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        renderer.setBlend(Renderer2D.BLEND_PREMULTIPLIED)
    }

    fun lightCount() = stats["lights"] ?: 0
    fun shadowCount() = stats["shadowQuads"] ?: 0
}
