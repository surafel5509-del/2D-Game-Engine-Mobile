package com.sengine.engine.core

import com.sengine.engine.math.Affine

/**
 * A node in the 2D scene graph.
 *
 * Transforms are strictly 2D: position (x, y), rotation around the Z axis (degrees) and
 * non-uniform scale (scaleX, scaleY). There is no third dimension anywhere in S Engine.
 */
class GameObject(var id: Long, var name: String) {
    var tag: String = "Untagged"

    /** Name of the sorting layer (see [Scene.sortingLayers]); lower index draws first. */
    var sortingLayer: String = Scene.DEFAULT_LAYER

    /** Order inside the sorting layer: higher is drawn on top. */
    var order: Int = 0

    /** When true the object's Y position also sorts it inside its layer (top-down/RPG style). */
    var sortByY: Boolean = false

    var active: Boolean = true

    var x = 0f
    var y = 0f
    var rotation = 0f
    var scaleX = 1f
    var scaleY = 1f

    var parent: GameObject? = null
    val components = mutableListOf<Component>()

    /** Free-form group membership used by queries (`scene.findAllInGroup("enemies")`). */
    val groups = LinkedHashSet<String>()

    @Volatile var destroyed = false

    /** Cached world transform, refreshed by [Scene.updateTransforms]. */
    val world = Affine()

    /** User signals: `go.signal("died").connect { ... }`. */
    private var signals: HashMap<String, Signal<Any?>>? = null

    @Suppress("UNCHECKED_CAST")
    fun <T> signal(name: String): Signal<T> {
        val map = signals ?: HashMap<String, Signal<Any?>>().also { signals = it }
        return map.getOrPut(name) { Signal<Any?>() } as Signal<T>
    }

    fun emit(name: String, payload: Any? = null) {
        signals?.get(name)?.emit(payload)
    }

    fun hasSignal(name: String) = signals?.containsKey(name) == true

    fun localMatrix(out: Affine = Affine()): Affine = out.setTRS(x, y, rotation, scaleX, scaleY)

    fun computeWorld(): Affine {
        val local = localMatrix()
        val p = parent ?: return local
        return Affine().setMul(p.computeWorld(), local)
    }

    fun setWorldPosition(wx: Float, wy: Float) {
        val p = parent
        if (p == null) {
            x = wx; y = wy
        } else {
            val inv = p.computeWorld().inverted() ?: return
            x = inv.mapX(wx, wy); y = inv.mapY(wx, wy)
        }
    }

    /** World-space position (uses the cached transform when available). */
    fun worldX(): Float = if (parent == null) x else computeWorld().tx
    fun worldY(): Float = if (parent == null) y else computeWorld().ty

    /** World-space rotation in degrees. */
    fun worldRotation(): Float = if (parent == null) rotation else computeWorld().rotationDeg

    fun isActiveInHierarchy(): Boolean = active && !destroyed && (parent?.isActiveInHierarchy() ?: true)

    fun isAncestorOf(other: GameObject): Boolean {
        var p = other.parent
        while (p != null) {
            if (p === this) return true
            p = p.parent
        }
        return false
    }

    fun depth(): Int {
        var d = 0
        var p = parent
        while (p != null) { d++; p = p.parent }
        return d
    }

    /** Adds a component and returns it, for `val rb = go.addGet(Rigidbody2D())` style code. */
    fun <T : Component> addGet(c: T): T {
        add(c)
        return c
    }

    fun add(c: Component): GameObject {
        c.gameObject = this
        components.add(c)
        for (req in c.requires()) {
            if (components.none { it.type == req }) {
                ComponentRegistry.create(req)?.let {
                    it.gameObject = this
                    components.add(it)
                }
            }
        }
        c.onAttach()
        return this
    }

    fun remove(c: Component) {
        if (components.remove(c)) c.onDetach()
    }

    inline fun <reified T : Component> get(): T? = components.firstOrNull { it is T && it.enabled } as T?
    inline fun <reified T : Component> getAny(): T? = components.firstOrNull { it is T } as T?
    fun getByType(type: String): Component? = components.firstOrNull { it.type == type }

    fun has(type: String) = components.any { it.type == type && it.enabled }

    fun addGroup(g: String) { groups.add(g) }
    fun inGroup(g: String) = g in groups

    fun destroy() { destroyed = true; emit("destroyed") }

    override fun toString() = "GameObject($id, $name)"

    /** Shallow copy of the transform and flags, used by prefab instancing and duplication. */
    fun copyTransformFrom(o: GameObject) {
        x = o.x; y = o.y; rotation = o.rotation; scaleX = o.scaleX; scaleY = o.scaleY
    }
}
