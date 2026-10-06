package com.sengine.engine.tilemap

import com.sengine.engine.math.M

/** A generated collider piece from a tilemap (world units, relative to the tilemap origin). */
class TileCollider(
    val x: Float, val y: Float, val w: Float, val h: Float,
    /** 0 = box, 1..4 = slope shapes. */
    val slope: Int = 0,
    val oneWay: Boolean = false,
    val material: String = "Default",
    val friction: Float = 0.4f
)

/**
 * Generates physics colliders from tilemaps.
 *
 * Solid tiles are merged with a greedy rectangle algorithm so a 200x50 level produces a handful
 * of colliders instead of thousands of bodies - the key optimisation for mobile 2D levels.
 * Slopes become convex polygons and one-way tiles keep their flag.
 */
object TilemapCollision {

    /** Greedy rectangle merge over a region of tile coordinates. */
    fun generate(
        data: TilemapData,
        layer: TileLayer,
        region: com.sengine.engine.math.Rect2,
        originX: Float = 0f,
        originY: Float = 0f
    ): List<TileCollider> {
        val ts = data.tilesetOf(layer) ?: return emptyList()
        val size = data.worldSizeOfTile()
        val x0 = M.floorI(region.left)
        val x1 = M.ceilI(region.right)
        val y0 = M.floorI(region.top)
        val y1 = M.ceilI(region.bottom)
        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        if (w <= 0 || h <= 0 || w * h > 4_000_000) return emptyList()
        val claimed = BooleanArray(w * h)
        val out = ArrayList<TileCollider>()

        for (ty in y0..y1) {
            for (tx in x0..x1) {
                val localX = tx - x0
                val localY = ty - y0
                val idx = localY * w + localX
                if (claimed[idx]) continue
                val id = layer.get(tx, ty)
                if (id == 0) continue
                val solid = ts.isSolid(id)
                val oneWay = ts.isOneWay(id)
                val slope = ts.slopeOf(id)
                if (!solid && !oneWay && slope == 0) continue
                if (slope != 0) {
                    claimed[idx] = true
                    out.add(slopeCollider(data, tx, ty, slope, originX, originY, ts))
                    continue
                }
                if (oneWay) {
                    claimed[idx] = true
                    out.add(
                        TileCollider(
                            originX + tx * size, originY + ty * size, size, size,
                            oneWay = true, material = ts.tags[id] ?: "Default",
                            friction = ts.friction[id] ?: 0.4f
                        )
                    )
                    continue
                }
                val friction = ts.friction[id] ?: 0.4f
                val material = ts.tags[id] ?: "Default"
                // extend the run to the right
                var runW = 1
                while (tx + runW <= x1 + 0) {
                    val nIdx = localY * w + (localX + runW)
                    if (nIdx >= claimed.size) break
                    if (claimed[nIdx]) break
                    val nid = layer.get(tx + runW, ty)
                    if (nid == 0 || !ts.isSolid(nid) || ts.slopeOf(nid) != 0 || ts.isOneWay(nid)) break
                    runW++
                }
                // extend downwards while the whole run is solid
                var runH = 1
                outer@ while (ty + runH <= y1 + 0) {
                    for (k in 0 until runW) {
                        val nIdx = (localY + runH) * w + (localX + k)
                        if (nIdx >= claimed.size) break@outer
                        if (claimed[nIdx]) break@outer
                        val nid = layer.get(tx + k, ty + runH)
                        if (nid == 0 || !ts.isSolid(nid) || ts.slopeOf(nid) != 0 || ts.isOneWay(nid)) break@outer
                    }
                    runH++
                }
                for (yy in 0 until runH) for (xx in 0 until runW) {
                    val i = (localY + yy) * w + (localX + xx)
                    if (i < claimed.size) claimed[i] = true
                }
                out.add(
                    TileCollider(
                        originX + tx * size, originY + ty * size, size * runW, size * runH,
                        material = material, friction = friction
                    )
                )
            }
        }
        return out
    }

    private fun slopeCollider(
        data: TilemapData, tx: Int, ty: Int, slope: Int,
        originX: Float, originY: Float, ts: Tileset
    ): TileCollider {
        val size = data.worldSizeOfTile()
        return TileCollider(
            originX + tx * size, originY + ty * size, size, size,
            slope = slope, material = ts.tags[ts.solid.firstOrNull() ?: 0] ?: "Default"
        )
    }

    /** Polygon points (local to the tile) for a slope type; null for a plain box. */
    fun slopePoints(slope: Int, size: Float): FloatArray? = when (slope) {
        1 -> floatArrayOf(0f, 0f, size, 0f, size, size)                 // rises to the right
        2 -> floatArrayOf(0f, 0f, size, 0f, 0f, size)                   // rises to the left
        3 -> floatArrayOf(0f, 0f, size, 0f, size, size * 0.5f, 0f, size * 0.5f)
        4 -> floatArrayOf(0f, 0f, size, 0f, size, size * 0.5f, 0f, size * 0.5f)
        else -> null
    }

    /**
     * Slope height at a local X position (0..1) for character controllers - lets characters walk
     * up slopes smoothly without polygon colliders.
     */
    fun slopeHeightAt(slope: Int, localT: Float): Float = when (slope) {
        1 -> M.clamp01(localT)
        2 -> M.clamp01(1f - localT)
        3, 4 -> 0.5f
        else -> 0f
    }

    /** True when the tile at (tx,ty) blocks movement. */
    fun isBlocking(tileset: Tileset, index: Int): Boolean =
        tileset.isSolid(index) || tileset.slopeOf(index) != 0
}
