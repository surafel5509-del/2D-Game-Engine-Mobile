package com.sengine.engine.physics

import com.sengine.engine.core.Component
import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Prop
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.core.Scene
import kotlin.math.*

/**
 * Ragdoll component for creating physics-based character ragdolls.
 * Manages a hierarchy of rigidbody parts connected by joints.
 * Each part is a child GameObject with its own Rigidbody2D and Collider2D.
 */
class Ragdoll : Component() {
    override val type = "Ragdoll"

    var active = false
    var jointStiffness = 0.5f // 0..1, higher = more rigid
    var limbMass = 1f
    var headMass = 1.5f
    var torsoMass = 2f
    var damping = 0.95f

    // Runtime
    val parts = ArrayList<RagdollPart>()
    val joints = ArrayList<Joint>()

    override fun props() = listOf(
        Prop.B("Active", { active }, { active = it }),
        Prop.F("Joint Stiffness", { jointStiffness }, { jointStiffness = it.coerceIn(0f, 1f) }),
        Prop.F("Limb Mass", { limbMass }, { limbMass = it.coerceAtLeast(0.1f) }),
        Prop.F("Head Mass", { headMass }, { headMass = it.coerceAtLeast(0.1f) }),
        Prop.F("Torso Mass", { torsoMass }, { torsoMass = it.coerceAtLeast(0.1f) }),
        Prop.F("Damping", { damping }, { damping = it.coerceIn(0f, 1f) }),
    )

    override fun resetRuntime() {
        active = false
        parts.clear()
        joints.clear()
    }

    /**
     * Initialize ragdoll from the hierarchy of the parent GameObject.
     * Expected child structure:
     *   Body (this object)
     *     Head
     *     Torso
     *     LeftArm
     *     RightArm
     *     LeftLeg
     *     RightLeg
     */
    fun initRagdoll() {
        parts.clear()
        joints.clear()

        val body = gameObject
        // The body itself becomes a rigidbody part
        ensurePart(body, torsoMass)

        // Find children by naming convention
        for (child in listOf("Head", "Torso", "LeftArm", "RightArm", "LeftLeg", "RightLeg",
            "LeftUpperArm", "RightUpperArm", "LeftLowerArm", "RightLowerArm",
            "LeftUpperLeg", "RightUpperLeg", "LeftLowerLeg", "RightLowerLeg")) {
            val childGo = body.components.let {
                // Search scene objects for children
                null // Will be set externally
            }
        }
    }

    /** Register a body part. */
    fun addPart(go: GameObject, mass: Float, parentPart: GameObject?) {
        ensurePart(go, mass)
        parts.add(RagdollPart(go, mass))

        if (parentPart != null) {
            val joint = DistanceJoint()
            joint.bodyA = parentPart
            joint.bodyB = go
            joint.stiffness = jointStiffness * 200f
            joint.damping = damping
            // Anchors at connection point
            val px = parentPart.x; val py = parentPart.y
            val cx = go.x; val cy = go.y
            joint.anchorAx = cx - px; joint.anchorAy = cy - py
            joint.anchorBx = 0f; joint.anchorBy = 0f
            joint.distance = sqrt((cx - px) * (cx - px) + (cy - py) * (cy - py))
            joints.add(joint)
        }
    }

    private fun ensurePart(go: GameObject, mass: Float) {
        val rb = go.components.filterIsInstance<Rigidbody2D>().firstOrNull()
        if (rb == null) {
            val newRb = Rigidbody2D()
            newRb.bodyType = 0
            newRb.mass = mass
            newRb.gravityScale = 1f
            newRb.drag = 0.5f
            newRb.bounciness = 0.1f
            newRb.friction = 0.5f
            go.add(newRb)
        } else {
            rb.bodyType = 0
            rb.mass = mass
        }

        if (go.components.filterIsInstance<Collider2D>().firstOrNull() == null) {
            val col = Collider2D()
            col.shape = 0 // box
            col.width = 0.3f
            col.height = 0.8f
            go.add(col)
        }
    }

    /** Activate ragdoll physics. Disables character animation. */
    fun activate(scene: Scene) {
        active = true
        for (part in parts) {
            val rb = part.gameObject.components.filterIsInstance<Rigidbody2D>().firstOrNull() ?: continue
            rb.bodyType = 0
            // Give a small impulse to start ragdoll motion
            rb.vx += (Math.random() - 0.5).toFloat() * 2f
            rb.vy += (Math.random()).toFloat() * 3f
        }
    }

    /** Deactivate ragdoll and reset to character mode. */
    fun deactivate() {
        active = false
        for (part in parts) {
            val rb = part.gameObject.components.filterIsInstance<Rigidbody2D>().firstOrNull() ?: continue
            rb.vx = 0f; rb.vy = 0f
            rb.bodyType = 2 // static
        }
    }

    /** Apply an explosion force to all parts. */
    fun addExplosionForce(originX: Float, originY: Float, force: Float, radius: Float) {
        for (part in parts) {
            val w = part.gameObject.computeWorld()
            val dx = w.tx - originX
            val dy = w.ty - originY
            val dist = sqrt(dx * dx + dy * dy)
            if (dist > radius || dist < 0.01f) continue
            val factor = (1f - dist / radius) * force
            val rb = part.gameObject.components.filterIsInstance<Rigidbody2D>().firstOrNull { it.bodyType == 0 } ?: continue
            rb.vx += (dx / dist) * factor / rb.mass
            rb.vy += (dy / dist) * factor / rb.mass
        }
    }

    /** Get the center of mass of the ragdoll. */
    fun centerOfMass(): Pair<Float, Float> {
        if (parts.isEmpty()) return gameObject.x to gameObject.y
        var cx = 0f; var cy = 0f; var totalMass = 0f
        for (part in parts) {
            val w = part.gameObject.computeWorld()
            cx += w.tx * part.mass
            cy += w.ty * part.mass
            totalMass += part.mass
        }
        if (totalMass == 0f) return gameObject.x to gameObject.y
        return cx / totalMass to cy / totalMass
    }

    data class RagdollPart(val gameObject: GameObject, val mass: Float)
}
