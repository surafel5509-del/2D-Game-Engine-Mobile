package com.sengine.engine.core

import com.sengine.engine.fx.ParticleEmitter
import com.sengine.engine.math.Colors
import com.sengine.engine.math.M

/** A JavaScript behaviour attached to an object (see [com.sengine.engine.script.ScriptSystem]). */
class ScriptComponent : Component() {
    override val type = "Script"
    var script = ""
    var params = ""
    /** Seconds between updates; 0 = every frame. */
    var updateInterval = 0f
    var enabledOnStart = true

    override fun props() = listOf(
        Prop.Asset("Script", AssetKind.SCRIPT, { script }, { script = it }),
        Prop.S("Params", { params }, { params = it }, multiline = true, tooltip = "key=value pairs available as variables inside the script."),
        Prop.F("Update Interval", { updateInterval }, { updateInterval = it.coerceAtLeast(0f) }, 0.01f, 0f, 10f)
    )
}

/** Audio source: music or sound effect, with bus routing, pitch and 2D panning. */
class AudioSource : Component() {
    override val type = "AudioSource"
    var clip = ""
    var playOnStart = true
    var loop = false
    var volume = 1f
    var pitch = 1f
    /** 0 = Music, 1 = SFX, 2 = UI, 3 = Ambience, 4 = Master. */
    var bus = 1

    /** Panning follows the object's X position relative to the camera. */
    var spatial = true
    var minDistance = 2f
    var maxDistance = 20f
    /** Random pitch variation for repeated sounds (footsteps, hits). */
    var pitchVariation = 0f
    /** Seconds to fade in when played. */
    var fadeIn = 0f

    override fun props() = listOf(
        Prop.Asset("Clip", AssetKind.SOUND, { clip }, { clip = it }),
        Prop.B("Play On Start", { playOnStart }, { playOnStart = it }),
        Prop.B("Loop", { loop }, { loop = it }),
        Prop.F("Volume", { volume }, { volume = it.coerceIn(0f, 1f) }, 0.05f, 0f, 1f),
        Prop.F("Pitch", { pitch }, { pitch = it.coerceIn(0.1f, 4f) }, 0.05f, 0.1f, 4f),
        Prop.Choice("Bus", BUSES, { bus }, { bus = it }),
        Prop.B("2D Spatial", { spatial }, { spatial = it }, "Pans/attenuates the sound based on its position in the 2D world."),
        Prop.F("Min Distance", { minDistance }, { minDistance = it.coerceAtLeast(0.1f) }, 0.5f, 0.1f, 500f),
        Prop.F("Max Distance", { maxDistance }, { maxDistance = it.coerceAtLeast(0.2f) }, 0.5f, 0.2f, 500f),
        Prop.F("Pitch Variation", { pitchVariation }, { pitchVariation = it.coerceIn(0f, 1f) }, 0.05f, 0f, 1f),
        Prop.F("Fade In", { fadeIn }, { fadeIn = it.coerceAtLeast(0f) }, 0.05f, 0f, 10f)
    )

    companion object {
        val BUSES = listOf("Music", "SFX", "UI", "Ambience", "Master")
    }
}

/**
 * Health / damage. Supports knockback, invulnerability frames, death effects, team filters and
 * script callbacks (`onDamage`, `onDeath`).
 */
class Health : Component() {
    override val type = "Health"
    var maxHealth = 100f
    var health = 100f
    var invulnerableTime = 0.5f
    /** Damage is ignored when it comes from the same team. */
    var team = 0
    /** Impulse applied to the attacker's direction when hit. */
    var knockback = 4f
    var knockbackScaleWithMass = true
    var destroyOnDeath = false
    var deathParticles = ""
    var deathSound = ""
    /** 0 = None, 1 = Flash, 2 = Shake. */
    var hitFeedback = 1
    var hitFlashColor = Colors.WHITE

    // runtime
    var invulnerableUntil = 0f
    var lastHitFromX = 0f
    var lastHitFromY = 0f
    var flash = 0f
    var dead = false

    val isAlive get() = health > 0f && !dead
    val ratio get() = if (maxHealth <= 0f) 0f else (health / maxHealth).coerceIn(0f, 1f)

    /** Applies damage; returns true when the hit landed. */
    fun applyDamage(amount: Float, sourceX: Float = 0f, sourceY: Float = 0f, time: Float = 0f): Boolean {
        if (dead) return false
        if (invulnerableTime > 0f && time < invulnerableUntil) return false
        health -= amount
        invulnerableUntil = time + invulnerableTime
        lastHitFromX = sourceX
        lastHitFromY = sourceY
        flash = 1f
        if (knockback > 0f) {
            val dx = go.worldX() - sourceX
            val dy = go.worldY() - sourceY
            val len = kotlin.math.sqrt(dx * dx + dy * dy)
            if (len > 1e-4f) {
                val strength = if (knockbackScaleWithMass) knockback * (go.get<Rigidbody2D>()?.mass ?: 1f) else knockback
                go.get<Rigidbody2D>()?.addImpulse(dx / len * strength, (dy / len + 0.35f) * strength)
            }
        }
        go.emit("damaged", amount)
        if (health <= 0f) {
            health = 0f
            dead = true
            go.emit("died")
        }
        return true
    }

    /** Per-frame upkeep: invulnerability window, hit flash decay and death feedback. */
    fun update(dt: Float, go: GameObject) {
        if (flash > 0f) flash = (flash - dt * 4f).coerceAtLeast(0f)
        if (dead || health > 0f) return
        dead = true
        go.emit("died")
        if (deathParticles.isNotBlank()) {
            val emitter = go.getAny<ParticleEmitter>()
            if (emitter != null) emitter.applyPreset(deathParticles)
        }
        if (destroyOnDeath) go.destroyed = true
    }

    fun heal(amount: Float) {
        if (dead) return
        health = (health + amount).coerceAtMost(maxHealth)
        go.emit("healed", amount)
    }

    override fun props() = listOf(
        Prop.F("Max Health", { maxHealth }, { maxHealth = it.coerceAtLeast(0.01f) }, 5f, 0.01f, 100000f),
        Prop.F("Health", { health }, { health = it.coerceIn(0f, maxHealth) }, 5f),
        Prop.F("Invulnerable Time", { invulnerableTime }, { invulnerableTime = it.coerceAtLeast(0f) }, 0.05f, 0f, 30f),
        Prop.I("Team", { team }, { team = it }),
        Prop.F("Knockback", { knockback }, { knockback = it.coerceAtLeast(0f) }, 0.1f, 0f, 500f),
        Prop.B("Knockback Scales With Mass", { knockbackScaleWithMass }, { knockbackScaleWithMass = it }),
        Prop.B("Destroy On Death", { destroyOnDeath }, { destroyOnDeath = it }),
        Prop.Asset("Death Particles", AssetKind.PREFAB, { deathParticles }, { deathParticles = it }),
        Prop.Asset("Death Sound", AssetKind.SOUND, { deathSound }, { deathSound = it }),
        Prop.Choice("Hit Feedback", listOf("None", "Flash", "Flash + Shake"), { hitFeedback }, { hitFeedback = it }),
        Prop.Color("Flash Color", { hitFlashColor }, { hitFlashColor = it })
    )

    override fun resetRuntime() {
        health = maxHealth
        dead = false
        invulnerableUntil = 0f
        flash = 0f
    }
}

/** Respawn point that the character controller can capture. */
class Checkpoint : Component() {
    override val type = "Checkpoint"
    var radius = 1.5f
    var autoActivate = true
    var label = ""
    var respawnOffsetY = 0.5f
    var activated = false

    var spawnX = 0f
    var spawnY = 0f

    override fun props() = listOf(
        Prop.F("Radius", { radius }, { radius = it.coerceAtLeast(0.1f) }, 0.1f, 0.1f, 50f),
        Prop.B("Auto Activate", { autoActivate }, { autoActivate = it }, "Activate when the player touches it."),
        Prop.S("Label", { label }, { label = it }),
        Prop.F("Respawn Offset Y", { respawnOffsetY }, { respawnOffsetY = it })
    )

    override fun resetRuntime() { activated = false }
}

/** Spawns prefabs on a timer (enemy waves, pickups, debris). */
class Spawner : Component() {
    override val type = "Spawner"
    /** 0 = Prefab asset, 1 = Scene object (cloned, like the old "inactive template" trick). */
    var source = 0
    var prefab = ""
    var templateObject = ""
    var interval = 2f
    var initialDelay = 0f
    var count = 1
    /** Maximum simultaneously alive children (0 = unlimited). */
    var maxAlive = 0
    var autoStart = true
    var randomScatter = 0f
    var velocityX = 0f
    var velocityY = 0f
    var destroyAfter = 0f
    var emitting = true

    var time = 0f
    var spawned = 0

    override fun props() = listOf(
        Prop.Choice("Source", listOf("Prefab Asset", "Scene Object"), { source }, { source = it }),
        Prop.Asset("Prefab", AssetKind.PREFAB, { prefab }, { prefab = it }),
        Prop.S("Template Object", { templateObject }, { templateObject = it }),
        Prop.F("Interval", { interval }, { interval = it.coerceAtLeast(0.01f) }, 0.05f, 0.01f, 600f),
        Prop.F("Initial Delay", { initialDelay }, { initialDelay = it.coerceAtLeast(0f) }, 0.1f, 0f, 600f),
        Prop.I("Count", { count }, { count = it.coerceIn(1, 200) }, 1, 1, 200),
        Prop.I("Max Alive", { maxAlive }, { maxAlive = it.coerceAtLeast(0) }, 1, 0, 5000),
        Prop.B("Auto Start", { autoStart }, { autoStart = it }),
        Prop.F("Random Scatter", { randomScatter }, { randomScatter = it.coerceAtLeast(0f) }, 0.1f, 0f, 50f),
        Prop.F("Velocity X", { velocityX }, { velocityX = it }, 0.1f),
        Prop.F("Velocity Y", { velocityY }, { velocityY = it }, 0.1f),
        Prop.F("Destroy After", { destroyAfter }, { destroyAfter = it.coerceAtLeast(0f) }, 0.1f, 0f, 600f),
        Prop.Info("Spawned", { spawned.toString() })
    )

    override fun resetRuntime() {
        time = 0f
        spawned = 0
        emitting = autoStart
    }
}

/** Destroys the object after [lifetime] seconds, optionally spawning an effect first. */
class Lifetime : Component() {
    override val type = "DestroyAfter"
    var lifetime = 2f
    var effect = ""
    var fadeOut = false
    var fadeDuration = 0.3f

    var time = 0f

    override fun props() = listOf(
        Prop.F("Lifetime", { lifetime }, { lifetime = it.coerceAtLeast(0.01f) }, 0.1f, 0.01f, 600f),
        Prop.Asset("Effect On End", AssetKind.PREFAB, { effect }, { effect = it }),
        Prop.B("Fade Out", { fadeOut }, { fadeOut = it }),
        Prop.F("Fade Duration", { fadeDuration }, { fadeDuration = it.coerceAtLeast(0.01f) }, 0.05f, 0.01f, 10f)
    )

    override fun resetRuntime() { time = 0f }
}

/** Moving platform driven by a path or a simple sine motion; carries riders. */
class MovingPlatform : Component() {
    override val type = "MovingPlatform"
    /** 0 = Path points, 1 = Sine, 2 = Ping pong. */
    var mode = 0
    var points = "0,0 3,0"
    var speed = 1.5f
    var amplitudeX = 3f
    var amplitudeY = 0f
    var frequency = 0.5f
    var carryRiders = true
    var waitTime = 0.2f

    var currentIndex = 0
    var progress = 0f
    var waitTimer = 0f
    var lastX = 0f
    var lastY = 0f
    var deltaX = 0f
    var deltaY = 0f

    override fun props() = listOf(
        Prop.Choice("Mode", listOf("Path", "Sine", "Ping Pong"), { mode }, { mode = it }),
        Prop.S("Path Points", { points }, { points = it }, multiline = true, tooltip = "x,y x,y ... in local space"),
        Prop.F("Speed", { speed }, { speed = it }, 0.1f),
        Prop.F("Amplitude X", { amplitudeX }, { amplitudeX = it }, 0.1f),
        Prop.F("Amplitude Y", { amplitudeY }, { amplitudeY = it }, 0.1f),
        Prop.F("Frequency", { frequency }, { frequency = it.coerceAtLeast(0.01f) }, 0.05f, 0.01f, 10f),
        Prop.B("Carry Riders", { carryRiders }, { carryRiders = it }),
        Prop.F("Wait Time", { waitTime }, { waitTime = it.coerceAtLeast(0f) }, 0.05f, 0f, 10f)
    )

    override fun resetRuntime() {
        currentIndex = 0
        progress = 0f
        waitTimer = 0f
        lastX = go.worldX(); lastY = go.worldY()
        deltaX = 0f; deltaY = 0f
    }

    fun localPoints(): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        for (p in points.trim().split(Regex("[\\s;]+"))) {
            if (p.isBlank()) continue
            val xy = p.split(',')
            if (xy.size != 2) continue
            val x = xy[0].trim().toFloatOrNull() ?: continue
            val y = xy[1].trim().toFloatOrNull() ?: continue
            out.add(floatArrayOf(x, y))
        }
        if (out.isEmpty()) out.add(floatArrayOf(0f, 0f))
        return out
    }
}

/** Small helper component for gameplay timers exposed to scripts and the inspector. */
class GameTimer : Component() {
    override val type = "Timer"
    var duration = 1f
    var repeat = false
    var autoStart = true
    var emitSignal = "timerDone"

    var time = 0f
    var running = true
    var elapsedCount = 0

    override fun props() = listOf(
        Prop.F("Duration", { duration }, { duration = it.coerceAtLeast(0.001f) }, 0.1f, 0.001f, 3600f),
        Prop.B("Repeat", { repeat }, { repeat = it }),
        Prop.B("Auto Start", { autoStart }, { autoStart = it }),
        Prop.S("Signal", { emitSignal }, { emitSignal = it }),
        Prop.Info("Remaining", { "%.2f".format((duration - time).coerceAtLeast(0f)) })
    )

    override fun resetRuntime() {
        time = 0f
        running = autoStart
        elapsedCount = 0
    }
}

/** Bounds the object to a rectangle and bounces (keeps gameplay inside the level). */
class AreaBounds : Component() {
    override val type = "AreaBounds"
    var width = 20f
    var height = 20f
    var bounce = 0.5f
    var wrap = false

    override fun props() = listOf(
        Prop.F("Width", { width }, { width = it.coerceAtLeast(0.1f) }, 0.5f, 0.1f, 10000f),
        Prop.F("Height", { height }, { height = it.coerceAtLeast(0.1f) }, 0.5f, 0.1f, 10000f),
        Prop.F("Bounce", { bounce }, { bounce = it.coerceIn(0f, 1f) }, 0.05f, 0f, 1f),
        Prop.B("Wrap Around", { wrap }, { wrap = it })
    )
}

/** Small utility: spawns a burst of particles when the object is destroyed. */
class DeathEffect : Component() {
    override val type = "DeathEffect"
    var prefab = ""
    var particleBurst = 12
    var emitterObject = ""

    override fun props() = listOf(
        Prop.Asset("Prefab", AssetKind.PREFAB, { prefab }, { prefab = it }),
        Prop.I("Particle Burst", { particleBurst }, { particleBurst = it.coerceIn(0, 500) }, 1, 0, 500),
        Prop.S("Emitter Object", { emitterObject }, { emitterObject = it })
    )

    fun emitter(scene: Scene): ParticleEmitter? =
        if (emitterObject.isBlank()) null else scene.find(emitterObject)?.getAny<ParticleEmitter>()
}
