package com.sengine.engine.core

import com.sengine.engine.math.Colors
import org.json.JSONArray
import org.json.JSONObject

/**
 * Scene / prefab (de)serialization. Format version 2 is strictly 2D: only x/y position,
 * Z-axis rotation and scaleX/scaleY are stored. Files written by older (3D-capable) builds
 * still load - unknown components and the removed 3D transform keys are ignored.
 */
object SceneSerializer {
    const val FORMAT_VERSION = 2

    fun objectToJson(go: GameObject): JSONObject {
        val o = JSONObject()
        o.put("id", go.id)
        o.put("name", go.name)
        o.put("tag", go.tag)
        o.put("active", go.active)
        o.put("sortingLayer", go.sortingLayer)
        o.put("order", go.order)
        if (go.sortByY) o.put("sortByY", true)
        o.put("x", go.x.toDouble())
        o.put("y", go.y.toDouble())
        o.put("rotation", go.rotation.toDouble())
        o.put("scaleX", go.scaleX.toDouble())
        o.put("scaleY", go.scaleY.toDouble())
        go.parent?.let { o.put("parent", it.id) }
        if (go.groups.isNotEmpty()) {
            val g = JSONArray()
            go.groups.forEach { g.put(it) }
            o.put("groups", g)
        }
        val comps = JSONArray()
        for (c in go.components) comps.put(c.toJson())
        o.put("components", comps)
        return o
    }

    /** Applies everything except id and parent. */
    fun applyObjectJson(go: GameObject, o: JSONObject) {
        go.tag = o.optString("tag", "Untagged")
        go.active = o.optBoolean("active", true)
        go.sortingLayer = o.optString("sortingLayer", Scene.DEFAULT_LAYER)
        go.order = o.optInt("order", 0)
        go.sortByY = o.optBoolean("sortByY", false)
        go.x = o.optDouble("x", 0.0).toFloat()
        go.y = o.optDouble("y", 0.0).toFloat()
        go.rotation = o.optDouble("rotation", 0.0).toFloat()
        go.scaleX = o.optDouble("scaleX", 1.0).toFloat()
        go.scaleY = o.optDouble("scaleY", 1.0).toFloat()
        go.groups.clear()
        o.optJSONArray("groups")?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i)?.takeIf { it.isNotBlank() }?.let { go.groups.add(it) }
        }
        go.components.clear()
        val comps = o.optJSONArray("components") ?: JSONArray()
        for (i in 0 until comps.length()) {
            val cj = comps.optJSONObject(i) ?: continue
            // Components that no longer exist (e.g. the removed 3D components) are skipped.
            val c = ComponentRegistry.create(cj.optString("type")) ?: continue
            c.gameObject = go
            c.fromJson(cj)
            go.components.add(c)
        }
    }

    /** Deep-clones [src] (and its children) into [scene], returning the new root. */
    fun clone(src: GameObject, scene: Scene, parent: GameObject? = null): GameObject {
        val copy = GameObject(scene.nextId++, src.name)
        copy.parent = parent
        applyObjectJson(copy, objectToJson(src))
        copy.x = src.x; copy.y = src.y
        scene.objects.add(copy)
        for (child in scene.objects.filter { it.parent === src }.toList()) clone(child, scene, copy)
        return copy
    }

    fun toJson(scene: Scene): JSONObject {
        val o = JSONObject()
        o.put("format", FORMAT_VERSION)
        o.put("name", scene.name)
        o.put("gravityX", scene.gravityX.toDouble())
        o.put("gravityY", scene.gravityY.toDouble())
        o.put("ambient", Colors.toHex(scene.ambient))
        val layers = JSONArray()
        scene.sortingLayers.forEach { layers.put(it) }
        o.put("sortingLayers", layers)
        o.put("nextId", scene.nextId)
        val arr = JSONArray()
        for (go in scene.objects) arr.put(objectToJson(go))
        o.put("objects", arr)
        return o
    }

    fun fromJson(o: JSONObject): Scene {
        val s = Scene(o.optString("name", "Main"))
        s.gravityX = o.optDouble("gravityX", 0.0).toFloat()
        s.gravityY = o.optDouble("gravityY", -30.0).toFloat()
        s.ambient = Colors.parse(o.optString("ambient", "#FF3A4152"))
        s.sortingLayers.clear()
        s.sortingLayers.add(Scene.DEFAULT_LAYER)
        o.optJSONArray("sortingLayers")?.let { layers ->
            for (i in 0 until layers.length()) layers.optString(i)?.takeIf { it.isNotBlank() }?.let { s.addSortingLayer(it) }
        }
        val arr = o.optJSONArray("objects") ?: JSONArray()
        val parents = HashMap<GameObject, Long>()
        var maxId = 0L
        for (i in 0 until arr.length()) {
            val oj = arr.optJSONObject(i) ?: continue
            val go = GameObject(oj.optLong("id", i + 1L), oj.optString("name", "GameObject"))
            applyObjectJson(go, oj)
            if (oj.has("parent")) parents[go] = oj.getLong("parent")
            s.objects.add(go)
            maxId = maxOf(maxId, go.id)
        }
        for ((go, pid) in parents) go.parent = s.findById(pid)
        s.nextId = maxOf(o.optLong("nextId", 1L), maxId + 1)
        return s
    }
}
