package com.sengine.engine.script

import com.sengine.engine.Engine
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.ScriptComponent
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Script
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/**
 * JavaScript behaviour runtime backed by Mozilla Rhino (interpreted mode).
 * Every Script component gets its own scope whose prototype is the shared global scope.
 */
class ScriptSystem(val engine: Engine) {

    private class Instance(val go: GameObject, val comp: ScriptComponent, val scope: Scriptable) {
        var started = false
        var failed = false
    }

    private var cx: Context? = null
    private var global: ScriptableObject? = null
    private var ownerThread: Thread? = null
    private val instances = ArrayList<Instance>()
    private val wrappers = HashMap<Long, SObject>()
    private val compiled = HashMap<String, Script>()
    private val inputApi = SInput(engine)

    val isRunning get() = cx != null

    fun begin() {
        end()
        val c = Context.enter()
        c.optimizationLevel = -1
        c.languageVersion = Context.VERSION_ES6
        // return Java strings / numbers / booleans as native JS values
        c.wrapFactory.isJavaPrimitiveWrap = false
        cx = c
        ownerThread = Thread.currentThread()
        val g = c.initStandardObjects()
        global = g
        put(g, "input", inputApi)
        put(g, "time", STime(engine))
        put(g, "scene", SScene(engine, this))
        put(g, "audio", SAudio(engine))
        put(g, "console", SConsole(engine))
        put(g, "physics", SPhysics(engine, this))
        put(g, "camera", SCamera(engine))
        put(g, "ui", SUi(engine, this))
        put(g, "particles", SParticles(engine, this))
        put(g, "tasks", STasks(engine, this))
        put(g, "random", SRandom())
        put(g, "noise", SNoise())
        put(g, "save", SSave(engine, this))
        put(g, "engine", SEngineInfo(engine))
        c.evaluateString(g, PRELUDE, "prelude", 1, null)
        compiled.clear()
        for (go in engine.scene.objects.toList()) attach(go)
        startPending()
    }

    fun end() {
        if (cx != null && Thread.currentThread() === ownerThread) {
            for (inst in instances.toList()) if (inst.started && !inst.failed) call(inst, "onStop")
            try { Context.exit() } catch (_: Exception) {}
        }
        cx = null
        global = null
        ownerThread = null
        instances.clear()
        wrappers.clear()
        compiled.clear()
    }

    private fun put(scope: Scriptable, name: String, obj: Any) {
        ScriptableObject.putProperty(scope, name, Context.javaToJS(obj, scope))
    }

    fun wrap(go: GameObject): SObject = wrappers.getOrPut(go.id) { SObject(go, engine, this) }

    fun newArray(items: List<Any?>): Scriptable? {
        val c = cx ?: return null
        val g = global ?: return null
        return c.newArray(g, items.toTypedArray())
    }

    /** Create script instances for a (newly spawned) object and its children. */
    fun attach(go: GameObject) {
        val c = cx ?: return
        val g = global ?: return
        for (comp in go.components) {
            if (comp !is ScriptComponent || comp.script.isBlank()) continue
            val script = compiled[comp.script] ?: run {
                val raw = engine.project.readAsset(comp.script)
                val src = if (raw != null && comp.script.endsWith(".bp")) {
                    try { com.sengine.engine.blueprint.BlueprintCompiler.compile(com.sengine.engine.blueprint.Blueprint.parse(raw)) }
                    catch (e: Exception) { engine.log(2, "Blueprint ${comp.script}: ${e.message}"); null }
                } else raw
                if (src == null) {
                    engine.log(2, "Script not found: ${comp.script} (on ${go.name})")
                    null
                } else try {
                    c.compileString(src, comp.script, 1, null).also { compiled[comp.script] = it }
                } catch (e: RhinoException) {
                    engine.log(2, "${comp.script}:${e.lineNumber()} ${e.details()}")
                    null
                }
            } ?: continue
            val scope = c.newObject(g)
            scope.prototype = g
            scope.parentScope = null
            val self = Context.javaToJS(wrap(go), g)
            ScriptableObject.putProperty(scope, "self", self)
            ScriptableObject.putProperty(scope, "transform", self)
            ScriptableObject.putProperty(scope, "gameObject", self)
            applyParams(scope, comp.params)
            val inst = Instance(go, comp, scope)
            try {
                script.exec(c, scope)
                // Inspector params override top-level defaults like `var speed = 5;`
                applyParams(scope, comp.params)
                instances.add(inst)
            } catch (e: RhinoException) {
                engine.log(2, "${comp.script}:${e.lineNumber()} ${e.details()}")
            } catch (e: Exception) {
                engine.log(2, "${comp.script}: ${e.message}")
            }
        }
        for (child in engine.scene.childrenOf(go)) attach(child)
    }

    private fun applyParams(scope: Scriptable, params: String) {
        for (raw in params.split(',', '\n', ';')) {
            val kv = raw.split('=', limit = 2)
            if (kv.size != 2) continue
            val k = kv[0].trim()
            val v = kv[1].trim()
            if (k.isEmpty()) continue
            val value: Any = v.toDoubleOrNull() ?: when (v.lowercase()) {
                "true" -> true
                "false" -> false
                else -> v.trim('"', '\'')
            }
            ScriptableObject.putProperty(scope, k, value)
        }
    }

    private fun startPending() {
        for (inst in instances.toList()) {
            if (!inst.started && inst.go.isActiveInHierarchy() && inst.comp.enabled) {
                inst.started = true
                call(inst, "start")
            }
        }
    }

    fun update(dt: Float) {
        if (cx == null) return
        startPending()
        val dtArg = dt.toDouble()
        val input = engine.input
        var tapTarget: GameObject? = null
        if (input.tapped) {
            tapTarget = engine.physics.overlapCircle(input.touchX, input.touchY, 0.35f)
                .firstOrNull { it.go != null }?.go
        }
        for (inst in instances.toList()) {
            if (inst.failed || !inst.started || inst.go.destroyed) continue
            if (!inst.go.isActiveInHierarchy() || !inst.comp.enabled) continue
            call(inst, "update", dtArg)
            if (tapTarget != null && tapTarget === inst.go) call(inst, "onTap")
        }
        val g = global ?: return
        val tick = g.get("__tick", g)
        if (tick is Function) {
            try { tick.call(cx, g, g, emptyArray()) } catch (e: RhinoException) {
                engine.log(2, "timer: line ${e.lineNumber()} ${e.details()}")
            } catch (e: Exception) { engine.log(2, "timer: ${e.message}") }
        }
    }

    private fun call(inst: Instance, fname: String, vararg args: Any?): Any? {
        val f = inst.scope.get(fname, inst.scope)
        if (f !is Function) return null
        return try {
            f.call(cx, inst.scope, inst.scope, arrayOf(*args))
        } catch (e: RhinoException) {
            engine.log(2, "${inst.comp.script}:${e.lineNumber()} in $fname(): ${e.details()}")
            inst.failed = true
            null
        } catch (e: Exception) {
            engine.log(2, "${inst.comp.script} in $fname(): ${e.message}")
            inst.failed = true
            null
        }
    }

    fun sendMessage(go: GameObject, fname: String, arg: Any?): Any? {
        var result: Any? = null
        for (inst in instances.toList()) {
            if (inst.go === go && !inst.failed) result = call(inst, fname, arg) ?: result
        }
        return result
    }

    private fun dispatch(go: GameObject, fname: String, other: GameObject) {
        if (cx == null) return
        val o = toJs(other)
        for (inst in instances.toList()) {
            if (inst.go === go && inst.started && !inst.failed && inst.comp.enabled) call(inst, fname, o)
        }
    }

    fun onDestroyed(go: GameObject) {
        for (inst in instances.toList()) if (inst.go === go) {
            if (inst.started && !inst.failed) call(inst, "onDestroy")
            instances.remove(inst)
        }
        wrappers.remove(go.id)
    }

    /** Contact dispatch used by the engine's physics listener. */
    fun collision(go: GameObject, other: GameObject, function: String) {
        dispatch(go, function, other)
    }

    /** Calls a JS function passed into Kotlin (task callbacks, signal slots, UI dialogs). */
    fun callFunction(fn: Function?, vararg args: Any?): Any? {
        val c = cx ?: return null
        val g = global ?: return null
        if (fn == null) return null
        return try {
            fn.call(c, g, g, arrayOf<Any?>(*args))
        } catch (e: RhinoException) {
            engine.log(2, "script callback:${e.lineNumber()} ${e.details()}")
            null
        } catch (e: Exception) {
            engine.log(2, "script callback: ${e.message}")
            null
        }
    }

    /** Wraps a Kotlin/engine value so scripts can use it (null stays null). */
    fun toJs(value: Any?): Any? {
        val g = global ?: return value
        return when (value) {
            null -> null
            is SObject -> Context.javaToJS(value, g)
            is GameObject -> Context.javaToJS(wrap(value), g)
            else -> Context.javaToJS(value, g)
        }
    }

    companion object {
        const val PRELUDE = """
function log() { var s = []; for (var i = 0; i < arguments.length; i++) s.push(String(arguments[i])); console.log(s.join(' ')); }
function warn(m) { console.warn(String(m)); }
function error(m) { console.error(String(m)); }
function random(a, b) { if (a === undefined) return Math.random(); return a + Math.random() * (b - a); }
function randomInt(a, b) { return Math.floor(random(a, b + 1)); }
function clamp(v, a, b) { return Math.max(a, Math.min(b, v)); }
function clamp01(v) { return Math.max(0, Math.min(1, v)); }
function lerp(a, b, t) { return a + (b - a) * t; }
function moveTowards(a, b, step) { var d = b - a; if (Math.abs(d) <= step) return b; return a + Math.sign(d) * step; }
function sign(v) { return v < 0 ? -1 : (v > 0 ? 1 : 0); }
function approach(cur, target, speed, dt) { return moveTowards(cur, target, speed * dt); }
function deg2rad(d) { return d * Math.PI / 180; }
function rad2deg(r) { return r * 180 / Math.PI; }
function dist(ax, ay, bx, by) { var dx = bx - ax, dy = by - ay; return Math.sqrt(dx * dx + dy * dy); }
function angleTo(ax, ay, bx, by) { return rad2deg(Math.atan2(by - ay, bx - ax)); }
function smoothstep(t) { t = clamp01(t); return t * t * (3 - 2 * t); }
function damp(current, target, smoothing, dt) { return lerp(current, target, 1 - Math.exp(-smoothing * dt)); }
function chance(p) { return Math.random() < p; }
function pick(arr) { return arr[Math.floor(Math.random() * arr.length)]; }
function repeat(n, fn) { for (var i = 0; i < n; i++) fn(i); }
function println() { log.apply(null, arguments); }
function find(name) { return scene.find(name); }
function findByTag(tag) { return scene.findTag(tag); }
function allByTag(tag) { return scene.findAllTag(tag); }
function spawn(name, x, y) { return scene.instantiate(name, x, y); }
function emit() { scene.emit.apply(scene, arguments); }
function on() { scene.on.apply(scene, arguments); }
function spawnParticles(preset, x, y) { return particles.play(preset, x, y, 2); }
function after(sec, fn) { return tasks.after(sec, function () { try { fn(); } catch (e) { console.error(e); } }); }
function every(sec, fn) { return tasks.every(sec, function () { try { fn(); } catch (e) { console.error(e); } }); }
function sequence(list) { for (var i = 0; i < list.length; i++) list[i](); }
"""
    }
}
