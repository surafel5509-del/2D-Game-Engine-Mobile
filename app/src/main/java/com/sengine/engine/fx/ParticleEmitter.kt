package com.sengine.engine.fx

import com.sengine.engine.core.Component
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Prop
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.core.AssetKind
import com.sengine.engine.math.AnimationCurve
import com.sengine.engine.math.ColorGradient
import com.sengine.engine.math.Colors
import com.sengine.engine.math.M
import com.sengine.engine.core.Scene

/**
 * ParticleEmitter component: owns a [ParticleSystem2D] and exposes every knob in the inspector
 * and to scripts. Presets are selectable from a dropdown; curves/gradients are stored as compact
 * specs ("0:1, 0.5:0.2, 1:0") so they serialise into plain scene JSON.
 */
class ParticleEmitter : Component() {
    override val type = "ParticleEmitter"

    var emitting = true
    var preset = "Fire"
    var loopBurst = false

    var maxParticles = 400
    var rate = 20f
    var lifetime = 1f
    var lifetimeVariation = 0.25f
    var speed = 3f
    var speedVariation = 0.3f
    var direction = 90f
    var spread = 25f

    /** 0 Point, 1 Circle, 2 Box, 3 Cone, 4 Edge. */
    var shape = 0
    var shapeRadius = 0.4f
    var shapeWidth = 1f
    var shapeHeight = 1f

    var sizeStart = 0.25f
    var sizeEnd = 0.05f
    var sizeCurveSpec = ""
    var alphaCurveSpec = ""
    var gradientSpec = ""
    var colorStart = 0xFFFFC940.toInt()
    var colorEnd = 0x00FF3D00

    var gravityX = 0f
    var gravityY = 0f
    var drag = 0.4f
    var radialAcceleration = 0f
    var tangentialAcceleration = 0f
    var inheritVelocity = 0f
    var turbulence = 0f
    var turbulenceFrequency = 0.7f

    var rotationStart = 0f
    var rotationSpeed = 0f
    var rotationRandom = 0f
    var additive = false
    var texture = ""
    var simulationSpace = 0

    var collideWithWorld = false
    var collisionMode = 1
    var collisionBounce = 0.3f

    var subEmitterPrefab = ""
    var subEmitterOnDeath = false
    var subEmitterChance = 0.35f

    var burstCount = 20
    /** Automatically bursts once when play mode starts (explosions, muzzle flashes). */
    var burstOnStart = false
    /** Destroys the object once the emitter is idle (one-shot effects). */
    var destroyWhenFinished = false

    var sortOrder = 0
    /** Hidden emitters keep simulating but are not drawn. */
    var visible = true
    /** Soft particle edge (0 = hard square, 1 = fully round); only used when no texture is set. */
    var softness = 0.35f

    /** Runtime system (created on first use; never serialised). */
    var system: ParticleSystem2D? = null
        private set
    var pendingBurst = 0
    private var spawnedSubEmitters = 0

    override fun props() = listOf(
        Prop.B("Emitting", { emitting }, { emitting = it }),
        Prop.Choice("Preset", ParticlePresets.names, { ParticlePresets.names.indexOf(preset).coerceAtLeast(0) }, {
            preset = ParticlePresets.names[it]
            pendingBurst = 0
        }, "Applying a preset overwrites the values below - tweak them afterwards."),
        Prop.I("Max Particles", { maxParticles }, { maxParticles = it.coerceIn(1, 4000) }, 10, 1, 4000),
        Prop.F("Rate (/s)", { rate }, { rate = it.coerceAtLeast(0f) }, 1f, 0f, 2000f),
        Prop.F("Lifetime", { lifetime }, { lifetime = it.coerceAtLeast(0.01f) }, 0.05f, 0.01f, 60f),
        Prop.F("Lifetime Variation", { lifetimeVariation }, { lifetimeVariation = it.coerceIn(0f, 1f) }, 0.05f, 0f, 1f),
        Prop.F("Speed", { speed }, { speed = it }, 0.1f, -100f, 100f),
        Prop.F("Speed Variation", { speedVariation }, { speedVariation = it.coerceIn(0f, 1f) }, 0.05f, 0f, 1f),
        Prop.F("Direction", { direction }, { direction = it }, 1f, -180f, 180f),
        Prop.F("Spread", { spread }, { spread = it.coerceIn(0f, 360f) }, 1f, 0f, 360f),
        Prop.Choice("Shape", listOf("Point", "Circle", "Box", "Cone", "Edge"), { shape }, { shape = it }),
        Prop.F("Shape Radius", { shapeRadius }, { shapeRadius = it.coerceAtLeast(0f) }, 0.05f, 0f, 100f),
        Prop.F("Shape Width", { shapeWidth }, { shapeWidth = it.coerceAtLeast(0f) }, 0.05f, 0f, 500f),
        Prop.F("Shape Height", { shapeHeight }, { shapeHeight = it.coerceAtLeast(0f) }, 0.05f, 0f, 500f),
        Prop.F("Size Start", { sizeStart }, { sizeStart = it.coerceAtLeast(0f) }, 0.01f, 0f, 50f),
        Prop.F("Size End", { sizeEnd }, { sizeEnd = it.coerceAtLeast(0f) }, 0.01f, 0f, 50f),
        Prop.S("Size Curve", { sizeCurveSpec }, { sizeCurveSpec = it }, tooltip = "t:value pairs, e.g. 0:1, 0.5:1.4, 1:0.1"),
        Prop.S("Alpha Curve", { alphaCurveSpec }, { alphaCurveSpec = it }, tooltip = "t:value pairs, e.g. 0:0, 0.2:1, 1:0"),
        Prop.S("Color Gradient", { gradientSpec }, { gradientSpec = it }, tooltip = "t:#AARRGGBB pairs, e.g. 0:#FFFFC940, 1:#00FF3D00"),
        Prop.Color("Color Start", { colorStart }, { colorStart = it }),
        Prop.Color("Color End", { colorEnd }, { colorEnd = it }),
        Prop.F("Gravity X", { gravityX }, { gravityX = it }, 0.1f),
        Prop.F("Gravity Y", { gravityY }, { gravityY = it }, 0.1f),
        Prop.F("Drag", { drag }, { drag = it.coerceAtLeast(0f) }, 0.05f, 0f, 50f),
        Prop.F("Radial Accel", { radialAcceleration }, { radialAcceleration = it }, 0.1f),
        Prop.F("Tangential Accel", { tangentialAcceleration }, { tangentialAcceleration = it }, 0.1f),
        Prop.F("Inherit Velocity", { inheritVelocity }, { inheritVelocity = it.coerceIn(0f, 2f) }, 0.05f, 0f, 2f),
        Prop.F("Turbulence", { turbulence }, { turbulence = it.coerceAtLeast(0f) }, 0.1f, 0f, 50f),
        Prop.F("Turbulence Freq", { turbulenceFrequency }, { turbulenceFrequency = it.coerceAtLeast(0.01f) }, 0.05f, 0.01f, 20f),
        Prop.F("Rotation Start", { rotationStart }, { rotationStart = it }, 5f),
        Prop.F("Rotation Speed", { rotationSpeed }, { rotationSpeed = it }, 5f),
        Prop.F("Rotation Random", { rotationRandom }, { rotationRandom = it }, 5f),
        Prop.B("Additive Blend", { additive }, { additive = it }),
        Prop.Asset("Texture", AssetKind.TEXTURE, { texture }, { texture = it }),
        Prop.Choice("Simulation Space", listOf("Local", "World"), { simulationSpace }, { simulationSpace = it }),
        Prop.B("Collide With World", { collideWithWorld }, { collideWithWorld = it }),
        Prop.Choice("Collision", listOf("Destroy", "Bounce", "Stick"), { collisionMode }, { collisionMode = it }),
        Prop.F("Bounce", { collisionBounce }, { collisionBounce = it.coerceIn(0f, 1f) }, 0.05f, 0f, 1f),
        Prop.Asset("Sub Emitter Prefab", AssetKind.PREFAB, { subEmitterPrefab }, { subEmitterPrefab = it }),
        Prop.B("Sub Emitter On Death", { subEmitterOnDeath }, { subEmitterOnDeath = it }),
        Prop.F("Sub Emitter Chance", { subEmitterChance }, { subEmitterChance = it.coerceIn(0f, 1f) }, 0.05f, 0f, 1f),
        Prop.I("Burst Count", { burstCount }, { burstCount = it.coerceIn(1, 2000) }, 1, 1, 2000),
        Prop.B("Burst On Start", { burstOnStart }, { burstOnStart = it }),
        Prop.B("Destroy When Finished", { destroyWhenFinished }, { destroyWhenFinished = it }),
        Prop.B("Visible", { visible }, { visible = it }),
        Prop.I("Sort Order", { sortOrder }, { sortOrder = it }),
        Prop.F("Softness", { softness }, { softness = it.coerceIn(0f, 1f) }, 0.05f, 0f, 1f),
        Prop.Info("Live Particles", { (system?.count ?: 0).toString() })
    )

    override fun resetRuntime() {
        system = null
        pendingBurst = 0
        spawnedSubEmitters = 0
    }

    /** Builds (or rebuilds) the runtime system from the component values. */
    fun ensureSystem(): ParticleSystem2D {
        val cap = maxParticles.coerceIn(1, 4000)
        var sys = system
        if (sys == null || sys.capacity() != cap) {
            sys = ParticleSystem2D(cap)
            system = sys
        }
        sys.rate = rate
        sys.lifetime = lifetime
        sys.lifetimeVariation = lifetimeVariation
        sys.speed = speed
        sys.speedVariation = speedVariation
        sys.direction = direction
        sys.spread = spread
        sys.shape = shape
        sys.shapeRadius = shapeRadius
        sys.shapeWidth = shapeWidth
        sys.shapeHeight = shapeHeight
        sys.sizeStart = sizeStart
        sys.sizeEnd = sizeEnd
        sys.sizeCurve = if (sizeCurveSpec.isBlank()) null else AnimationCurve.parse(sizeCurveSpec, 1f)
        sys.alphaCurve = if (alphaCurveSpec.isBlank()) null else AnimationCurve.parse(alphaCurveSpec, 1f)
        sys.colorGradient = if (gradientSpec.isBlank()) null else ColorGradient.parse(gradientSpec)
        sys.colorStart = colorStart
        sys.colorEnd = colorEnd
        sys.gravityX = gravityX
        sys.gravityY = gravityY
        sys.drag = drag
        sys.radialAcceleration = radialAcceleration
        sys.tangentialAcceleration = tangentialAcceleration
        sys.inheritVelocity = inheritVelocity
        sys.turbulence = turbulence
        sys.turbulenceFrequency = turbulenceFrequency
        sys.rotationStart = rotationStart
        sys.rotationSpeed = rotationSpeed
        sys.rotationRandom = rotationRandom
        sys.additive = additive
        sys.texture = texture
        sys.simulationSpace = simulationSpace
        sys.collideWithWorld = collideWithWorld
        sys.collisionMode = collisionMode
        sys.collisionBounce = collisionBounce
        sys.burstCount = burstCount
        sys.emitting = emitting
        return sys
    }
    /** Applies the selected preset to the component values (keeps the user's tweaks after it). */
    fun applyPreset(name: String) {
        val p = ParticlePresets.find(name) ?: return
        preset = p.name
        val tmp = ParticleSystem2D(64)
        p.apply(tmp)
        rate = tmp.rate; lifetime = tmp.lifetime; lifetimeVariation = tmp.lifetimeVariation
        speed = tmp.speed; speedVariation = tmp.speedVariation; direction = tmp.direction; spread = tmp.spread
        shape = tmp.shape; shapeRadius = tmp.shapeRadius; shapeWidth = tmp.shapeWidth; shapeHeight = tmp.shapeHeight
        sizeStart = tmp.sizeStart; sizeEnd = tmp.sizeEnd
        sizeCurveSpec = tmp.sizeCurve?.let { AnimationCurve.toSpec(it) } ?: ""
        alphaCurveSpec = tmp.alphaCurve?.let { AnimationCurve.toSpec(it) } ?: ""
        gradientSpec = tmp.colorGradient?.let { it.toSpec() } ?: ""
        colorStart = tmp.colorStart; colorEnd = tmp.colorEnd
        gravityX = tmp.gravityX; gravityY = tmp.gravityY; drag = tmp.drag
        radialAcceleration = tmp.radialAcceleration; tangentialAcceleration = tmp.tangentialAcceleration
        inheritVelocity = tmp.inheritVelocity; turbulence = tmp.turbulence; turbulenceFrequency = tmp.turbulenceFrequency
        rotationStart = tmp.rotationStart; rotationSpeed = tmp.rotationSpeed; rotationRandom = tmp.rotationRandom
        additive = tmp.additive; simulationSpace = tmp.simulationSpace
        collisionMode = tmp.collisionMode; collisionBounce = tmp.collisionBounce
        burstCount = tmp.burstCount
        maxParticles = maxOf(maxParticles, tmp.maxParticles)
        system = null
    }

    fun burst(n: Int = burstCount) {
        pendingBurst += n
    }

    fun stopEmitting() {
        emitting = false
    }

    fun startEmitting() {
        emitting = true
        system?.emitting = true
    }

    /** True when this emitter can be skipped entirely this frame. */
    fun isIdle(): Boolean = !emitting && (system?.count ?: 0) == 0

    /**
     * Advances the emitter: applies the component's parameters to its system, spawns this frame's
     * particles, runs the simulation (collision, turbulence, sub-emitters) and collects the
     * sub-emitter spawn positions for the engine to instantiate.
     */
    fun update(
        world: com.sengine.engine.physics.PhysicsWorld2D?, scene: Scene, dt: Float,
        worldTransform: com.sengine.engine.math.Affine
    ) {
        val sys = ensureSystem()
        sys.emitting = emitting && enabled
        val velocity = go.getAny<Rigidbody2D>()
        sys.emitterX = worldTransform.tx
        sys.emitterY = worldTransform.ty
        sys.emitterAngle = worldTransform.rotationDeg
        sys.emitterVx = (velocity?.vx ?: 0f) * inheritVelocity
        sys.emitterVy = (velocity?.vy ?: 0f) * inheritVelocity
        if (pendingBurst > 0) {
            sys.burst(pendingBurst)
            pendingBurst = 0
        }
        sys.collisionQuery = if (world != null && collideWithWorld) { x, y, vx, vy, radius, out ->
            val hit = world.rayCast(x, y, x + vx * 0.05f, y + vy * 0.05f)
            if (hit != null) {
                out[0] = hit.pointX; out[1] = hit.pointY; out[2] = hit.normalX; out[3] = hit.normalY
                true
            } else false
        } else null
        subEmitterSpawns.clear()
        sys.onParticleDeath = if (subEmitterPrefab.isNotBlank() && subEmitterOnDeath) { _, x, y, _ ->
            if (subEmitterChance >= 1f || kotlin.random.Random.nextFloat() < subEmitterChance) {
                subEmitterSpawns.add(floatArrayOf(x, y))
            }
        } else null
        sys.update(dt)
    }

    /** Positions where sub-emitters were requested this frame (the engine spawns the prefabs). */
    val subEmitterSpawns = ArrayList<FloatArray>()
}

/**
 * Trail2D - a ribbon of points following the object (sword swings, drifting cars, projectiles).
 * The renderer builds the triangle strip from the point buffer.
 */
class Trail2D : Component() {
    override val type = "Trail2D"

    var maxPoints = 24
    var time = 0.25f
    var width = 0.25f
    var endWidth = 0.0f
    var color = 0xFFFFFFFF.toInt()
    var endColor = 0x00FFFFFF
    var additive = true
    var texture = ""
    var minDistance = 0.05f
    var order = 0
    var emitting = true

    /** Runtime ring buffer: x, y pairs. */
    val pointsX = FloatArray(256)
    val pointsY = FloatArray(256)
    var count = 0; private set
    private var head = 0
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var fadeTimer = 0f

    override fun props() = listOf(
        Prop.B("Emitting", { emitting }, { emitting = it }),
        Prop.I("Max Points", { maxPoints }, { maxPoints = it.coerceIn(2, 240) }, 1, 2, 240),
        Prop.F("Fade Time", { time }, { time = it.coerceAtLeast(0.01f) }, 0.05f, 0.01f, 10f),
        Prop.F("Width", { width }, { width = it.coerceAtLeast(0f) }, 0.01f, 0f, 10f),
        Prop.F("End Width", { endWidth }, { endWidth = it.coerceAtLeast(0f) }, 0.01f, 0f, 10f),
        Prop.Color("Color", { color }, { color = it }),
        Prop.Color("End Color", { endColor }, { endColor = it }),
        Prop.B("Additive", { additive }, { additive = it }),
        Prop.Asset("Texture", AssetKind.TEXTURE, { texture }, { texture = it }),
        Prop.F("Min Distance", { minDistance }, { minDistance = it.coerceAtLeast(0f) }, 0.01f, 0f, 5f),
        Prop.I("Sort Order", { order }, { order = it })
    )

    override fun resetRuntime() {
        count = 0
        head = 0
        lastX = Float.NaN
        lastY = Float.NaN
        fadeTimer = 0f
    }

    fun push(x: Float, y: Float, dt: Float) {
        fadeTimer += dt
        if (lastX.isNaN() || M.dist(lastX, lastY, x, y) >= minDistance) {
            val cap = maxPoints.coerceIn(2, 240)
            pointsX[head] = x
            pointsY[head] = y
            head = (head + 1) % cap
            if (count < cap) count++
            lastX = x; lastY = y
        }
    }

    /** Oldest to newest point [i] in [0, count). */
    fun pointX(i: Int): Float {
        val cap = maxPoints.coerceIn(2, 240)
        return pointsX[(head - count + i + cap * 2) % cap]
    }

    fun pointY(i: Int): Float {
        val cap = maxPoints.coerceIn(2, 240)
        return pointsY[(head - count + i + cap * 2) % cap]
    }

    /** Ages the ribbon while the emitter is off so old points fade away instead of freezing. */
    fun update(dt: Float) {
        if (dt <= 0f) return
        fadeTimer += dt
        val fade = (fadeTimer / time.coerceAtLeast(0.01f))
        if (fade >= 1f) {
            if (count > 0) { count--; }
            fadeTimer = 0f
        }
    }

    fun clear() {
        count = 0
        lastX = Float.NaN
    }

    /** Attaches a trail to an object (used by scripts: `trail.attach(obj)`). */
    companion object {
        fun attach(go: GameObject, width: Float = 0.2f, color: Int = 0xFFFFFFFF.toInt()): Trail2D {
            val existing = go.getAny<Trail2D>()
            if (existing != null) return existing
            val t = Trail2D()
            t.width = width
            t.color = color
            go.add(t)
            return t
        }

        fun velocityTrail(go: GameObject, rb: Rigidbody2D?): Trail2D {
            val t = attach(go)
            val v = rb?.speed ?: 0f
            t.additive = true
            t.width = M.lerp(0.1f, 0.35f, M.clamp01(v / 10f))
            return t
        }
    }
}
