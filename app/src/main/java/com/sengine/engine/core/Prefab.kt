package com.sengine.engine.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * A reusable 2D object tree ("prefab"). Prefabs are stored as `.prefab` assets and can be
 * instantiated at runtime (`scene.instantiate(prefab, x, y)` / `scene.spawn("Enemy", x, y)`).
 */
class Prefab(var name: String) {
    val roots = ArrayList<GameObject>()

    val size get() = roots.size
    fun isEmpty() = roots.isEmpty()

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("format", SceneSerializer.FORMAT_VERSION)
        o.put("name", name)
        val arr = JSONArray()
        roots.forEach { arr.put(SceneSerializer.objectToJson(it)) }
        o.put("roots", arr)
        return o
    }

    /** Instantiates a fresh copy of the whole tree into [scene]. */
    fun instantiate(scene: Scene, parent: GameObject? = null): GameObject {
        require(roots.isNotEmpty()) { "Prefab '$name' is empty" }
        val temps = Scene("prefab")
        temps.nextId = 1L
        for (r in roots) SceneSerializer.clone(r, temps, null)
        // re-instantiate into the target scene (ids come from the target scene)
        val created = ArrayList<GameObject>()
        for (r in temps.objects.filter { it.parent === null }) {
            created.add(SceneSerializer.clone(r, scene, parent))
        }
        val root = created.first()
        val link = root.getAny<PrefabLink>() ?: root.addGet(PrefabLink())
        link.prefab = name
        return root
    }

    companion object {
        fun fromJson(o: JSONObject): Prefab {
            val p = Prefab(o.optString("name", "Prefab"))
            val arr = o.optJSONArray("roots") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val oj = arr.optJSONObject(i) ?: continue
                val go = GameObject(oj.optLong("id", i + 1L), oj.optString("name", "GameObject"))
                SceneSerializer.applyObjectJson(go, oj)
                p.roots.add(go)
            }
            return p
        }

        /** Builds a prefab out of an existing scene object (and its children). */
        fun fromObject(go: GameObject, name: String = go.name): Prefab {
            val p = Prefab(name)
            p.roots.add(go)
            return p
        }
    }
}

/** Marks an object as an instance of a prefab asset (used for "Revert to prefab"). */
class PrefabLink : Component() {
    override val type = "PrefabLink"
    var prefab = ""
    var revertOnPlay = false

    override fun props() = listOf(
        Prop.Asset("Prefab", AssetKind.PREFAB, { prefab }, { prefab = it }),
        Prop.B("Revert On Play", { revertOnPlay }, { revertOnPlay = it })
    )
}
