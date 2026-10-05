package com.sengine.engine.scene

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import kotlin.math.*

/**
 * Screen transition system for smooth scene changes.
 */
class TransitionSystem {
    private var currentTransition: Transition? = null
    private var isTransitioning = false

    /**
     * Start a transition
     */
    fun startTransition(type: TransitionType, duration: Float, onComplete: () -> Unit = {}) {
        currentTransition = Transition(type, duration, onComplete)
        isTransitioning = true
    }

    /**
     * Update transition
     */
    fun update(dt: Float) {
        if (!isTransitioning || currentTransition == null) return

        currentTransition!!.update(dt)

        if (currentTransition!!.isComplete()) {
            isTransitioning = false
            currentTransition!!.onComplete?.invoke()
            currentTransition = null
        }
    }

    /**
     * Draw transition overlay
     */
    fun draw(canvas: Canvas) {
        if (!isTransitioning || currentTransition == null) return
        currentTransition!!.draw(canvas)
    }

    /**
     * Check if currently transitioning
     */
    fun isTransitioning(): Boolean = isTransitioning

    /**
     * Force stop current transition
     */
    fun stopTransition() {
        isTransitioning = false
        currentTransition = null
    }
}

class Transition(
    val type: TransitionType,
    val duration: Float,
    val onComplete: (() -> Unit)? = null
) {
    private var elapsed = 0f
    private var phase = TransitionPhase.IN
    private val paint = Paint()

    enum class TransitionPhase { IN, OUT }

    fun update(dt: Float) {
        elapsed += dt

        // Switch to OUT phase at midpoint
        if (elapsed >= duration / 2f && phase == TransitionPhase.IN) {
            phase = TransitionPhase.OUT
        }
    }

    fun isComplete(): Boolean = elapsed >= duration

    fun draw(canvas: Canvas) {
        val progress = if (phase == TransitionPhase.IN) {
            (elapsed / (duration / 2f)).coerceIn(0f, 1f)
        } else {
            1f - ((elapsed - duration / 2f) / (duration / 2f)).coerceIn(0f, 1f)
        }

        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()

        when (type) {
            TransitionType.FADE -> drawFade(canvas, progress, width, height)
            TransitionType.SLIDE_LEFT -> drawSlideLeft(canvas, progress, width, height)
            TransitionType.SLIDE_RIGHT -> drawSlideRight(canvas, progress, width, height)
            TransitionType.SLIDE_UP -> drawSlideUp(canvas, progress, width, height)
            TransitionType.SLIDE_DOWN -> drawSlideDown(canvas, progress, width, height)
            TransitionType.CIRCLE -> drawCircle(canvas, progress, width, height)
            TransitionType.DIAMOND -> drawDiamond(canvas, progress, width, height)
            TransitionType.BOXES -> drawBoxes(canvas, progress, width, height)
            TransitionType.BLINDS -> drawBlinds(canvas, progress, width, height)
            TransitionType.WIPE -> drawWipe(canvas, progress, width, height)
        }
    }

    private fun drawFade(canvas: Canvas, progress: Float, w: Float, h: Float) {
        paint.color = Color.BLACK
        paint.alpha = (progress * 255).toInt()
        canvas.drawRect(0f, 0f, w, h, paint)
    }

    private fun drawSlideLeft(canvas: Canvas, progress: Float, w: Float, h: Float) {
        paint.color = Color.BLACK
        val x = w * (1f - progress)
        canvas.drawRect(x, 0f, w, h, paint)
    }

    private fun drawSlideRight(canvas: Canvas, progress: Float, w: Float, h: Float) {
        paint.color = Color.BLACK
        val x = w * progress
        canvas.drawRect(0f, 0f, x, h, paint)
    }

    private fun drawSlideUp(canvas: Canvas, progress: Float, w: Float, h: Float) {
        paint.color = Color.BLACK
        val y = h * (1f - progress)
        canvas.drawRect(0f, y, w, h, paint)
    }

    private fun drawSlideDown(canvas: Canvas, progress: Float, w: Float, h: Float) {
        paint.color = Color.BLACK
        val y = h * progress
        canvas.drawRect(0f, 0f, w, y, paint)
    }

    private fun drawCircle(canvas: Canvas, progress: Float, w: Float, h: Float) {
        paint.color = Color.BLACK
        val maxRadius = sqrt(w * w + h * h) / 2f
        val radius = maxRadius * progress
        canvas.drawCircle(w / 2f, h / 2f, radius, paint)
    }

    private fun drawDiamond(canvas: Canvas, progress: Float, w: Float, h: Float) {
        paint.color = Color.BLACK
        val cx = w / 2f
        val cy = h / 2f
        val size = max(w, h) * progress

        val path = android.graphics.Path()
        path.moveTo(cx, cy - size)
        path.lineTo(cx + size, cy)
        path.lineTo(cx, cy + size)
        path.lineTo(cx - size, cy)
        path.close()

        canvas.drawPath(path, paint)
    }

    private fun drawBoxes(canvas: Canvas, progress: Float, w: Float, h: Float) {
        paint.color = Color.BLACK
        val cols = 8
        val rows = 6
        val boxW = w / cols
        val boxH = h / rows

        for (i in 0 until cols) {
            for (j in 0 until rows) {
                val boxProgress = ((i + j) % 2).toFloat() * 0.5f + progress * 0.5f
                if (boxProgress > 0.5f) {
                    val alpha = ((boxProgress - 0.5f) * 2f * 255).toInt().coerceIn(0, 255)
                    paint.alpha = alpha
                    canvas.drawRect(i * boxW, j * boxH, (i + 1) * boxW, (j + 1) * boxH, paint)
                }
            }
        }
    }

    private fun drawBlinds(canvas: Canvas, progress: Float, w: Float, h: Float) {
        paint.color = Color.BLACK
        val blindCount = 10
        val blindH = h / blindCount

        for (i in 0 until blindCount) {
            val blindW = w * progress
            canvas.drawRect(0f, i * blindH, blindW, (i + 1) * blindH, paint)
        }
    }

    private fun drawWipe(canvas: Canvas, progress: Float, w: Float, h: Float) {
        paint.color = Color.BLACK
        paint.alpha = 255
        val x = w * progress
        canvas.drawRect(0f, 0f, x, h, paint)
    }
}

enum class TransitionType {
    FADE, SLIDE_LEFT, SLIDE_RIGHT, SLIDE_UP, SLIDE_DOWN,
    CIRCLE, DIAMOND, BOXES, BLINDS, WIPE
}
