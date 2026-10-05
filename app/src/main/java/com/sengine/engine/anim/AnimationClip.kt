package com.sengine.engine.anim

import com.sengine.engine.core.Animator
import com.sengine.engine.core.SpriteRenderer
import com.sengine.project.Project
import org.json.JSONArray
import org.json.JSONObject

/** Sprite-sheet animation clip stored as a small JSON `.anim` asset. */
class AnimationClip(
    var texture: String = "",
    var columns: Int = 1,
    var rows: Int = 1,
    var frames: MutableList<Int> = mutableListOf(0),
    var fps: Float = 10f,
    var loop: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("texture", texture).put("columns", columns).put("rows", rows)
        .put("frames", JSONArray(frames)).put("fps", fps.toDouble()).put("loop", loop)

    val duration: Float get() = if (fps <= 0f) 0f else frames.size / fps

    /** UV rectangle for a sheet cell: u0, vBottom, u1, vTop. */
    fun cellUv(cell: Int, out: FloatArray) {
        val c = columns.coerceAtLeast(1)
        val r = rows.coerceAtLeast(1)
        val col = cell % c
        val row = (cell / c).coerceAtMost(r - 1)
        out[0] = col.toFloat() / c
        out[2] = (col + 1f) / c
        out[1] = (row + 1f) / r
        out[3] = row.toFloat() / r
    }

    companion object {
        fun fromJson(o: JSONObject): AnimationClip {
            val arr = o.optJSONArray("frames") ?: JSONArray()
            val frames = MutableList(arr.length()) { arr.getInt(it) }
            if (frames.isEmpty()) frames.add(0)
            return AnimationClip(
                o.optString("texture", ""), o.optInt("columns", 1).coerceAtLeast(1), o.optInt("rows", 1).coerceAtLeast(1),
                frames, o.optDouble("fps", 10.0).toFloat(), o.optBoolean("loop", true)
            )
        }

        /** Parses "0-3, 5, 7-6" into frame indices. */
        fun parseFrames(s: String): MutableList<Int> {
            val out = mutableListOf<Int>()
            for (part in s.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }) {
                val range = part.split('-')
                if (range.size == 2) {
                    val a = range[0].toIntOrNull() ?: continue
                    val b = range[1].toIntOrNull() ?: continue
                    if (a <= b) for (i in a..b) out.add(i) else for (i in a downTo b) out.add(i)
                } else part.toIntOrNull()?.let { out.add(it) }
            }
            return out
        }

        fun formatFrames(frames: List<Int>): String {
            if (frames.isEmpty()) return ""
            val parts = ArrayList<String>()
            var start = frames[0]
            var prev = frames[0]
            for (i in 1..frames.size) {
                val f = if (i < frames.size) frames[i] else Int.MIN_VALUE
                if (f == prev + 1) { prev = f; continue }
                parts.add(if (start == prev) "$start" else "$start-$prev")
                start = f; prev = f
            }
            return parts.joinToString(", ")
        }
    }
}

/** Loads clips (cached by modification time) and drives Animator components. */
class AnimationSystem(private val project: Project) {
    private val cache = HashMap<String, Pair<Long, AnimationClip>>()

    fun clip(name: String): AnimationClip? {
        if (name.isBlank()) return null
        val n = if (name.endsWith(".anim")) name else "$name.anim"
        val f = project.assetFile(n)
        if (!f.exists()) return null
        val stamp = f.lastModified()
        cache[n]?.let { if (it.first == stamp) return it.second }
        return try {
            AnimationClip.fromJson(JSONObject(f.readText())).also { cache[n] = stamp to it }
        } catch (e: Exception) { null }
    }

    fun update(anim: Animator, sr: SpriteRenderer, dt: Float, playing: Boolean) {
        val name = if (playing) anim.current else anim.clip
        val c = clip(name)
        if (c == null) { sr.resetUv(); return }
        if (playing && anim.playing && anim.enabled) {
            anim.time += dt * anim.speed
            val total = c.frames.size
            var idx = (anim.time * c.fps).toInt()
            if (c.loop) idx = Math.floorMod(idx, total)
            else if (idx >= total) { idx = total - 1; anim.finished = true }
            anim.frame = idx.coerceIn(0, total - 1)
        } else if (!playing) anim.frame = 0
        sr.animTexture = c.texture.ifBlank { null }
        c.cellUv(c.frames[anim.frame.coerceIn(0, c.frames.size - 1)], sr.uv)
    }
}
