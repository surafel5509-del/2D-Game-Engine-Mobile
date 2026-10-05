package com.sengine.engine.input

import android.view.MotionEvent
import kotlin.math.*

/**
 * Gesture input system for mobile touch gestures.
 * Detects swipes, pinches, taps, long presses, etc.
 */
class GestureInputSystem {
    private var enabled = true

    // Single touch tracking
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchStartTime = 0L
    private var isTouching = false
    private var touchCount = 0

    // Multi-touch tracking
    private val activeTouches = mutableMapOf<Int, TouchPoint>()
    private var initialPinchDistance = 0f

    // Gesture callbacks
    var onTap: ((Float, Float) -> Unit)? = null
    var onDoubleTap: ((Float, Float) -> Unit)? = null
    var onLongPress: ((Float, Float) -> Unit)? = null
    var onSwipe: ((direction: SwipeDirection, distance: Float, velocity: Float) -> Unit)? = null
    var onPinch: ((scale: Float, centerX: Float, centerY: Float) -> Unit)? = null
    var onRotate: ((angle: Float, centerX: Float, centerY: Float) -> Unit)? = null
    var onDrag: ((dx: Float, dy: Float, x: Float, y: Float) -> Unit)? = null

    // Settings
    var swipeThreshold = 100f
    var longPressTime = 500L
    var doubleTapTime = 300L
    var tapSlop = 20f

    // State
    private var lastTapTime = 0L
    private var isDragging = false
    private var isLongPressing = false
    private var dragStartTime = 0L

    data class TouchPoint(
        var x: Float,
        var y: Float,
        var startX: Float,
        var startY: Float,
        var startTime: Long
    )

    /**
     * Process touch events
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        if (!enabled) return false

        val action = event.actionMasked
        val pointerIndex = event.actionIndex
        val pointerId = event.getPointerId(pointerIndex)

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                handleTouchDown(event, pointerIndex)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                handlePointerDown(event, pointerIndex)
            }
            MotionEvent.ACTION_MOVE -> {
                handleTouchMove(event)
            }
            MotionEvent.ACTION_UP -> {
                handleTouchUp(event, pointerIndex)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                handlePointerUp(event, pointerIndex)
            }
            MotionEvent.ACTION_CANCEL -> {
                handleCancel()
            }
        }

        return true
    }

    private fun handleTouchDown(event: MotionEvent, pointerIndex: Int) {
        touchStartX = event.x
        touchStartY = event.y
        touchStartTime = System.currentTimeMillis()
        isTouching = true
        touchCount = 1
        isDragging = false
        isLongPressing = false

        val touch = TouchPoint(event.x, event.y, event.x, event.y, touchStartTime)
        activeTouches[0] = touch
    }

    private fun handlePointerDown(event: MotionEvent, pointerIndex: Int) {
        touchCount++
        if (touchCount == 2) {
            // Start multi-touch gesture
            val x0 = event.getX(0)
            val y0 = event.getY(0)
            val x1 = event.getX(1)
            val y1 = event.getY(1)
            initialPinchDistance = distance(x0, y0, x1, y1)
        }

        val pointerId = event.getPointerId(pointerIndex)
        val touch = TouchPoint(
            event.getX(pointerIndex),
            event.getY(pointerIndex),
            event.getX(pointerIndex),
            event.getY(pointerIndex),
            System.currentTimeMillis()
        )
        activeTouches[pointerId] = touch
    }

    private fun handleTouchMove(event: MotionEvent) {
        if (touchCount == 1) {
            val x = event.x
            val y = event.y
            val dx = x - touchStartX
            val dy = y - touchStartY
            val dist = sqrt(dx * dx + dy * dy)

            // Check for drag
            if (dist > tapSlop && !isDragging) {
                isDragging = true
                dragStartTime = System.currentTimeMillis()
            }

            // Handle drag
            if (isDragging) {
                onDrag?.invoke(dx, dy, x, y)
            }

            // Update touch point
            activeTouches[0]?.x = x
            activeTouches[0]?.y = y
        } else if (touchCount >= 2) {
            // Multi-touch gestures
            val x0 = event.getX(0)
            val y0 = event.getY(0)
            val x1 = event.getX(1)
            val y1 = event.getY(1)

            val currentDistance = distance(x0, y0, x1, y1)
            val centerX = (x0 + x1) / 2f
            val centerY = (y0 + y1) / 2f

            // Pinch zoom
            if (initialPinchDistance > 0) {
                val scale = currentDistance / initialPinchDistance
                onPinch?.invoke(scale, centerX, centerY)
            }

            // Rotation
            val angle = calculateAngle(x0, y0, x1, y1)
            onRotate?.invoke(angle, centerX, centerY)
        }
    }

    private fun handleTouchUp(event: MotionEvent, pointerIndex: Int) {
        val x = event.x
        val y = event.y
        val currentTime = System.currentTimeMillis()
        val duration = currentTime - touchStartTime

        val dx = x - touchStartX
        val dy = y - touchStartY
        val dist = sqrt(dx * dx + dy * dy)

        // Detect gestures
        if (touchCount == 1) {
            when {
                // Long press
                duration > longPressTime && dist < tapSlop -> {
                    onLongPress?.invoke(x, y)
                }
                // Swipe
                dist > swipeThreshold -> {
                    val direction = getSwipeDirection(dx, dy)
                    val velocity = dist / (duration / 1000f)
                    onSwipe?.invoke(direction, dist, velocity)
                }
                // Tap or double tap
                dist < tapSlop -> {
                    if (currentTime - lastTapTime < doubleTapTime) {
                        onDoubleTap?.invoke(x, y)
                        lastTapTime = 0
                    } else {
                        onTap?.invoke(x, y)
                        lastTapTime = currentTime
                    }
                }
            }
        }

        // Cleanup
        activeTouches.remove(event.getPointerId(pointerIndex))
        touchCount--

        if (touchCount == 0) {
            isTouching = false
            isDragging = false
            isLongPressing = false
            initialPinchDistance = 0f
        }
    }

    private fun handlePointerUp(event: MotionEvent, pointerIndex: Int) {
        touchCount--
        activeTouches.remove(event.getPointerId(pointerIndex))

        if (touchCount < 2) {
            initialPinchDistance = 0f
        }
    }

    private fun handleCancel() {
        activeTouches.clear()
        touchCount = 0
        isTouching = false
        isDragging = false
        isLongPressing = false
        initialPinchDistance = 0f
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }

    private fun calculateAngle(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }

    private fun getSwipeDirection(dx: Float, dy: Float): SwipeDirection {
        return if (abs(dx) > abs(dy)) {
            if (dx > 0) SwipeDirection.RIGHT else SwipeDirection.LEFT
        } else {
            if (dy > 0) SwipeDirection.DOWN else SwipeDirection.UP
        }
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    fun isEnabled(): Boolean = enabled

    fun isTouching(): Boolean = isTouching
    fun isDragging(): Boolean = isDragging
    fun getTouchCount(): Int = touchCount
    fun getTouchPosition(index: Int = 0): Pair<Float, Float>? {
        return activeTouches[index]?.let { it.x to it.y }
    }
}

enum class SwipeDirection {
    UP, DOWN, LEFT, RIGHT
}
