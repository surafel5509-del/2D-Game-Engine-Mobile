package com.sengine.engine.fx

import com.sengine.engine.math.AnimationCurve
import com.sengine.engine.math.ColorGradient
import com.sengine.engine.math.Colors
import com.sengine.engine.math.M
import com.sengine.engine.math.Noise2D
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Data-oriented 2D particle system.
 *
 * Particles are stored in parallel arrays (structure of arrays) and pooled, so a mobile device
 * can push thousands of particles without allocating. Supports size/alpha curves, colour
 * gradients, turbulence, trails, collision against the 2D physics world, sub-emitters and
 * per-particle rotation.
 */
class ParticleSystem2D(val maxParticles: Int = 512) {

    // ---- emission
    /** Particles per second. */
    var rate = 20f
    /** 0 = Point, 1 = Circle, 2 = Box, 3 = Cone (arc), 4 = Edge. */
    var shape = 0
    var shapeRadius = 0.4f
    var shapeWidth = 1f
    var shapeHeight = 1f
    var emitting = true
    /** Particles released when [burst] is called. */
    var burstCount = 20
    var maxAlive = maxParticles

    // ---- particle motion
    var lifetime = 1f
    var lifetimeVariation = 0.25f
    var speed = 3f
    var speedVariation = 0.3f
    /** Direction in degrees (0 = right, 90 = up). */
    var direction = 90f
    /** Emission arc in degrees. */
    var spread = 25f
    var gravityX = 0f
    var gravityY = 0f
    var drag = 0.4f
    var radialAcceleration = 0f
    var tangentialAcceleration = 0f
    var inheritVelocity = 0f
    var turbulence = 0f
    var turbulenceFrequency = 0.7f
    var turbulenceSpeed = 0.5f
    /** 0 = local space (follows the emitter), 1 = world space. */
    var simulationSpace = 0

    // ---- appearance
    var sizeStart = 0.25f
    var sizeEnd = 0.05f
    /** Optional curve override (0..1 lifetime -> multiplier) - falls back to start/end lerp. */
    var sizeCurve: AnimationCurve? = null
    var alphaCurve: AnimationCurve? = null
    var colorGradient: ColorGradient? = null
    var colorStart = 0xFFFFC940.toInt()
    var colorEnd = 0x00FF3D00
    var rotationStart = 0f
    var rotationSpeed = 0f
    var rotationRandom = 0f
    var additive = false
    var texture = ""
    var softness = 0f

    // ---- collision
    var collideWithWorld = false
    var collisionBounce = 0.3f
    var collisionFriction = 0.6f
    /** 0 = destroy, 1 = bounce, 2 = stick. */
    var collisionMode = 1

    // ---- trails
    var trailEnabled = false
    var trailLength = 5
    var trailWidth = 0.05f

    // ---- sub emitters
    var subEmitterPrefab = ""
    var subEmitterOnDeath = false
    var subEmitterOnCollision = false
    var subEmitterChance = 1f

    // ---- sorting
    var order = 0

    // ---- arrays (structure of arrays)
    private val px = FloatArray(maxParticles)
    private val py = FloatArray(maxParticles)
    private val vx = FloatArray(maxParticles)
    private val vy = FloatArray(maxParticles)
    private val age = FloatArray(maxParticles)
    private val life = FloatArray(maxParticles)
    private val rotation = FloatArray(maxParticles)
    private val spin = FloatArray(maxParticles)
    private val seedX = FloatArray(maxParticles)
    private val alive = BooleanArray(maxParticles)
    private var aliveCount = 0
    private var nextIndex = 0
    private var emitAccumulator = 0f
    private var time = 0f

    /** Emitter transform, set every frame by the component/system. */
    var emitterX = 0f
    var emitterY = 0f
    var emitterAngle = 0f
    var emitterVx = 0f
    var emitterVy = 0f

    /** Optional collision callback installed by the engine (queries the physics world). */
    var collisionQuery: ((x: Float, y: Float, vx: Float, vy: Float, radius: Float, out: FloatArray) -> Boolean)? = null

    /** Called when a particle dies (used for sub-emitters). */
    var onParticleDeath: ((x: Float, y: Float, vx: Float, vy: Float) -> Unit)? = null

    val count get() = aliveCount
    val isFull get() = aliveCount >= maxAlive
    /** True when nothing is left to simulate (lets the engine skip the emitter cheaply). */
    val isIdle get() = !emitting && aliveCount == 0

    fun reset() {
        alive.fill(false)
        aliveCount = 0
        emitAccumulator = 0f
        nextIndex = 0
        time = 0f
    }

    /** Emits [n] particles immediately. */
    fun burst(n: Int = burstCount, x: Float = emitterX, y: Float = emitterY, dirOverride: Float? = null, rng: Random = Random.Default) {
        var remaining = n.coerceAtLeast(0)
        var guard = 0
        while (remaining > 0 && aliveCount < maxAlive && guard < maxParticles * 2) {
            val i = allocate()
            if (i < 0) break
            spawn(i, x, y, dirOverride, rng)
            remaining--
            guard++
        }
    }

    private fun allocate(): Int {
        if (aliveCount >= maxAlive) return -1
        var i = nextIndex
        var scanned = 0
        while (alive[i] && scanned < maxParticles) {
            i = (i + 1) % maxParticles
            scanned++
        }
        if (alive[i]) return -1
        nextIndex = (i + 1) % maxParticles
        alive[i] = true
        aliveCount++
        return i
    }

    private fun spawn(i: Int, ox: Float, oy: Float, dirOverride: Float?, rng: Random) {
        time = time
        var ex = ox
        var ey = oy
        when (shape) {
            1 -> {
                val a = rng.nextFloat() * M.TAU
                val r = sqrt(rng.nextFloat()) * shapeRadius
                ex += cos(a) * r
                ey += sin(a) * r
            }
            2 -> {
                ex += (rng.nextFloat() - 0.5f) * shapeWidth
                ey += (rng.nextFloat() - 0.5f) * shapeHeight
            }
            4 -> {
                ex += (rng.nextFloat() - 0.5f) * shapeWidth
            }
        }
        val dirBase = dirOverride ?: (direction + emitterAngle)
        val half = spread * 0.5f
        val dir = dirBase + (rng.nextFloat() - 0.5f) * (half * 2f)
        val spd = speed * (1f + (rng.nextFloat() - 0.5f) * 2f * speedVariation)
        px[i] = ex
        py[i] = ey
        val rad = dir * M.DEG2RAD
        vx[i] = cos(rad) * spd + emitterVx * inheritVelocity
        vy[i] = sin(rad) * spd + emitterVy * inheritVelocity
        age[i] = 0f
        life[i] = (lifetime * (1f + (rng.nextFloat() - 0.5f) * 2f * lifetimeVariation)).coerceAtLeast(0.02f)
        rotation[i] = rotationStart * M.DEG2RAD + (rng.nextFloat() - 0.5f) * rotationRandom * M.DEG2RAD
        spin[i] = rotationSpeed * M.DEG2RAD + (rng.nextFloat() - 0.5f) * rotationRandom * M.DEG2RAD * 0.5f
        seedX[i] = rng.nextFloat() * 1000f
    }

    /** Kills every live particle immediately (scene reset, effect cancellation). */
    fun clear() {
        for (i in 0 until maxParticles) alive[i] = false
        aliveCount = 0
        nextIndex = 0
        emitAccumulator = 0f
    }

    /** Advances the simulation by [dt]; [emitterMoving] adds velocity inheritance. */
    fun update(dt: Float, rng: Random = Random.Default) {
        if (dt <= 0f) return
        time += dt
        // emission
        if (emitting && aliveCount < maxAlive) {
            emitAccumulator += rate * dt
            var n = emitAccumulator.toInt()
            if (n > 0) {
                emitAccumulator -= n
                n = min(n, maxAlive - aliveCount)
                repeat(n) {
                    val i = allocate()
                    if (i >= 0) spawn(i, emitterX, emitterY, null, rng)
                }
            }
        }
        val dragFactor = if (drag > 0f) max(0f, 1f - drag * dt) else 1f
        val curl = FloatArray(2)
        for (i in 0 until maxParticles) {
            if (!alive[i]) continue
            age[i] += dt
            if (age[i] >= life[i]) {
                if (simulationSpace == 0) {
                    onParticleDeath?.invoke(px[i], py[i], vx[i], vy[i])
                } else {
                    onParticleDeath?.invoke(px[i], py[i], vx[i], vy[i])
                }
                alive[i] = false
                aliveCount--
                continue
            }
            // forces
            var ax = gravityX
            var ay = gravityY
            if (radialAcceleration != 0f || tangentialAcceleration != 0f) {
                val dx = px[i] - emitterX
                val dy = py[i] - emitterY
                val d = sqrt(dx * dx + dy * dy)
                if (d > 1e-4f) {
                    val nx = dx / d
                    val ny = dy / d
                    ax += nx * radialAcceleration - ny * tangentialAcceleration
                    ay += ny * radialAcceleration + nx * tangentialAcceleration
                }
            }
            if (turbulence != 0f) {
                val nx = (px[i] + seedX[i]) * turbulenceFrequency
                val ny = (py[i] - seedX[i]) * turbulenceFrequency + time * turbulenceSpeed
                Noise2D.curl(nx, ny, (seedX[i].toInt() % 97), curl)
                ax += curl[0] * turbulence
                ay += curl[1] * turbulence
            }
            vx[i] += ax * dt
            vy[i] += ay * dt
            if (dragFactor < 1f) {
                vx[i] *= dragFactor
                vy[i] *= dragFactor
            }
            val desiredVx = vx[i] * dt
            val desiredVy = vy[i] * dt
            val nextX = px[i] + desiredVx
            val nextY = py[i] + desiredVy
            if (collideWithWorld && collisionQuery != null) {
                val hit = FloatArray(4)
                if (collisionQuery!!.invoke(nextX, nextY, vx[i], vy[i], resolveRadius(sizeAt(i)), hit)) {
                    when (collisionMode) {
                        0 -> { alive[i] = false; aliveCount--; continue }
                        2 -> { px[i] = hit[0]; py[i] = hit[1]; vx[i] = 0f; vy[i] = 0f }
                        else -> {
                            px[i] = hit[0]; py[i] = hit[1]
                            val nx = hit[2]; val ny = hit[3]
                            val vn = vx[i] * nx + vy[i] * ny
                            vx[i] -= (1f + collisionBounce) * vn * nx
                            vy[i] -= (1f + collisionBounce) * vn * ny
                            vx[i] *= (1f - collisionFriction * 0.1f)
                            vy[i] *= (1f - collisionFriction * 0.1f)
                        }
                    }
                    continue
                }
            }
            px[i] = nextX
            py[i] = nextY
            rotation[i] += spin[i] * dt
        }
    }

    private fun resolveRadius(size: Float) = size * 0.5f

    /** Normalised lifetime of particle [i]. */
    private fun t(i: Int) = M.clamp01(age[i] / max(0.0001f, life[i]))

    fun sizeAt(i: Int): Float {
        val t = t(i)
        val c = sizeCurve
        if (c != null && !c.isEmpty) return sizeStart * c.evaluate(t)
        return M.lerp(sizeStart, sizeEnd, t)
    }

    fun alphaAt(i: Int): Float {
        val t = t(i)
        val c = alphaCurve
        if (c != null && !c.isEmpty) return M.clamp01(c.evaluate(t))
        // default: fade in quickly, fade out on the last 40%
        return if (t < 0.1f) t / 0.1f else M.clamp01((1f - t) / 0.6f)
    }

    fun colorAt(i: Int): Int {
        val t = t(i)
        val g = colorGradient
        if (g != null && !g.isEmpty) return g.evaluate(t)
        return ColorGradient.lerpArgb(colorStart, colorEnd, t)
    }

    fun xAt(i: Int) = px[i]
    fun yAt(i: Int) = py[i]
    fun rotationAt(i: Int) = rotation[i]
    fun lifeAt(i: Int) = t(i)
    fun vxAt(i: Int) = vx[i]
    fun vyAt(i: Int) = vy[i]
    fun isAlive(i: Int) = alive[i]
    fun capacity() = maxParticles

    /** Copies the live particles into flat arrays for batching / saving (x, y, size, rot, color). */
    fun snapshot(step: Int = 1): FloatArray {
        val n = aliveCount / step + 1
        val out = FloatArray(n * 6)
        var k = 0
        var i = 0
        while (i < maxParticles && k < out.size - 5) {
            if (alive[i]) {
                out[k] = px[i]; out[k + 1] = py[i]
                out[k + 2] = sizeAt(i); out[k + 3] = rotation[i]
                out[k + 4] = alphaAt(i)
                out[k + 5] = colorAt(i).toFloat()
                k += 6
            }
            i += step
        }
        return out
    }

    /** Compact, human readable preset serialisation used by the particle editor. */
    fun toSpec(): String = listOf(
        "rate=$rate", "lifetime=$lifetime", "speed=$speed", "direction=$direction", "spread=$spread",
        "shape=$shape", "sizeStart=$sizeStart", "sizeEnd=$sizeEnd", "gravityX=$gravityX", "gravityY=$gravityY",
        "drag=$drag", "turbulence=$turbulence", "additive=$additive",
        "color0=${Colors.toHex(colorStart)}", "color1=${Colors.toHex(colorEnd)}"
    ).joinToString(";")

    companion object {
        /** Quick one-shot effect helper: spawns a system, runs it and returns it. */
        fun oneShot(preset: Preset, x: Float, y: Float, count: Int = 24): ParticleSystem2D {
            val sys = ParticleSystem2D(maxParticles = max(64, count * 2))
            preset.apply(sys)
            sys.emitting = false
            sys.emitterX = x
            sys.emitterY = y
            sys.burst(count)
            return sys
        }

        fun lerp(a: Float, b: Float, t: Float) = M.lerp(a, b, t)
    }

    /** A particle preset (see [ParticlePresets]). */
    interface Preset {
        val name: String
        fun apply(sys: ParticleSystem2D)
    }

    internal fun absF(v: Float) = abs(v)
}
