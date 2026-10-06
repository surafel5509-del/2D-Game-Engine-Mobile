package com.sengine.engine.core

import com.sengine.engine.math.Colors
import org.json.JSONArray
import org.json.JSONObject

/**
 * A 2D scene: an ordered list of [GameObject]s (with parenting), 2D gravity, sorting layers
 * and 2D ambient light. S Engine has no 3D scene concept.
 */
class Scene(var name: String) {
    val objects = mutableListOf<GameObject>()

    /** 2D gravity, world units / s^2. Default is a platformer-friendly -30. */
    var gravityX = 0f
    var gravityY = -30f

    /** Ambient light colour used by the 2D lighting pass (also the fallback clear colour). */
    var ambient = 0xFF3A4152.toInt()

    /** Sorting layers, back to front. */
    val sortingLayers = mutableListOf(DEFAULT_LAYER, "Background", "Foreground", "UI")

    var nextId = 1L

    fun create(name: String, parent: GameObject? = null): GameObject {
        val go = GameObject(nextId++, uniqueName(name))
        go.parent = parent
        objects.add(go)
        return go
    }

    /** Adds an already built object tree (prefab instancing, undo/redo restore). */
    fun addExisting(go: GameObject) {
        if (objects.none { it === go }) objects.add(go)
        if (go.id >= nextId) nextId = go.id + 1
    }

    fun uniqueName(base: String): String {
        if (objects.none { it.name == base }) return base
        val stem = base.replace(Regex(" \\(\\d+\\)$"), "")
        var i = 1
        while (objects.any { it.name == "$stem ($i)" }) i++
        return "$stem ($i)"
    }

    fun findById(id: Long) = objects.firstOrNull { it.id == id }
    fun find(name: String) = objects.firstOrNull { it.name == name && !it.destroyed }
    fun findOrNull(name: String?) = if (name.isNullOrBlank()) null else find(name)
    fun findByTag(tag: String) = objects.filter { it.tag == tag && !it.destroyed }
    fun findByType(type: String) = objects.filter { !it.destroyed && it.getByType(type) != null }
    fun findAllInGroup(group: String) = objects.filter { !it.destroyed && it.inGroup(group) }

    fun childrenOf(go: GameObject?) = objects.filter { it.parent === go }

    /** Depth-first hierarchy listing with depth. */
    fun hierarchy(): List<Pair<GameObject, Int>> {
        val out = ArrayList<Pair<GameObject, Int>>(objects.size)
        fun walk(p: GameObject?, depth: Int) {
            for (c in objects) if (c.parent === p) {
                out.add(c to depth); walk(c, depth + 1)
            }
        }
        walk(null, 0)
        return out
    }

    fun layerIndex(layer: String): Int {
        val i = sortingLayers.indexOf(layer)
        return if (i < 0) 0 else i
    }

    fun addSortingLayer(layer: String) {
        if (layer.isNotBlank() && layer !in sortingLayers) sortingLayers.add(layer)
    }

    /**
     * Objects ordered back-to-front for rendering:
     * sorting layer index, then explicit order, then Y (when [GameObject.sortByY]) and finally
     * hierarchy order so rendering stays deterministic.
     */
    fun renderOrder(): List<GameObject> {
        val hier = hierarchy().map { it.first }
        val index = HashMap<GameObject, Int>(objects.size)
        hier.forEachIndexed { i, go -> index[go] = i }
        return objects.filter { it.isActiveInHierarchy() }.sortedWith(
            compareBy(
                { layerIndex(it.sortingLayer) },
                { it.order },
                { if (it.sortByY) -it.world.ty else 0f },
                { index[it] ?: 0 }
            )
        )
    }

    fun remove(go: GameObject) {
        for (c in childrenOf(go).toList()) remove(c)
        go.destroyed = true
        objects.remove(go)
    }

    fun updateTransforms() {
        for ((go, _) in hierarchy()) {
            go.localMatrix(go.world)
            val p = go.parent ?: continue
            val out = go.world
            val pa = p.world
            val la = go.world
            val na = pa.a * la.a + pa.c * la.b
            val nb = pa.b * la.a + pa.d * la.b
            val nc = pa.a * la.c + pa.c * la.d
            val nd = pa.b * la.c + pa.d * la.d
            val ntx = pa.a * la.tx + pa.c * la.ty + pa.tx
            val nty = pa.b * la.tx + pa.d * la.ty + pa.ty
            out.a = na; out.b = nb; out.c = nc; out.d = nd; out.tx = ntx; out.ty = nty
        }
    }

    /** Bounding box of every renderer/collider in the scene - used by "frame all". */
    fun contentBounds(): com.sengine.engine.math.Rect2 {
        val r = com.sengine.engine.math.Rect2(0f, 0f, 0f, 0f)
        var first = true
        for (go in objects) {
            if (!go.isActiveInHierarchy()) continue
            val col = go.get<Collider2D>()
            val spr = go.get<SpriteRenderer>()
            val w: Float; val h: Float
            if (col != null) {
                w = if (col.shape == Collider2D.SHAPE_CIRCLE) col.radius * 2f else col.width
                h = if (col.shape == Collider2D.SHAPE_CIRCLE) col.radius * 2f else col.height
            } else if (spr != null) {
                w = spr.width * spr.tileX; h = spr.height * spr.tileY
            } else continue
            val x0 = go.world.mapX(-w * 0.5f, -h * 0.5f)
            val y0 = go.world.mapY(-w * 0.5f, -h * 0.5f)
            val x1 = go.world.mapX(w * 0.5f, h * 0.5f)
            val y1 = go.world.mapY(w * 0.5f, h * 0.5f)
            if (first) {
                r.set(minOf(x0, x1), minOf(y0, y1), kotlin.math.abs(x1 - x0), kotlin.math.abs(y1 - y0))
                first = false
            } else {
                r.expandToInclude(x0, y0); r.expandToInclude(x1, y1)
            }
        }
        return r
    }

    /** Deep copy of an object (and its children) placed next to the original. */
    fun duplicate(src: GameObject, newParent: GameObject? = src.parent): GameObject {
        val copy = SceneSerializer.clone(src, this, newParent)
        copy.name = uniqueName(src.name)
        return copy
    }

    fun moveInOrder(go: GameObject, delta: Int) {
        val siblings = objects.filter { it.parent === go.parent }
        val i = siblings.indexOf(go)
        if (i < 0) return
        val j = (i + delta).coerceIn(0, siblings.size - 1)
        if (i == j) return
        val other = siblings[j]
        val a = objects.indexOf(go)
        val b = objects.indexOf(other)
        objects[a] = other
        objects[b] = go
    }

    /** Instantiates [prefab] at (x, y); the new root object is returned. */
    fun instantiate(prefab: Prefab, x: Float = 0f, y: Float = 0f, parent: GameObject? = null): GameObject {
        val root = prefab.instantiate(this, parent)
        root.name = uniqueName(prefab.name)
        root.x = x
        root.y = y
        val link = root.getAny<PrefabLink>() ?: root.addGet(PrefabLink())
        link.prefab = prefab.name
        return root
    }

    companion object {
        const val DEFAULT_LAYER = "Default"
    }
}
