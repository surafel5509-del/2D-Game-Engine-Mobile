package com.sengine.engine.tilemap

import com.sengine.engine.math.M
import com.sengine.engine.math.Rect2
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * A tileset: one texture plus tile metrics and per-tile properties (solid, one-way, slope,
 * animated, damage...). Strictly 2D.
 */
class Tileset(var name: String = "Tiles", var texture: String = "") {
    var tileWidth = 16
    var tileHeight = 16
    var margin = 0
    var spacing = 0

    /** Collision flag per tile index. */
    val solid = HashSet<Int>()
    val oneWay = HashSet<Int>()
    /** Slope tiles: index -> 0 flat, 1 = 45° up-right, 2 = 45° up-left, 3 = shallow up-right, 4 = shallow up-left. */
    val slopes = HashMap<Int, Int>()
    /** Animated tiles: index -> list of frames (tile indices) + fps. */
    val animated = HashMap<Int, IntArray>()
    var animationFps = 4f
    /** Tile properties by index ("terrain material" for vehicle traction/friction). */
    val friction = HashMap<Int, Float>()
    val tags = HashMap<Int, String>()
    /** Damage dealt when touched (spikes/lava). */
    val damage = HashMap<Int, Float>()

    fun isSolid(index: Int) = index in solid
    fun isOneWay(index: Int) = index in oneWay
    fun slopeOf(index: Int) = slopes[index] ?: 0

    fun columns(textureWidth: Int): Int =
        if (tileWidth <= 0) 1 else max(1, (textureWidth - margin * 2 + spacing) / (tileWidth + spacing))

    fun uvFor(index: Int, textureWidth: Int, textureHeight: Int): FloatArray {
        val cols = max(1, (textureWidth - margin * 2 + spacing) / (tileWidth + spacing))
        val cx = index % cols
        val cy = index / cols
        val x0 = margin + cx * (tileWidth + spacing)
        val y0 = margin + cy * (tileHeight + spacing)
        val u0 = x0.toFloat() / textureWidth
        val u1 = (x0 + tileWidth).toFloat() / textureWidth
        val v1 = 1f - y0.toFloat() / textureHeight
        val v0 = 1f - (y0 + tileHeight).toFloat() / textureHeight
        return floatArrayOf(u0, v0, u1, v1)
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("name", name)
        o.put("texture", texture)
        o.put("tileWidth", tileWidth)
        o.put("tileHeight", tileHeight)
        o.put("margin", margin)
        o.put("spacing", spacing)
        o.put("solid", JSONArray(solid.toList().sorted()))
        o.put("oneWay", JSONArray(oneWay.toList().sorted()))
        val sl = JSONObject()
        slopes.forEach { (k, v) -> sl.put(k.toString(), v) }
        o.put("slopes", sl)
        val an = JSONObject()
        animated.forEach { (k, v) -> an.put(k.toString(), JSONArray(v.toList())) }
        o.put("animated", an)
        o.put("animationFps", animationFps.toDouble())
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Tileset {
            val t = Tileset(o.optString("name", "Tiles"), o.optString("texture", ""))
            t.tileWidth = o.optInt("tileWidth", 16)
            t.tileHeight = o.optInt("tileHeight", 16)
            t.margin = o.optInt("margin", 0)
            t.spacing = o.optInt("spacing", 0)
            o.optJSONArray("solid")?.let { a -> for (i in 0 until a.length()) t.solid.add(a.optInt(i)) }
            o.optJSONArray("oneWay")?.let { a -> for (i in 0 until a.length()) t.oneWay.add(a.optInt(i)) }
            o.optJSONObject("slopes")?.let { obj ->
                obj.keys().forEach { k -> t.slopes[k.toIntOrNull() ?: return@forEach] = obj.optInt(k) }
            }
            o.optJSONObject("animated")?.let { obj ->
                obj.keys().forEach { k ->
                    val idx = k.toIntOrNull() ?: return@forEach
                    val arr = obj.optJSONArray(k) ?: return@forEach
                    val frames = IntArray(arr.length()) { arr.optInt(it) }
                    t.animated[idx] = frames
                }
            }
            t.animationFps = o.optDouble("animationFps", 4.0).toFloat()
            return t
        }
    }
}

/**
 * A single tilemap layer: a sparse grid of tile indices.
 * Supports unbounded (infinite) coordinates through chunked storage.
 */
class TileLayer(var name: String = "Layer", var tilesetIndex: Int = 0) {
    /** Chunk size in tiles. */
    var chunkSize = 32
    /** chunkKey -> (localIndex -> tileIndex + 1) */
    private val chunks = HashMap<Long, IntArray>()
    var visible = true
    var opacity = 1f
    /** Vertical parallax factor for background layers. */
    var parallaxX = 0f
    var parallaxY = 0f
    /** Layer tint. */
    var tint = 0xFFFFFFFF.toInt()
    var collision = true
    /** Sorting layer used when rendering. */
    var sortingLayer = "Default"
    var order = 0

    var minChunkX = 0; var maxChunkX = 0
    var minChunkY = 0; var maxChunkY = 0
    private var boundsValid = false

    fun chunkKey(cx: Int, cy: Int): Long = (cx.toLong() shl 32) xor (cy.toLong() and 0xFFFFFFFFL)

    fun chunkOf(tx: Int): Int = if (tx >= 0) tx / chunkSize else -((-tx + chunkSize - 1) / chunkSize)
    fun localOf(tx: Int): Int = tx - chunkOf(tx) * chunkSize

    /** Gets the tile index (0 = empty, also for tiles whose chunk exists but holds no tile). */
    fun get(tx: Int, ty: Int): Int {
        val chunk = chunks[chunkKey(chunkOf(tx), chunkOf(ty))] ?: return 0
        val i = localOf(ty) * chunkSize + localOf(tx)
        val stored = chunk[i]
        return if (stored <= 0) 0 else stored - 1
    }

    /** Sets a tile (0 clears it). */
    fun set(tx: Int, ty: Int, id: Int) {
        val key = chunkKey(chunkOf(tx), chunkOf(ty))
        var chunk = chunks[key]
        if (chunk == null) {
            if (id == 0) return
            chunk = IntArray(chunkSize * chunkSize)
            chunks[key] = chunk
            val cx = chunkOf(tx); val cy = chunkOf(ty)
            if (!boundsValid) {
                minChunkX = cx; maxChunkX = cx; minChunkY = cy; maxChunkY = cy; boundsValid = true
            } else {
                minChunkX = min(minChunkX, cx); maxChunkX = max(maxChunkX, cx)
                minChunkY = min(minChunkY, cy); maxChunkY = max(maxChunkY, cy)
            }
        }
        chunk[localOf(ty) * chunkSize + localOf(tx)] = id + 1
        if (id == 0 && chunk.all { it == 0 }) {
            chunks.remove(key)
        }
    }

    fun isEmpty(): Boolean = chunks.isEmpty()

    /** Tile-space bounds of all painted tiles (may be empty). */
    fun bounds(): Rect2 {
        if (chunks.isEmpty()) return Rect2(0f, 0f, 0f, 0f)
        var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE; var maxY = Int.MIN_VALUE
        for (key in chunks.keys) {
            val cx = (key shr 32).toInt()
            val cy = (key and 0xFFFFFFFFL).toInt()
            minX = min(minX, cx * chunkSize); maxX = max(maxX, cx * chunkSize + chunkSize)
            minY = min(minY, cy * chunkSize); maxY = max(maxY, cy * chunkSize + chunkSize)
        }
        return Rect2(minX.toFloat(), minY.toFloat(), (maxX - minX).toFloat(), (maxY - minY).toFloat())
    }

    /**
     * Iterates the painted tiles inside [region] (tile coordinates). This is intentionally not
     * `inline`: the chunk storage stays private and the hot paths in the renderer and collision
     * builder use [chunkAt] instead.
     */
    fun forEachIn(region: Rect2, action: (tx: Int, ty: Int, id: Int) -> Unit) {
        val x0 = region.left.toInt() - 1
        val x1 = region.right.toInt() + 1
        val y0 = region.top.toInt() - 1
        val y1 = region.bottom.toInt() + 1
        val cx0 = chunkOf(x0); val cx1 = chunkOf(x1)
        val cy0 = chunkOf(y0); val cy1 = chunkOf(y1)
        for (cx in cx0..cx1) for (cy in cy0..cy1) {
            val chunk = chunks[chunkKey(cx, cy)] ?: continue
            for (ly in 0 until chunkSize) {
                for (lx in 0 until chunkSize) {
                    val id = chunk[ly * chunkSize + lx] - 1
                    if (id < 0) continue
                    val tx = cx * chunkSize + lx
                    val ty = cy * chunkSize + ly
                    if (tx < x0 || tx > x1 || ty < y0 || ty > y1) continue
                    action(tx, ty, id)
                }
            }
        }
    }

    fun chunkCount() = chunks.size

    fun clear() {
        chunks.clear()
        boundsValid = false
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("name", name)
        o.put("tileset", tilesetIndex)
        o.put("visible", visible)
        o.put("opacity", opacity.toDouble())
        o.put("collision", collision)
        o.put("parallaxX", parallaxX.toDouble())
        o.put("parallaxY", parallaxY.toDouble())
        o.put("tint", String.format("#%08X", tint))
        o.put("sortingLayer", sortingLayer)
        o.put("order", order)
        // compact run-length encoding per chunk: "cx,cy,count,id1,count,id2,..."
        val data = StringBuilder()
        var first = true
        for ((key, chunk) in chunks) {
            val cx = (key shr 32).toInt()
            val cy = (key and 0xFFFFFFFFL).toInt()
            if (!first) data.append(';')
            first = false
            data.append(cx).append(',').append(cy)
            var i = 0
            while (i < chunk.size) {
                val v = chunk[i]
                var run = 1
                while (i + run < chunk.size && chunk[i + run] == v) run++
                data.append(',').append(run).append(',').append(v - 1)
                i += run
            }
        }
        o.put("data", data.toString())
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): TileLayer {
            val l = TileLayer(o.optString("name", "Layer"), o.optInt("tileset", 0))
            l.visible = o.optBoolean("visible", true)
            l.opacity = o.optDouble("opacity", 1.0).toFloat()
            l.collision = o.optBoolean("collision", true)
            l.parallaxX = o.optDouble("parallaxX", 0.0).toFloat()
            l.parallaxY = o.optDouble("parallaxY", 0.0).toFloat()
            l.tint = com.sengine.engine.math.Colors.parse(o.optString("tint", "#FFFFFFFF"))
            l.sortingLayer = o.optString("sortingLayer", "Default")
            l.order = o.optInt("order", 0)
            val data = o.optString("data", "")
            if (data.isNotEmpty()) {
                for (chunkStr in data.split(';')) {
                    if (chunkStr.isBlank()) continue
                    val parts = chunkStr.split(',')
                    if (parts.size < 4) continue
                    val cx = parts[0].toIntOrNull() ?: continue
                    val cy = parts[1].toIntOrNull() ?: continue
                    val key = l.chunkKey(cx, cy)
                    val chunk = IntArray(l.chunkSize * l.chunkSize)
                    var i = 2
                    var write = 0
                    while (i + 1 < parts.size) {
                        val run = parts[i].toIntOrNull() ?: break
                        val id = parts[i + 1].toIntOrNull() ?: break
                        for (r in 0 until run) {
                            if (write < chunk.size) chunk[write++] = id + 1
                        }
                        i += 2
                    }
                    if (chunk.any { it != 0 }) {
                        l.chunks[key] = chunk
                        if (!l.boundsValid) {
                            l.minChunkX = cx; l.maxChunkX = cx; l.minChunkY = cy; l.maxChunkY = cy; l.boundsValid = true
                        } else {
                            l.minChunkX = min(l.minChunkX, cx); l.maxChunkX = max(l.maxChunkX, cx)
                            l.minChunkY = min(l.minChunkY, cy); l.maxChunkY = max(l.maxChunkY, cy)
                        }
                    }
                }
            }
            return l
        }
    }
}

/**
 * Runtime tilemap data: tilesets + layers with chunked (infinite) storage, autotiling and
 * collision generation.
 */
class TilemapData(var name: String = "Tilemap") {
    val tilesets = ArrayList<Tileset>()
    val layers = ArrayList<TileLayer>()
    var tileWidth = 16
    var tileHeight = 16
    /** Pixels per world unit for the tileset art (16px tiles with ppu 16 => 1 unit per tile). */
    var pixelsPerUnit = 16f
    /** Extra chunk padding kept in memory (streaming). */
    var streamingEnabled = false
    var chunkMargin = 2

    fun layer(name: String, tilesetIndex: Int = 0): TileLayer {
        val l = layers.firstOrNull { it.name == name }
        if (l != null) return l
        val created = TileLayer(name, tilesetIndex)
        layers.add(created)
        return created
    }

    fun addLayer(layer: TileLayer): TileLayer {
        layers.add(layer)
        return layer
    }

    fun removeLayer(layer: TileLayer) = layers.remove(layer)

    fun tilesetOf(layer: TileLayer): Tileset? = tilesets.getOrNull(layer.tilesetIndex)

    fun worldSizeOfTile(): Float = tileWidth / pixelsPerUnit

    /** Converts a world position to tile coordinates. */
    fun tileX(worldX: Float, originX: Float = 0f): Int {
        val size = worldSizeOfTile()
        return M.floorI((worldX - originX) / size)
    }

    fun tileY(worldY: Float, originY: Float = 0f): Int {
        val size = worldSizeOfTile()
        return M.floorI((worldY - originY) / size)
    }

    fun worldX(tileX: Int, originX: Float = 0f) = originX + tileX * worldSizeOfTile()
    fun worldY(tileY: Int, originY: Float = 0f) = originY + tileY * worldSizeOfTile()

    fun isEmpty() = layers.all { it.isEmpty() }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("name", name)
        o.put("tileWidth", tileWidth)
        o.put("tileHeight", tileHeight)
        o.put("pixelsPerUnit", pixelsPerUnit.toDouble())
        val ts = JSONArray()
        tilesets.forEach { ts.put(it.toJson()) }
        o.put("tilesets", ts)
        val ls = JSONArray()
        layers.forEach { ls.put(it.toJson()) }
        o.put("layers", ls)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): TilemapData {
            val m = TilemapData(o.optString("name", "Tilemap"))
            m.tileWidth = o.optInt("tileWidth", 16)
            m.tileHeight = o.optInt("tileHeight", 16)
            m.pixelsPerUnit = o.optDouble("pixelsPerUnit", 16.0).toFloat()
            o.optJSONArray("tilesets")?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { m.tilesets.add(Tileset.fromJson(it)) }
            }
            o.optJSONArray("layers")?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { m.layers.add(TileLayer.fromJson(it)) }
            }
            if (m.tilesets.isEmpty()) m.tilesets.add(Tileset())
            return m
        }
    }
}
