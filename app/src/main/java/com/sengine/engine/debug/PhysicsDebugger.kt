package com.sengine.engine.debug

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.sengine.engine.core.*
import com.sengine.engine.physics.PhysicsEngine

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
    var showNormals = true
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

    private val contactPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val normalPaint = Paint().apply {
        color = Color.MAGENTA
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    private val aabbPaint = Paint().apply {
        color = Color.argb(100, 255, 255, 0)
        style = Paint.Style.STROKE
        strokeWidth = 1f
        isAntiAlias = true
    }

    private val comPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
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
    fun draw(canvas: Canvas, physicsEngine: PhysicsEngine, scene: Scene) {
        if (!enabled) return

        // Draw all colliders
        if (showColliders) {
            drawAllColliders(canvas, scene)
        }

        // Draw joints
        if (showJoints) {
            drawAllJoints(canvas, scene)
        }

        // Draw contact points
        if (showContacts) {
            drawContactPoints(canvas, physicsEngine)
        }

        // Draw AABB
        if (showAABB) {
            drawAllAABB(canvas, scene)
        }
    }

    private fun drawAllColliders(canvas: Canvas, scene: Scene) {
        scene.root.forEachChild { go ->
            val collider = go.getComponent(Collider2D::class.java) ?: return@forEachChild

            val paint = if (collider.isTrigger) triggerPaint else colliderPaint

            when (collider.shape.lowercase()) {
                "box" -> drawBoxCollider(canvas, go, collider, paint)
                "circle" -> drawCircleCollider(canvas, go, collider, paint)
                "capsule" -> drawCapsuleCollider(canvas, go, collider, paint)
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

        // Draw capsule as rounded rectangle
        val rect = android.graphics.RectF(cx - r, cy - h / 2f, cx + r, cy + h / 2f)
        canvas.drawRoundRect(rect, r, r, paint)
    }

    private fun drawAllJoints(canvas: Canvas, scene: Scene) {
        scene.root.forEachChild { go ->
            val joint = go.getComponent(JointComponent::class.java) ?: return@forEachChild

            val target = scene.root.findChildRecursive { it.name == joint.targetName }
            if (target != null) {
                canvas.drawLine(go.x, go.y, target.x, target.y, jointPaint)

                // Draw anchor points
                canvas.drawCircle(go.x, go.y, 5f, jointPaint)
                canvas.drawCircle(target.x, target.y, 5f, jointPaint)
            }
        }
    }

    private fun drawContactPoints(canvas: Canvas, physicsEngine: PhysicsEngine) {
        // Would need contact point data from physics engine
        // This is a placeholder for the actual implementation
    }

    private fun drawAllAABB(canvas: Canvas, scene: Scene) {
        scene.root.forEachChild { go ->
            val collider = go.getComponent(Collider2D::class.java) ?: return@forEachChild

            // Calculate AABB
            val aabb = when (collider.shape.lowercase()) {
                "box" -> {
                    val cx = go.x + collider.offsetX
                    val cy = go.y + collider.offsetY
                    android.graphics.RectF(cx - collider.width / 2f, cy - collider.height / 2f,
                        cx + collider.width / 2f, cy + collider.height / 2f)
                }
                "circle" -> {
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
    fun drawDebugText(canvas: Canvas, physicsEngine: PhysicsEngine) {
        if (!enabled) return

        val stats = physicsEngine.getStats()
        var y = 50f

        canvas.drawText("Physics Debug", 20f, y, textPaint)
        y += 30f
        canvas.drawText("Active Bodies: ${stats.activeBodies}", 20f, y, textPaint)
        y += 30f
        canvas.drawText("Contact Points: ${stats.contactCount}", 20f, y, textPaint)
        y += 30f
        canvas.drawText("Raycasts: ${stats.raycastCount}", 20f, y, textPaint)
        y += 30f
        canvas.drawText("Physics Time: ${"%.2f".format(stats.physicsTimeMs)}ms", 20f, y, textPaint)
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

    // Physics stats
    var physicsTimeMs = 0f
    var bodyCount = 0
    var contactCount = 0

    // Memory stats
    var usedMemoryMb = 0f
    var totalMemoryMb = 0f

    fun beginFrame() {
        lastFrameTime = System.nanoTime()
    }

    fun endFrame() {
        val elapsed = (System.nanoTime() - lastFrameTime) / 1_000_000f
        frameTime = elapsed
        fps = 1000f / elapsed

        frameTimes.add(elapsed)
        if (frameTimes.size > maxFrameSamples) {
            frameTimes.removeAt(0)
        }

        // Calculate average FPS
        if (frameTimes.isNotEmpty()) {
            val avgFrameTime = frameTimes.average().toFloat()
            avgFps = 1000f / avgFrameTime
            minFps = 1000f / frameTimes.max()!!
            maxFps = 1000f / frameTimes.min()!!
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
        y += 25f
        canvas.drawText("Physics: %.2f ms (%d bodies)".format(physicsTimeMs, bodyCount), 10f, y, paint)

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

        val maxFrameTime = frameTimes.max() ?: 1f
        val step = graphW / frameTimes.size

        var prevX = graphX
        var prevY = graphY + graphH

        for (i in 1 until frameTimes.size) {
            val x = graphX + i * step
            val y = graphY + graphH - (frameTimes[i] / maxFrameTime) * graphH
            canvas.drawLine(prevX, prevY, x, y, graphPaint)
            prevX = x
            prevY = y
        }
    }

    fun reset() {
        frameTimes.clear()
        minFps = Float.MAX_VALUE
        maxFps = 0f
    }
}
