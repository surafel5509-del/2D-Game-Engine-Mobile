package com.sengine.engine.anim

import com.sengine.engine.core.AssetKind
import com.sengine.engine.core.Component
import com.sengine.engine.core.Prop
import com.sengine.engine.core.SpriteRenderer
import com.sengine.engine.core.TextRenderer
import com.sengine.engine.math.AnimationCurve
import com.sengine.engine.math.Colors
import com.sengine.engine.math.M

/**
 * A state inside an [Animator]'s state machine.
 */
class AnimationState(
    var name: String,
    var clip: String = "",
    var speed: Float = 1f,
    var loop: LoopMode? = null
) {
    /** Transitions leaving this state. */
    val transitions = ArrayList<AnimationTransition>()
}

/** A transition between two states, driven by parameters, exit time and/or a trigger. */
class AnimationTransition(
    var to: String,
    var duration: Float = 0.1f,
    /** "paramName op value" conditions, e.g. "speed > 1", "grounded == true", "jump" (trigger). */
    var conditions: MutableList<String> = ArrayList(),
    /** Normalised exit time window (0 = any time). */
    var exitTime: Float = 0f,
    var hasExitTime: Boolean = false
)

/**
 * Animation params + state machine driven by the Animator component.
 * Frames come from [AnimationClip]s, timeline curves can animate any component property, and
 * events are forwarded to scripts and signals.
 */
class AnimationStateMachine {
    val states = LinkedHashMap<String, AnimationState>()
    var defaultState = ""
    /** Global parameters (float/bool/trigger). */
    val params = HashMap<String, Float>()
    private val triggers = HashSet<String>()

    fun addState(name: String, clip: String, speed: Float = 1f, loop: LoopMode? = null): AnimationState {
        val s = AnimationState(name, clip, speed, loop)
        states[name] = s
        if (defaultState.isEmpty()) defaultState = name
        return s
    }

    fun addTransition(from: String, to: String, duration: Float = 0.1f, vararg conditions: String): AnimationTransition {
        val state = states.getOrPut(from) { AnimationState(from) }
        val t = AnimationTransition(to, duration, conditions.toMutableList())
        state.transitions.add(t)
        return t
    }

    fun setFloat(name: String, v: Float) { params[name] = v }
    fun setBool(name: String, v: Boolean) { params[name] = if (v) 1f else 0f }
    fun setTrigger(name: String) { triggers.add(name) }
    fun getFloat(name: String) = params[name] ?: 0f
    fun getBool(name: String) = (params[name] ?: 0f) > 0.5f
    fun resetTrigger(name: String) { triggers.remove(name) }
    fun hasTrigger(name: String) = name in triggers

    /** Evaluates a condition string like `speed > 2`, `grounded == true` or `attack` (trigger). */
    fun evaluate(condition: String): Boolean {
        val c = condition.trim()
        if (c.isEmpty()) return true
        val parts = c.split(Regex("\\s+"))
        if (parts.size == 1) {
            // bare name: bool param or trigger
            return getBool(parts[0]) || hasTrigger(parts[0])
        }
        if (parts.size >= 3) {
            val name = parts[0]
            val op = parts[1]
            val raw = parts.subList(2, parts.size).joinToString(" ")
            val num = raw.toFloatOrNull()
            val bool = raw.equals("true", true) || raw.equals("false", true)
            val v = if (bool) (if (raw.equals("true", true)) 1f else 0f) else (num ?: return false)
            val current = params[name] ?: 0f
            return when (op) {
                ">" -> current > v
                "<" -> current < v
                ">=" -> current >= v
                "<=" -> current <= v
                "==", "=" -> kotlin.math.abs(current - v) < 1e-4f
                "!=" -> kotlin.math.abs(current - v) >= 1e-4f
                else -> false
            }
        }
        return false
    }

    /** Finds the first satisfied transition from [state]. */
    fun nextTransition(state: AnimationState, normalizedTime: Float): AnimationTransition? {
        for (t in state.transitions) {
            if (t.hasExitTime && normalizedTime < t.exitTime) continue
            var ok = true
            for (cond in t.conditions) if (!evaluate(cond)) { ok = false; break }
            if (ok) return t
        }
        return null
    }

    fun clearTriggers() { triggers.clear() }
}

/**
 * Animator - the 2D animation component. Plays sprite-sheet clips, drives a state machine,
 * fires events, applies timeline curves and supports cross-fading between states.
 */
class Animator : Component() {
    override val type = "Animator"

    /** Default clip asset (.anim). */
    var clip = ""
    var playOnStart = true
    var speed = 1f
    var loop: LoopMode? = null
    /** Random start time (keeps crowds out of sync). */
    var randomStart = false
    var randomSpeedVariation = 0f

    /** State machine definition: "Idle=idle.anim, Run=run.anim; Idle->Run: speed > 0.1" */
    var stateMachineSpec = ""
    var defaultState = ""

    /** Flip the sprite based on horizontal velocity (platformers). */
    var flipWithVelocity = false
    /** When the clip has no texture, keep the sprite's own texture. */
    var sortOrder = 0

    // ---- runtime state
    var currentClip: AnimationClip? = null
    var currentName = ""
    var time = 0f
    var playing = false
    var frameIndex = 0
    var finished = false
    /** Blend weight of the previous state during a cross-fade (0..1). */
    var blend = 0f
    var previousClip: AnimationClip? = null
    var previousTime = 0f
    var machine: AnimationStateMachine? = null
    var events = ArrayList<String>()
    /** Previous clip time, used for event-window detection. */
    internal var lastTime = 0f
    /** Last event time fired for the current clip (-1 = none since the clip started). */
    internal var lastEventTime = -1f
    /** Cross-fade time in seconds (ghost frame of the previous clip fades out). */
    var blendTime = 0.12f
    /** Ghost (previous clip) frame UVs used by the renderer during a cross-fade. */
    var ghostVisible = false
    var ghostU0 = 0f; var ghostV0 = 1f; var ghostU1 = 1f; var ghostV1 = 0f
    internal var previousSpeed = 1f
    /** True while the clip is a state-machine state (drives transitions). */
    var stateDriven = false

    override fun props() = listOf(
        Prop.Asset("Clip", AssetKind.ANIMATION, { clip }, { clip = it }),
        Prop.B("Play On Start", { playOnStart }, { playOnStart = it }),
        Prop.F("Speed", { speed }, { speed = it.coerceIn(0.02f, 10f) }, 0.05f, 0.02f, 10f),
        Prop.Choice("Loop Override", listOf("Clip Default", "Once", "Loop", "Ping Pong"), {
            loop?.let { LoopMode.indexOf(it) + 1 } ?: 0
        }, { loop = if (it <= 0) null else LoopMode.of(it - 1) }),
        Prop.B("Random Start", { randomStart }, { randomStart = it }),
        Prop.F("Random Speed Variation", { randomSpeedVariation }, { randomSpeedVariation = it.coerceIn(0f, 1f) }, 0.05f, 0f, 1f),
        Prop.S("State Machine", { stateMachineSpec }, { stateMachineSpec = it }, multiline = true,
            tooltip = "States: Idle=idle.anim, Run=run.anim\nTransitions: Idle->Run: speed > 0.1"),
        Prop.S("Default State", { defaultState }, { defaultState = it }),
        Prop.B("Flip With Velocity", { flipWithVelocity }, { flipWithVelocity = it }),
        Prop.Info("Playing", { "$currentName ${"%.2f".format(time)}s frame $frameIndex" }),
        Prop.Info("Blend", { "%.2f".format(blend) })
    )

    override fun resetRuntime() {
        currentClip = null
        previousClip = null
        currentName = ""
        time = 0f
        playing = false
        frameIndex = 0
        finished = false
        blend = 0f
        lastEventTime = -1f
        events.clear()
        machine = if (stateMachineSpec.isBlank()) null else parseStateMachine(stateMachineSpec)
        if (machine != null) {
            val start = defaultState.ifBlank { machine!!.defaultState }
            playState(start)
        } else if (playOnStart && clip.isNotBlank()) {
            currentName = clip
        }
    }

    /** Plays a clip asset by name. */
    fun play(clipName: String, restart: Boolean = true) {
        if (clipName == currentName && !restart) return
        if (clipName == currentName && restart) {
            time = 0f
            lastEventTime = -1f
            return
        }
        previousClip = currentClip
        previousTime = time
        currentName = clipName
        currentClip = null
        time = 0f
        frameIndex = 0
        finished = false
        lastEventTime = -1f
        playing = true
        blend = if (previousClip != null) 1f else 0f
    }

    fun playState(stateName: String) {
        val m = machine ?: return
        val s = m.states[stateName] ?: return
        play(s.clip)
        if (s.loop != null) loop = s.loop
        speed = s.speed
    }

    fun stop() { playing = false }

    fun setFloat(name: String, v: Float) { machine?.setFloat(name, v) }
    fun setBool(name: String, v: Boolean) { machine?.setBool(name, v) }
    fun setTrigger(name: String) { machine?.setTrigger(name) }
    fun currentStateName(): String {
        val m = machine ?: return currentName
        return m.states.entries.firstOrNull { it.value.clip == currentName }?.key ?: currentName
    }

    /** Applies the frame UVs to a sprite renderer. */
    fun applyFrame(sprite: SpriteRenderer, clip: AnimationClip, index: Int) {
        val f = clip.frameAt(index) ?: return
        sprite.uvU0 = f.u0; sprite.uvV0 = f.v0
        sprite.uvU1 = f.u1; sprite.uvV1 = f.v1
        sprite.useFrameUv = true
        if (clip.texture.isNotBlank()) sprite.texture = clip.texture
    }

    private fun parseStateMachine(spec: String): AnimationStateMachine {
        val m = AnimationStateMachine()
        for (raw in spec.split('\n', ';')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.contains("->")) {
                val head = line.substringBefore(':').trim()
                val cond = line.substringAfter(':', "").trim()
                val from = head.substringBefore("->").trim()
                val to = head.substringAfter("->").trim()
                val conds = if (cond.isBlank()) emptyList() else cond.split(',').map { it.trim() }
                m.addTransition(from, to, 0.1f, *conds.toTypedArray())
            } else {
                val key = line.substringBefore('=').trim()
                val value = line.substringAfter('=', "").trim()
                if (key.isNotEmpty() && value.isNotEmpty()) m.addState(key, value)
            }
        }
        if (m.states.isEmpty()) m.addState("Default", clip)
        return m
    }

    /** Timeline application: writes curve values into the target component properties. */
    fun applyTimeline(clip: AnimationClip, t: Float, go: com.sengine.engine.core.GameObject, weight: Float = 1f) {
        for (track in clip.tracks) {
            if (!track.enabled) continue
            val v = track.curve.evaluate(t)
            applyTrack(track.target, v, go, weight)
        }
    }

    private fun applyTrack(target: String, value: Float, go: com.sengine.engine.core.GameObject, weight: Float) {
        val path = target.split('.')
        val root = path.firstOrNull() ?: return
        val prop = path.drop(1).joinToString(".")
        when (root) {
            "self", "transform" -> when (prop) {
                "x" -> go.x = apply(go.x, value, weight)
                "y" -> go.y = apply(go.y, value, weight)
                "rotation", "rot" -> go.rotation = apply(go.rotation, value, weight)
                "scaleX" -> go.scaleX = apply(go.scaleX, value, weight)
                "scaleY" -> go.scaleY = apply(go.scaleY, value, weight)
                "order" -> go.order = value.toInt()
            }
            "sprite" -> {
                val sr = go.get<SpriteRenderer>() ?: return
                when {
                    prop == "color" -> sr.color = Colors.withAlpha(sr.color, value)
                    prop == "color.alpha" -> sr.color = Colors.withAlpha(sr.color, value)
                    prop == "color.r" -> sr.color = Colors.rgba((value * 255).toInt(), Colors.green(sr.color), Colors.blue(sr.color), Colors.alpha(sr.color))
                    prop == "width" -> sr.width = apply(sr.width, value, weight)
                    prop == "height" -> sr.height = apply(sr.height, value, weight)
                    prop == "roundness" -> sr.roundness = apply(sr.roundness, value, weight)
                    prop == "flipX" -> sr.flipX = value > 0.5f
                }
            }
            "text" -> {
                val tr = go.get<TextRenderer>() ?: return
                when (prop) {
                    "color" -> tr.color = Colors.withAlpha(tr.color, value)
                    "color.alpha" -> tr.color = Colors.withAlpha(tr.color, value)
                    "size" -> tr.size = apply(tr.size, value, weight)
                }
            }
            else -> {
                // generic: find a component by type name and a matching float property
                val comp = go.components.firstOrNull { it.type.equals(root, true) } ?: return
                for (p in comp.props()) {
                    if (p is Prop.F && p.name.equals(prop, true)) {
                        p.set(apply(p.get(), value, weight))
                        return
                    }
                    if (p is Prop.I && p.name.equals(prop, true)) {
                        p.set(value.toInt())
                        return
                    }
                    if (p is Prop.Color && p.name.equals(prop, true)) {
                        p.set(Colors.withAlpha(p.get(), value))
                        return
                    }
                }
            }
        }
    }

    private fun apply(current: Float, value: Float, weight: Float): Float =
        if (weight >= 1f) value else M.lerp(current, value, weight)
}
