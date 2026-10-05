package com.sengine.engine.animation

import com.sengine.engine.core.Component
import com.sengine.engine.core.Prop
import com.sengine.engine.core.Signals
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sprite-sheet animation component.
 * Supports multiple named animations, variable frame rates,
 * looping, flipping, and animation events.
 */
class SpriteAnimator : Component() {
    override val type = "SpriteAnimator"

    // Configuration
    var currentAnimation = ""
    var playing = true
    var speed = 1f // playback speed multiplier
    var loop = true

    // Frame data for each animation
    val animations = LinkedHashMap<String, AnimationData>()

    // Runtime
    var currentFrame = 0
    var frameTimer = 0f
    var finished = false
    private var lastAnimation = ""

    data class AnimationData(
        val name: String,
        val frames: List<FrameData>,
        val fps: Float = 12f,
        var loop: Boolean = true
    ) {
        val frameCount: Int get() = frames.size
    }

    data class FrameData(
        val textureIndex: Int = 0, // Index into texture array or atlas region
        val duration: Float = 0f, // 0 = use animation FPS
        val flipX: Boolean = false,
        val flipY: Boolean = false,
        val offsetX: Float = 0f,
        val offsetY: Float = 0f,
        val event: String = "" // Triggered when this frame is shown
    )

    override fun props() = listOf(
        Prop.S("Current Animation", { currentAnimation }, { currentAnimation = it }),
        Prop.B("Playing", { playing }, { playing = it }),
        Prop.F("Speed", { speed }, { speed = it.coerceAtLeast(0f) }),
        Prop.B("Loop", { loop }, { loop = it }),
    )

    override fun resetRuntime() {
        currentFrame = 0
        frameTimer = 0f
        finished = false
        lastAnimation = ""
    }

    /** Define an animation. */
    fun addAnimation(name: String, frameCount: Int, fps: Float = 12f, loop: Boolean = true,
                     startFrame: Int = 0, flipX: Boolean = false): AnimationData {
        val frames = (0 until frameCount).map { i ->
            FrameData(
                textureIndex = startFrame + i,
                flipX = flipX
            )
        }
        val data = AnimationData(name, frames, fps, loop)
        animations[name] = data
        return data
    }

    /** Add a complex animation with per-frame data. */
    fun addAnimationData(data: AnimationData) {
        animations[data.name] = data
    }

    /** Play an animation by name. */
    fun play(name: String, restart: Boolean = true) {
        if (name == currentAnimation && !restart && !finished) return
        currentAnimation = name
        if (restart || lastAnimation != name) {
            currentFrame = 0
            frameTimer = 0f
            finished = false
        }
        playing = true
        lastAnimation = name
    }

    /** Stop the current animation. */
    fun stop() {
        playing = false
    }

    /** Pause the current animation. */
    fun pause() {
        playing = false
    }

    /** Resume playback. */
    fun resume() {
        playing = true
    }

    /** Jump to a specific frame. */
    fun gotoFrame(frame: Int) {
        val anim = animations[currentAnimation] ?: return
        currentFrame = frame.coerceIn(0, anim.frameCount - 1)
        frameTimer = 0f
    }

    /** Get the current frame data. */
    fun currentFrameData(): FrameData? {
        val anim = animations[currentAnimation] ?: return null
        if (anim.frames.isEmpty()) return null
        return anim.frames[currentFrame.coerceIn(0, anim.frames.size - 1)]
    }

    /** Update animation state. Call each frame. */
    fun updateAnimation(dt: Float): String? {
        if (!playing || !enabled) return null
        val anim = animations[currentAnimation] ?: return null
        if (anim.frames.isEmpty()) return null

        val frame = anim.frames[currentFrame]
        val frameDuration = if (frame.duration > 0f) frame.duration else (1f / anim.fps)
        frameTimer += dt * speed

        if (frameTimer >= frameDuration) {
            frameTimer -= frameDuration
            currentFrame++

            // Fire frame event
            if (frame.event.isNotEmpty()) {
                return frame.event
            }

            if (currentFrame >= anim.frameCount) {
                if (anim.loop) {
                    currentFrame = 0
                } else {
                    currentFrame = anim.frameCount - 1
                    finished = true
                    playing = false
                    return "animation_complete"
                }
            }
        }
        return null
    }

    /** Get progress through current animation (0..1). */
    fun progress(): Float {
        val anim = animations[currentAnimation] ?: return 0f
        if (anim.frameCount <= 1) return 1f
        return currentFrame.toFloat() / (anim.frameCount - 1)
    }

    /** Check if the current animation has finished (non-looping). */
    fun isFinished(): Boolean = finished

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("currentAnimation", currentAnimation)
        o.put("playing", playing)
        o.put("speed", speed.toDouble())
        o.put("loop", loop)
        val anims = JSONArray()
        for ((name, data) in animations) {
            val a = JSONObject()
            a.put("name", name)
            a.put("fps", data.fps.toDouble())
            a.put("loop", data.loop)
            val frames = JSONArray()
            for (f in data.frames) {
                val fj = JSONObject()
                fj.put("textureIndex", f.textureIndex)
                fj.put("duration", f.duration.toDouble())
                fj.put("flipX", f.flipX)
                fj.put("flipY", f.flipY)
                fj.put("offsetX", f.offsetX.toDouble())
                fj.put("offsetY", f.offsetY.toDouble())
                fj.put("event", f.event)
                frames.put(fj)
            }
            a.put("frames", frames)
            anims.put(a)
        }
        o.put("animations", anims)
        return o
    }

    fun fromJson(o: JSONObject) {
        currentAnimation = o.optString("currentAnimation", "")
        playing = o.optBoolean("playing", true)
        speed = o.optDouble("speed", 1.0).toFloat()
        loop = o.optBoolean("loop", true)
        animations.clear()
        val anims = o.optJSONArray("animations") ?: return
        for (i in 0 until anims.length()) {
            val a = anims.getJSONObject(i)
            val name = a.optString("name", "anim_$i")
            val fps = a.optDouble("fps", 12.0).toFloat()
            val aLoop = a.optBoolean("loop", true)
            val framesJ = a.optJSONArray("frames") ?: JSONArray()
            val frames = ArrayList<FrameData>()
            for (j in 0 until framesJ.length()) {
                val fj = framesJ.getJSONObject(j)
                frames.add(FrameData(
                    textureIndex = fj.optInt("textureIndex", 0),
                    duration = fj.optDouble("duration", 0.0).toFloat(),
                    flipX = fj.optBoolean("flipX", false),
                    flipY = fj.optBoolean("flipY", false),
                    offsetX = fj.optDouble("offsetX", 0.0).toFloat(),
                    offsetY = fj.optDouble("offsetY", 0.0).toFloat(),
                    event = fj.optString("event", "")
                ))
            }
            animations[name] = AnimationData(name, frames, fps, aLoop)
        }
    }
}

/**
 * Animation state machine for complex multi-state animations.
 * Manages transitions between animation states with conditions.
 */
class AnimationStateMachine {
    data class State(val name: String, val animationName: String)
    data class Transition(
        val from: String,
        val to: String,
        val condition: (StateContext) -> Boolean,
        val crossfade: Float = 0f // seconds
    )
    class StateContext(
        val params: HashMap<String, Any?> = HashMap()
    ) {
        fun getBool(name: String): Boolean = params[name] as? Boolean ?: false
        fun setBool(name: String, value: Boolean) { params[name] = value }
        fun getFloat(name: String): Float = (params[name] as? Number)?.toFloat() ?: 0f
        fun setFloat(name: String, value: Float) { params[name] = value }
        fun getInt(name: String): Int = (params[name] as? Number)?.toInt() ?: 0
        fun setInt(name: String, value: Int) { params[name] = value }
        fun getString(name: String): String = params[name] as? String ?: ""
        fun setString(name: String, value: String) { params[name] = value }
    }

    private val states = LinkedHashMap<String, State>()
    private val transitions = ArrayList<Transition>()
    val context = StateContext()
    var currentState: String = ""
        private set
    var previousState: String = ""
        private set

    fun addState(name: String, animationName: String): State {
        val s = State(name, animationName)
        states[name] = s
        if (currentState.isEmpty()) currentState = name
        return s
    }

    fun addTransition(from: String, to: String, condition: (StateContext) -> Boolean, crossfade: Float = 0f) {
        transitions.add(Transition(from, to, condition, crossfade))
    }

    /** Add a transition that fires when a bool parameter becomes true. */
    fun addBoolTransition(from: String, to: String, paramName: String) {
        addTransition(from, to, { it.getBool(paramName) })
    }

    /** Add a transition that fires when a float parameter exceeds a threshold. */
    fun addFloatGreaterThanTransition(from: String, to: String, paramName: String, threshold: Float) {
        addTransition(from, to, { it.getFloat(paramName) > threshold })
    }

    /** Force a transition to a specific state. */
    fun forceTransition(stateName: String) {
        if (stateName in states) {
            previousState = currentState
            currentState = stateName
        }
    }

    /** Update the state machine. Returns the animation name to play. */
    fun update(): String {
        for (t in transitions) {
            if (t.from == currentState && t.condition(context)) {
                previousState = currentState
                currentState = t.to
                break
            }
        }
        return states[currentState]?.animationName ?: ""
    }

    val currentStateObj: State? get() = states[currentState]
    fun hasState(name: String) = name in states
}
