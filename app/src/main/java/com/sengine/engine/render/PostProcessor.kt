package com.sengine.engine.render

import android.opengl.GLES20
import com.sengine.engine.math.Colors
import com.sengine.engine.math.M

/**
 * 2D post-processing chain.
 *
 * The scene is rendered into an off-screen target, then a stack of effects is applied:
 * bloom (bright pass + separable blur), vignette, chromatic aberration, scanlines/CRT,
 * grayscale, invert, colour grading, screen distortion and shockwave. Effects are configured per
 * camera (`Camera2D.postFx`) or globally, and the whole chain can be disabled on low-end devices.
 */
class PostProcessor {

    /** Effect ids, kept in sync with `ComponentRegistry.POST_FX`. */
    enum class Effect(val label: String) {
        NONE("None"),
        BLOOM("Bloom"),
        BLUR("Blur"),
        VIGNETTE("Vignette"),
        CHROMATIC("Chromatic"),
        CRT("CRT Scanlines"),
        GRAYSCALE("Grayscale"),
        INVERT("Invert"),
        DISTORTION("Distortion"),
        SHOCKWAVE("Shockwave"),
        DREAM("Dream"),
        PIXELATE("Pixelate");

        companion object {
            val labels = entries.map { it.label }
            fun of(index: Int) = entries.getOrElse(index) { NONE }
        }
    }

    var enabled = true
    /** Bloom is the most expensive pass; low-end devices can turn it off. */
    var bloomEnabled = true
    var bloomThreshold = 0.75f
    var bloomSoftKnee = 0.25f
    var bloomIntensity = 0.85f
    var bloomBlurPasses = 2
    /** Screen distortion amount (heat haze, underwater). */
    var distortion = 0f
    var time = 0f
    var tint = Colors.WHITE
    var exposure = 1f
    /** Shockwave: (x, y, progress 0..1). */
    var shockwaveX = 0.5f
    var shockwaveY = 0.5f
    var shockwaveProgress = 0f
    var shockwaveAmount = 0f
    /** Pixelation grid (1 = off). */
    var pixelate = 1f

    private var scene: GL.Target? = null
    private var bloomA: GL.Target? = null
    private var bloomB: GL.Target? = null
    private var width = 0
    private var height = 0

    /** Per-effect timing shown in the profiler. */
    var bloomMs = 0f
    var compositeMs = 0f

    fun ensureTargets(w: Int, h: Int) {
        if (w == width && h == height && scene != null) return
        width = w.coerceAtLeast(1)
        height = h.coerceAtLeast(1)
        val s = scene
        if (s == null) scene = GL.Target(width, height) else s.resize(width, height)
        val bw = (width / 2).coerceAtLeast(1)
        val bh = (height / 2).coerceAtLeast(1)
        val a = bloomA
        if (a == null) bloomA = GL.Target(bw, bh) else a.resize(bw, bh)
        val b = bloomB
        if (b == null) bloomB = GL.Target(bw, bh) else b.resize(bw, bh)
    }

    fun release() {
        scene?.release(); bloomA?.release(); bloomB?.release()
        scene = null; bloomA = null; bloomB = null
        width = 0; height = 0
    }

    /**
     * Binds the render target the game is drawn into: the off-screen scene target when post
     * processing is active, otherwise the default framebuffer (skip the extra pass entirely).
     */
    fun beginWorld(renderer: Renderer2D, clearColor: Int) {
        val t = scene
        if (!enabled || t == null) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, renderer.screenWidth.toInt(), renderer.screenHeight.toInt())
            GLES20.glClearColor(GL.r(clearColor), GL.g(clearColor), GL.b(clearColor), 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            return
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, t.fbo)
        GLES20.glViewport(0, 0, t.width, t.height)
        GLES20.glClearColor(GL.r(clearColor), GL.g(clearColor), GL.b(clearColor), 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }

    /** Re-applies the world projection when the scene target has a different size than the screen. */
    fun overrideProjection(renderer: Renderer2D, view: View2D) {
        val t = scene
        if (!enabled || t == null) return
        GLES20.glViewport(0, 0, t.width, t.height)
    }

    val sceneFbo: Int get() = if (enabled) scene?.fbo ?: 0 else 0
    val sceneWidth: Int get() = scene?.width ?: 0
    val sceneHeight: Int get() = scene?.height ?: 0

    /**
     * Runs the effect stack and presents the result to the screen. Must be called after the scene
     * and UI have been drawn into the scene target.
     */
    fun present(renderer: Renderer2D, effect: Effect, intensity: Float) {
        val sceneTarget = scene
        if (!enabled || sceneTarget == null) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, renderer.screenWidth.toInt(), renderer.screenHeight.toInt())
            return
        }
        val start = System.nanoTime()
        var source = sceneTarget.texture
        var usedBloom = false

        if (bloomEnabled && (effect == Effect.BLOOM || effect == Effect.DREAM) && bloomA != null && bloomB != null) {
            val a = bloomA!!
            val b = bloomB!!
            val prefilter = renderer.shaders.bloomPrefilter
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, a.fbo)
            GLES20.glViewport(0, 0, a.width, a.height)
            renderer.setScreenProjection(a.width.toFloat(), a.height.toFloat())
            prefilter?.let {
                if (it.uThreshold >= 0) GLES20.glUniform1f(it.uThreshold, bloomThreshold)
                if (it.uSoftKnee >= 0) GLES20.glUniform1f(it.uSoftKnee, bloomSoftKnee)
            }
            renderer.blit(source, prefilter)
            // separable gaussian blur
            var horizontal = true
            var input = a
            var output = b
            for (pass in 0 until bloomBlurPasses * 2) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, output.fbo)
                GLES20.glViewport(0, 0, output.width, output.height)
                renderer.setScreenProjection(output.width.toFloat(), output.height.toFloat())
                val blur = renderer.shaders.blur
                blur?.let {
                    if (it.uStep >= 0) {
                        val sx = if (horizontal) 1f / input.width else 0f
                        val sy = if (horizontal) 0f else 1f / input.height
                        GLES20.glUniform2f(it.uStep, sx, sy)
                    }
                }
                renderer.blit(input.texture, blur)
                horizontal = !horizontal
                val tmp = input
                input = output
                output = tmp
            }
            source = input.texture
            usedBloom = true
        }
        bloomMs = ((System.nanoTime() - start) / 1_000_000.0).toFloat()

        val cStart = System.nanoTime()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, renderer.screenWidth.toInt(), renderer.screenHeight.toInt())
        renderer.setScreenProjection(renderer.screenWidth, renderer.screenHeight)
        val prog = renderer.shaders.post
        if (prog != null) {
            prog.use()
            if (prog.uBloom >= 0) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (usedBloom) source else 0)
                GLES20.glUniform1i(prog.uBloom, 1)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            }
            val a = intensity.coerceIn(0f, 4f)
            if (prog.uBloomAmount >= 0) GLES20.glUniform1f(prog.uBloomAmount, if (usedBloom) bloomIntensity * a else 0f)
            if (prog.uVignette >= 0) GLES20.glUniform1f(prog.uVignette, if (effect == Effect.VIGNETTE || effect == Effect.DREAM) 0.85f * a else 0f)
            if (prog.uChromatic >= 0) GLES20.glUniform1f(prog.uChromatic, if (effect == Effect.CHROMATIC || effect == Effect.CRT) 1.2f * a else 0f)
            if (prog.uScanlines >= 0) GLES20.glUniform1f(prog.uScanlines, if (effect == Effect.CRT) a else 0f)
            if (prog.uGray >= 0) GLES20.glUniform1f(prog.uGray, if (effect == Effect.GRAYSCALE) a.coerceAtMost(1f) else 0f)
            if (prog.uInvert >= 0) GLES20.glUniform1f(prog.uInvert, if (effect == Effect.INVERT) a.coerceAtMost(1f) else 0f)
            if (prog.uDistortion >= 0) GLES20.glUniform1f(prog.uDistortion, if (effect == Effect.DISTORTION || effect == Effect.DREAM) maxOf(distortion, 0.6f * a) else distortion)
            if (prog.uShockwave >= 0) GLES20.glUniform1f(prog.uShockwave, shockwaveAmount * a)
            if (prog.uShockCenter >= 0) GLES20.glUniform2f(prog.uShockCenter, shockwaveX, shockwaveY)
            if (prog.uShockTime >= 0) GLES20.glUniform1f(prog.uShockTime, shockwaveProgress)
            if (prog.uTime >= 0) GLES20.glUniform1f(prog.uTime, time)
            if (prog.uPixelate >= 0) GLES20.glUniform1f(prog.uPixelate, if (effect == Effect.PIXELATE) maxOf(48f, 240f / a) else pixelate)
            if (prog.uTint >= 0) GLES20.glUniform3f(prog.uTint, GL.r(tint), GL.g(tint), GL.b(tint))
            if (prog.uExposure >= 0) GLES20.glUniform1f(prog.uExposure, exposure)
            if (prog.uResolution >= 0) GLES20.glUniform2f(prog.uResolution, renderer.screenWidth, renderer.screenHeight)
            if (prog.uTex >= 0) GLES20.glUniform1i(prog.uTex, 0)
        }
        renderer.blit(source, prog ?: renderer.shaders.blit)
        compositeMs = ((System.nanoTime() - cStart) / 1_000_000.0).toFloat()

        // shockwave and one-frame distortions decay automatically
        shockwaveAmount = maxOf(0f, shockwaveAmount - 0.06f)
        shockwaveProgress = M.clamp01(shockwaveProgress + 0.06f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    /** Triggers a 2D shockwave effect (explosions, impacts, screen-space feedback). */
    fun triggerShockwave(normalizedX: Float, normalizedY: Float, amount: Float = 1f) {
        shockwaveX = normalizedX
        shockwaveY = normalizedY
        shockwaveProgress = 0f
        shockwaveAmount = amount
        distortion = maxOf(distortion, 0.35f * amount)
    }

    /** Applies a camera's post-FX selection. */
    fun applyCamera(effectIndex: Int, intensity: Float) {
        val effect = Effect.of(effectIndex)
        enabled = effect != Effect.NONE || distortion > 0.001f || shockwaveAmount > 0.001f
        if (effect != Effect.NONE) lastEffect = effect
        lastIntensity = intensity
    }

    var lastEffect = Effect.NONE
        private set
    var lastIntensity = 1f
        private set

    /** Selects the camera's post effect and its intensity. */
    fun configure(effect: Effect, intensity: Float) {
        lastEffect = effect
        lastIntensity = intensity
        if (effect != Effect.NONE) enabled = true
    }

    fun sceneTexture(): Int = scene?.texture ?: 0

    fun targetSize(): IntArray = intArrayOf(scene?.width ?: 0, scene?.height ?: 0)
}
