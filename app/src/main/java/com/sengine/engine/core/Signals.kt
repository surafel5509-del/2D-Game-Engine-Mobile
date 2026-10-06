package com.sengine.engine.core

/**
 * Tiny typed signal (observer) implementation used for gameplay events.
 * Unlike the scripting layer these are compile-time checked and allocation-light.
 */
class Signal<T> {
    private val slots = ArrayList<(T) -> Unit>(2)
    private val onceSlots = ArrayList<(T) -> Unit>(0)
    var emitCount = 0; private set

    fun connect(slot: (T) -> Unit): Signal<T> {
        slots.add(slot)
        return this
    }

    fun connectOnce(slot: (T) -> Unit): Signal<T> {
        onceSlots.add(slot)
        return this
    }

    fun disconnect(slot: (T) -> Unit) {
        slots.remove(slot)
        onceSlots.remove(slot)
    }

    fun disconnectAll() {
        slots.clear()
        onceSlots.clear()
    }

    val isEmpty get() = slots.isEmpty() && onceSlots.isEmpty()
    val connectionCount get() = slots.size + onceSlots.size

    fun emit(value: T) {
        emitCount++
        if (slots.isNotEmpty()) {
            // iterate over a snapshot so handlers may disconnect safely
            val snapshot = slots.toTypedArray()
            for (s in snapshot) s(value)
        }
        if (onceSlots.isNotEmpty()) {
            val snapshot = onceSlots.toTypedArray()
            onceSlots.clear()
            for (s in snapshot) s(value)
        }
    }

    /** Fires the signal when [condition] returns true. */
    fun emitIf(condition: Boolean, value: T) { if (condition) emit(value) }
}

/** Global, engine-wide event bus (used by scripting `events.emit("levelDone")` and the editor). */
object EventBus {
    private val map = HashMap<String, Signal<Any?>>()
    private val log = ArrayList<String>(64)

    fun on(name: String, slot: (Any?) -> Unit) {
        synchronized(map) { map.getOrPut(name) { Signal() }.connect(slot) }
    }

    fun onOnce(name: String, slot: (Any?) -> Unit) {
        synchronized(map) { map.getOrPut(name) { Signal() }.connectOnce(slot) }
    }

    fun off(name: String, slot: (Any?) -> Unit) {
        synchronized(map) { map[name]?.disconnect(slot) }
    }

    fun emit(name: String, payload: Any? = null) {
        val s = synchronized(map) { map[name] }
        synchronized(log) {
            log.add(name)
            while (log.size > 64) log.removeAt(0)
        }
        s?.emit(payload)
    }

    fun clear() {
        synchronized(map) { map.clear() }
        synchronized(log) { log.clear() }
    }

    fun recent(): List<String> = synchronized(log) { log.toList() }
}
