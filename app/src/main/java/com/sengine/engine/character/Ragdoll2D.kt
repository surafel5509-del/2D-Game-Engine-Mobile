package com.sengine.engine.character

import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.Component
import com.sengine.engine.core.Health
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.HingeJoint
import com.sengine.engine.core.Prop
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.core.Scene
import com.sengine.engine.core.SpriteRenderer
import com.sengine.engine.math.Colors
import com.sengine.engine.math.M

/**
 * Ragdoll2D - a breakable 2D ragdoll for characters.
 *
 * The rig is generated from a compact spec string (head, torso, arms and legs with hinge joints
 * and limits). While "stiff" the bones are kinematic; calling [goLimp] (or dying, optionally)
 * turns every bone into a dynamic body so the character collapses with real physics.
 */
class Ragdoll2D : Component() {
    override val type = "Ragdoll2D"

    /** Total height of the rig in world units. */
    var height = 1.6f
    /** Bone spec: `name,width,height,offsetY[,parentAngleRange]`. */
    var bones = ""
    var color = 0xFFD9DEE8.toInt()
    var density = 0.9f
    var goLimpOnDeath = true
    /** Delay before going limp after death (impact feel). */
    var limpDelay = 0.05f
    /** Impulse applied at each bone when going limp. */
    var explosiveForce = 2.5f
    /** Disable the host sprite while the ragdoll is active. */
    var hideHostSprite = true
    var stiffness = 0.6f

    var limp = false
        private set
    val boneObjects = ArrayList<GameObject>()
    private var limpTimer = -1f

    override fun props() = listOf(
        Prop.F("Height", { height }, { height = it.coerceAtLeast(0.2f) }, 0.05f, 0.2f, 20f),
        Prop.S("Bones", { bones }, { bones = it }, multiline = true, tooltip = "name,width,height,offsetY[,angleMin,angleMax] separated by ';'. Empty = default humanoid."),
        Prop.Color("Color", { color }, { color = it }),
        Prop.F("Density", { density }, { density = it.coerceAtLeast(0.05f) }, 0.05f, 0.05f, 20f),
        Prop.B("Go Limp On Death", { goLimpOnDeath }, { goLimpOnDeath = it }),
        Prop.F("Limp Delay", { limpDelay }, { limpDelay = it.coerceAtLeast(0f) }, 0.01f, 0f, 2f),
        Prop.F("Explosive Force", { explosiveForce }, { explosiveForce = it.coerceAtLeast(0f) }, 0.1f, 0f, 100f),
        Prop.B("Hide Host Sprite", { hideHostSprite }, { hideHostSprite = it }),
        Prop.F("Stiffness", { stiffness }, { stiffness = it.coerceIn(0f, 1f) }, 0.05f, 0f, 1f),
        Prop.Info("Bones", { boneObjects.size.toString() }),
        Prop.Info("State", { if (limp) "Limp" else "Stiff" })
    )

    override fun resetRuntime() {
        limp = false
        limpTimer = -1f
        boneObjects.clear()
    }

    /** Builds the rig as children of the host object. Called by the engine at scene start. */
    fun build(scene: Scene) {
        if (boneObjects.isNotEmpty()) return
        val specs = parseSpec()
        for (spec in specs) {
            val bone = scene.create("${go.name}_${spec.name}", go)
            bone.x = spec.offsetX
            bone.y = spec.offsetY
            bone.tag = "RagdollBone"
            val rb = Rigidbody2D()
            rb.bodyType = 1 // kinematic while stiff
            rb.mass = (spec.width * spec.height * density).coerceAtLeast(0.05f)
            bone.add(rb)
            val col = Collider2D()
            col.shape = Collider2D.SHAPE_BOX
            col.width = spec.width
            col.height = spec.height
            col.density = density
            col.friction = 0.6f
            col.restitution = 0.05f
            bone.add(col)
            val spr = SpriteRenderer()
            spr.shape = 0
            spr.width = spec.width
            spr.height = spec.height
            spr.color = color
            spr.roundness = 0.35f
            bone.add(spr)
            if (spec.parentName != null) {
                val joint = HingeJoint()
                joint.connectedTo = boneObjects.lastOrNull { it.name.endsWith("_${spec.parentName}") }?.name ?: go.name
                joint.anchorX = spec.offsetX
                joint.anchorY = spec.offsetY + spec.height * 0.5f
                joint.enableLimit = true
                joint.lowerAngle = spec.angleMin
                joint.upperAngle = spec.angleMax
                bone.add(joint)
            } else {
                val joint = HingeJoint()
                joint.connectedTo = ""
                joint.anchorX = 0f
                joint.anchorY = 0f
                bone.add(joint)
            }
            boneObjects.add(bone)
        }
        if (hideHostSprite) {
            go.getAny<SpriteRenderer>()?.let { it.visible = false }
        }
    }

    private class BoneSpec(
        val name: String, val width: Float, val height: Float,
        val offsetX: Float, val offsetY: Float, val parentName: String?,
        val angleMin: Float, val angleMax: Float
    )

    private fun parseSpec(): List<BoneSpec> {
        if (bones.isBlank()) return defaultHumanoid()
        val out = ArrayList<BoneSpec>()
        for (raw in bones.split(';', '\n')) {
            val parts = raw.split(',').map { it.trim() }
            if (parts.size < 4) continue
            val name = parts[0]
            val w = parts[1].toFloatOrNull() ?: continue
            val h = parts[2].toFloatOrNull() ?: continue
            val oy = parts[3].toFloatOrNull() ?: continue
            val ox = parts.getOrNull(4)?.toFloatOrNull() ?: 0f
            val parent = parts.getOrNull(5)?.takeIf { it.isNotBlank() && it != "-" }
            val amin = parts.getOrNull(6)?.toFloatOrNull() ?: -70f
            val amax = parts.getOrNull(7)?.toFloatOrNull() ?: 70f
            out.add(BoneSpec(name, w, h, ox, oy, parent, amin, amax))
        }
        return if (out.isEmpty()) defaultHumanoid() else out
    }

    private fun defaultHumanoid(): List<BoneSpec> {
        val h = height / 1.6f
        return listOf(
            BoneSpec("torso", 0.36f * h, 0.55f * h, 0f, 0.32f * h, null, -25f, 25f),
            BoneSpec("head", 0.28f * h, 0.28f * h, 0f, 0.78f * h, "torso", -35f, 35f),
            BoneSpec("armL", 0.16f * h, 0.4f * h, -0.26f * h, 0.5f * h, "torso", -80f, 20f),
            BoneSpec("armR", 0.16f * h, 0.4f * h, 0.26f * h, 0.5f * h, "torso", -20f, 80f),
            BoneSpec("legL", 0.18f * h, 0.45f * h, -0.12f * h, -0.12f * h, "torso", -80f, 20f),
            BoneSpec("legR", 0.18f * h, 0.45f * h, 0.12f * h, -0.12f * h, "torso", -20f, 80f)
        )
    }

    /** Makes the ragdoll go limp (death, knock-out, explosion). */
    fun goLimp(impactX: Float = 0f, impactY: Float = 0f) {
        if (limp) return
        limp = true
        if (hideHostSprite) go.getAny<SpriteRenderer>()?.visible = false
        val worldAngle = go.computeWorld().rotationDeg
        for (bone in boneObjects) {
            val rb = bone.getAny<Rigidbody2D>() ?: continue
            rb.bodyType = 0
            rb.allowSleep = true
            val world = bone.computeWorld()
            rb.body?.let {
                it.setTransform(world.tx, world.ty, Math.toRadians(world.rotationDeg.toDouble()).toFloat())
                it.refreshMass()
                it.wake()
            }
            if (explosiveForce > 0f) {
                val dx = world.tx - go.worldX()
                val dy = world.ty - go.worldY()
                val len = M.dist(0f, 0f, dx, dy).coerceAtLeast(0.05f)
                rb.addImpulse(dx / len * explosiveForce + impactX, dy / len * explosiveForce * 0.6f + impactY)
                rb.addAngularImpulse((M.dist(0f, 0f, dx, dy) * 0.6f))
            }
        }
        go.emit("ragdoll")
    }

    /** Restores the character: bones become kinematic again and the host sprite returns. */
    fun reset() {
        limp = false
        for (bone in boneObjects) {
            bone.getAny<Rigidbody2D>()?.bodyType = 1
            val rb = bone.getAny<Rigidbody2D>()
            rb?.body?.let { b ->
                b.vx = 0f; b.vy = 0f; b.angularVelocity = 0f
            }
        }
        if (hideHostSprite) go.getAny<SpriteRenderer>()?.visible = true
        go.emit("ragdollReset")
    }

    /** Called by the engine each frame. */
    fun update(dt: Float, health: Health?) {
        if (limpTimer >= 0f) {
            limpTimer -= dt
            if (limpTimer <= 0f) {
                limpTimer = -1f
                goLimp()
            }
        }
    }

    /** Hooks the host's Health component so the ragdoll goes limp on death. */
    fun bindDeath(health: Health) {
        if (!goLimpOnDeath) return
        go.signal<Unit>("died").connect {
            if (limpDelay <= 0f) goLimp() else limpTimer = limpDelay
        }
    }

    /** Applies an impulse to every bone (explosion knock-back). */
    fun applyExplosion(x: Float, y: Float, force: Float, radius: Float) {
        for (bone in boneObjects) {
            val rb = bone.getAny<Rigidbody2D>() ?: continue
            if (rb.bodyType != 0) continue
            val w = bone.computeWorld()
            val d = M.dist(x, y, w.tx, w.ty)
            if (d > radius) continue
            val falloff = 1f - d / radius
            val dx = (w.tx - x) / d.coerceAtLeast(0.01f)
            val dy = (w.ty - y) / d.coerceAtLeast(0.01f)
            rb.addImpulse(dx * force * falloff, (dy + 0.5f) * force * falloff)
            rb.addAngularImpulse((Colors.alpha(color).toFloat() % 3f - 1f) * force * 0.2f)
        }
    }
}
