package com.sengine.engine.core

import org.json.JSONObject

/**
 * Base class for every behaviour attached to a [GameObject].
 *
 * Lifecycle: [onAttach] -> [onEnable]/[onDisable] (when the component or its object is
 * toggled) -> [resetRuntime] when play mode starts -> per-frame updates driven by the
 * systems (physics, animation, particles, scripts) -> [onDetach].
 */
abstract class Component {
    lateinit var gameObject: GameObject
    abstract val type: String
    var enabled = true
        set(value) {
            if (field == value) return
            field = value
            if (value) onEnable() else onDisable()
        }

    /** Properties shown in the inspector and saved to disk. */
    abstract fun props(): List<Prop>

    /** Reset transient runtime state (called when play mode starts). */
    open fun resetRuntime() {}

    open fun onAttach() {}
    open fun onDetach() {}
    open fun onEnable() {}
    open fun onDisable() {}

    /** Component types that must exist on the same object (created automatically). */
    open fun requires(): List<String> = emptyList()

    val go: GameObject get() = gameObject

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("type", type)
        o.put("enabled", enabled)
        for (p in props()) {
            when (p) {
                is Prop.F -> o.put(p.name, p.get().toDouble())
                is Prop.I -> o.put(p.name, p.get())
                is Prop.B -> o.put(p.name, p.get())
                is Prop.S -> o.put(p.name, p.get())
                is Prop.Color -> o.put(p.name, PropCodec.colorToJson(p.get()))
                is Prop.Choice -> o.put(p.name, p.options.getOrElse(p.get()) { p.options.firstOrNull() ?: "" })
                is Prop.Asset -> o.put(p.name, p.get())
                is Prop.V2 -> o.put(p.name, "${p.getX()},${p.getY()}")
                is Prop.Flags -> o.put(p.name, p.get())
                is Prop.Info -> {}
            }
        }
        return o
    }

    fun fromJson(o: JSONObject) {
        enabled = o.optBoolean("enabled", true)
        for (p in props()) {
            if (!o.has(p.name)) continue
            try {
                when (p) {
                    is Prop.F -> p.set(o.getDouble(p.name).toFloat())
                    is Prop.I -> p.set(o.getInt(p.name))
                    is Prop.B -> p.set(o.getBoolean(p.name))
                    is Prop.S -> p.set(o.getString(p.name))
                    is Prop.Color -> p.set(PropCodec.jsonToColor(o.getString(p.name)))
                    is Prop.Choice -> {
                        val idx = p.options.indexOf(o.getString(p.name))
                        if (idx >= 0) p.set(idx)
                    }
                    is Prop.Asset -> p.set(o.getString(p.name))
                    is Prop.V2 -> {
                        val v = o.getString(p.name).split(',')
                        if (v.size == 2) {
                            p.setX(v[0].toFloat())
                            p.setY(v[1].toFloat())
                        }
                    }
                    is Prop.Flags -> p.set(o.getInt(p.name))
                    is Prop.Info -> {}
                }
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        fun parseColor(s: String): Int = PropCodec.jsonToColor(s)
    }
}
