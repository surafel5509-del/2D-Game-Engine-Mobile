package com.sengine.engine.tilemap

import com.sengine.engine.core.AssetKind
import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.Component
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Prop
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.core.Scene
import com.sengine.engine.math.Rect2

/**
 * Tilemap component: renders a `.tilemap` asset with per-layer culling and (optionally)
 * generates physics colliders from it. Supports unbounded/chunked maps, animated tiles,
 * autotiling and per-layer parallax.
 */
class TilemapRenderer : Component() {
    override val type = "Tilemap"

    /** .tilemap asset. */
    var map = ""
    /** Render a procedural placeholder checkerboard when no asset is set (editor convenience). */
    var previewTiles = true
    var generateCollision = true
    var autoTile = false
    var autoTileFirstTile = 1
    var collisionHolderName = "__TilemapCollision"
    /** Only generate colliders in this radius around the origin (0 = everywhere). */
    var collisionRadius = 0f
    var pixelSnap = true
    var visible = true
    /** Sorting layer for the map (Background, Default, Foreground...). */
    var sortingLayer = "Default"
    var order = 0
    /** Optional 2D material (tint, dissolve, water...) applied to every tile chunk. */
    var material = ""
    var additive = false

    /** Runtime data (loaded from the asset). */
    var data: TilemapData? = null
    private var collidersBuilt = false
    private var holder: GameObject? = null

    val colliderCount: Int get() = holder?.components?.size ?: 0

    override fun props() = listOf(
        Prop.B("Visible", { visible }, { visible = it }),
        Prop.Asset("Tilemap", AssetKind.TILEMAP, { map }, { map = it }),
        Prop.B("Preview Tiles", { previewTiles }, { previewTiles = it }, "Draws a placeholder grid when no tilemap asset is assigned."),
        Prop.B("Generate Collision", { generateCollision }, { generateCollision = it }),
        Prop.B("Auto Tile", { autoTile }, { autoTile = it }, "Recomputes blob autotiles when the map is loaded."),
        Prop.I("Auto Tile First Index", { autoTileFirstTile }, { autoTileFirstTile = it.coerceAtLeast(0) }, 1, 0, 4096),
        Prop.F("Collision Radius", { collisionRadius }, { collisionRadius = it.coerceAtLeast(0f) }, 0.5f, 0f, 10000f),
        Prop.B("Pixel Snap", { pixelSnap }, { pixelSnap = it }),
        Prop.S("Sorting Layer", { sortingLayer }, { sortingLayer = it }),
        Prop.I("Sort Order", { order }, { order = it }),
        Prop.Asset("Material", AssetKind.MATERIAL, { material }, { material = it }),
        Prop.B("Additive Blend", { additive }, { additive = it }),
        Prop.Info("Layers", { (data?.layers?.size ?: 0).toString() }),
        Prop.Info("Colliders", { colliderCount.toString() })
    )

    override fun resetRuntime() {
        collidersBuilt = false
        holder = null
    }

    /** Loads the tilemap asset (called by the engine when the scene starts). */
    fun assignData(d: TilemapData?) {
        data = d
        collidersBuilt = false
    }

    /** Builds static colliders as merged rectangles under a hidden holder object. */
    fun buildColliders(scene: Scene) {
        val d = data ?: return
        if (collidersBuilt) return
        collidersBuilt = true
        val existing = scene.find(collisionHolderName)
        if (existing != null) scene.remove(existing)
        val originWorld = go.computeWorld()
        val h = scene.create(collisionHolderName, go)
        h.tag = "Terrain"
        h.add(Rigidbody2D().also { it.bodyType = 2 })
        holder = h
        val region = if (collisionRadius > 0f) {
            val cx = originWorld.tx / d.worldSizeOfTile()
            val cy = originWorld.ty / d.worldSizeOfTile()
            val r = collisionRadius / d.worldSizeOfTile()
            Rect2(cx - r, cy - r, r * 2, r * 2)
        } else {
            null
        }
        for (layer in d.layers) {
            if (!layer.collision) continue
            val area = region ?: layer.bounds()
            val colliders = TilemapCollision.generate(d, layer, area.grow(2f))
            for (c in colliders) {
                val child = scene.create("Tile", h)
                child.x = c.x + c.w * 0.5f
                child.y = c.y + c.h * 0.5f
                child.tag = "Terrain"
                val col = Collider2D()
                if (c.slope != 0) {
                    col.shape = Collider2D.SHAPE_POLYGON
                    col.points = pointsSpec(TilemapCollision.slopePoints(c.slope, d.worldSizeOfTile())!!, c.w * 0.5f, c.h * 0.5f)
                } else {
                    col.shape = Collider2D.SHAPE_BOX
                    col.width = c.w
                    col.height = c.h
                }
                col.friction = c.friction
                col.oneWay = c.oneWay
                col.material = c.material
                col.layer = 3
                child.add(col)
            }
        }
    }

    private fun pointsSpec(points: FloatArray, cx: Float, cy: Float): String {
        val sb = StringBuilder()
        var i = 0
        while (i < points.size) {
            if (i > 0) sb.append(' ')
            sb.append(points[i] - cx).append(',').append(points[i + 1] - cy)
            i += 2
        }
        return sb.toString()
    }

    /** Applies autotiling over the whole map. */
    /** Re-runs autotiling after an edit when the component asks for it. */
    fun runAutoTileIfEnabled() {
        if (autoTile) runAutoTile()
    }

    fun runAutoTile() {
        val d = data ?: return
        if (autoTileFirstTile <= 0) return // 0 is the "empty" id: there is no autotile sheet to use
        for (layer in d.layers) {
            AutoTile.repaint(d, layer, layer.tilesetIndex, layer.bounds().grow(1f), autoTileFirstTile)
        }
    }

    /** Statistics for the profiler / asset diagnostics. */
    fun stats(): String {
        val d = data ?: return "no data"
        val tiles = d.layers.sumOf { it.chunkCount() }
        return "${d.layers.size} layers, $tiles chunks, ${colliderCount} colliders"
    }
}
