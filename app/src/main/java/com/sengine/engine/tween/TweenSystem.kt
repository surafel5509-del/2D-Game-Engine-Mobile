package com.sengine.engine.tween

import com.sengine.engine.core.GameObject
import kotlin.math.*

/**
 * Tween system for smooth animations and transitions.
 * Supports position, rotation, scale, color, and custom property tweening.
 */
class TweenSystem {
    private val activeTweens = mutableListOf<Tween>()
    private val completedTweens = mutableListOf<Tween>()

    /**
     * Create a new tween
     */
    fun tween(target: GameObject): TweenBuilder {
        return TweenBuilder(this, target)
    }

    /**
     * Update all active tweens
     */
    fun update(dt: Float) {
        val iterator = activeTweens.iterator()
        while (iterator.hasNext()) {
            val tween = iterator.next()
            tween.update(dt)
            if (tween.isComplete()) {
                iterator.remove()
                completedTweens.add(tween)
                tween.onComplete?.invoke()
            }
        }
    }

    /**
     * Stop all tweens for a specific target
     */
    fun stopTweens(target: GameObject) {
        activeTweens.removeAll { it.target === target }
    }

    /**
     * Stop all active tweens
     */
    fun stopAll() {
        activeTweens.clear()
    }

    /**
     * Get count of active tweens
     */
    fun getActiveCount(): Int = activeTweens.size

    internal fun addTween(tween: Tween) {
        activeTweens.add(tween)
    }
}

/**
 * Builder pattern for creating tweens
 */
class TweenBuilder(private val system: TweenSystem, private val target: GameObject) {
    private var duration = 1f
    private var delay = 0f
    private var easing = Easing.LINEAR
    private var loop = false
    private var yoyo = false
    private var onStart: (() -> Unit)? = null
    private var onUpdate: ((Float) -> Unit)? = null
    private var onComplete: (() -> Unit)? = null

    fun to(endX: Float, endY: Float, duration: Float): TweenBuilder {
        this.duration = duration
        val tween = Tween(target, TweenType.POSITION, duration)
        tween.startValues = floatArrayOf(target.x, target.y)
        tween.endValues = floatArrayOf(endX, endY)
        tween.delay = delay
        tween.easing = easing
        tween.loop = loop
        tween.yoyo = yoyo
        tween.onStart = onStart
        tween.onUpdate = onUpdate
        tween.onComplete = onComplete
        system.addTween(tween)
        return this
    }

    fun rotateTo(endRotation: Float, duration: Float): TweenBuilder {
        this.duration = duration
        val tween = Tween(target, TweenType.ROTATION, duration)
        tween.startValues = floatArrayOf(target.rotation)
        tween.endValues = floatArrayOf(endRotation)
        tween.delay = delay
        tween.easing = easing
        tween.loop = loop
        tween.yoyo = yoyo
        tween.onStart = onStart
        tween.onUpdate = onUpdate
        tween.onComplete = onComplete
        system.addTween(tween)
        return this
    }

    fun scaleTo(endScaleX: Float, endScaleY: Float, duration: Float): TweenBuilder {
        this.duration = duration
        val tween = Tween(target, TweenType.SCALE, duration)
        tween.startValues = floatArrayOf(target.scaleX, target.scaleY)
        tween.endValues = floatArrayOf(endScaleX, endScaleY)
        tween.delay = delay
        tween.easing = easing
        tween.loop = loop
        tween.yoyo = yoyo
        tween.onStart = onStart
        tween.onUpdate = onUpdate
        tween.onComplete = onComplete
        system.addTween(tween)
        return this
    }

    fun colorTo(startColor: Int, endColor: Int, duration: Float): TweenBuilder {
        this.duration = duration
        val tween = Tween(target, TweenType.COLOR, duration)
        tween.startValues = floatArrayOf(
            ((startColor shr 16) and 0xFF).toFloat(),
            ((startColor shr 8) and 0xFF).toFloat(),
            (startColor and 0xFF).toFloat(),
            ((startColor shr 24) and 0xFF).toFloat()
        )
        tween.endValues = floatArrayOf(
            ((endColor shr 16) and 0xFF).toFloat(),
            ((endColor shr 8) and 0xFF).toFloat(),
            (endColor and 0xFF).toFloat(),
            ((endColor shr 24) and 0xFF).toFloat()
        )
        tween.delay = delay
        tween.easing = easing
        tween.loop = loop
        tween.yoyo = yoyo
        tween.onStart = onStart
        tween.onUpdate = onUpdate
        tween.onComplete = onComplete
        system.addTween(tween)
        return this
    }

    fun withDelay(delay: Float): TweenBuilder {
        this.delay = delay
        return this
    }

    fun withEasing(easing: Easing): TweenBuilder {
        this.easing = easing
        return this
    }

    fun withLoop(loop: Boolean): TweenBuilder {
        this.loop = loop
        return this
    }

    fun withYoyo(yoyo: Boolean): TweenBuilder {
        this.yoyo = yoyo
        return this
    }

    fun onStart(callback: () -> Unit): TweenBuilder {
        this.onStart = callback
        return this
    }

    fun onUpdate(callback: (Float) -> Unit): TweenBuilder {
        this.onUpdate = callback
        return this
    }

    fun onComplete(callback: () -> Unit): TweenBuilder {
        this.onComplete = callback
        return this
    }
}

/**
 * Individual tween instance
 */
class Tween(
    val target: GameObject,
    val type: TweenType,
    val duration: Float
) {
    var startValues = floatArrayOf()
    var endValues = floatArrayOf()
    var delay = 0f
    var easing = Easing.LINEAR
    var loop = false
    var yoyo = false

    var onStart: (() -> Unit)? = null
    var onUpdate: ((Float) -> Unit)? = null
    var onComplete: (() -> Unit)? = null

    private var elapsed = 0f
    private var started = false
    private var direction = 1 // 1 = forward, -1 = backward (for yoyo)

    fun update(dt: Float) {
        if (delay > 0) {
            delay -= dt
            return
        }

        if (!started) {
            started = true
            onStart?.invoke()
        }

        elapsed += dt * direction
        val progress = (elapsed / duration).coerceIn(0f, 1f)
        val easedProgress = easing.apply(progress)

        // Apply tween values
        when (type) {
            TweenType.POSITION -> {
                target.x = lerp(startValues[0], endValues[0], easedProgress)
                target.y = lerp(startValues[1], endValues[1], easedProgress)
            }
            TweenType.ROTATION -> {
                target.rotation = lerp(startValues[0], endValues[0], easedProgress)
            }
            TweenType.SCALE -> {
                target.scaleX = lerp(startValues[0], endValues[0], easedProgress)
                target.scaleY = lerp(startValues[1], endValues[1], easedProgress)
            }
            TweenType.COLOR -> {
                // Color interpolation would be handled by the renderer
                // This is a simplified version
            }
        }

        onUpdate?.invoke(easedProgress)

        // Handle completion and looping
        if (progress >= 1f) {
            if (yoyo) {
                direction *= -1
                elapsed = duration
            } else if (loop) {
                elapsed = 0f
            } else {
                elapsed = duration
            }
        } else if (progress <= 0f && direction == -1) {
            if (loop) {
                direction = 1
                elapsed = 0f
            }
        }
    }

    fun isComplete(): Boolean {
        return !loop && !yoyo && elapsed >= duration
    }

    private fun lerp(start: Float, end: Float, t: Float): Float {
        return start + (end - start) * t
    }
}

enum class TweenType {
    POSITION, ROTATION, SCALE, COLOR
}

/**
 * Easing functions for tween animations
 */
enum class Easing {
    LINEAR {
        override fun apply(t: Float): Float = t
    },
    EASE_IN_QUAD {
        override fun apply(t: Float): Float = t * t
    },
    EASE_OUT_QUAD {
        override fun apply(t: Float): Float = t * (2 - t)
    },
    EASE_IN_OUT_QUAD {
        override fun apply(t: Float): Float = if (t < 0.5f) 2 * t * t else -1 + (4 - 2 * t) * t
    },
    EASE_IN_CUBIC {
        override fun apply(t: Float): Float = t * t * t
    },
    EASE_OUT_CUBIC {
        override fun apply(t: Float): Float {
            val t1 = t - 1
            return t1 * t1 * t1 + 1
        }
    },
    EASE_IN_OUT_CUBIC {
        override fun apply(t: Float): Float {
            return if (t < 0.5f) 4 * t * t * t else (t - 1) * (2 * t - 2) * (2 * t - 2) + 1
        }
    },
    EASE_IN_BACK {
        override fun apply(t: Float): Float {
            val s = 1.70158f
            return t * t * ((s + 1) * t - s)
        }
    },
    EASE_OUT_BACK {
        override fun apply(t: Float): Float {
            val s = 1.70158f
            val t1 = t - 1
            return t1 * t1 * ((s + 1) * t1 + s) + 1
        }
    },
    EASE_IN_ELASTIC {
        override fun apply(t: Float): Float {
            if (t == 0f || t == 1f) return t
            val p = 0.3f
            val s = p / 4
            val t1 = t - 1
            return -(2f.pow(10 * t1) * sin((t1 - s) * (2 * PI / p).toFloat()))
        }
    },
    EASE_OUT_ELASTIC {
        override fun apply(t: Float): Float {
            if (t == 0f || t == 1f) return t
            val p = 0.3f
            val s = p / 4
            return 2f.pow(-10 * t) * sin((t - s) * (2 * PI / p).toFloat()) + 1
        }
    },
    EASE_IN_BOUNCE {
        override fun apply(t: Float): Float = 1 - EASE_OUT_BOUNCE.apply(1 - t)
    },
    EASE_OUT_BOUNCE {
        override fun apply(t: Float): Float {
            return when {
                t < 1 / 2.75f -> 7.5625f * t * t
                t < 2 / 2.75f -> {
                    val t1 = t - 1.5f / 2.75f
                    7.5625f * t1 * t1 + 0.75f
                }
                t < 2.5 / 2.75f -> {
                    val t1 = t - 2.25f / 2.75f
                    7.5625f * t1 * t1 + 0.9375f
                }
                else -> {
                    val t1 = t - 2.625f / 2.75f
                    7.5625f * t1 * t1 + 0.984375f
                }
            }
        }
    };

    abstract fun apply(t: Float): Float
}
