package com.sengine.engine.core

/**
 * Signal/Event system for decoupled communication between game objects.
 * Similar to Unity's events or Godot's signals.
 * Supports named signals with typed arguments.
 */

class Signal {
    private val handlers = ArrayList<Handler>()
    private var nextId = 0L

    private data class Handler(val id: Long, val callback: (Array<Any?>) -> Unit)

    /** Connect a handler to this signal. Returns a connection ID for disconnection. */
    fun connect(callback: (Array<Any?>) -> Unit): Long {
        val id = nextId++
        handlers.add(Handler(id, callback))
        return id
    }

    /** Connect a no-arg handler. */
    fun connect0(callback: () -> Unit): Long {
        return connect { callback() }
    }

    /** Connect a single-arg handler. */
    fun connect1(callback: (Any?) -> Unit): Long {
        return connect { args -> callback(if (args.isNotEmpty()) args[0] else null) }
    }

    /** Connect a two-arg handler. */
    fun connect2(callback: (Any?, Any?) -> Unit): Long {
        return connect { args -> callback(
            if (args.isNotEmpty()) args[0] else null,
            if (args.size > 1) args[1] else null
        )}
    }

    /** Disconnect a handler by its connection ID. */
    fun disconnect(id: Long) {
        handlers.removeAll { it.id == id }
    }

    /** Disconnect all handlers. */
    fun disconnectAll() {
        handlers.clear()
    }

    /** Emit the signal with arguments. */
    fun emit(vararg args: Any?) {
        val snapshot = handlers.toList() // avoid concurrent modification
        for (h in snapshot) {
            try { h.callback(args) } catch (_: Exception) {}
        }
    }

    /** Emit with no arguments. */
    fun emit0() = emit()

    /** Emit with one argument. */
    fun emit1(arg: Any?) = emit(arg)

    /** Emit with two arguments. */
    fun emit2(arg1: Any?, arg2: Any?) = emit(arg1, arg2)

    val handlerCount: Int get() = handlers.size
    val hasConnections: Boolean get() = handlers.isNotEmpty()
}

/**
 * Global signal bus for engine-wide events.
 * Allows any system to emit or listen for named events without direct references.
 */
class SignalBus {
    private val signals = HashMap<String, Signal>()
    private val queue = ArrayList<Pair<String, Array<Any?>>>()
    private var deferred = false

    /** Get or create a named signal. */
    fun signal(name: String): Signal {
        return signals.getOrPut(name) { Signal() }
    }

    /** Connect to a named signal. Returns connection ID. */
    fun connect(name: String, callback: (Array<Any?>) -> Unit): Long {
        return signal(name).connect(callback)
    }

    /** Connect a no-arg callback to a named signal. */
    fun connect0(name: String, callback: () -> Unit): Long {
        return signal(name).connect0(callback)
    }

    /** Connect a single-arg callback to a named signal. */
    fun connect1(name: String, callback: (Any?) -> Unit): Long {
        return signal(name).connect1(callback)
    }

    /** Disconnect from a named signal. */
    fun disconnect(name: String, id: Long) {
        signals[name]?.disconnect(id)
    }

    /** Emit a named signal immediately. */
    fun emit(name: String, vararg args: Any?) {
        if (deferred) {
            queue.add(name to arrayOf(*args))
        } else {
            signal(name).emit(*args)
        }
    }

    /** Emit with no arguments. */
    fun emit0(name: String) = emit(name)

    /** Emit with one argument. */
    fun emit1(name: String, arg: Any?) = emit(name, arg)

    /** Start deferring signals (they are queued). */
    fun beginDefer() { deferred = true }

    /** Flush all deferred signals. */
    fun endDefer() {
        deferred = false
        val q = queue.toList()
        queue.clear()
        for ((name, args) in q) {
            signal(name).emit(*args)
        }
    }

    /** Remove a named signal and all its handlers. */
    fun remove(name: String) {
        signals[name]?.disconnectAll()
        signals.remove(name)
    }

    /** Remove all signals. */
    fun clear() {
        for (s in signals.values) s.disconnectAll()
        signals.clear()
        queue.clear()
    }

    /** Check if a signal has any handlers. */
    fun hasConnections(name: String): Boolean = signals[name]?.hasConnections == true

    val signalNames: Set<String> get() = signals.keys
}

/**
 * Object-level signal emitter.
 * GameObjects can emit named signals that other components can listen to.
 */
class ObjectSignals {
    private val signals = HashMap<String, Signal>()

    fun signal(name: String): Signal {
        return signals.getOrPut(name) { Signal() }
    }

    fun connect(name: String, callback: (Array<Any?>) -> Unit): Long {
        return signal(name).connect(callback)
    }

    fun emit(name: String, vararg args: Any?) {
        signals[name]?.emit(*args)
    }

    fun disconnect(name: String, id: Long) {
        signals[name]?.disconnect(id)
    }

    fun clear() {
        for (s in signals.values) s.disconnectAll()
        signals.clear()
    }
}

// --- Common engine signals ---
object EngineSignals {
    const val GAME_START = "game_start"
    const val GAME_OVER = "game_over"
    const val LEVEL_COMPLETE = "level_complete"
    const val SCORE_CHANGED = "score_changed"
    const val HEALTH_CHANGED = "health_changed"
    const val ITEM_COLLECTED = "item_collected"
    const val ENEMY_KILLED = "enemy_killed"
    const val PLAYER_DIED = "player_died"
    const val CHECKPOINT_REACHED = "checkpoint_reached"
    const val SCENE_LOADED = "scene_loaded"
    const val SCENE_UNLOADED = "scene_unloaded"
    const val PAUSE_CHANGED = "pause_changed"
    const val OBJECT_SPAWNED = "object_spawned"
    const val OBJECT_DESTROYED = "object_destroyed"
    const val PHYSICS_COLLISION = "physics_collision"
    const val PHYSICS_TRIGGER = "physics_trigger"
    const val ANIMATION_FINISHED = "animation_finished"
    const val AUDIO_PLAYED = "audio_played"
    const val AUDIO_STOPPED = "audio_stopped"
    const val UI_BUTTON_CLICKED = "ui_button_clicked"
    const val UI_VALUE_CHANGED = "ui_value_changed"
    const val INPUT_ACTION = "input_action"
    const val TIMER_FINISHED = "timer_finished"
    const val CUSTOM = "custom"
}
