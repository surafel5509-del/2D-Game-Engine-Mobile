package com.sengine.engine.fx

import com.sengine.engine.fx.ParticleSystem2D.Preset
import com.sengine.engine.math.AnimationCurve
import com.sengine.engine.math.ColorGradient
import com.sengine.engine.math.Colors

/**
 * Ready-to-use 2D particle presets. Every preset configures curves, gradients and physics so a
 * game can drop in a convincing effect in one line: `ParticlePresets.spawn("explosion", x, y)`.
 */
object ParticlePresets {

    private fun preset(name: String, block: ParticleSystem2D.() -> Unit): Preset = object : Preset {
        override val name = name
        override fun apply(sys: ParticleSystem2D) = sys.block()
    }

    val all: List<Preset> = listOf(
        preset("Fire") {
            rate = 45f; lifetime = 0.9f; lifetimeVariation = 0.3f
            speed = 1.6f; speedVariation = 0.4f; direction = 90f; spread = 18f
            shape = 1; shapeRadius = 0.15f
            sizeStart = 0.45f; sizeEnd = 0.02f
            sizeCurve = AnimationCurve(1f, 1.15f, 0.6f, 0.1f)
            alphaCurve = AnimationCurve(0.0f, 1f, 0.65f, 0f)
            colorGradient = ColorGradient.of(0f to 0xFFFFF3B0.toInt(), 0.35f to 0xFFFFA000.toInt(), 1f to 0x00FF3D00)
            gravityY = 1.2f; drag = 0.8f; turbulence = 0.6f; additive = true
        },
        preset("Smoke") {
            rate = 18f; lifetime = 2.4f; lifetimeVariation = 0.4f
            speed = 0.8f; direction = 90f; spread = 35f
            sizeStart = 0.4f; sizeEnd = 1.4f
            colorGradient = ColorGradient.of(0f to 0x774C4C4C, 0.4f to 0x665A5A5A, 1f to 0x00909090)
            alphaCurve = AnimationCurve(0f, 0.9f, 0.3f, 0.55f, 1f, 0f)
            gravityY = 0.6f; drag = 1.1f; turbulence = 0.35f
        },
        preset("Explosion") {
            rate = 0f; lifetime = 0.7f; lifetimeVariation = 0.45f
            speed = 9f; speedVariation = 0.5f; direction = 90f; spread = 360f
            sizeStart = 0.55f; sizeEnd = 0.05f
            sizeCurve = AnimationCurve(1f, 1.3f, 0.5f, 0.05f)
            colorGradient = ColorGradient.of(0f to 0xFFFFF1A8.toInt(), 0.25f to 0xFFFF8A00.toInt(), 0.7f to 0xCCFF3B00.toInt(), 1f to 0x00302020)
            drag = 2.4f; additive = true; gravityY = 2f
            burstCount = 42; trailEnabled = false
        },
        preset("Sparks") {
            rate = 0f; lifetime = 0.55f; speed = 7f; speedVariation = 0.6f
            direction = 90f; spread = 70f; sizeStart = 0.09f; sizeEnd = 0.01f
            colorGradient = ColorGradient.of(0f to 0xFFFFF7C4.toInt(), 0.5f to 0xFFFFC400.toInt(), 1f to 0x00FF6A00)
            gravityY = -14f; drag = 0.6f; additive = true; collideWithWorld = true; collisionBounce = 0.45f
            burstCount = 16
        },
        preset("Dust") {
            rate = 12f; lifetime = 1.1f; speed = 1.4f; direction = 90f; spread = 120f
            shape = 2; shapeWidth = 0.4f; shapeHeight = 0.1f
            sizeStart = 0.2f; sizeEnd = 0.5f; colorStart = 0x88CFC6B8.toInt(); colorEnd = 0x00CFC6B8
            alphaCurve = AnimationCurve(0.35f, 1f, 1f, 0f)
            drag = 1.6f; gravityY = -0.6f
        },
        preset("Rain") {
            rate = 120f; lifetime = 1.4f; speed = 16f; speedVariation = 0.1f
            direction = -90f; spread = 3f; shape = 2; shapeWidth = 26f; shapeHeight = 0.5f
            sizeStart = 0.06f; sizeEnd = 0.05f
            colorStart = 0x99A8D8FF.toInt(); colorEnd = 0x55A8D8FF
            gravityY = -22f; collideWithWorld = true; collisionMode = 0; additive = true
        },
        preset("Snow") {
            rate = 30f; lifetime = 5f; speed = 1.5f; direction = -90f; spread = 20f
            shape = 2; shapeWidth = 24f; shapeHeight = 0.5f
            sizeStart = 0.08f; sizeEnd = 0.04f; colorStart = Colors.WHITE
            gravityY = -1.6f; turbulence = 0.8f; turbulenceFrequency = 0.5f; drag = 0.4f
        },
        preset("Fog") {
            rate = 4f; lifetime = 8f; speed = 0.35f; direction = 0f; spread = 25f
            sizeStart = 2.2f; sizeEnd = 4.5f
            colorGradient = ColorGradient.of(0f to 0x339FB6C4, 1f to 0x009FB6C4)
            alphaCurve = AnimationCurve(0f, 0f, 0.25f, 0.45f, 0.75f, 0.4f, 1f, 0f)
            turbulence = 0.15f; drag = 0.2f
        },
        preset("Steam") {
            rate = 22f; lifetime = 1.6f; speed = 2.2f; direction = 90f; spread = 22f
            sizeStart = 0.25f; sizeEnd = 0.9f
            colorGradient = ColorGradient.of(0f to 0x99FFFFFF.toInt(), 1f to 0x00FFFFFF)
            alphaCurve = AnimationCurve(0f, 0f, 0.15f, 0.8f, 1f, 0f)
            gravityY = 1.4f; drag = 1.4f; turbulence = 0.4f
        },
        preset("Magic") {
            rate = 26f; lifetime = 1.5f; speed = 1.8f; direction = 90f; spread = 60f
            shape = 1; shapeRadius = 0.3f
            sizeStart = 0.16f; sizeEnd = 0.02f
            colorGradient = ColorGradient.of(0f to 0xFFE9C7FF.toInt(), 0.4f to 0xFFB15BFF.toInt(), 1f to 0x003A0A5E)
            alphaCurve = AnimationCurve(0f, 0f, 0.2f, 1f, 0.7f, 0.8f, 1f, 0f)
            turbulence = 1.2f; additive = true; drag = 0.3f; gravityY = 0.2f
        },
        preset("Electricity") {
            rate = 40f; lifetime = 0.35f; speed = 5f; speedVariation = 0.8f
            direction = 0f; spread = 360f; shape = 1; shapeRadius = 0.25f
            sizeStart = 0.14f; sizeEnd = 0.02f
            colorGradient = ColorGradient.of(0f to 0xFFFFFFFF.toInt(), 0.4f to 0xFF8BD2FF.toInt(), 1f to 0x002020AA)
            additive = true; drag = 3f; turbulence = 2.5f
        },
        preset("Energy") {
            rate = 30f; lifetime = 1.2f; speed = 0.6f; direction = 90f; spread = 360f
            shape = 1; shapeRadius = 0.6f
            sizeStart = 0.22f; sizeEnd = 0.4f
            colorGradient = ColorGradient.of(0f to 0xFF9BFFE0.toInt(), 1f to 0x0000FFA8)
            additive = true; radialAcceleration = -1.4f; turbulence = 0.3f
        },
        preset("Water Splash") {
            rate = 0f; lifetime = 0.85f; speed = 6.5f; speedVariation = 0.5f
            direction = 90f; spread = 70f
            sizeStart = 0.16f; sizeEnd = 0.03f
            colorGradient = ColorGradient.of(0f to 0xFFCDEEFF.toInt(), 0.6f to 0x8890C8FF.toInt(), 1f to 0x005090D0)
            gravityY = -16f; drag = 0.5f; burstCount = 26
        },
        preset("Shockwave") {
            rate = 0f; lifetime = 0.5f; speed = 0f; spread = 0f
            shape = 1; shapeRadius = 0.1f
            sizeStart = 0.2f; sizeEnd = 3.2f
            sizeCurve = AnimationCurve(0.15f, 1.6f, 1f)
            alphaCurve = AnimationCurve(0f, 0.9f, 0.5f, 0.25f, 1f, 0f)
            colorStart = 0xFFFFFFFF.toInt(); colorEnd = 0x44FFFFFF
            additive = true; burstCount = 1
        },
        preset("Debris") {
            rate = 0f; lifetime = 1.8f; speed = 5f; speedVariation = 0.5f; direction = 90f; spread = 180f
            sizeStart = 0.18f; sizeEnd = 0.12f
            colorStart = 0xFFB0A79A.toInt(); colorEnd = 0xFF8A8175.toInt()
            alphaCurve = AnimationCurve(0f, 1f, 0.7f, 1f, 1f, 0f)
            gravityY = -18f; rotationSpeed = 320f; rotationRandom = 180f
            collideWithWorld = true; collisionBounce = 0.35f; collisionFriction = 0.8f
            burstCount = 20
        },
        preset("Leaves") {
            rate = 8f; lifetime = 4.5f; speed = 1.2f; direction = -75f; spread = 45f
            sizeStart = 0.2f; sizeEnd = 0.16f
            colorGradient = ColorGradient.of(0f to 0xFFE0A33C.toInt(), 0.5f to 0xFFC56A1E.toInt(), 1f to 0x00A85A14)
            alphaCurve = AnimationCurve(0f, 1f, 0.75f, 1f, 1f, 0f)
            gravityY = -0.9f; turbulence = 1.6f; drag = 0.7f; rotationSpeed = 90f; rotationRandom = 120f
        },
        preset("Sand") {
            rate = 24f; lifetime = 1.6f; speed = 3f; speedVariation = 0.5f; direction = 20f; spread = 40f
            shape = 2; shapeWidth = 1.5f; shapeHeight = 0.3f
            sizeStart = 0.09f; sizeEnd = 0.04f
            colorGradient = ColorGradient.of(0f to 0xFFE8CF9A.toInt(), 1f to 0x00C9A96A)
            gravityY = -6f; drag = 1.2f; turbulence = 0.8f
        },
        preset("Exhaust") {
            rate = 30f; lifetime = 0.8f; speed = 2.4f; direction = 180f; spread = 12f
            sizeStart = 0.16f; sizeEnd = 0.5f
            colorGradient = ColorGradient.of(0f to 0x66707070, 1f to 0x00707070)
            alphaCurve = AnimationCurve(0f, 0.7f, 1f, 0f)
            drag = 1.8f; gravityY = 0.8f; inheritVelocity = 0.35f
        },
        preset("Rocket Flame") {
            rate = 90f; lifetime = 0.45f; speed = 6f; speedVariation = 0.25f
            direction = -90f; spread = 14f
            sizeStart = 0.34f; sizeEnd = 0.06f
            colorGradient = ColorGradient.of(0f to 0xFFFFFDE7.toInt(), 0.3f to 0xFFFFC107.toInt(), 0.7f to 0xFFFF5722.toInt(), 1f to 0x00B71C1C)
            alphaCurve = AnimationCurve(0f, 1f, 0.7f, 0.9f, 1f, 0f)
            additive = true; drag = 2.2f; inheritVelocity = 0.6f
        },
        preset("Muzzle Flash") {
            rate = 0f; lifetime = 0.14f; speed = 5f; speedVariation = 0.4f; direction = 0f; spread = 30f
            sizeStart = 0.5f; sizeEnd = 0.12f
            colorGradient = ColorGradient.of(0f to 0xFFFFFFFF.toInt(), 0.4f to 0xFFFFD54F.toInt(), 1f to 0x44FF6D00)
            alphaCurve = AnimationCurve(0f, 1f, 1f, 0f)
            additive = true; drag = 6f; burstCount = 8
        },
        preset("Impact") {
            rate = 0f; lifetime = 0.4f; speed = 4.5f; speedVariation = 0.6f; direction = 90f; spread = 130f
            sizeStart = 0.14f; sizeEnd = 0.02f
            colorGradient = ColorGradient.of(0f to 0xFFFFFDE7.toInt(), 0.5f to 0xAAFFE082.toInt(), 1f to 0x00FF8F00)
            additive = true; drag = 4f; burstCount = 12
        },
        preset("Trail") {
            rate = 40f; lifetime = 0.5f; speed = 0.2f; spread = 360f
            sizeStart = 0.14f; sizeEnd = 0.01f
            colorGradient = ColorGradient.of(0f to 0xCCFFFFFF.toInt(), 0.6f to 0x66FFE082.toInt(), 1f to 0x00000000)
            alphaCurve = AnimationCurve(0f, 1f, 1f, 0f)
            additive = true; drag = 1.2f
        },
        preset("Speed Lines") {
            rate = 60f; lifetime = 0.35f; speed = 0.4f; spread = 360f
            shape = 1; shapeRadius = 3.5f
            sizeStart = 0.1f; sizeEnd = 0.02f
            colorStart = 0xCCFFFFFF.toInt(); colorEnd = 0x00FFFFFF
            alphaCurve = AnimationCurve(0f, 0f, 0.2f, 1f, 1f, 0f)
            additive = true
        },
        preset("Confetti") {
            rate = 40f; lifetime = 2.6f; speed = 7f; speedVariation = 0.6f; direction = 90f; spread = 70f
            sizeStart = 0.16f; sizeEnd = 0.14f
            colorGradient = ColorGradient.of(
                0f to 0xFFFF5252.toInt(), 0.3f to 0xFFFFEB3B.toInt(),
                0.6f to 0xFF4CAF50.toInt(), 0.85f to 0xFF40C4FF.toInt(), 1f to 0xFFE040FB.toInt()
            )
            alphaCurve = AnimationCurve(0f, 1f, 0.8f, 1f, 1f, 0f)
            gravityY = -12f; drag = 1.6f; rotationSpeed = 260f; rotationRandom = 360f; turbulence = 0.5f
        }
    )

    private val byName = HashMap<String, Preset>().also { map -> all.forEach { map[it.name.lowercase()] = it } }

    val names: List<String> = all.map { it.name }

    fun find(name: String): Preset? = byName[name.lowercase()]

    /** Applies a preset to an existing system. */
    fun apply(name: String, sys: ParticleSystem2D): Boolean {
        val p = find(name) ?: return false
        p.apply(sys)
        return true
    }

    /** Creates a one-shot system (already burst) - convenient for gameplay code. */
    fun spawn(name: String, x: Float, y: Float, count: Int = -1): ParticleSystem2D? {
        val p = find(name) ?: return null
        val sys = ParticleSystem2D(512)
        p.apply(sys)
        sys.emitterX = x
        sys.emitterY = y
        sys.emitting = false
        sys.burst(if (count > 0) count else sys.burstCount)
        return sys
    }
}
