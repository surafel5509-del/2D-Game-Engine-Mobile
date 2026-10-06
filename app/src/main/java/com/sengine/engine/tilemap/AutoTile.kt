package com.sengine.engine.tilemap

import com.sengine.engine.math.M

/**
 * Autotiling ("terrain") logic.
 *
 * Tiles are matched by their 8-neighbour mask (47-tile blob style). Corner bits are normalised
 * so a corner only counts when both of its neighbouring edges are filled - this is what makes
 * the standard 47-tile sheets work. The mapping table can be overridden per tileset, so any
 * artist sheet can be supported.
 */
object AutoTile {

    /** Bit layout: N=1, E=2, S=4, W=8, NE=16, SE=32, SW=64, NW=128. */
    fun mask(data: TilemapData, layer: TileLayer, tx: Int, ty: Int, tilesetIndex: Int): Int {
        val ts = data.tilesets.getOrNull(tilesetIndex) ?: return 0
        fun solid(x: Int, y: Int): Boolean {
            val id = layer.get(x, y)
            return id != 0 && ts.isSolid(id)
        }
        val n = solid(tx, ty + 1)
        val e = solid(tx + 1, ty)
        val s = solid(tx, ty - 1)
        val w = solid(tx - 1, ty)
        val ne = solid(tx + 1, ty + 1)
        val se = solid(tx + 1, ty - 1)
        val sw = solid(tx - 1, ty - 1)
        val nw = solid(tx - 1, ty + 1)
        var m = 0
        if (n) m = m or 1
        if (e) m = m or 2
        if (s) m = m or 4
        if (w) m = m or 8
        // corners only exist when both adjacent edges exist
        if (ne && n && e) m = m or 16
        if (se && s && e) m = m or 32
        if (sw && s && w) m = m or 64
        if (nw && n && w) m = m or 128
        return m
    }

    /**
     * Reduced 47-tile index for a mask (0..46). This is the order used by the built-in
     * autotile template; custom tilesets can supply their own table via [mapping].
     */
    private val canonicalCache = HashMap<Int, Int>()

    private val DEFAULT_ORDER: IntArray = run {
        // index by full 8-bit mask, 0 = isolated tile
        val table = IntArray(256)
        for (m in 0 until 256) table[m] = reduce(m)
        table
    }

    private fun reduce(mask: Int): Int {
        val n = mask and 1 != 0
        val e = mask and 2 != 0
        val s = mask and 4 != 0
        val w = mask and 8 != 0
        val ne = mask and 16 != 0
        val se = mask and 32 != 0
        val sw = mask and 64 != 0
        val nw = mask and 128 != 0
        // The 47 canonical cases: edges give 16 combos; corners that are "inner" corners add 31 more.
        var edge = (if (n) 1 else 0) or (if (e) 2 else 0) or (if (s) 4 else 0) or (if (w) 8 else 0)
        var corner = 0
        if (ne && n && e) corner = corner or 1
        if (se && s && e) corner = corner or 2
        if (sw && s && w) corner = corner or 4
        if (nw && n && w) corner = corner or 8
        return canonicalIndex(edge, corner)
    }

    private fun canonicalIndex(edge: Int, corner: Int): Int {
        val key = edge or (corner shl 4)
        canonicalCache[key]?.let { return it }
        // Deterministic ordering: sort by (edge, corner) so the same set always maps to the
        // same index - this is the index expected by the generated placeholder sheet.
        val keys = ArrayList<Int>()
        for (e in 0..15) for (c in 0..15) {
            if (!cornersValid(e, c)) continue
            keys.add(e or (c shl 4))
        }
        keys.sort()
        val idx = keys.indexOf(key).coerceAtLeast(0)
        keys.forEachIndexed { i, k -> canonicalCache[k] = i }
        return idx
    }

    /** A corner can only exist when both of its edges are filled. */
    private fun cornersValid(edge: Int, corner: Int): Boolean {
        val n = edge and 1 != 0
        val e = edge and 2 != 0
        val s = edge and 4 != 0
        val w = edge and 8 != 0
        if (corner and 1 != 0 && !(n && e)) return false
        if (corner and 2 != 0 && !(s && e)) return false
        if (corner and 4 != 0 && !(s && w)) return false
        if (corner and 8 != 0 && !(n && w)) return false
        return true
    }

    /** Number of canonical autotile shapes (47 for the blob layout). */
    val shapeCount: Int get() = canonicalCache.size.coerceAtLeast(47)

    /** Maps a mask to a tile index given the first autotile index in the sheet. */
    fun tileFor(mask: Int, firstTile: Int): Int = firstTile + DEFAULT_ORDER[mask and 0xFF]

    /**
     * Repaints a region with autotiles. Returns the number of tiles changed.
     * [firstTile] is the index of the isolated/centre tile in the sheet.
     */
    fun repaint(data: TilemapData, layer: TileLayer, tilesetIndex: Int, region: com.sengine.engine.math.Rect2, firstTile: Int): Int {
        var changed = 0
        val ts = data.tilesets.getOrNull(tilesetIndex) ?: return 0
        val x0 = M.floorI(region.left) - 1
        val x1 = M.ceilI(region.right) + 1
        val y0 = M.floorI(region.top) - 1
        val y1 = M.ceilI(region.bottom) + 1
        for (ty in y0..y1) {
            for (tx in x0..x1) {
                val id = layer.get(tx, ty)
                if (id == 0 || !ts.isSolid(id)) continue
                val mask = mask(data, layer, tx, ty, tilesetIndex)
                val want = tileFor(mask, firstTile)
                if (layer.get(tx, ty) != want) {
                    layer.set(tx, ty, want)
                    changed++
                }
            }
        }
        return changed
    }

    /** Blob (47-tile) neighbour table used by the editor's autotile previews. */
    fun neighbours(mask: Int): String {
        val sb = StringBuilder()
        if (mask and 128 != 0) sb.append('╲') else sb.append(' ')
        if (mask and 1 != 0) sb.append('▲') else sb.append(' ')
        if (mask and 16 != 0) sb.append('╱') else sb.append(' ')
        sb.append('\n')
        if (mask and 8 != 0) sb.append('◀') else sb.append(' ')
        sb.append('■')
        if (mask and 2 != 0) sb.append('▶') else sb.append(' ')
        sb.append('\n')
        if (mask and 64 != 0) sb.append('╱') else sb.append(' ')
        if (mask and 4 != 0) sb.append('▼') else sb.append(' ')
        if (mask and 32 != 0) sb.append('╲') else sb.append(' ')
        return sb.toString()
    }
}
