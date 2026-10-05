package com.sengine.engine.core

import kotlin.math.floor
import kotlin.math.max
import kotlin.random.Random

object ComponentRegistry {
    val types: LinkedHashMap<String, () -> Component> = linkedMapOf(
        "SpriteRenderer" to { SpriteRenderer() },
        "SpriteAnimator" to { SpriteAnimator() },
        "TextRenderer" to { TextRenderer() },
        "Camera" to { Camera2D() },
        "Rigidbody2D" to { Rigidbody2D() },
        "Joint2D" to { Joint2D() },
        "Collider2D" to { Collider2D() },
        "Script" to { ScriptComponent() },
        "ParticleEmitter" to { ParticleEmitter() },
        "AudioSource" to { AudioSource() },
    )

    fun create(type: String): Component? = types[type]?.invoke()
}

class SpriteRenderer : Component() {
    override val type = "SpriteRenderer"
    var shape = 0 // 0 Square, 1 Circle, 2 Triangle
    var color = 0xFFFFFFFF.toInt()
    var texture = ""
    var flipX = false
    var flipY = false

    /** Normalized top-left UV rectangle within the texture. Supports sprite atlases. */
    var uvX = 0f
    var uvY = 0f
    var uvWidth = 1f
    var uvHeight = 1f

    override fun props() = listOf(
        Prop.Choice("Shape", SHAPES, { shape }, { shape = it.coerceIn(0, SHAPES.lastIndex) }),
        Prop.Color("Color", { color }, { color = it }),
        Prop.Asset("Texture", AssetKind.TEXTURE, { texture }, { texture = it }),
        Prop.B("Flip X", { flipX }, { flipX = it }),
        Prop.B("Flip Y", { flipY }, { flipY = it }),
        Prop.F("Atlas U", { uvX }, { uvX = it.coerceIn(0f, 1f) }, 0.01f),
        Prop.F("Atlas V", { uvY }, { uvY = it.coerceIn(0f, 1f) }, 0.01f),
        Prop.F("Atlas Width", { uvWidth }, { uvWidth = it.coerceIn(0.001f, 1f) }, 0.01f),
        Prop.F("Atlas Height", { uvHeight }, { uvHeight = it.coerceIn(0.001f, 1f) }, 0.01f),
    )

    companion object {
        val SHAPES = listOf("Square", "Circle", "Triangle")
    }
}

/**
 * Animated grid-based sprite sheet player. Frames are addressed left-to-right,
 * top-to-bottom and can be confined to the atlas rectangle on SpriteRenderer.
 */
class SpriteAnimator : Component() {
    override val type = "SpriteAnimator"
    var columns = 1
    var rows = 1
    var firstFrame = 0
    var frameCount = 1
    var framesPerSecond = 8f
    var looping = true
    var playOnStart = true
    var previewInEditor = false

    var playing = false
        private set
    var frame = 0
        private set
    private var elapsed = 0f

    override fun props() = listOf(
        Prop.I("Columns", { columns }, { columns = it.coerceIn(1, 256) }),
        Prop.I("Rows", { rows }, { rows = it.coerceIn(1, 256) }),
        Prop.I("First Frame", { firstFrame }, { firstFrame = it.coerceAtLeast(0) }),
        Prop.I("Frame Count", { frameCount }, { frameCount = it.coerceAtLeast(1) }),
        Prop.F("Frames Per Second", { framesPerSecond }, { framesPerSecond = it.coerceAtLeast(0f) }),
        Prop.B("Loop", { looping }, { looping = it }),
        Prop.B("Play On Start", { playOnStart }, { playOnStart = it }),
        Prop.B("Preview In Editor", { previewInEditor }, { previewInEditor = it }),
    )

    fun play(fromFrame: Int = firstFrame) {
        frame = fromFrame.coerceAtLeast(0)
        elapsed = 0f
        playing = true
    }

    fun stop() {
        playing = false
        elapsed = 0f
    }

    fun preview(dt: Float) {
        if (!playing) play(firstFrame)
        advance(dt)
    }

    fun advance(dt: Float) {
        if (!enabled || !playing || framesPerSecond <= 0f || dt <= 0f) return
        val cells = (columns.coerceAtLeast(1) * rows.coerceAtLeast(1))
        val count = frameCount.coerceAtLeast(1).coerceAtMost(cells)
        val start = firstFrame.coerceIn(0, cells - 1)
        val end = (start + count).coerceAtMost(cells)
        if (frame !in start until end) frame = start
        elapsed += dt
        val step = 1f / framesPerSecond
        val advances = floor(elapsed / step).toInt()
        if (advances <= 0) return
        elapsed -= advances * step
        val next = frame + advances
        if (next < end) frame = next
        else if (looping) frame = start + ((next - start) % (end - start))
        else { frame = end - 1; playing = false; elapsed = 0f }
    }

    /** Returns normalized top-left UVs (u, v, width, height) for the current cell. */
    fun atlasUv(sprite: SpriteRenderer, out: FloatArray): FloatArray {
        require(out.size >= 4)
        val cols = columns.coerceAtLeast(1)
        val rowsSafe = rows.coerceAtLeast(1)
        val cells = cols * rowsSafe
        val local = frame.coerceIn(0, cells - 1)
        out[0] = sprite.uvX + (local % cols) * sprite.uvWidth / cols
        out[1] = sprite.uvY + (local / cols) * sprite.uvHeight / rowsSafe
        out[2] = sprite.uvWidth / cols
        out[3] = sprite.uvHeight / rowsSafe
        return out
    }

    override fun resetRuntime() {
        elapsed = 0f
        frame = firstFrame.coerceAtLeast(0)
        playing = playOnStart
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
        Prop.Choice("Align", listOf("Left", "Center", "Right"), { align }, { align = it.coerceIn(0, 2) }),
        Prop.B("Bold", { bold }, { bold = it }),
    )
}

class Camera2D : Component() {
    override val type = "Camera"
    var size = 5f
    var background = 0xFF1B2533.toInt()
    var follow = ""
    var smoothing = 5f
    var offsetX = 0f
    var offsetY = 0f
    var lookAhead = 0f
    var pixelPerfect = false
    var limitEnabled = false
    var limitLeft = -100f
    var limitRight = 100f
    var limitBottom = -100f
    var limitTop = 100f

    // Transient shake state. It is intentionally not serialized.
    var shakeX = 0f
        private set
    var shakeY = 0f
        private set
    private var shakeMagnitude = 0f
    private var shakeRemaining = 0f
    private var shakeDuration = 0f

    override fun props() = listOf(
        Prop.F("Size", { size }, { size = it.coerceAtLeast(0.1f) }),
        Prop.Color("Background", { background }, { background = it }),
        Prop.S("Follow Target", { follow }, { follow = it }),
        Prop.F("Follow Smoothing", { smoothing }, { smoothing = it.coerceAtLeast(0f) }),
        Prop.F("Offset X", { offsetX }, { offsetX = it }),
        Prop.F("Offset Y", { offsetY }, { offsetY = it }),
        Prop.F("Look Ahead", { lookAhead }, { lookAhead = it.coerceAtLeast(0f) }),
        Prop.B("Pixel Perfect", { pixelPerfect }, { pixelPerfect = it }),
        Prop.B("Use Limits", { limitEnabled }, { limitEnabled = it }),
        Prop.F("Limit Left", { limitLeft }, { limitLeft = it }),
        Prop.F("Limit Right", { limitRight }, { limitRight = it }),
        Prop.F("Limit Bottom", { limitBottom }, { limitBottom = it }),
        Prop.F("Limit Top", { limitTop }, { limitTop = it }),
    )

    fun shake(magnitude: Float, duration: Float) {
        shakeMagnitude = max(shakeMagnitude, magnitude.coerceAtLeast(0f))
        shakeDuration = max(shakeDuration, duration.coerceAtLeast(0.01f))
        shakeRemaining = max(shakeRemaining, duration.coerceAtLeast(0f))
    }

    fun updateShake(dt: Float) {
        if (shakeRemaining <= 0f) { shakeX = 0f; shakeY = 0f; return }
        shakeRemaining = (shakeRemaining - dt).coerceAtLeast(0f)
        val envelope = if (shakeDuration <= 0f) 0f else shakeRemaining / shakeDuration
        shakeX = (Random.nextFloat() * 2f - 1f) * shakeMagnitude * envelope
        shakeY = (Random.nextFloat() * 2f - 1f) * shakeMagnitude * envelope
        if (shakeRemaining == 0f) { shakeX = 0f; shakeY = 0f; shakeMagnitude = 0f; shakeDuration = 0f }
    }

    override fun resetRuntime() {
        shakeX = 0f; shakeY = 0f; shakeMagnitude = 0f; shakeRemaining = 0f; shakeDuration = 0f
    }
}

class Rigidbody2D : Component() {
    override val type = "Rigidbody2D"
    var bodyType = 0 // 0 Dynamic, 1 Kinematic, 2 Static
    var mass = 1f
    var gravityScale = 1f
    var drag = 0f
    var angularDrag = 0f
    var bounciness = 0f
    var friction = 0.4f
    var startVx = 0f
    var startVy = 0f
    var startAngularVelocity = 0f
    var continuous = false

    // Runtime state. Forces are accumulated until the next physics step.
    var vx = 0f
    var vy = 0f
    var angularVelocity = 0f // degrees per second
    var grounded = false
        internal set
    internal var forceX = 0f
    internal var forceY = 0f
    internal var torque = 0f

    override fun props() = listOf(
        Prop.Choice("Body Type", listOf("Dynamic", "Kinematic", "Static"), { bodyType }, { bodyType = it.coerceIn(0, 2) }),
        Prop.F("Mass", { mass }, { mass = it.coerceAtLeast(0.001f) }),
        Prop.F("Gravity Scale", { gravityScale }, { gravityScale = it }),
        Prop.F("Linear Drag", { drag }, { drag = it.coerceAtLeast(0f) }),
        Prop.F("Angular Drag", { angularDrag }, { angularDrag = it.coerceAtLeast(0f) }),
        Prop.F("Bounciness", { bounciness }, { bounciness = it.coerceIn(0f, 1f) }, 0.05f),
        Prop.F("Friction", { friction }, { friction = it.coerceIn(0f, 1f) }, 0.05f),
        Prop.F("Velocity X", { startVx }, { startVx = it }),
        Prop.F("Velocity Y", { startVy }, { startVy = it }),
        Prop.F("Angular Velocity", { startAngularVelocity }, { startAngularVelocity = it }),
        Prop.B("Continuous Collision", { continuous }, { continuous = it }),
    )

    fun applyForce(x: Float, y: Float) { forceX += x; forceY += y }
    fun applyImpulse(x: Float, y: Float) {
        val inverseMass = 1f / mass.coerceAtLeast(0.001f)
        vx += x * inverseMass; vy += y * inverseMass
    }
    fun applyTorque(value: Float) { torque += value }
    fun applyAngularImpulse(degreesPerSecond: Float) { angularVelocity += degreesPerSecond }
    internal fun clearForces() { forceX = 0f; forceY = 0f; torque = 0f }

    override fun resetRuntime() {
        vx = startVx; vy = startVy; angularVelocity = startAngularVelocity
        grounded = false
        clearForces()
    }
}

/** Distance, pin and damped spring constraints between two 2D bodies (or a world anchor). */
class Joint2D : Component() {
    override val type = "Joint2D"
    var jointType = 0 // 0 Distance, 1 Pin, 2 Spring
    var connectedBody = ""
    var anchorX = 0f
    var anchorY = 0f
    var connectedAnchorX = 0f
    var connectedAnchorY = 0f
    var length = 1f
    var frequency = 4f
    var dampingRatio = 0.7f
    var maxForce = 1000f
    var collideConnected = false

    override fun props() = listOf(
        Prop.Choice("Joint Type", listOf("Distance", "Pin", "Spring"), { jointType }, { jointType = it.coerceIn(0, 2) }),
        Prop.S("Connected Body (blank = world)", { connectedBody }, { connectedBody = it }),
        Prop.F("Anchor X", { anchorX }, { anchorX = it }),
        Prop.F("Anchor Y", { anchorY }, { anchorY = it }),
        Prop.F("Connected Anchor X", { connectedAnchorX }, { connectedAnchorX = it }),
        Prop.F("Connected Anchor Y", { connectedAnchorY }, { connectedAnchorY = it }),
        Prop.F("Length", { length }, { length = it.coerceAtLeast(0f) }),
        Prop.F("Spring Frequency", { frequency }, { frequency = it.coerceIn(0f, 30f) }),
        Prop.F("Damping Ratio", { dampingRatio }, { dampingRatio = it.coerceIn(0f, 2f) }, 0.05f),
        Prop.F("Max Force", { maxForce }, { maxForce = it.coerceAtLeast(0f) }),
        Prop.B("Collide Connected Bodies", { collideConnected }, { collideConnected = it }),
    )
}

class Collider2D : Component() {
    override val type = "Collider2D"
    var shape = 0 // 0 Box, 1 Circle
    var width = 1f
    var height = 1f
    var radius = 0.5f
    var offsetX = 0f
    var offsetY = 0f
    var isTrigger = false
    /** 32 collision layers. A pair collides only when both objects include each other in their masks. */
    var collisionLayer = 1
    var collisionMask = -1

    override fun props() = listOf(
        Prop.Choice("Shape", listOf("Box", "Circle"), { shape }, { shape = it.coerceIn(0, 1) }),
        Prop.F("Width", { width }, { width = it.coerceAtLeast(0.01f) }),
        Prop.F("Height", { height }, { height = it.coerceAtLeast(0.01f) }),
        Prop.F("Radius", { radius }, { radius = it.coerceAtLeast(0.01f) }),
        Prop.F("Offset X", { offsetX }, { offsetX = it }),
        Prop.F("Offset Y", { offsetY }, { offsetY = it }),
        Prop.B("Is Trigger", { isTrigger }, { isTrigger = it }),
        Prop.I("Collision Layer (bitmask)", { collisionLayer }, { collisionLayer = it }),
        Prop.I("Collision Mask (bitmask)", { collisionMask }, { collisionMask = it }),
    )
}

class ScriptComponent : Component() {
    override val type = "Script"
    var script = ""
    var params = ""

    override fun props() = listOf(
        Prop.Asset("Script", AssetKind.SCRIPT, { script }, { script = it }),
        Prop.S("Params", { params }, { params = it }),
    )
}

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

    // Runtime
    val particles = ArrayList<Particle>()
    var accumulator = 0f
    var pendingBurst = 0

    class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, var age: Float, var life: Float)

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
        Prop.I("Max Particles", { maxParticles }, { maxParticles = it.coerceIn(1, 5000) }),
    )

    override fun resetRuntime() {
        particles.clear(); accumulator = 0f; pendingBurst = 0
    }
}

class AudioSource : Component() {
    override val type = "AudioSource"
    var clip = ""
    var playOnStart = true
    var loop = false
    var volume = 1f

    override fun props() = listOf(
        Prop.Asset("Clip", AssetKind.SOUND, { clip }, { clip = it }),
        Prop.B("Play On Start", { playOnStart }, { playOnStart = it }),
        Prop.B("Loop", { loop }, { loop = it }),
        Prop.F("Volume", { volume }, { volume = it.coerceIn(0f, 1f) }, 0.05f),
    )
}
