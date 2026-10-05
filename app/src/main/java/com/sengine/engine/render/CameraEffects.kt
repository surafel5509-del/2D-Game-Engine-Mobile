package com.sengine.engine.render

import com.sengine.engine.core.GameObject
import kotlin.math.*

/**
 * Camera effects system for shake, zoom, follow, and cinematic effects.
 */
class CameraEffects {
    // Camera shake
    private var shakeIntensity = 0f
    private var shakeDuration = 0f
    private var shakeElapsed = 0f
    private var shakeOffsetX = 0f
    private var shakeOffsetY = 0f

    // Camera follow
    private var followTarget: GameObject? = null
    private var followSmoothness = 5f
    private var followOffsetX = 0f
    private var followOffsetY = 0f
    private var followDeadZone = 0f

    // Camera zoom
    private var targetZoom = 1f
    private var currentZoom = 1f
    private var zoomSmoothness = 5f

    // Camera bounds
    var minX = Float.MIN_VALUE
    var maxX = Float.MAX_VALUE
    var minY = Float.MIN_VALUE
    var maxY = Float.MAX_VALUE

    // Position
    var x = 0f
    var y = 0f
    var rotation = 0f

    /**
     * Apply camera shake
     */
    fun shake(intensity: Float, duration: Float) {
        shakeIntensity = intensity
        shakeDuration = duration
        shakeElapsed = 0f
    }

    /**
     * Set follow target
     */
    fun follow(target: GameObject, smoothness: Float = 5f, offsetX: Float = 0f, offsetY: Float = 0f, deadZone: Float = 0f) {
        followTarget = target
        followSmoothness = smoothness
        followOffsetX = offsetX
        followOffsetY = offsetY
        followDeadZone = deadZone
    }

    /**
     * Stop following
     */
    fun stopFollow() {
        followTarget = null
    }

    /**
     * Zoom to target level
     */
    fun zoomTo(targetZoom: Float, smoothness: Float = 5f) {
        this.targetZoom = targetZoom.coerceIn(0.1f, 10f)
        this.zoomSmoothness = smoothness
    }

    /**
     * Update camera effects
     */
    fun update(dt: Float) {
        // Update shake
        if (shakeElapsed < shakeDuration) {
            shakeElapsed += dt
            val progress = 1f - (shakeElapsed / shakeDuration)
            val currentIntensity = shakeIntensity * progress

            shakeOffsetX = (random(-1f, 1f)) * currentIntensity
            shakeOffsetY = (random(-1f, 1f)) * currentIntensity
        } else {
            shakeOffsetX = 0f
            shakeOffsetY = 0f
        }

        // Update follow
        if (followTarget != null) {
            val targetX = followTarget!!.x + followOffsetX
            val targetY = followTarget!!.y + followOffsetY

            // Check dead zone
            val dx = targetX - x
            val dy = targetY - y
            val dist = sqrt(dx * dx + dy * dy)

            if (dist > followDeadZone) {
                val t = 1f - exp(-followSmoothness * dt)
                x += (targetX - x) * t
                y += (targetY - y) * t
            }
        }

        // Update zoom
        val zoomT = 1f - exp(-zoomSmoothness * dt)
        currentZoom += (targetZoom - currentZoom) * zoomT

        // Clamp to bounds
        x = x.coerceIn(minX, maxX)
        y = y.coerceIn(minY, maxY)
    }

    /**
     * Get final camera position with shake applied
     */
    fun getFinalPosition(): Pair<Float, Float> {
        return (x + shakeOffsetX) to (y + shakeOffsetY)
    }

    /**
     * Get current zoom level
     */
    fun getZoom(): Float = currentZoom

    /**
     * Set camera position directly
     */
    fun setPosition(x: Float, y: Float) {
        this.x = x
        this.y = y
    }

    /**
     * Look at a specific position
     */
    fun lookAt(x: Float, y: Float, smoothness: Float = 5f, dt: Float = 0.016f) {
        val t = 1f - exp(-smoothness * dt)
        this.x += (x - this.x) * t
        this.y += (y - this.y) * t
    }

    /**
     * Set camera bounds
     */
    fun setBounds(minX: Float, maxX: Float, minY: Float, maxY: Float) {
        this.minX = minX
        this.maxX = maxX
        this.minY = minY
        this.maxY = maxY
    }

    /**
     * Cinematic effects
     */
    fun fadeIn(duration: Float) {
        // Would be handled by transition system
    }

    fun fadeOut(duration: Float) {
        // Would be handled by transition system
    }

    private fun random(min: Float, max: Float): Float {
        return min + (Math.random() * (max - min)).toFloat()
    }

    /**
     * Reset all effects
     */
    fun reset() {
        shakeIntensity = 0f
        shakeDuration = 0f
        shakeElapsed = 0f
        shakeOffsetX = 0f
        shakeOffsetY = 0f
        followTarget = null
        targetZoom = 1f
        currentZoom = 1f
    }
}

/**
 * Screen-space effects overlay
 */
class ScreenEffects {
    var enabled = true

    // Vignette
    var vignetteEnabled = false
    var vignetteIntensity = 0.5f
    var vignetteSmoothness = 0.5f

    // Chromatic aberration
    var chromaticAberrationEnabled = false
    var chromaticAberrationIntensity = 0.01f

    // Color grading
    var brightness = 1f
    var contrast = 1f
    var saturation = 1f

    // Flash
    var flashEnabled = false
    var flashIntensity = 0f
    var flashColor = 0xFFFFFFFF.toInt()

    /**
     * Apply screen flash effect
     */
    fun flash(color: Int, intensity: Float, duration: Float) {
        flashEnabled = true
        flashColor = color
        flashIntensity = intensity
    }

    /**
     * Update screen effects
     */
    fun update(dt: Float) {
        if (flashEnabled) {
            flashIntensity -= dt * 2f
            if (flashIntensity <= 0f) {
                flashEnabled = false
                flashIntensity = 0f
            }
        }
    }
}
