package com.sengine.engine.core

import com.sengine.engine.render.View2D
import kotlin.math.*

/**
 * Advanced camera system with follow, smooth damping, shake, zoom, and boundary limits.
 * Extends Camera2D component behavior at runtime.
 */
class CameraSystem {
    // Follow settings
    var followTarget: GameObject? = null
    var followSmoothing = 5f // Higher = snappier
    var followOffsetX = 0f
    var followOffsetY = 0f
    var lookAheadDistance = 1.5f // Camera leads in movement direction
    var lookAheadSpeed = 3f
    var deadZoneWidth = 0.5f // No follow within this range
    var deadZoneHeight = 0.5f

    // Smooth zoom
    var targetSize = 5f
    var currentSize = 5f
    var zoomSpeed = 5f
    var minZoom = 1f
    var maxZoom = 20f

    // Camera shake
    var shakeIntensity = 0f
    var shakeDuration = 0f
    var shakeTimer = 0f
    var shakeOffsetX = 0f
    var shakeOffsetY = 0f
    var shakeDamping = true

    // Boundary limits
    var useBounds = false
    var boundsMinX = -100f
    var boundsMinY = -100f
    var boundsMaxX = 100f
    var boundsMaxY = 100f

    // Cinematic
    var cinematicMode = false
    var cinematicPath: List<Pair<Float, Float>> = emptyList()
    var cinematicSpeed = 1f
    var cinematicTimer = 0f

    // Internal
    private var lookAheadX = 0f
    private var lookAheadY = 0f
    private var lastTargetX = 0f
    private var lastTargetY = 0f

    /** Start a camera shake. */
    fun shake(intensity: Float, duration: Float) {
        shakeIntensity = max(shakeIntensity, intensity)
        shakeDuration = max(shakeDuration, duration)
        shakeTimer = 0f
    }

    /** Set zoom level. */
    fun zoom(size: Float) {
        targetSize = size.coerceIn(minZoom, maxZoom)
    }

    /** Zoom in by amount. */
    fun zoomIn(amount: Float) = zoom(targetSize - amount)

    /** Zoom out by amount. */
    fun zoomOut(amount: Float) = zoom(targetSize + amount)

    /** Set camera bounds. */
    fun setBounds(minX: Float, minY: Float, maxX: Float, maxY: Float) {
        useBounds = true
        boundsMinX = minX; boundsMinY = minY
        boundsMaxX = maxX; boundsMaxY = maxY
    }

    /** Clear camera bounds. */
    fun clearBounds() { useBounds = false }

    /** Start a cinematic camera path. */
    fun startCinematic(path: List<Pair<Float, Float>>, speed: Float = 1f) {
        cinematicMode = true
        cinematicPath = path
        cinematicSpeed = speed
        cinematicTimer = 0f
    }

    /** Stop cinematic mode. */
    fun stopCinematic() {
        cinematicMode = false
    }

    /** Update the camera for this frame. Called every tick. */
    fun update(view: View2D, dt: Float) {
        // Zoom interpolation
        currentSize += (targetSize - currentSize) * zoomSpeed * dt
        currentSize = currentSize.coerceIn(minZoom, maxZoom)
        view.size = currentSize

        // Camera shake
        if (shakeTimer < shakeDuration) {
            shakeTimer += dt
            val progress = shakeTimer / shakeDuration
            val decay = if (shakeDamping) (1f - progress) else 1f
            shakeOffsetX = (Random.nextFloat() * 2f - 1f) * shakeIntensity * decay
            shakeOffsetY = (Random.nextFloat() * 2f - 1f) * shakeIntensity * decay
        } else {
            shakeIntensity = 0f
            shakeDuration = 0f
            shakeOffsetX = 0f
            shakeOffsetY = 0f
        }

        // Cinematic mode
        if (cinematicMode && cinematicPath.size >= 2) {
            updateCinematic(view, dt)
            applyShake(view)
            applyBounds(view)
            return
        }

        // Follow target
        val target = followTarget
        if (target != null) {
            val w = target.computeWorld()
            val tx = w.tx + followOffsetX
            val ty = w.ty + followOffsetY

            // Look-ahead
            val moveDx = tx - lastTargetX
            val moveDy = ty - lastTargetY
            val speed = sqrt(moveDx * moveDx + moveDy * moveDy)
            if (speed > 0.01f) {
                val targetLAx = (moveDx / speed) * lookAheadDistance
                val targetLAy = (moveDy / speed) * lookAheadDistance
                lookAheadX += (targetLAx - lookAheadX) * lookAheadSpeed * dt
                lookAheadY += (targetLAy - lookAheadY) * lookAheadSpeed * dt
            } else {
                lookAheadX *= (1f - 2f * dt)
                lookAheadY *= (1f - 2f * dt)
            }
            lastTargetX = tx
            lastTargetY = ty

            // Dead zone
            val desiredX = tx + lookAheadX
            val desiredY = ty + lookAheadY
            val camX = view.cx
            val camY = view.cy

            var newCamX = camX
            var newCamY = camY

            val dxToTarget = desiredX - camX
            val dyToTarget = desiredY - camY

            if (abs(dxToTarget) > deadZoneWidth * 0.5f) {
                val deadAdj = if (dxToTarget > 0) deadZoneWidth * 0.5f else -deadZoneWidth * 0.5f
                newCamX += (dxToTarget - deadAdj) * followSmoothing * dt
            }
            if (abs(dyToTarget) > deadZoneHeight * 0.5f) {
                val deadAdj = if (dyToTarget > 0) deadZoneHeight * 0.5f else -deadZoneHeight * 0.5f
                newCamY += (dyToTarget - deadAdj) * followSmoothing * dt
            }

            // Smooth interpolation
            val k = (1f - exp(-followSmoothing * dt)).coerceIn(0f, 1f)
            view.cx = camX + (newCamX - camX) * k
            view.cy = camY + (newCamY - camY) * k
        }

        applyShake(view)
        applyBounds(view)
    }

    private fun applyShake(view: View2D) {
        view.cx += shakeOffsetX
        view.cy += shakeOffsetY
    }

    private fun applyBounds(view: View2D) {
        if (!useBounds) return
        val halfW = view.halfW
        val halfH = view.size
        view.cx = view.cx.coerceIn(boundsMinX + halfW, boundsMaxX - halfW)
        view.cy = view.cy.coerceIn(boundsMinY + halfH, boundsMaxY - halfH)
    }

    private fun updateCinematic(view: View2D, dt: Float) {
        cinematicTimer += dt * cinematicSpeed
        val totalTime = cinematicPath.size.toFloat()
        val t = cinematicTimer % totalTime

        val index = t.toInt().coerceIn(0, cinematicPath.size - 1)
        val nextIndex = (index + 1) % cinematicPath.size
        val frac = t - index

        val p0 = cinematicPath[index]
        val p1 = cinematicPath[nextIndex]

        view.cx = p0.first + (p1.first - p0.first) * frac
        view.cy = p0.second + (p1.second - p0.second) * frac
    }

    companion object {
        private val Random = java.util.Random()
    }
}

/**
 * Extension functions to integrate CameraSystem with Camera2D component.
 */
fun Camera2D.createCameraSystem(): CameraSystem {
    val sys = CameraSystem()
    sys.followSmoothing = this.smoothing
    sys.targetSize = this.size
    sys.currentSize = this.size
    return sys
}
