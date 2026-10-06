package com.sengine.project

import com.sengine.engine.anim.AnimationClip
import org.json.JSONObject

/**
 * Loads and saves animation clips (`.anim` assets) for the engine's animation system and for the
 * animation editor. Clips are cached per project path; [invalidate] drops the cache after edits.
 */
object ClipLibrary {

    private val cache = HashMap<String, AnimationClip?>()

    /** Resolves `name` (with or without extension) against the project's asset folder. */
    fun clip(project: Project, name: String): AnimationClip? {
        if (name.isBlank()) return null
        val key = "${project.dir.absolutePath}/$name"
        if (cache.containsKey(key)) return cache[key]
        val raw = project.readAsset(name)
            ?: project.readAsset("$name.anim")
            ?: project.readAsset("animations/$name")
            ?: project.readAsset("animations/$name.anim")
        val clip = raw?.let { parse(it) }
        cache[key] = clip
        return clip
    }

    fun invalidate(name: String? = null) {
        if (name == null) {
            cache.clear()
        } else {
            val key = name.removeSuffix(".anim")
            cache.keys.removeAll { it.substringAfterLast('/').removeSuffix(".anim") == key }
        }
    }

    /** Parses a clip; returns null when the JSON is not a valid clip (never throws). */
    fun parse(raw: String): AnimationClip? = try {
        AnimationClip.fromJson(JSONObject(raw))
    } catch (e: Exception) {
        null
    }

    fun toJson(clip: AnimationClip): String = clip.toJson().toString(2)

    /** Writes a clip into the project as `<name>.anim` and drops the cached copy. */
    fun save(project: Project, clip: AnimationClip): Boolean = try {
        if (clip.name.isBlank()) clip.name = "Animation"
        project.writeAsset("${clip.name}.anim", toJson(clip))
        invalidate("${clip.name}.anim")
        true
    } catch (e: Exception) {
        false
    }

    fun list(project: Project): List<String> =
        (project.assetsDir.listFiles() ?: emptyArray())
            .filter { it.name.endsWith(".anim") }
            .map { it.name }
            .sorted()

    /** Creates and saves a grid clip for a sprite sheet (used by the sprite-sheet importer). */
    fun createGridClip(
        project: Project, name: String, texture: String, columns: Int, rows: Int, fps: Float
    ): AnimationClip {
        val clip = AnimationClip(name)
        clip.texture = texture
        clip.buildGrid(columns, rows)
        clip.fps = fps
        save(project, clip)
        return clip
    }

    /** A minimal clip template written by the animation editor's "New clip" command. */
    fun template(name: String, texture: String = ""): AnimationClip {
        val clip = AnimationClip(name)
        clip.texture = texture
        clip.columns = 4
        clip.rows = 4
        clip.fps = 12f
        clip.buildGrid(4, 4)
        return clip
    }
}
