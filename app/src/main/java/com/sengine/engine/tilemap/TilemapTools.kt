package com.sengine.engine.tilemap

import com.sengine.engine.math.M
import com.sengine.engine.math.Rect2

/**
 * Tile painting operations shared by the editor tools, autotiling and procedural generation.
 * Everything here is undo-friendly: each call reports how many tiles changed.
 */
object TilemapTools {

    /** Paints a circular brush. */
    fun paintBrush(layer: TileLayer, tx: Int, ty: Int, radius: Int, tile: Int, erase: Boolean = false): Int {
        var changed = 0
        val r = radius.coerceAtLeast(0)
        for (y in ty - r..ty + r) {
            for (x in tx - r..tx + r) {
                val dx = x - tx; val dy = y - ty
                if (dx * dx + dy * dy > r * r + r) continue
                if (layer.get(x, y) != tile || erase) {
                    layer.set(x, y, if (erase) 0 else tile)
                    changed++
                }
            }
        }
        return changed
    }

    /** Paints a filled rectangle. */
    fun paintRect(layer: TileLayer, x0: Int, y0: Int, x1: Int, y1: Int, tile: Int, erase: Boolean = false): Int {
        var changed = 0
        val minX = minOf(x0, x1); val maxX = maxOf(x0, x1)
        val minY = minOf(y0, y1); val maxY = maxOf(y0, y1)
        for (y in minY..maxY) for (x in minX..maxX) {
            if (layer.get(x, y) != tile || erase) {
                layer.set(x, y, if (erase) 0 else tile)
                changed++
            }
        }
        return changed
    }

    /** Paints a line (Bresenham) - useful for drawing platforms quickly. */
    fun paintLine(layer: TileLayer, x0: Int, y0: Int, x1: Int, y1: Int, tile: Int, thickness: Int = 0): Int {
        var changed = 0
        var x = x0; var y = y0
        val dx = kotlin.math.abs(x1 - x0)
        val dy = -kotlin.math.abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        var guard = 0
        while (guard++ < 100000) {
            changed += paintBrush(layer, x, y, thickness, tile)
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x += sx }
            if (e2 <= dx) { err += dx; y += sy }
        }
        return changed
    }

    /** Flood fill (bucket) bounded by [limit] tiles. */
    fun floodFill(layer: TileLayer, sx: Int, sy: Int, tile: Int, limit: Int = 200000): Int {
        val target = layer.get(sx, sy)
        if (target == tile) return 0
        val stack = ArrayDeque<Long>()
        stack.addLast(pack(sx, sy))
        var changed = 0
        val visited = HashSet<Long>()
        while (stack.isNotEmpty() && changed < limit) {
            val p = stack.removeLast()
            if (!visited.add(p)) continue
            val x = (p shr 32).toInt()
            val y = (p and 0xFFFFFFFFL).toInt()
            if (layer.get(x, y) != target) continue
            layer.set(x, y, tile)
            changed++
            stack.addLast(pack(x + 1, y)); stack.addLast(pack(x - 1, y))
            stack.addLast(pack(x, y + 1)); stack.addLast(pack(x, y - 1))
        }
        return changed
    }

    /** Replaces every occurrence of [from] with [to]. */
    fun replaceTile(layer: TileLayer, region: Rect2, from: Int, to: Int): Int {
        var changed = 0
        layer.forEachIn(region) { tx, ty, id ->
            if (id == from) { layer.set(tx, ty, to); changed++ }
        }
        return changed
    }

    /**
     * Procedural terrain (Hill Climb / endless runner style): a smooth height field generated
     * from fBm noise, optionally filled solid beneath the surface.
     */
    fun generateTerrain(
        layer: TileLayer,
        fromTileX: Int, toTileX: Int,
        baseY: Int, amplitude: Int,
        groundTile: Int, fillTile: Int = 0, seed: Int = 0, frequency: Float = 0.06f,
        fillDepth: Int = 6
    ): Int {
        var changed = 0
        for (x in minOf(fromTileX, toTileX)..maxOf(fromTileX, toTileX)) {
            val n = com.sengine.engine.math.Noise2D.fbm(x * frequency, 0.37f, 3, 2f, 0.5f, seed)
            val h = baseY + (n * amplitude).toInt()
            if (layer.get(x, h) != groundTile) { layer.set(x, h, groundTile); changed++ }
            for (d in 1..fillDepth) {
                val y = h - d
                if (fillTile != 0 && layer.get(x, y) != fillTile) { layer.set(x, y, fillTile); changed++ }
            }
        }
        return changed
    }

    /** Rounds a rough painted surface into a smooth slope-aware terrain (autotile helper). */
    fun applySlopes(layer: TileLayer, tileset: Tileset, region: Rect2, slopeTile: Int): Int {
        var changed = 0
        val x0 = M.floorI(region.left); val x1 = M.ceilI(region.right)
        val y0 = M.floorI(region.top); val y1 = M.ceilI(region.bottom)
        for (y in y0..y1) {
            for (x in x0..x1) {
                if (layer.get(x, y) != 0) continue
                val below = layer.get(x, y - 1)
                if (below == 0 || !tileset.isSolid(below)) continue
                val leftBelow = layer.get(x - 1, y - 1)
                val rightBelow = layer.get(x + 1, y - 1)
                val leftSolid = leftBelow != 0 && tileset.isSolid(leftBelow)
                val rightSolid = rightBelow != 0 && tileset.isSolid(rightBelow)
                if (Math.abs((leftSolid).compareTo(false)) == 0 && Math.abs((rightSolid).compareTo(false)) == 0) continue
                if (!leftSolid && rightSolid) { layer.set(x, y, slopeTile); changed++ }
                if (leftSolid && !rightSolid) { layer.set(x, y, slopeTile + 1); changed++ }
            }
        }
        return changed
    }

    private fun pack(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
}
