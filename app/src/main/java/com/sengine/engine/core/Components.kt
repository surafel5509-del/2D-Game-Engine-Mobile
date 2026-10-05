package com.sengine.engine.core

import com.sengine.engine.physics.CharacterBody
import com.sengine.engine.physics.VehicleBody
import com.sengine.engine.physics.WheelComponent
import com.sengine.engine.physics.Ragdoll
import com.sengine.engine.animation.SpriteAnimator

/**
 * Registry of all component types available in the engine.
 * New components must be registered here to be serializable and editable.
 */
object ComponentRegistry {
    val types: LinkedHashMap<String, () -> Component> = linkedMapOf(
        "SpriteRenderer" to { SpriteRenderer() },
        "TextRenderer" to { TextRenderer() },
        "Camera" to { Camera2D() },
        "Rigidbody2D" to { Rigidbody2D() },
        "Collider2D" to { Collider2D() },
        "Script" to { ScriptComponent() },
        "ParticleEmitter" to { ParticleEmitter() },
        "AudioSource" to { AudioSource() },
        // Professional-grade components
        "CharacterBody" to { CharacterBody() },
        "VehicleBody" to { VehicleBody() },
        "Wheel" to { WheelComponent() },
        "Ragdoll" to { Ragdoll() },
        "TileMap" to { TileMap() },
        "Terrain" to { Terrain() },
        "SpriteAnimator" to { SpriteAnimator() },
        "JointComponent" to { JointComponent() },
    )

    fun create(type: String): Component? = types[type]?.invoke()

    fun register(typeName: String, factory: () -> Component) {
        types[typeName] = factory
    }

    fun isKnownType(type: String): Boolean = type in types
}

// ============================================================================
// Rendering Components
// ============================================================================

class SpriteRenderer : Component() {
    override val type = "SpriteRenderer"
    var shape = 0 // 0 Square, 1 Circle, 2 Triangle
    var color = 0xFFFFFFFF.toInt()
    var texture = ""
    var flipX = false
    var flipY = false
    var sortingOrder = 0

    override fun props() = listOf(
        Prop.Choice("Shape", SHAPES, { shape }, { shape = it }),
        Prop.Color("Color", { color }, { color = it }),
        Prop.Asset("Texture", AssetKind.TEXTURE, { texture }, { texture = it }),
        Prop.B("Flip X", { flipX }, { flipX = it }),
        Prop.B("Flip Y", { flipY }, { flipY = it }),
    )

    companion object {
        val SHAPES = listOf("Square", "Circle", "Triangle")
    }
}

class TextRenderer : Component() {
    override val type = "TextRenderer"
    var text = "Hello S Engine"
    var size = 0.5f
    var color = 0xFFFFFFFF.toInt()
    var align = 1 // 0 left, 1 center, 2 right
    var bold = false

    override fun props() = listOf(
        Prop.S("Text", { text }, { text = it }, multiline = true),
        Prop.F("Size", { size }, { size = it.coerceAtLeast(0.01f) }, 0.05f),
        Prop.Color("Color", { color }, { color = it }),
        Prop.Choice("Align", listOf("Left", "Center", "Right"), { align }, { align = it }),
        Prop.B("Bold", { bold }, { bold = it }),
    )
}

// ============================================================================
// Camera
// ============================================================================

class Camera2D : Component() {
    override val type = "Camera"
    var size = 5f
    var background = 0xFF1B2533.toInt()
    var follow = ""
    var smoothing = 5f
    var zoomMin = 1f
    var zoomMax = 20f
    var shakeEnabled = true
    var boundsEnabled = false
    var boundsMinX = -100f
    var boundsMinY = -100f
    var boundsMaxX = 100f
    var boundsMaxY = 100f

    override fun props() = listOf(
        Prop.F("Size", { size }, { size = it.coerceAtLeast(0.1f) }),
        Prop.Color("Background", { background }, { background = it }),
        Prop.S("Follow Target", { follow }, { follow = it }),
        Prop.F("Follow Smoothing", { smoothing }, { smoothing = it.coerceAtLeast(0f) }),
        Prop.F("Zoom Min", { zoomMin }, { zoomMin = it.coerceAtLeast(0.1f) }),
        Prop.F("Zoom Max", { zoomMax }, { zoomMax = it.coerceAtLeast(0.1f) }),
        Prop.B("Shake Enabled", { shakeEnabled }, { shakeEnabled = it }),
        Prop.B("Use Bounds", { boundsEnabled }, { boundsEnabled = it }),
        Prop.F("Bounds Min X", { boundsMinX }, { boundsMinX = it }),
        Prop.F("Bounds Min Y", { boundsMinY }, { boundsMinY = it }),
        Prop.F("Bounds Max X", { boundsMaxX }, { boundsMaxX = it }),
        Prop.F("Bounds Max Y", { boundsMaxY }, { boundsMaxY = it }),
    )
}

// ============================================================================
// Physics Components
// ============================================================================

class Rigidbody2D : Component() {
    override val type = "Rigidbody2D"
    var bodyType = 0 // 0 Dynamic, 1 Kinematic, 2 Static
    var mass = 1f
    var gravityScale = 1f
    var drag = 0f
    var angularDrag = 0.05f
    var bounciness = 0f
    var friction = 0.4f
    var startVx = 0f
    var startVy = 0f
    var startAngularVelocity = 0f
    var fixedRotation = false
    var collisionLayer = 0
    var collisionMask = -1 // -1 = all layers

    // runtime
    var vx = 0f
    var vy = 0f
    var angularVelocity = 0f
    var grounded = false

    override fun props() = listOf(
        Prop.Choice("Body Type", listOf("Dynamic", "Kinematic", "Static"), { bodyType }, { bodyType = it }),
        Prop.F("Mass", { mass }, { mass = it.coerceAtLeast(0.001f) }),
        Prop.F("Gravity Scale", { gravityScale }, { gravityScale = it }),
        Prop.F("Linear Drag", { drag }, { drag = it.coerceAtLeast(0f) }),
        Prop.F("Angular Drag", { angularDrag }, { angularDrag = it.coerceAtLeast(0f) }),
        Prop.F("Bounciness", { bounciness }, { bounciness = it.coerceIn(0f, 1f) }, 0.05f),
        Prop.F("Friction", { friction }, { friction = it.coerceIn(0f, 1f) }, 0.05f),
        Prop.F("Start Velocity X", { startVx }, { startVx = it }),
        Prop.F("Start Velocity Y", { startVy }, { startVy = it }),
        Prop.F("Start Angular Vel", { startAngularVelocity }, { startAngularVelocity = it }),
        Prop.B("Fixed Rotation", { fixedRotation }, { fixedRotation = it }),
        Prop.I("Collision Layer", { collisionLayer }, { collisionLayer = it.coerceIn(0, 31) }),
        Prop.I("Collision Mask", { collisionMask }, { collisionMask = it }),
    )

    override fun resetRuntime() {
        vx = startVx; vy = startVy
        angularVelocity = startAngularVelocity
        grounded = false
    }

    /** Apply a force at the center of mass. */
    fun addForce(fx: Float, fy: Float) {
        if (bodyType != 0) return
        vx += fx / mass
        vy += fy / mass
    }

    /** Apply an impulse (instant velocity change). */
    fun addImpulse(ix: Float, iy: Float) {
        if (bodyType != 0) return
        vx += ix / mass
        vy += iy / mass
    }

    /** Apply torque (angular impulse). */
    fun addTorque(torque: Float) {
        if (bodyType != 0 || fixedRotation) return
        angularVelocity += torque / mass
    }
}

class Collider2D : Component() {
    override val type = "Collider2D"
    var shape = 0 // 0 Box, 1 Circle, 2 Capsule
    var width = 1f
    var height = 1f
    var radius = 0.5f
    var offsetX = 0f
    var offsetY = 0f
    var isTrigger = false
    var collisionLayer = 0
    var density = 1f

    override fun props() = listOf(
        Prop.Choice("Shape", listOf("Box", "Circle", "Capsule"), { shape }, { shape = it }),
        Prop.F("Width", { width }, { width = it.coerceAtLeast(0.01f) }),
        Prop.F("Height", { height }, { height = it.coerceAtLeast(0.01f) }),
        Prop.F("Radius", { radius }, { radius = it.coerceAtLeast(0.01f) }),
        Prop.F("Offset X", { offsetX }, { offsetX = it }),
        Prop.F("Offset Y", { offsetY }, { offsetY = it }),
        Prop.B("Is Trigger", { isTrigger }, { isTrigger = it }),
        Prop.I("Collision Layer", { collisionLayer }, { collisionLayer = it.coerceIn(0, 31) }),
        Prop.F("Density", { density }, { density = it.coerceAtLeast(0.001f) }),
    )
}

class JointComponent : Component() {
    override val type = "JointComponent"
    var jointType = 0 // 0=Distance, 1=Hinge, 2=Spring, 3=Wheel, 4=Rope
    var targetName = ""
    var anchorAx = 0f
    var anchorAy = 0f
    var anchorBx = 0f
    var anchorBy = 0f
    var distance = 1f
    var stiffness = 0f
    var damping = 0.5f
    var enableLimit = false
    var lowerAngle = -45f
    var upperAngle = 45f
    var enableMotor = false
    var motorSpeed = 0f
    var motorTorque = 0f
    var suspensionStiffness = 100f
    var suspensionDamping = 5f
    var suspensionRestLength = 1f
    var wheelRadius = 0.5f
    var maxLength = 2f
    var breakable = false
    var breakForce = 1000f

    override fun props() = listOf(
        Prop.Choice("Joint Type", listOf("Distance", "Hinge", "Spring", "Wheel", "Rope"), { jointType }, { jointType = it }),
        Prop.S("Target", { targetName }, { targetName = it }),
        Prop.F("Anchor A X", { anchorAx }, { anchorAx = it }),
        Prop.F("Anchor A Y", { anchorAy }, { anchorAy = it }),
        Prop.F("Anchor B X", { anchorBx }, { anchorBx = it }),
        Prop.F("Anchor B Y", { anchorBy }, { anchorBy = it }),
        Prop.F("Distance", { distance }, { distance = it.coerceAtLeast(0.01f) }),
        Prop.F("Stiffness", { stiffness }, { stiffness = it.coerceAtLeast(0f) }),
        Prop.F("Damping", { damping }, { damping = it.coerceAtLeast(0f) }),
        Prop.F("Max Length", { maxLength }, { maxLength = it.coerceAtLeast(0.01f) }),
        Prop.B("Breakable", { breakable }, { breakable = it }),
        Prop.F("Break Force", { breakForce }, { breakForce = it.coerceAtLeast(0f) }),
    )
}

// ============================================================================
// Scripting
// ============================================================================

class ScriptComponent : Component() {
    override val type = "Script"
    var script = ""
    var params = ""

    override fun props() = listOf(
        Prop.Asset("Script", AssetKind.SCRIPT, { script }, { script = it }),
        Prop.S("Params", { params }, { params = it }),
    )
}

// ============================================================================
// Particles
// ============================================================================

class ParticleEmitter : Component() {
    override val type = "ParticleEmitter"
    var emitting = true
    var rate = 30f
    var lifetime = 1.2f
    var speed = 3f
    var direction = 90f
    var spread = 30f
    var startSize = 0.25f
    var endSize = 0.02f
    var startColor = 0xFFFFC940.toInt()
    var endColor = 0x00FF3D00
    var gravity = 0f
    var maxParticles = 300
    var burstCount = 0
    var rotationSpeed = 0f

    // runtime
    val particles = ArrayList<Particle>()
    var accumulator = 0f
    var pendingBurst = 0

    class Particle(
        var x: Float, var y: Float,
        var vx: Float, var vy: Float,
        var age: Float, var life: Float,
        var rotation: Float = 0f,
        var rotSpeed: Float = 0f,
        var startSize: Float = 1f
    )

    override fun props() = listOf(
        Prop.B("Emitting", { emitting }, { emitting = it }),
        Prop.F("Rate", { rate }, { rate = it.coerceAtLeast(0f) }, 1f),
        Prop.F("Lifetime", { lifetime }, { lifetime = it.coerceAtLeast(0.01f) }),
        Prop.F("Speed", { speed }, { speed = it }),
        Prop.F("Direction", { direction }, { direction = it }, 1f),
        Prop.F("Spread", { spread }, { spread = it.coerceIn(0f, 360f) }, 1f),
        Prop.F("Start Size", { startSize }, { startSize = it.coerceAtLeast(0f) }, 0.01f),
        Prop.F("End Size", { endSize }, { endSize = it.coerceAtLeast(0f) }, 0.01f),
        Prop.Color("Start Color", { startColor }, { startColor = it }),
        Prop.Color("End Color", { endColor }, { endColor = it }),
        Prop.F("Gravity", { gravity }, { gravity = it }),
        Prop.F("Rotation Speed", { rotationSpeed }, { rotationSpeed = it }),
        Prop.I("Max Particles", { maxParticles }, { maxParticles = it.coerceIn(1, 10000) }),
        Prop.I("Burst Count", { burstCount }, { burstCount = it.coerceIn(0, 1000) }),
    )

    override fun resetRuntime() {
        particles.clear(); accumulator = 0f; pendingBurst = 0
    }
}

// ============================================================================
// Audio
// ============================================================================

class AudioSource : Component() {
    override val type = "AudioSource"
    var clip = ""
    var playOnStart = true
    var loop = false
    var volume = 1f
    var pitch = 1f
    var spatialBlend = 0f
    var minDistance = 1f
    var maxDistance = 50f

    override fun props() = listOf(
        Prop.Asset("Clip", AssetKind.SOUND, { clip }, { clip = it }),
        Prop.B("Play On Start", { playOnStart }, { playOnStart = it }),
        Prop.B("Loop", { loop }, { loop = it }),
        Prop.F("Volume", { volume }, { volume = it.coerceIn(0f, 1f) }, 0.05f),
        Prop.F("Pitch", { pitch }, { pitch = it.coerceAtLeast(0.1f) }, 0.05f),
        Prop.F("Spatial Blend", { spatialBlend }, { spatialBlend = it.coerceIn(0f, 1f) }),
        Prop.F("Min Distance", { minDistance }, { minDistance = it.coerceAtLeast(0f) }),
        Prop.F("Max Distance", { maxDistance }, { maxDistance = it.coerceAtLeast(0.1f) }),
    )
}
