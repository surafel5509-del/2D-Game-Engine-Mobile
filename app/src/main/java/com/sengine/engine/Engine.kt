package com.sengine.engine

import com.sengine.engine.core.AudioSource
import com.sengine.engine.core.Camera2D
import com.sengine.engine.core.CameraSystem
import com.sengine.engine.core.Component
import com.sengine.engine.physics.CharacterBody
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.ObjectPool
import com.sengine.engine.core.ParticleEmitter
import com.sengine.engine.core.Scene
import com.sengine.engine.core.SceneSerializer
import com.sengine.engine.core.SignalBus
import com.sengine.engine.debug.DebugConsole
import com.sengine.engine.debug.DebugOverlay
import com.sengine.engine.debug.Profiler
import com.sengine.engine.physics.PhysicsWorld
import com.sengine.engine.render.View2D
import com.sengine.engine.resource.ResourceManager
import com.sengine.engine.save.SaveSystem
import com.sengine.engine.script.ScriptSystem
import com.sengine.project.Project
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * S Engine 2D — Production Runtime Core
 *
 * Integrates physics, scripting, rendering, audio, UI, save/load,
 * signals, pooling, profiling, and camera systems into a unified game loop.
 */
class Engine(val project: Project, initialScene: Scene) {

    enum class Mode { EDIT, PLAY, PAUSED }

    interface Listener {
        fun onLog(level: Int, message: String) {}
        fun onModeChanged(mode: Mode) {}
        fun onSceneReplaced() {}
    }

    val lock = Any()
    @Volatile var scene: Scene = initialScene
        private set
    @Volatile var mode = Mode.EDIT
        private set

    // Core systems
    val input = Input()
    val physics = PhysicsWorld()
    val scripts = ScriptSystem(this)
    val audio = AudioSystem(project)
    val gameView = View2D()

    // Professional systems
    val signalBus = SignalBus()
    val saveSystem = SaveSystem(this)
    val objectPool = ObjectPool(initialScene)
    val resources = ResourceManager(project)
    val profiler = Profiler()
    val debugConsole = DebugConsole()
    val debugOverlay = DebugOverlay()
    var cameraSystem = CameraSystem()

    // Time
    var time = 0.0; private set
    var frame = 0L; private set
    @Volatile var fps = 0f; private set
    private var fpsAcc = 0f
    private var fpsFrames = 0
    var deltaTime = 0f; private set

    val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()
    private val commands = ConcurrentLinkedQueue<() -> Unit>()
    private var snapshot: String? = null
    private var snapshotScene = ""
    private var pendingSceneLoad: String? = null

    val logs = ArrayDeque<String>()

    init {
        physics.listener = scripts
        registerDefaultDebugCommands()
    }

    // ================================================================ Commands
    fun post(cmd: () -> Unit) { commands.add(cmd) }

    fun play() = post {
        when (mode) {
            Mode.EDIT -> startPlay()
            Mode.PAUSED -> setMode(Mode.PLAY)
            else -> {}
        }
    }

    fun pause() = post { if (mode == Mode.PLAY) setMode(Mode.PAUSED) }
    fun stop() = post { if (mode != Mode.EDIT) stopPlay() }
    fun stepFrame() = post { if (mode == Mode.PAUSED) runFrame(1f / 60f) }

    /** Replace the edited scene (editor only, call while holding lock). */
    fun replaceScene(s: Scene) {
        scene = s
        listeners.forEach { it.onSceneReplaced() }
    }

    fun requestLoadScene(name: String) { pendingSceneLoad = name }

    fun log(level: Int, msg: String) {
        synchronized(logs) {
            logs.addLast(msg)
            while (logs.size > 500) logs.removeFirst()
        }
        debugConsole.addLine(msg, level)
        listeners.forEach { it.onLog(level, msg) }
    }

    private fun setMode(m: Mode) {
        mode = m
        signalBus.emit("mode_changed", m.name)
        listeners.forEach { it.onModeChanged(m) }
    }

    // ================================================================ Play mode
    private fun startPlay() {
        snapshot = SceneSerializer.toJson(scene).toString()
        snapshotScene = scene.name
        time = 0.0; frame = 0
        input.clear()
        setMode(Mode.PLAY)
        beginScene()
        log(0, "▶ Play: ${scene.name}")
    }

    private fun beginScene() {
        for (go in scene.objects) for (c in go.components) c.resetRuntime()
        physics.reset()
        physics.syncJoints(scene)
        audio.start()
        scene.updateTransforms()
        initCameraSystem()
        snapCameraToTarget()
        updateGameView()
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            go.get<AudioSource>()?.let { if (it.playOnStart) audio.play(it.clip, it.volume, it.loop) }
        }
        scripts.begin()
        signalBus.emit("scene_loaded", scene.name)
        profiler.objectCount = scene.objects.size
    }

    private fun initCameraSystem() {
        val camGo = mainCamera() ?: return
        val cam = camGo.get<Camera2D>()!!
        cameraSystem = CameraSystem()
        cameraSystem.followSmoothing = cam.smoothing
        cameraSystem.targetSize = cam.size
        cameraSystem.currentSize = cam.size
        cameraSystem.minZoom = cam.zoomMin
        cameraSystem.maxZoom = cam.zoomMax
        if (cam.boundsEnabled) {
            cameraSystem.setBounds(cam.boundsMinX, cam.boundsMinY, cam.boundsMaxX, cam.boundsMaxY)
        }
        if (cam.follow.isNotBlank()) {
            cameraSystem.followTarget = scene.find(cam.follow)
        }
    }

    private fun endScene() {
        scripts.end()
        audio.stop()
        signalBus.emit("scene_unloaded", scene.name)
    }

    private fun stopPlay() {
        endScene()
        val snap = snapshot
        if (snap != null) scene = SceneSerializer.fromJson(JSONObject(snap))
        snapshot = null
        input.clear()
        setMode(Mode.EDIT)
        listeners.forEach { it.onSceneReplaced() }
        log(0, "■ Stopped")
    }

    // ================================================================ Main loop
    /** Called on the GL thread with [lock] held. */
    fun tick(dt: Float) {
        profiler.beginFrame()
        profiler.begin("commands")
        while (true) {
            val c = commands.poll() ?: break
            try { c() } catch (e: Exception) { log(2, "Engine error: ${e.message}") }
        }
        profiler.end("commands")

        fpsAcc += dt; fpsFrames++
        if (fpsAcc >= 0.5f) { fps = fpsFrames / fpsAcc; fpsAcc = 0f; fpsFrames = 0 }

        when (mode) {
            Mode.PLAY -> runFrame(dt)
            Mode.EDIT -> { scene.updateTransforms(); updateParticles(dt) }
            Mode.PAUSED -> scene.updateTransforms()
        }

        // Update profiler stats
        profiler.objectCount = scene.objects.size
        profiler.physicsBodies = physics.bodyCount(scene)
        profiler.scriptInstances = scripts.instanceCount()
        profiler.particleCount = countParticles()
        profiler.memoryUsageMB = profiler.estimateMemory()
    }

    private fun runFrame(dt0: Float) {
        val dt = dt0.coerceAtMost(0.1f)
        deltaTime = dt
        time += dt; frame++

        profiler.begin("transforms")
        scene.updateTransforms()
        profiler.end("transforms")

        profiler.begin("gameview")
        updateGameView()
        profiler.end("gameview")

        profiler.begin("input")
        input.beginFrame(gameView)
        profiler.end("input")

        profiler.begin("scripts")
        scripts.update(dt)
        profiler.end("scripts")

        profiler.begin("characterBodies")
        updateCharacterBodies(dt)
        profiler.end("characterBodies")

        profiler.begin("physics")
        physics.step(scene, dt)
        profiler.end("physics")

        profiler.begin("cleanup")
        cleanupDestroyed()
        profiler.end("cleanup")

        profiler.begin("transforms2")
        scene.updateTransforms()
        profiler.end("transforms2")

        profiler.begin("camera")
        cameraSystem.update(gameView, dt)
        profiler.end("camera")

        profiler.begin("gameview2")
        updateGameView()
        profiler.end("gameview2")

        profiler.begin("particles")
        updateParticles(dt)
        profiler.end("particles")

        profiler.begin("animations")
        updateAnimations(dt)
        profiler.end("animations")

        // Process resource queue
        resources.processLoadQueue(2)

        // Handle scene load requests
        val load = pendingSceneLoad
        if (load != null) {
            pendingSceneLoad = null
            val snap = snapshot
            val fromSnapshot = snap != null && load == snapshotScene
            if (fromSnapshot || project.sceneExists(load)) {
                endScene()
                scene = if (fromSnapshot) SceneSerializer.fromJson(JSONObject(snap!!)) else project.loadScene(load)
                listeners.forEach { it.onSceneReplaced() }
                beginScene()
                log(0, "Loaded scene $load")
            } else log(2, "Scene not found: $load")
        }
    }

    private fun updateCharacterBodies(dt: Float) {
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            val cb = go.components.filterIsInstance<CharacterBody>().firstOrNull() ?: continue
            if (cb.enabled) cb.updatePhysics(scene, dt)
        }
    }

    private fun updateAnimations(dt: Float) {
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            val anim = go.components.filterIsInstance<com.sengine.engine.animation.SpriteAnimator>().firstOrNull() ?: continue
            if (!anim.enabled) continue
            val event = anim.updateAnimation(dt)
            if (event != null) {
                signalBus.emit("animation_event", go.name, event)
            }
        }
    }

    private fun cleanupDestroyed() {
        if (scene.objects.none { it.destroyed }) return
        val dead = scene.objects.filter { it.destroyed || isUnderDestroyed(it) }
        for (d in dead) {
            d.destroyed = true
            scripts.onDestroyed(d)
            signalBus.emit("object_destroyed", d.name)
        }
        scene.objects.removeAll(dead.toSet())
        listeners.forEach { it.onSceneReplaced() }
    }

    private fun isUnderDestroyed(go: GameObject): Boolean {
        var p = go.parent
        while (p != null) { if (p.destroyed) return true; p = p.parent }
        return false
    }

    fun mainCamera(): GameObject? = scene.objects.firstOrNull { it.isActiveInHierarchy() && it.get<Camera2D>() != null }

    private fun snapCameraToTarget() {
        val camGo = mainCamera() ?: return
        val cam = camGo.get<Camera2D>()!!
        if (cam.follow.isBlank()) return
        val t = scene.find(cam.follow) ?: return
        camGo.setWorldPosition(t.world.tx, t.world.ty)
    }

    fun updateGameView() {
        val camGo = mainCamera()
        if (camGo == null) {
            gameView.cx = 0f; gameView.cy = 0f; gameView.size = 5f
            return
        }
        val w = camGo.computeWorld()
        gameView.cx = w.tx; gameView.cy = w.ty
        gameView.size = camGo.get<Camera2D>()!!.size
    }

    fun backgroundColor(): Int = mainCamera()?.get<Camera2D>()?.background ?: 0xFF1B2533.toInt()

    // ================================================================ Particles
    private fun updateParticles(dt: Float) {
        for (go in scene.objects) {
            val pe = go.getAny<ParticleEmitter>() ?: continue
            val alive = go.isActiveInHierarchy() && pe.enabled
            val w = go.world
            if (alive && pe.emitting) pe.accumulator += pe.rate * dt
            var toEmit = pe.accumulator.toInt() + pe.pendingBurst
            pe.accumulator -= pe.accumulator.toInt()
            pe.pendingBurst = 0
            if (!alive) toEmit = 0
            while (toEmit-- > 0 && pe.particles.size < pe.maxParticles) {
                val ang = Math.toRadians((pe.direction + w.rotationDeg + (Random.nextFloat() - 0.5f) * pe.spread).toDouble())
                val sp = pe.speed * (0.6f + Random.nextFloat() * 0.8f)
                pe.particles.add(
                    ParticleEmitter.Particle(
                        w.tx, w.ty, (cos(ang) * sp).toFloat(), (sin(ang) * sp).toFloat(),
                        0f, pe.lifetime * (0.7f + Random.nextFloat() * 0.6f),
                        rotation = 0f, rotSpeed = pe.rotationSpeed * (0.5f + Random.nextFloat()),
                        startSize = pe.startSize
                    )
                )
            }
            val it = pe.particles.iterator()
            while (it.hasNext()) {
                val p = it.next()
                p.age += dt
                if (p.age >= p.life) { it.remove(); continue }
                p.vy += pe.gravity * dt
                p.x += p.vx * dt; p.y += p.vy * dt
                p.rotation += p.rotSpeed * dt
            }
        }
    }

    private fun countParticles(): Int {
        var count = 0
        for (go in scene.objects) {
            count += go.getAny<ParticleEmitter>()?.particles?.size ?: 0
        }
        return count
    }

    fun findComponentOwner(c: Component): GameObject? = scene.objects.firstOrNull { c in it.components }

    // ================================================================ Debug
    private fun registerDefaultDebugCommands() {
        debugConsole.registerCommand("spawn", "Spawn object by name") { args ->
            if (args.isEmpty()) return@registerDefaultDebugCommands "Usage: spawn <name>"
            val name = args.joinToString(" ")
            val go = scene.find(name) ?: return@registerDefaultDebugCommands "Not found: $name"
            val copy = scene.duplicate(go, null)
            copy.setWorldPosition(gameView.cx, gameView.cy)
            "Spawned ${copy.name}"
        }
        debugConsole.registerCommand("count", "Count objects") { _ ->
            "Objects: ${scene.objects.size}"
        }
        debugConsole.registerCommand("fps", "Show FPS") { _ ->
            "FPS: ${"%.1f".format(fps)}"
        }
        debugConsole.registerCommand("gravity", "Set gravity (x y)") { args ->
            if (args.size >= 2) {
                scene.gravityX = args[0].toFloatOrNull() ?: 0f
                scene.gravityY = args[1].toFloatOrNull() ?: -9.81f
            }
            "Gravity: (${scene.gravityX}, ${scene.gravityY})"
        }
        debugConsole.registerCommand("save", "Save to slot") { args ->
            val slot = args.firstOrNull()?.toIntOrNull() ?: 0
            saveSystem.saveScene(slot)
            "Saved to slot $slot"
        }
        debugConsole.registerCommand("load", "Load from slot") { args ->
            val slot = args.firstOrNull()?.toIntOrNull() ?: 0
            if (saveSystem.loadScene(slot)) "Loaded slot $slot" else "No save in slot $slot"
        }
        debugConsole.registerCommand("pool", "Show pool stats") { _ ->
            val stats = objectPool.stats()
            "Pools: ${stats.poolCount}, Active: ${stats.activeObjects}, Available: ${stats.availableObjects}"
        }
        debugConsole.registerCommand("perf", "Show performance report") { _ ->
            profiler.report()
        }
    }

    fun release() {
        synchronized(lock) {
            if (mode != Mode.EDIT) endScene()
            audio.stop()
            signalBus.clear()
        }
    }
}
