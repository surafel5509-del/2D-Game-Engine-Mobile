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
