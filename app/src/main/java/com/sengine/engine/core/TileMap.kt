package com.sengine.engine.core

import com.sengine.engine.math.Affine
import com.sengine.engine.render.Renderer2D
import com.sengine.engine.render.View2D
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/**
 * TileMap component for grid-based level design.
 * Supports multiple layers, tile collision, auto-tiling, and terrain.
 */
class TileMap : Component() {
    override val type = "TileMap"

    // Grid configuration
    var tileWidth = 1f
    var tileHeight = 1f
    var columns = 32
    var rows = 32

    // Tileset atlas
    var tilesetTexture = ""
    var tilesetColumns = 16 // Number of tile columns in the atlas
    var tilesetRows = 16

    // Layers (bottom to top)
    val layers = ArrayList<TileLayer>()

    // Runtime
    var needsRebuild = true

    override fun props() = listOf(
        Prop.F("Tile Width", { tileWidth }, { tileWidth = it.coerceAtLeast(0.1f) }),
        Prop.F("Tile Height", { tileHeight }, { tileHeight = it.coerceAtLeast(0.1f) }),
        Prop.I("Columns", { columns }, { columns = it.coerceIn(1, 1024) }),
        Prop.I("Rows", { rows }, { rows = it.coerceIn(1, 1024) }),
        Prop.Asset("Tileset", AssetKind.TEXTURE, { tilesetTexture }, { tilesetTexture = it }),
        Prop.I("Tileset Columns", { tilesetColumns }, { tilesetColumns = it.coerceIn(1, 256) }),
        Prop.I("Tileset Rows", { tilesetRows }, { tilesetRows = it.coerceIn(1, 256) }),
    )

    override fun resetRuntime() {
        needsRebuild = true
    }

    /** Add a new layer. */
    fun addLayer(name: String): TileLayer {
        val layer = TileLayer(name, columns, rows)
        layers.add(layer)
        needsRebuild = true
        return layer
    }

    /** Get a layer by name. */
    fun getLayer(name: String): TileLayer? = layers.firstOrNull { it.name == name }

    /** Get the total number of tiles across all layers. */
    fun totalTiles(): Int = layers.sumOf { it.nonEmptyTiles() }

    /** Set a tile at grid position. */
    fun setTile(layerName: String, col: Int, row: Int, tileId: Int) {
        val layer = getLayer(layerName) ?: return
        if (col !in 0 until columns || row !in 0 until rows) return
        layer.setTile(col, row, tileId)
        needsRebuild = true
    }

    /** Get a tile ID at grid position. */
    fun getTile(layerName: String, col: Int, row: Int): Int {
        return getLayer(layerName)?.getTile(col, row) ?: -1
    }

    /** Convert world position to grid coordinates. */
    fun worldToGrid(worldX: Float, worldY: Float): Pair<Int, Int> {
        val go = gameObject
        val col = ((worldX - go.x) / tileWidth).floor().toInt()
        val row = ((worldY - go.y) / tileHeight).floor().toInt()
        return col to row
    }

    /** Convert grid coordinates to world position (center of tile). */
    fun gridToWorld(col: Int, row: Int): Pair<Float, Float> {
        val go = gameObject
        val wx = go.x + (col + 0.5f) * tileWidth
        val wy = go.y + (row + 0.5f) * tileHeight
        return wx to wy
    }

    /** Fill a rectangular area with a tile. */
    fun fillRect(layerName: String, startCol: Int, startRow: Int, endCol: Int, endRow: Int, tileId: Int) {
        val layer = getLayer(layerName) ?: return
        val c0 = max(startCol, 0); val c1 = min(endCol, columns - 1)
        val r0 = max(startRow, 0); val r1 = min(endRow, rows - 1)
        for (r in r0..r1) for (c in c0..c1) layer.setTile(c, r, tileId)
        needsRebuild = true
    }

    /** Clear all tiles in a layer. */
    fun clearLayer(layerName: String) {
        getLayer(layerName)?.clear()
        needsRebuild = true
    }

    /** Check if a grid position has a solid (non-empty, non-trigger) tile. */
    fun isSolid(layerName: String, col: Int, row: Int): Boolean {
        val layer = getLayer(layerName) ?: return false
        val tile = layer.getTile(col, row)
        return tile > 0 && !layer.isTriggerTile(tile)
    }

    /** Generate collision shapes from the tilemap. Returns list of AABB colliders. */
    fun generateCollisionColliders(layerName: String): List<TileCollisionRect> {
        val layer = getLayer(layerName) ?: return emptyList()
        val rects = ArrayList<TileCollisionRect>()
        // Simple greedy merge of adjacent solid tiles into horizontal strips
        var r = 0
        while (r < rows) {
            var c = 0
            while (c < columns) {
                if (layer.isSolidTile(layer.getTile(c, r))) {
                    // Find run of solid tiles in this row
                    var endC = c
                    while (endC + 1 < columns && layer.isSolidTile(layer.getTile(endC + 1, r))) endC++
                    // Try to extend downward
                    var endR = r
                    outer@ while (endR + 1 < rows) {
                        for (cc in c..endC) {
                            if (!layer.isSolidTile(layer.getTile(cc, endR + 1))) break@outer
                        }
                        endR++
                    }
                    rects.add(TileCollisionRect(c, r, endC - c + 1, endR - r + 1))
                    c = endC + 1
                } else c++
            }
            r++
        }
        return rects
    }

    /** Serialize tilemap to JSON. */
    fun toMapJson(): JSONObject {
        val o = JSONObject()
        o.put("tileWidth", tileWidth.toDouble())
        o.put("tileHeight", tileHeight.toDouble())
        o.put("columns", columns)
        o.put("rows", rows)
        o.put("tilesetTexture", tilesetTexture)
        o.put("tilesetColumns", tilesetColumns)
        o.put("tilesetRows", tilesetRows)
        val layerArr = JSONArray()
        for (l in layers) layerArr.put(l.toJson())
        o.put("layers", layerArr)
        return o
    }

    fun fromMapJson(o: JSONObject) {
        tileWidth = o.optDouble("tileWidth", 1.0).toFloat()
        tileHeight = o.optDouble("tileHeight", 1.0).toFloat()
        columns = o.optInt("columns", 32)
        rows = o.optInt("rows", 32)
        tilesetTexture = o.optString("tilesetTexture", "")
        tilesetColumns = o.optInt("tilesetColumns", 16)
        tilesetRows = o.optInt("tilesetRows", 16)
        layers.clear()
        val layerArr = o.optJSONArray("layers") ?: return
        for (i in 0 until layerArr.length()) {
            val l = TileLayer("layer_$i", columns, rows)
            l.fromJson(layerArr.getJSONObject(i))
            layers.add(l)
        }
    }

    data class TileCollisionRect(val col: Int, val row: Int, val width: Int, val height: Int)
}

/**
 * A single layer in a tilemap.
 */
class TileLayer(var name: String, val columns: Int, val rows: Int) {
    // -1 = empty, 0+ = tile ID
    val tiles = IntArray(columns * rows) { -1 }
    var visible = true
    var opacity = 1f
    var hasCollision = false
    // Tile IDs that act as triggers (non-blocking)
    val triggerTiles = HashSet<Int>()
    // Tile IDs that are solid (blocking)
    val solidTiles = HashSet<Int>()

    init { solidTiles.add(1) } // By default, tile 1 is solid

    fun setTile(col: Int, row: Int, tileId: Int) {
        if (col !in 0 until columns || row !in 0 until rows) return
        tiles[row * columns + col] = tileId
    }

    fun getTile(col: Int, row: Int): Int {
        if (col !in 0 until columns || row !in 0 until rows) return -1
        return tiles[row * columns + col]
    }

    fun clear() { tiles.fill(-1) }

    fun nonEmptyTiles(): Int = tiles.count { it >= 0 }

    fun isSolidTile(tileId: Int): Boolean = tileId in solidTiles
    fun isTriggerTile(tileId: Int): Boolean = tileId in triggerTiles

    fun markSolid(tileId: Int) { solidTiles.add(tileId) }
    fun markTrigger(tileId: Int) { triggerTiles.add(tileId) }

    /** Get all non-empty tile positions. */
    fun enumerate(): List<Triple<Int, Int, Int>> {
        val result = ArrayList<Triple<Int, Int, Int>>()
        for (r in 0 until rows) for (c in 0 until columns) {
            val id = tiles[r * columns + c]
            if (id >= 0) result.add(Triple(c, r, id))
        }
        return result
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("name", name)
        o.put("visible", visible)
        o.put("opacity", opacity.toDouble())
        o.put("hasCollision", hasCollision)
        // Run-length encode
        val encoded = JSONArray()
        var i = 0
        while (i < tiles.size) {
            val v = tiles[i]
            var count = 1
            while (i + count < tiles.size && tiles[i + count] == v && count < 65535) count++
            encoded.put(v)
            encoded.put(count)
            i += count
        }
        o.put("tiles", encoded)
        val solids = JSONArray()
        for (s in solidTiles) solids.put(s)
        o.put("solidTiles", solids)
        val triggers = JSONArray()
        for (t in triggerTiles) triggers.put(t)
        o.put("triggerTiles", triggers)
        return o
    }

    fun fromJson(o: JSONObject) {
        name = o.optString("name", name)
        visible = o.optBoolean("visible", true)
        opacity = o.optDouble("opacity", 1.0).toFloat()
        hasCollision = o.optBoolean("hasCollision", false)
        // Decode RLE
        val encoded = o.optJSONArray("tiles") ?: return
        var idx = 0
        var i = 0
        while (i < encoded.length() - 1 && idx < tiles.size) {
            val v = encoded.getInt(i)
            val count = encoded.getInt(i + 1).coerceAtMost(tiles.size - idx)
            for (j in 0 until count) tiles[idx + j] = v
            idx += count
            i += 2
        }
        solidTiles.clear()
        val solids = o.optJSONArray("solidTiles") ?: JSONArray()
        for (j in 0 until solids.length()) solidTiles.add(solids.getInt(j))
        triggerTiles.clear()
        val triggers = o.optJSONArray("triggerTiles") ?: JSONArray()
        for (j in 0 until triggers.length()) triggerTiles.add(triggers.getInt(j))
    }
}

/**
 * Terrain component for procedural terrain using heightmap data.
 * Useful for Hill Climb Racing style games.
 */
class Terrain : Component() {
    override val type = "Terrain"

    var width = 100f
    var resolution = 0.5f // Distance between height samples
    var heights = FloatArray(0) // Height values at each sample point

    // Runtime
    private var heightCache = HashMap<Int, Float>()

    override fun props() = listOf(
        Prop.F("Width", { width }, { width = it.coerceAtLeast(1f) }),
        Prop.F("Resolution", { resolution }, { resolution = it.coerceAtLeast(0.1f) }),
    )

    /** Generate terrain from a height function. */
    fun generate(heightFunc: (Float) -> Float) {
        val sampleCount = (width / resolution).toInt().coerceAtLeast(2)
        heights = FloatArray(sampleCount)
        for (i in 0 until sampleCount) {
            val x = i * resolution
            heights[i] = heightFunc(x)
        }
        heightCache.clear()
    }

    /** Generate hilly terrain (like Hill Climb Racing). */
    fun generateHills(amplitude: Float = 3f, frequency: Float = 0.1f, octaves: Int = 3) {
        generate { x ->
            var h = 0f
            var amp = amplitude
            var freq = frequency
            for (o in 0 until octaves) {
                h += amp * sin(x * freq + o * 1.7f).toFloat()
                h += amp * 0.5f * sin(x * freq * 2.3f + o * 3.1f).toFloat()
                amp *= 0.5f
                freq *= 2f
            }
            h
        }
    }

    /** Get the terrain height at a world X position (interpolated). */
    fun heightAt(x: Float): Float {
        if (heights.isEmpty()) return 0f
        val go = gameObject
        val localX = x - go.x
        val sampleX = localX / resolution
        val i0 = sampleX.toInt().coerceIn(0, heights.size - 2)
        val i1 = i0 + 1
        val frac = sampleX - i0
        return go.y + heights[i0] + (heights[i1] - heights[i0]) * frac
    }

    /** Get the terrain normal at a world X position. */
    fun normalAt(x: Float): Pair<Float, Float> {
        val h1 = heightAt(x - resolution * 0.5f)
        val h2 = heightAt(x + resolution * 0.5f)
        val dx = resolution
        val dy = h2 - h1
        val len = sqrt(dx * dx + dy * dy)
        return -dy / len to dx / len // Normal pointing "up" from surface
    }

    override fun resetRuntime() {
        heightCache.clear()
    }
}
