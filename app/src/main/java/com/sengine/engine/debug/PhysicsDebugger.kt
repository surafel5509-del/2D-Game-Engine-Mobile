package com.sengine.engine.debug

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.sengine.engine.core.*
import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.JointComponent
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Scene

/**
 * Physics debugger for visualizing colliders, joints, rays, and contact points.
 */
class PhysicsDebugger {
    var enabled = false
    var showColliders = true
    var showJoints = true
    var showContacts = true
    var showRaycasts = true
    var showAABB = false
    var showCenterOfMass = true

    private val colliderPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    private val triggerPaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    private val jointPaint = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    private val comPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val aabbPaint = Paint().apply {
        color = Color.argb(100, 255, 255, 0)
        style = Paint.Style.STROKE
        strokeWidth = 1f
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 24f
        isAntiAlias = true
        isFakeBoldText = true
    }

    /**
     * Draw physics debug visualization
     */
    fun draw(canvas: Canvas, scene: Scene) {
        if (!enabled) return

        // Draw all colliders
        if (showColliders) {
            drawAllColliders(canvas, scene)
        }

        // Draw joints
        if (showJoints) {
            drawAllJoints(canvas, scene)
        }

        // Draw AABB
        if (showAABB) {
            drawAllAABB(canvas, scene)
        }
    }

    private fun drawAllColliders(canvas: Canvas, scene: Scene) {
        for (go in scene.objects) {
            val collider = go.getAny<Collider2D>() ?: continue

            val paint = if (collider.isTrigger) triggerPaint else colliderPaint

            when (collider.shape) {
                0 -> drawBoxCollider(canvas, go, collider, paint) // Box
                1 -> drawCircleCollider(canvas, go, collider, paint) // Circle
                2 -> drawCapsuleCollider(canvas, go, collider, paint) // Capsule
            }

            // Draw center of mass
            if (showCenterOfMass) {
                val cx = go.x + collider.offsetX
                val cy = go.y + collider.offsetY
                canvas.drawCircle(cx, cy, 4f, comPaint)
            }
        }
    }

    private fun drawBoxCollider(canvas: Canvas, go: GameObject, collider: Collider2D, paint: Paint) {
        val w = collider.width
        val h = collider.height
        val cx = go.x + collider.offsetX
        val cy = go.y + collider.offsetY

        if (go.rotation != 0f) {
            canvas.save()
            canvas.rotate(go.rotation, cx, cy)
            canvas.drawRect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f, paint)
            canvas.restore()
        } else {
            canvas.drawRect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f, paint)
        }
    }

    private fun drawCircleCollider(canvas: Canvas, go: GameObject, collider: Collider2D, paint: Paint) {
        val cx = go.x + collider.offsetX
        val cy = go.y + collider.offsetY
        canvas.drawCircle(cx, cy, collider.radius, paint)
    }

    private fun drawCapsuleCollider(canvas: Canvas, go: GameObject, collider: Collider2D, paint: Paint) {
        val cx = go.x + collider.offsetX
        val cy = go.y + collider.offsetY
        val r = collider.radius
        val h = collider.height

        val rect = android.graphics.RectF(cx - r, cy - h / 2f, cx + r, cy + h / 2f)
        canvas.drawRoundRect(rect, r, r, paint)
    }

    private fun drawAllJoints(canvas: Canvas, scene: Scene) {
        for (go in scene.objects) {
            val joint = go.getAny<JointComponent>() ?: continue

            val target = scene.find(joint.targetName)
            if (target != null) {
                canvas.drawLine(go.x, go.y, target.x, target.y, jointPaint)

                // Draw anchor points
                canvas.drawCircle(go.x, go.y, 5f, jointPaint)
                canvas.drawCircle(target.x, target.y, 5f, jointPaint)
            }
        }
    }

    private fun drawAllAABB(canvas: Canvas, scene: Scene) {
        for (go in scene.objects) {
            val collider = go.getAny<Collider2D>() ?: continue

            val aabb = when (collider.shape) {
                0 -> { // Box
                    val cx = go.x + collider.offsetX
                    val cy = go.y + collider.offsetY
                    android.graphics.RectF(cx - collider.width / 2f, cy - collider.height / 2f,
                        cx + collider.width / 2f, cy + collider.height / 2f)
                }
                1 -> { // Circle
                    val cx = go.x + collider.offsetX
                    val cy = go.y + collider.offsetY
                    android.graphics.RectF(cx - collider.radius, cy - collider.radius,
                        cx + collider.radius, cy + collider.radius)
                }
                else -> null
            }

            aabb?.let {
                canvas.drawRect(it, aabbPaint)
            }
        }
    }

    /**
     * Draw debug text overlay
     */
    fun drawDebugText(canvas: Canvas, scene: Scene) {
        if (!enabled) return

        val bodyCount = scene.objects.count { it.getAny<Rigidbody2D>() != null }
        val colliderCount = scene.objects.count { it.getAny<Collider2D>() != null }
        val jointCount = scene.objects.count { it.getAny<JointComponent>() != null }

        var y = 50f
        canvas.drawText("Physics Debug", 20f, y, textPaint)
        y += 30f
        canvas.drawText("Rigid Bodies: $bodyCount", 20f, y, textPaint)
        y += 30f
        canvas.drawText("Colliders: $colliderCount", 20f, y, textPaint)
        y += 30f
        canvas.drawText("Joints: $jointCount", 20f, y, textPaint)
    }
}

/**
 * Performance profiler for real-time debugging
 */
class Profiler {
    var enabled = false
    private val frameTimes = mutableListOf<Float>()
    private val maxFrameSamples = 120
    private var lastFrameTime = 0L

    // Frame stats
    var fps = 0f
    var frameTime = 0f
    var minFps = Float.MAX_VALUE
    var maxFps = 0f
    var avgFps = 0f

    // Component stats
    var gameObjectCount = 0
    var componentCount = 0
    var particleCount = 0
    var drawCalls = 0
    var textureMemoryMb = 0f

    // Memory stats
    var usedMemoryMb = 0f
    var totalMemoryMb = 0f

    fun beginFrame() {
        lastFrameTime = System.nanoTime()
    }

    fun endFrame() {
        val elapsed = (System.nanoTime() - lastFrameTime) / 1_000_000f
        frameTime = elapsed
        fps = if (elapsed > 0) 1000f / elapsed else 0f

        frameTimes.add(elapsed)
        if (frameTimes.size > maxFrameSamples) {
            frameTimes.removeAt(0)
        }

        // Calculate average FPS
        if (frameTimes.isNotEmpty()) {
            val avgFrameTime = frameTimes.average().toFloat()
            avgFps = if (avgFrameTime > 0) 1000f / avgFrameTime else 0f
            val maxTime = frameTimes.maxOrNull() ?: 1f
            val minTime = frameTimes.minOrNull() ?: 1f
            minFps = if (maxTime > 0) 1000f / maxTime else 0f
            maxFps = if (minTime > 0) 1000f / minTime else 0f
        }

        // Update memory stats
        val runtime = Runtime.getRuntime()
        usedMemoryMb = (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576f
        totalMemoryMb = runtime.totalMemory() / 1_048_576f
    }

    /**
     * Draw profiler overlay
     */
    fun draw(canvas: Canvas) {
        if (!enabled) return

        val paint = Paint().apply {
            color = Color.WHITE
            textSize = 20f
            isAntiAlias = true
            isFakeBoldText = true
        }

        val bgPaint = Paint().apply {
            color = Color.argb(150, 0, 0, 0)
        }

        // Draw background
        canvas.drawRect(0f, 0f, 400f, 300f, bgPaint)

        var y = 30f
        canvas.drawText("=== Performance Profiler ===", 10f, y, paint)
        y += 25f
        canvas.drawText("FPS: %.1f (min: %.1f, max: %.1f)".format(fps, minFps, maxFps), 10f, y, paint)
        y += 25f
        canvas.drawText("Frame Time: %.2f ms".format(frameTime), 10f, y, paint)
        y += 25f
        canvas.drawText("Avg FPS: %.1f".format(avgFps), 10f, y, paint)
        y += 25f
        canvas.drawText("GameObjects: $gameObjectCount", 10f, y, paint)
        y += 25f
        canvas.drawText("Components: $componentCount", 10f, y, paint)
        y += 25f
        canvas.drawText("Particles: $particleCount", 10f, y, paint)
        y += 25f
        canvas.drawText("Draw Calls: $drawCalls", 10f, y, paint)
        y += 25f
        canvas.drawText("Memory: %.1f / %.1f MB".format(usedMemoryMb, totalMemoryMb), 10f, y, paint)

        // Draw FPS graph
        drawFpsGraph(canvas, paint)
    }

    private fun drawFpsGraph(canvas: Canvas, paint: Paint) {
        val graphX = 10f
        val graphY = 280f
        val graphW = 380f
        val graphH = 80f

        val graphPaint = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }

        if (frameTimes.size < 2) return

        val maxFrameTime = frameTimes.maxOrNull() ?: 1f
        val step = graphW / frameTimes.size

        var prevX = graphX
        var prevY = graphY + graphH

        for (i in 1 until frameTimes.size) {
            val x = graphX + i * step
            val normalizedTime = if (maxFrameTime > 0) frameTimes[i] / maxFrameTime else 0f
            val yPos = graphY + graphH - normalizedTime * graphH
            canvas.drawLine(prevX, prevY, x, yPos, graphPaint)
            prevX = x
            prevY = yPos
        }
    }

    fun reset() {
        frameTimes.clear()
        minFps = Float.MAX_VALUE
        maxFps = 0f
    }
}
