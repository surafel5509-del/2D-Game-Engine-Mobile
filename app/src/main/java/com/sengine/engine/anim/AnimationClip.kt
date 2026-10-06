package com.sengine.engine.anim

import com.sengine.engine.math.AnimationCurve
import com.sengine.engine.math.M
import org.json.JSONArray
import org.json.JSONObject

/** A single sprite-sheet frame: UV rect plus an optional display duration override. */
class AnimationFrame(
    var u0: Float, var v0: Float, var u1: Float, var v1: Float,
    /** Extra pivot offset for sprite alignment (pixel art often needs this). */
    var offsetX: Float = 0f,
    var offsetY: Float = 0f,
    var duration: Float = 0f
)

/** Animation events fired at a specific time inside a clip (footsteps, hits, spawns). */
class AnimationEvent(var time: Float, var name: String, var value: String = "")

/** Loop behaviour. */
enum class LoopMode(val label: String) {
    ONCE("Once"), LOOP("Loop"), PING_PONG("Ping Pong");

    companion object {
        fun of(index: Int) = entries.getOrElse(index) { LOOP }
        fun indexOf(mode: LoopMode) = entries.indexOf(mode)
        val labels = entries.map { it.label }
    }
}

/**
 * Timeline track: animates any component property (by name) with an [AnimationCurve].
 * Targets are addressed as "self.scaleX", "sprite.color", "Light2D.intensity"...
 */
class TimelineTrack(var target: String, var curve: AnimationCurve, var enabled: Boolean = true) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("target", target)
        o.put("enabled", enabled)
        o.put("curve", AnimationCurve.toSpec(curve))
        return o
    }

    companion object {
        fun fromJson(o: JSONObject) = TimelineTrack(
            o.optString("target", "self.scaleX"),
            AnimationCurve.parse(o.optString("curve", "0:1, 1:1"), 1f),
            o.optBoolean("enabled", true)
        )
    }
}

/**
 * A 2D animation clip: sprite frames from a texture (or sprite sheet), events, timeline tracks,
 * loop mode and playback speed. Strictly 2D - clips animate sprites, transforms, colours and
 * any other component property, never 3D transforms.
 */
class AnimationClip(var name: String = "New Animation") {
    /** Texture used by this clip (an image asset, typically a sprite sheet). */
    var texture = ""
    var columns = 4
    var rows = 4
    var fps = 12f
    var loop = LoopMode.LOOP
    var speed = 1f
    /** Explicit frames; when empty the whole [columns] x [rows] grid is used. */
    var frames = ArrayList<AnimationFrame>()
    val events = ArrayList<AnimationEvent>()
    val tracks = ArrayList<TimelineTrack>()

    val duration: Float
        get() {
            val n = frameCount
            if (n <= 0) return 0.1f
            val custom = frames.filter { it.duration > 0f }.sumOf { it.duration.toDouble() }.toFloat()
            return if (custom > 0f) custom else n / maxOf(1f, fps * speed)
        }

    val frameCount: Int
        get() = if (frames.isNotEmpty()) frames.size else maxOf(0, columns * rows)

    /** Builds uniform frames from the grid definition (what the sprite-sheet importer does). */
    fun buildGrid(columns: Int, rows: Int, startFrame: Int = 0, frameCount: Int = -1) {
        this.columns = columns.coerceAtLeast(1)
        this.rows = rows.coerceAtLeast(1)
        frames.clear()
        val total = this.columns * this.rows
        val count = if (frameCount <= 0) total - startFrame else frameCount
        for (i in 0 until count) {
            val f = (startFrame + i) % total
            val cx = f % this.columns
            val cy = f / this.columns
            val u0 = cx.toFloat() / this.columns
            val u1 = (cx + 1f) / this.columns
            // texture V axis: row 0 is the top row of the sheet
            val v0 = 1f - cy.toFloat() / this.rows
            val v1 = 1f - (cy + 1f) / this.rows
            frames.add(AnimationFrame(u0, v0, u1, v1))
        }
    }

    /** UV rect of frame [index]; wraps around for looping clips. */
    fun frameAt(index: Int): AnimationFrame? {
        val n = frameCount
        if (n <= 0) return null
        if (frames.isEmpty()) buildGrid(columns, rows)
        val i = ((index % n) + n) % n
        return frames.getOrNull(i)
    }

    /** Normalised time -> frame index according to the loop mode. */
    fun frameIndexAt(time: Float): Int {
        val n = frameCount
        if (n <= 0) return 0
        val t = time * fps * speed
        return when (loop) {
            LoopMode.LOOP -> ((t.toInt() % n) + n) % n
            LoopMode.ONCE -> t.toInt().coerceIn(0, n - 1)
            LoopMode.PING_PONG -> {
                val period = (n - 1) * 2
                if (period <= 0) 0 else {
                    val p = ((t.toInt() % period) + period) % period
                    if (p < n) p else period - p
                }
            }
        }
    }

    fun normalizedTime(time: Float): Float = if (duration <= 0f) 0f else M.clamp01(time / duration)

    fun addEvent(time: Float, name: String, value: String = ""): AnimationClip {
        events.add(AnimationEvent(time, name, value))
        events.sortBy { it.time }
        return this
    }

    fun addTrack(target: String, curve: AnimationCurve): AnimationClip {
        tracks.add(TimelineTrack(target, curve))
        return this
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("name", name)
        o.put("texture", texture)
        o.put("columns", columns)
        o.put("rows", rows)
        o.put("fps", fps.toDouble())
        o.put("loop", loop.label)
        o.put("speed", speed.toDouble())
        val farr = JSONArray()
        for (f in frames) {
            farr.put(JSONObject().apply {
                put("u0", f.u0.toDouble()); put("v0", f.v0.toDouble())
                put("u1", f.u1.toDouble()); put("v1", f.v1.toDouble())
                put("ox", f.offsetX.toDouble()); put("oy", f.offsetY.toDouble())
                if (f.duration > 0f) put("dur", f.duration.toDouble())
            })
        }
        o.put("frames", farr)
        val earr = JSONArray()
        for (e in events) earr.put(JSONObject().apply { put("t", e.time.toDouble()); put("name", e.name); put("value", e.value) })
        o.put("events", earr)
        val tarr = JSONArray()
        for (t in tracks) tarr.put(t.toJson())
        o.put("tracks", tarr)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): AnimationClip {
            val c = AnimationClip(o.optString("name", "Animation"))
            c.texture = o.optString("texture", "")
            c.columns = o.optInt("columns", 4)
            c.rows = o.optInt("rows", 4)
            c.fps = o.optDouble("fps", 12.0).toFloat()
            c.loop = LoopMode.entries.firstOrNull { it.label == o.optString("loop", "Loop") } ?: LoopMode.LOOP
            c.speed = o.optDouble("speed", 1.0).toFloat()
            o.optJSONArray("frames")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val f = arr.optJSONObject(i) ?: continue
                    c.frames.add(
                        AnimationFrame(
                            f.optDouble("u0", 0.0).toFloat(), f.optDouble("v0", 1.0).toFloat(),
                            f.optDouble("u1", 1.0).toFloat(), f.optDouble("v1", 0.0).toFloat(),
                            f.optDouble("ox", 0.0).toFloat(), f.optDouble("oy", 0.0).toFloat(),
                            f.optDouble("dur", 0.0).toFloat()
                        )
                    )
                }
            }
            o.optJSONArray("events")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    c.addEvent(e.optDouble("t", 0.0).toFloat(), e.optString("name", ""), e.optString("value", ""))
                }
            }
            o.optJSONArray("tracks")?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { c.tracks.add(TimelineTrack.fromJson(it)) }
            }
            if (c.frames.isEmpty() && c.columns > 0 && c.rows > 0) c.buildGrid(c.columns, c.rows)
            return c
        }

        /** Convenience: a one-row strip of frames from a sheet with [columns] columns. */
        fun strip(name: String, texture: String, columns: Int, rows: Int, row: Int = 0, fps: Float = 12f): AnimationClip {
            val c = AnimationClip(name)
            c.texture = texture
            c.fps = fps
            c.buildGrid(columns, rows)
            val keep = ArrayList<AnimationFrame>()
            for (i in 0 until columns) {
                val idx = row * columns + i
                c.frames.getOrNull(idx)?.let { keep.add(it) }
            }
            if (keep.isNotEmpty()) c.frames = keep
            c.columns = maxOf(1, keep.size)
            c.rows = 1
            return c
        }
    }
}
