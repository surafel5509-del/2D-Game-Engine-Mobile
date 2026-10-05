package com.sengine.engine.resource

import com.sengine.engine.core.AssetKind
import com.sengine.project.Project
import java.io.File

/**
 * Resource management system for loading, caching, and managing assets.
 * Provides async loading, reference counting, and memory management.
 */
class ResourceManager(private val project: Project) {
    private val cache = HashMap<String, CachedResource>()
    private val loadQueue = ArrayDeque<LoadRequest>()
    private var loading = false

    data class CachedResource(
        val path: String,
        val kind: AssetKind?,
        var data: Any? = null,
        var refCount: Int = 0,
        var loadedAt: Long = System.currentTimeMillis(),
        var lastAccess: Long = System.currentTimeMillis(),
        var sizeBytes: Long = 0
    )

    data class LoadRequest(
        val path: String,
        val callback: ((Any?) -> Unit)? = null
    )

    /** Get a resource, loading if necessary. */
    fun get(path: String): Any? {
        val cached = cache[path]
        if (cached != null) {
            cached.refCount++
            cached.lastAccess = System.currentTimeMillis()
            return cached.data
        }
        return loadImmediate(path)
    }

    /** Load a resource synchronously. */
    fun loadImmediate(path: String): Any? {
        val file = project.assetFile(path)
        if (!file.exists()) return null
        val kind = AssetKind.of(path)
        val data: Any? = when (kind) {
            AssetKind.TEXTURE -> loadImageData(file)
            AssetKind.SCRIPT -> loadScript(file)
            AssetKind.SOUND -> loadSoundData(file)
            null -> loadRaw(file)
        }
        val resource = CachedResource(path, kind, data, 1, sizeBytes = file.length())
        cache[path] = resource
        return data
    }

    /** Queue a resource for async loading. */
    fun loadAsync(path: String, callback: ((Any?) -> Unit)? = null) {
        loadQueue.add(LoadRequest(path, callback))
    }

    /** Process the load queue (call periodically or on a background thread). */
    fun processLoadQueue(maxPerFrame: Int = 2): Int {
        var processed = 0
        while (processed < maxPerFrame && loadQueue.isNotEmpty()) {
            val req = loadQueue.removeFirst()
            val data = loadImmediate(req.path)
            req.callback?.invoke(data)
            processed++
        }
        return processed
    }

    /** Release a reference to a resource. */
    fun release(path: String) {
        val cached = cache[path] ?: return
        cached.refCount--
        if (cached.refCount <= 0) {
            cache.remove(path)
        }
    }

    /** Force release all cached resources. */
    fun releaseAll() {
        cache.clear()
    }

    /** Get total memory used by cached resources. */
    fun totalMemoryBytes(): Long = cache.values.sumOf { it.sizeBytes }

    /** Get total memory in MB. */
    fun totalMemoryMB(): Float = totalMemoryBytes() / 1_048_576f

    /** Prune resources not accessed within the given time (ms). */
    fun pruneOlderThan(maxAgeMs: Long) {
        val now = System.currentTimeMillis()
        val toRemove = cache.entries.filter { now - it.value.lastAccess > maxAgeMs && it.value.refCount <= 0 }
        for (entry in toRemove) cache.remove(entry.key)
    }

    /** Get cache statistics. */
    fun stats(): ResourceStats {
        return ResourceStats(
            totalCached = cache.size,
            totalMemoryBytes = totalMemoryBytes(),
            queuedLoads = loadQueue.size,
            byKind = AssetKind.values().associate { kind ->
                kind.name to cache.values.count { it.kind == kind }
            }
        )
    }

    /** List all available assets of a kind. */
    fun listAssets(kind: AssetKind? = null): List<String> = project.listAssets(kind)

    /** Check if an asset exists. */
    fun exists(path: String): Boolean = project.assetFile(path).exists()

    /** Preload all assets of a given kind. */
    fun preloadAll(kind: AssetKind) {
        for (name in project.listAssets(kind)) {
            loadImmediate(name)
        }
    }

    // --- Loaders ---

    private fun loadImageData(file: File): Any? {
        // Return the file path; actual texture loading happens in TextureCache on GL thread
        return file.absolutePath
    }

    private fun loadScript(file: File): String? {
        return try { file.readText() } catch (_: Exception) { null }
    }

    private fun loadSoundData(file: File): Any? {
        return file.absolutePath
    }

    private fun loadRaw(file: File): ByteArray? {
        return try { file.readBytes() } catch (_: Exception) { null }
    }

    data class ResourceStats(
        val totalCached: Int,
        val totalMemoryBytes: Long,
        val queuedLoads: Int,
        val byKind: Map<String, Int>
    )
}

/**
 * Texture atlas manager for efficient sprite rendering.
 * Groups multiple sprites into a single texture for batch rendering.
 */
class TextureAtlasManager {
    data class AtlasRegion(
        val atlasName: String,
        val u0: Float, val v0: Float,
        val u1: Float, val v1: Float,
        val pixelWidth: Int,
        val pixelHeight: Int
    )

    private val atlases = HashMap<String, Atlas>()
    private val regions = HashMap<String, AtlasRegion>()

    data class Atlas(
        val name: String,
        val textureName: String,
        val width: Int,
        val height: Int,
        val regions: HashMap<String, AtlasRegion> = HashMap()
    )

    /** Define an atlas from a sprite sheet. */
    fun defineAtlas(name: String, textureName: String, width: Int, height: Int,
                    spriteWidth: Int, spriteHeight: Int, spriteNames: List<String>? = null): Atlas {
        val cols = width / spriteWidth
        val rows = height / spriteHeight
        val atlas = Atlas(name, textureName, width, height)
        var idx = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val spriteName = spriteNames?.getOrElse(idx) { "${name}_$idx" } ?: "${name}_${r}_$c"
                val region = AtlasRegion(
                    name,
                    (c * spriteWidth).toFloat() / width,
                    (r * spriteHeight).toFloat() / height,
                    ((c + 1) * spriteWidth).toFloat() / width,
                    ((r + 1) * spriteHeight).toFloat() / height,
                    spriteWidth, spriteHeight
                )
                atlas.regions[spriteName] = region
                regions[spriteName] = region
                idx++
            }
        }
        atlases[name] = atlas
        return atlas
    }

    /** Get a region by sprite name. */
    fun getRegion(spriteName: String): AtlasRegion? = regions[spriteName]

    /** Get all regions in an atlas. */
    fun getAtlasRegions(atlasName: String): Collection<AtlasRegion>? = atlases[atlasName]?.regions?.values

    /** Get the texture name for a region. */
    fun getTextureForRegion(spriteName: String): String? {
        val region = regions[spriteName] ?: return null
        return atlases[region.atlasName]?.textureName
    }
}

/**
 * Prefab system for reusable game object templates.
 */
class PrefabManager(private val project: Project) {
    private val prefabsDir = File(project.dir, "prefabs").also { it.mkdirs() }
    private val cache = HashMap<String, String>()

    /** Save a prefab (serialized JSON). */
    fun savePrefab(name: String, json: String) {
        val file = File(prefabsDir, "$name.prefab.json")
        file.writeText(json)
        cache[name] = json
    }

    /** Load a prefab. */
    fun loadPrefab(name: String): String? {
        cache[name]?.let { return it }
        val file = File(prefabsDir, "$name.prefab.json")
        if (!file.exists()) return null
        val json = file.readText()
        cache[name] = json
        return json
    }

    /** List all available prefabs. */
    fun listPrefabs(): List<String> {
        return (prefabsDir.listFiles() ?: emptyArray())
            .filter { it.name.endsWith(".prefab.json") }
            .map { it.name.removeSuffix(".prefab.json") }
            .sorted()
    }

    /** Delete a prefab. */
    fun deletePrefab(name: String) {
        File(prefabsDir, "$name.prefab.json").delete()
        cache.remove(name)
    }
}
