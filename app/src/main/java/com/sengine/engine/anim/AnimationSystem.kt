package com.sengine.engine.anim

import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.core.SpriteRenderer
import com.sengine.engine.math.M
import kotlin.random.Random

/** Provides animation clips to the runtime (implemented by the project asset library). */
interface ClipProvider {
    fun clip(name: String): AnimationClip?
}

/**
 * Drives every [Animator] in the scene: advances playback, selects frames, fires events,
 * evaluates timeline curves and handles state-machine transitions with cross-fades.
 */
class AnimationSystem(private val provider: ClipProvider) {

    /** Statistics for the profiler. */
    var activeAnimators = 0
    var eventsFired = 0

    private val cache = HashMap<String, AnimationClip>()

    fun clip(name: String): AnimationClip? {
        if (name.isBlank()) return null
        cache[name]?.let { return it }
        val c = provider.clip(name) ?: return null
        cache[name] = c
        return c
    }

    fun invalidate(name: String? = null) {
        if (name == null) cache.clear() else cache.remove(name)
    }

    /** Runs one frame of animation for every object in the scene. */
    fun update(scene: com.sengine.engine.core.Scene, dt: Float, playing: Boolean) {
        activeAnimators = 0
        for (go in scene.objects) {
            val animator = go.getAny<Animator>() ?: continue
            val sprite = go.getAny<SpriteRenderer>() ?: continue
            if (!go.isActiveInHierarchy()) continue
            activeAnimators++
            updateAnimator(go, animator, sprite, dt, playing)
        }
    }

    fun updateAnimator(go: GameObject, animator: Animator, sprite: SpriteRenderer, dt: Float, playing: Boolean) {
        // resolve the clip
        if (animator.currentClip == null && animator.currentName.isNotBlank()) {
            val clip = clip(animator.currentName)
            animator.currentClip = clip
            if (clip != null && animator.randomStart) {
                animator.time = Random.nextFloat() * clip.duration
            }
        }
        val clip = animator.currentClip
        if (clip == null) {
            // fall back to the default clip when the animator has none playing yet
            if (animator.playOnStart && animator.clip.isNotBlank() && !animator.playing) {
                animator.play(animator.clip)
            }
            return
        }
        var speed = animator.speed
        if (animator.randomSpeedVariation > 0f) {
            speed *= 1f + (Random.nextFloat() - 0.5f) * 2f * animator.randomSpeedVariation
        }
        val loopMode = animator.loop ?: clip.loop
        val advancing = animator.playing || animator.playOnStart
        if (advancing && playing) {
            animator.lastTime = animator.time
            val prev = animator.time
            animator.time += dt * speed
            if (loopMode == LoopMode.ONCE && animator.time >= clip.duration) {
                animator.time = clip.duration
                animator.finished = true
                animator.playing = false
                go.emit("animationFinished", clip.name)
            } else if (loopMode != LoopMode.ONCE && clip.duration > 0f && animator.time > clip.duration) {
                // loop / ping-pong wrap: replay events from the start of the clip
                animator.time %= maxOf(0.0001f, clip.duration)
                animator.lastEventTime = -1f
                animator.lastTime = 0f
            }
            // fire every event crossed during this frame (including across a loop wrap)
            val wrapped = animator.time < prev
            for (e in clip.events) {
                val crossed = if (wrapped) (e.time >= prev || e.time <= animator.time) else (e.time in prev..animator.time)
                if (crossed && e.time > animator.lastEventTime) {
                    animator.lastEventTime = e.time
                    go.emit("animationEvent", e.name)
                    go.emit(e.name, e.value)
                    eventsFired++
                }
            }
        }
        animator.frameIndex = clip.frameIndexAt(animator.time)
        animator.applyFrame(sprite, clip, animator.frameIndex)

        // cross-fade: the previous clip keeps playing underneath as a ghost frame so the
        // renderer can blend the two animation states (blend-like 2D behaviour).
        val previous = animator.previousClip
        if (previous != null && animator.blend > 0f) {
            animator.previousTime += dt * animator.previousSpeed
            val pd = maxOf(0.0001f, previous.duration)
            if (previous.loop == LoopMode.ONCE) animator.previousTime = minOf(animator.previousTime, pd)
            else if (animator.previousTime > pd) animator.previousTime %= pd
            val pf = previous.frameAt(previous.frameIndexAt(animator.previousTime))
            if (pf != null) {
                animator.ghostVisible = true
                animator.ghostU0 = pf.u0; animator.ghostV0 = pf.v0
                animator.ghostU1 = pf.u1; animator.ghostV1 = pf.v1
            }
            animator.blend = maxOf(0f, animator.blend - dt / maxOf(0.02f, animator.blendTime))
            if (animator.blend <= 0f) {
                animator.blend = 0f
                animator.ghostVisible = false
                animator.previousClip = null
            }
        } else {
            animator.ghostVisible = false
        }
        val weight = if (animator.previousClip != null && animator.blend > 0f) 1f - animator.blend else 1f
        if (clip.tracks.isNotEmpty()) {
            animator.applyTimeline(clip, animator.animatorNormalizedTime(), go, weight)
        }

        // state machine
        val machine = animator.machine
        if (machine != null) {
            val state = machine.states.values.firstOrNull { it.clip == animator.currentName }
            if (state != null) {
                val nt = clip.normalizedTime(animator.time)
                val trans = machine.nextTransition(state, nt)
                if (trans != null) {
                    val from = animator.currentName
                    animator.previousClip = clip
                    animator.play(trans.to)
                    animator.blend = 1f
                    go.emit("stateChanged", "${from}->${trans.to}")
                    machine.clearTriggers()
                }
            }
        }

        // flip towards movement for platformers
        if (animator.flipWithVelocity) {
            val rb = go.get<Rigidbody2D>()
            if (rb != null) {
                val v = rb.vx
                if (v > 0.1f) sprite.flipX = false
                else if (v < -0.1f) sprite.flipX = true
            }
        }
    }

    /** Normalised playback time of the currently playing clip. */
    fun Animator.animatorNormalizedTime(): Float {
        val c = currentClip ?: return 0f
        return c.normalizedTime(time)
    }

    /** Previews a clip without a scene (used by the animation editor). */
    fun preview(clip: AnimationClip, time: Float): AnimationFrame? = clip.frameAt(clip.frameIndexAt(time))
}
