package com.sengine.engine

import com.sengine.engine.anim.AnimationClip
import com.sengine.engine.anim.AnimationSystem
import com.sengine.engine.anim.ClipProvider
import com.sengine.engine.character.CharacterController2D
import com.sengine.engine.character.Ragdoll2D
import com.sengine.engine.core.AudioSource
import com.sengine.engine.core.Camera2D
import com.sengine.engine.core.Component
import com.sengine.engine.core.EventBus
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Health
import com.sengine.engine.core.Lifetime
import com.sengine.engine.fx.ParticleEmitter
import com.sengine.engine.core.Prefab
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.core.Scene
import com.sengine.engine.core.SceneSerializer
import com.sengine.engine.core.SpriteRenderer
import com.sengine.engine.core.TaskScheduler
import com.sengine.engine.fx.Trail2D
import com.sengine.engine.fx.ParticlePresets
import com.sengine.engine.lighting.LightSystem
import com.sengine.engine.physics.Body2D
import com.sengine.engine.physics.Contact2D
import com.sengine.engine.physics.PhysicsWorld2D
import com.sengine.engine.render.EditorState
import com.sengine.engine.render.View2D
import com.sengine.engine.script.ScriptSystem
import com.sengine.engine.tilemap.TilemapData
import com.sengine.engine.tilemap.TilemapRenderer
import com.sengine.engine.ui.UiSystem
import com.sengine.engine.vehicle.Vehicle2D
import com.sengine.project.Project
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The 2D runtime core.
 *
 * Owns the scene and every 2D subsystem - physics, animation, particles, tilemaps, lighting, UI,
 * audio, scripting and the task scheduler - and drives the per-frame update order. Scene mutation
 * from other threads goes through [post]; the simulation runs on the render thread inside [tick].
 *
 * There is exactly one renderer, one camera type and one physics world, and all of them are 2D.
 */
class Engine(val project: Project, initialScene: Scene) : ClipProvider {

    enum class Mode { EDIT, PLAY, PAUSED }

    /** Events the Android layer (activities, editor panels) listens to. */
    interface Listener {
        fun onLog(level: Int, message: String) {}
        fun onModeChanged(mode: Mode) {}
        fun onSceneReplaced() {}
        fun onSelectionChanged() {}
    }

    /** Guards scene mutation against the render thread. */
    val lock = Any()

    @Volatile var scene: Scene = initialScene
        private set
    @Volatile var mode = Mode.EDIT
        private set

    /** The GL renderer bound to this engine (set when the surface is created). */
    @Volatile var renderer: com.sengine.engine.render.SceneRenderer? = null

    /** Set by the editor to enable gizmos/overlays; null during gameplay. */
    @Volatile var editor: EditorState? = null

    // ---------------------------------------------------------------- subsystems
    val input = Input()
    val physics = PhysicsWorld2D()
    val animation = AnimationSystem(this)
    val scripts = ScriptSystem(this)
    val audio = AudioSystem(project)
    val ui = UiSystem()
    val tasks = TaskScheduler()
    val lighting = LightSystem()
    /** Current camera view used by input mapping, the editor and the renderer. */
    val gameView = View2D()

    /** Clip lookup hook - the project asset library implements it. */
    var clipLoader: ((String) -> AnimationClip?)? = null
    /** Set while a UI dialog with a script callback is open. */
    var pendingConfirm: (() -> Any?)? = null

    // ---------------------------------------------------------------- profiler
    var time = 0.0
        private set
    var frame = 0L
        private set
    @Volatile var fps = 0f
        private set
    @Volatile var lastDelta = 1f / 60f
        private set
    @Volatile var scriptMs = 0f
        private set
    @Volatile var physicsMs = 0f
        private set
    @Volatile var animationMs = 0f
        private set
    @Volatile var particlesMs = 0f
        private set
    @Volatile var uiMs = 0f
        private set
    @Volatile var renderMs = 0f
    var drawCalls = 0
    @Volatile var rigidBodyCount = 0
        private set
    @Volatile var activeParticles = 0
        private set
    @Volatile var activeTasks = 0
        private set
    @Volatile var objectsUpdated = 0
        private set
    /** Time scale used by gameplay slow-motion effects. */
    var timeScale = 1f

    private var fpsAcc = 0f
    private var fpsFrames = 0

    val listeners = CopyOnWriteArrayList<Listener>()
    private val commands = ConcurrentLinkedQueue<() -> Unit>()
    private var snapshot: String? = null
    private var snapshotScene = ""
    private var pendingSceneLoad: String? = null
    private val logs = ArrayDeque<String>()
    private val prefabCache = HashMap<String, Prefab>()

    init {
        physics.listener = object : PhysicsWorld2D.Listener {
            override fun onCollisionEnter(a: GameObject, b: GameObject, contact: Contact2D) {
                a.emit("collisionEnter", contact)
                b.emit("collisionEnter", contact)
                a.emit("collision", contact)
                b.emit("collision", contact)
                scripts.collision(a, b, "onCollisionEnter")
                scripts.collision(b, a, "onCollisionEnter")
            }

            override fun onCollisionStay(a: GameObject, b: GameObject, contact: Contact2D) {
                a.emit("collision", contact)
                b.emit("collision", contact)
                scripts.collision(a, b, "onCollision")
                scripts.collision(b, a, "onCollision")
            }

            override fun onCollisionExit(a: GameObject, b: GameObject) {
                scripts.collision(a, b, "onCollisionExit")
                scripts.collision(b, a, "onCollisionExit")
            }

            override fun onTriggerEnter(a: GameObject, b: GameObject, contact: Contact2D) {
                a.emit("triggerEnter", contact)
                b.emit("triggerEnter", contact)
                scripts.collision(a, b, "onTriggerEnter")
                scripts.collision(b, a, "onTriggerEnter")
            }

            override fun onTriggerStay(a: GameObject, b: GameObject, contact: Contact2D) {
                scripts.collision(a, b, "onTrigger")
                scripts.collision(b, a, "onTrigger")
            }

            override fun onTriggerExit(a: GameObject, b: GameObject) {
                scripts.collision(a, b, "onTriggerExit")
                scripts.collision(b, a, "onTriggerExit")
            }
        }
        ui.playSound = { name -> audio.play(name, 1f, false, AudioSystem.Bus.UI) }
        loadTilemapAssets()
    }

    // ---------------------------------------------------------------- clip provider
    override fun clip(name: String): AnimationClip? = clipLoader?.invoke(name)

    // ---------------------------------------------------------------- tilemap assets
    /**
     * Loads the `.tilemap` asset referenced by every [TilemapRenderer] that has no runtime data yet
     * and applies its autotile pass. Called when a scene becomes active (editor load and play mode)
     * so tilemaps survive save/load round trips.
     */
    fun loadTilemapAssets() {
        for (go in scene.objects) {
            val renderer = go.getAny<TilemapRenderer>() ?: continue
            if (renderer.data == null && renderer.map.isNotBlank()) {
                val path = renderer.map
                val raw = project.readAsset(path) ?: project.readAsset("tilemaps/$path")
                if (raw != null) {
                    try {
                        renderer.assignData(TilemapData.fromJson(JSONObject(raw)))
                    } catch (e: Exception) {
                        log(2, "Tilemap '$path': ${e.message}")
                    }
                } else {
                    log(1, "Tilemap asset not found: $path")
                }
            }
            renderer.runAutoTileIfEnabled()
        }
    }

    // ---------------------------------------------------------------- commands (thread-safe)
    fun post(cmd: () -> Unit) {
        commands.add(cmd)
    }

    fun play() = post {
        when (mode) {
            Mode.EDIT -> startPlay()
            Mode.PAUSED -> setMode(Mode.PLAY)
            else -> {}
        }
    }

    fun pause() = post { if (mode == Mode.PLAY) setMode(Mode.PAUSED) }

    fun resume() = post { if (mode == Mode.PAUSED) setMode(Mode.PLAY) }

    fun stop() = post { if (mode != Mode.EDIT) stopPlay() }

    /** Advances exactly one frame while paused (frame stepping in the editor). */
    fun stepFrame() = post { if (mode == Mode.PAUSED) runFrame(1f / 60f) }

    fun replaceScene(s: Scene) {
        scene = s
        loadTilemapAssets()
        listeners.forEach { it.onSceneReplaced() }
    }

    fun requestLoadScene(name: String) {
        pendingSceneLoad = name
    }

    fun log(level: Int, msg: String) {
        synchronized(logs) {
            logs.addLast(msg)
            while (logs.size > 500) logs.removeFirst()
        }
        listeners.forEach { it.onLog(level, msg) }
    }

    fun clearLogs() {
        synchronized(logs) { logs.clear() }
    }

    fun logLines(): List<String> = synchronized(logs) { logs.toList() }

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    private fun setMode(m: Mode) {
        mode = m
        listeners.forEach { it.onModeChanged(m) }
    }

    // ---------------------------------------------------------------- play mode
    private fun startPlay() {
        snapshot = SceneSerializer.toJson(scene).toString()
        snapshotScene = scene.name
        time = 0.0; frame = 0
        timeScale = 1f
        input.clear()
        setMode(Mode.PLAY)
        beginScene()
        log(0, "Play: ${scene.name} (${scene.objects.count { !it.destroyed }} objects)")
    }

    /** (Re)builds runtime state for the current scene: components, bodies, scripts, audio. */
    private fun beginScene() {
        for (go in scene.objects) for (c in go.components) c.resetRuntime()
        physics.reset()
        physics.gravityX = scene.gravityX
        physics.gravityY = scene.gravityY
        ui.clear()
        scripts.begin()
        audio.start()
        audio.resumeAll()
        tasks.cancelAll()
        scene.updateTransforms()
        loadTilemapAssets()
        for (go in scene.objects.toList()) {
            go.getAny<Ragdoll2D>()?.reset()
            go.getAny<Vehicle2D>()?.refresh(scene, physics)
            go.getAny<CharacterController2D>()?.resetState()
            go.getAny<TilemapRenderer>()?.let { if (it.generateCollision) it.buildColliders(scene) }
        }
        snapCameraToTarget()
        scene.updateTransforms()
        updateGameView()
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            val src = go.get<AudioSource>() ?: continue
            if (src.playOnStart && src.clip.isNotBlank()) {
                val volume = src.volume * (1f + (kotlin.random.Random.nextFloat() - 0.5f) * src.pitchVariation)
                if (src.spatial) audio.playAt(src.clip, go.worldX(), go.worldY(), volume, src.loop, src.bus, src.minDistance, src.maxDistance)
                else audio.play(src.clip, volume, src.loop, src.bus, src.pitch)
            }
        }
        EventBus.emit("sceneStarted", scene.name)
    }

    private fun endScene() {
        tasks.cancelAll()
        scripts.end()
        audio.stopAll()
        ui.clear()
        physics.reset()
        EventBus.emit("sceneStopped", scene.name)
    }

    private fun stopPlay() {
        endScene()
        val snap = snapshot
        if (snap != null) scene = SceneSerializer.fromJson(JSONObject(snap))
        loadTilemapAssets()
        snapshot = null
        input.clear()
        setMode(Mode.EDIT)
        listeners.forEach { it.onSceneReplaced() }
        log(0, "Stopped - scene restored")
    }

    private var lastFrameNanos = 0L

    /**
     * Wall-clock seconds since the previous call, clamped to 100 ms (frame spikes must not
     * teleport physics). The GL thread calls this to drive [tick].
     */
    fun frameDelta(): Float {
        val now = System.nanoTime()
        if (lastFrameNanos == 0L) {
            lastFrameNanos = now
            return 1f / 60f
        }
        val dt = ((now - lastFrameNanos) / 1_000_000_000.0).toFloat()
        lastFrameNanos = now
        return dt.coerceIn(0f, 0.1f)
    }

    // ---------------------------------------------------------------- frame loop
    /** Runs one frame. Called on the render thread with [lock] held. */
    fun tick(dt: Float) {
        while (true) {
            val c = commands.poll() ?: break
            try {
                c()
            } catch (e: Exception) {
                log(2, "Engine error: ${e.message}")
            }
        }
        val clamped = dt.coerceIn(0f, 0.1f)
        lastDelta = clamped
        fpsAcc += clamped; fpsFrames++
        if (fpsAcc >= 0.5f) {
            fps = fpsFrames / fpsAcc
            fpsAcc = 0f; fpsFrames = 0
        }
        when (mode) {
            Mode.PLAY -> runFrame(clamped)
            Mode.EDIT -> editFrame(clamped)
            Mode.PAUSED -> {
                scene.updateTransforms()
                updateUi(0f, paused = true)
            }
        }
        activeTasks = tasks.activeCount
        collectTaskFailures()
    }

    /** Editor preview: animation, particles, trails and UI keep running so the scene feels alive. */
    private fun editFrame(dt: Float) {
        val t0 = System.nanoTime()
        scene.updateTransforms()
        updateGameView()
        input.beginFrame(gameView, dt)
        animation.update(scene, dt, playing = false)
        animationMs = smooth(animationMs, t0)
        updateParticles(dt)
        updateTrails(dt)
        updateUi(dt, paused = false)
        updateCameraShake(dt)
        audio.update(dt)
        objectsUpdated = scene.objects.size
    }

    private fun runFrame(dt0: Float) {
        val dt = dt0 * timeScale
        time += dt; frame++
        scene.updateTransforms()
        updateGameView()
        input.beginFrame(gameView, lastDelta)

        var t0 = System.nanoTime()
        scripts.update(dt)
        scriptMs = smooth(scriptMs, t0)
        tasks.update(dt)

        t0 = System.nanoTime()
        updateCharacters(dt)
        updateVehicles(dt)
        physics.step(scene, dt)
        updateGameplayComponents(dt)
        physicsMs = smooth(physicsMs, t0)
        rigidBodyCount = physics.stats.bodies

        cleanupDestroyed()
        updateLifetime(dt)
        scene.updateTransforms()
        updateCameraFollow(dt)
        updateCameraShake(dt)
        updateGameView()

        t0 = System.nanoTime()
        updateParticles(dt)
        updateTrails(dt)
        particlesMs = smooth(particlesMs, t0)

        t0 = System.nanoTime()
        animation.update(scene, dt, playing = true)
        animationMs = smooth(animationMs, t0)

        t0 = System.nanoTime()
        updateUi(dt, paused = false)
        uiMs = smooth(uiMs, t0)

        audio.update(dt)
        handleSceneLoad()
        objectsUpdated = scene.objects.size
    }

    private fun smooth(previous: Float, startNanos: Long): Float =
        previous * 0.85f + ((System.nanoTime() - startNanos) / 1_000_000.0).toFloat() * 0.15f

    private fun handleSceneLoad() {
        val load = pendingSceneLoad ?: return
        pendingSceneLoad = null
        val snap = snapshot
        val fromSnapshot = snap != null && load == snapshotScene
        if (fromSnapshot || project.sceneExists(load)) {
            endScene()
            scene = if (fromSnapshot) SceneSerializer.fromJson(JSONObject(snap!!)) else project.loadScene(load)
            listeners.forEach { it.onSceneReplaced() }
            beginScene()
            log(0, "Loaded scene: $load")
        } else {
            log(2, "Scene not found: $load")
        }
    }

    private fun collectTaskFailures() {
        if (tasks.failed.isEmpty()) return
        for (t in tasks.failed) log(2, "Task '${t.name}' failed: ${t.exception?.message}")
        tasks.clearFailures()
    }

    // ---------------------------------------------------------------- component updates
    private fun updateCharacters(dt: Float) {
        for (go in scene.objects) {
            if (go.destroyed || !go.isActiveInHierarchy()) continue
            val ctrl = go.getAny<CharacterController2D>() ?: continue
            ctrl.update(physics, scene, dt, physics.gravityY)
        }
    }

    private fun updateVehicles(dt: Float) {
        for (go in scene.objects) {
            if (go.destroyed || !go.isActiveInHierarchy()) continue
            val v = go.getAny<Vehicle2D>() ?: continue
            if (v.controlsEnabled) v.update(physics, dt)
        }
    }

    private fun updateGameplayComponents(dt: Float) {
        for (go in scene.objects) {
            if (go.destroyed || !go.isActiveInHierarchy()) continue
            go.getAny<Health>()?.update(dt, go)
            go.getAny<Ragdoll2D>()?.update(dt, go.getAny<Health>())
        }
    }

    private fun cleanupDestroyed() {
        if (scene.objects.none { it.destroyed }) return
        val dead = scene.objects.filter { it.destroyed || isUnderDestroyed(it) }
        for (d in dead) {
            d.destroyed = true
            d.getAny<Rigidbody2D>()?.body?.let { physics.removeBody(it) }
            scripts.onDestroyed(d)
            d.emit("destroyed")
        }
        scene.objects.removeAll(dead.toSet())
        listeners.forEach { it.onSceneReplaced() }
    }

    private fun isUnderDestroyed(go: GameObject): Boolean {
        var p = go.parent
        while (p != null) {
            if (p.destroyed) return true
            p = p.parent
        }
        return false
    }

    private fun updateLifetime(dt: Float) {
        for (go in scene.objects) {
            val life = go.getAny<Lifetime>() ?: continue
            if (!go.isActiveInHierarchy()) continue
            life.time += dt
            if (life.fadeOut) {
                val k = 1f - (life.time / life.lifetime.coerceAtLeast(0.001f))
                if (k < 1f) {
                    go.getAny<SpriteRenderer>()?.let { sr ->
                        val a = (k * com.sengine.engine.math.Colors.alpha(sr.color).toFloat()).toInt().coerceIn(0, 255)
                        sr.color = com.sengine.engine.math.Colors.withAlphaI(sr.color, a)
                    }
                }
            }
            if (life.time >= life.lifetime) {
                go.destroyed = true
                if (life.effect.isNotBlank()) spawnPrefab(life.effect, go.worldX(), go.worldY())
            }
        }
    }

    // ---------------------------------------------------------------- camera
    fun mainCamera(): GameObject? =
        scene.objects.firstOrNull { !it.destroyed && it.isActiveInHierarchy() && it.getAny<Camera2D>() != null }

    fun shake(amount: Float) {
        mainCamera()?.getAny<Camera2D>()?.let { it.shake = maxOf(it.shake, amount) }
    }

    private fun snapCameraToTarget() {
        val camGo = mainCamera() ?: return
        val cam = camGo.getAny<Camera2D>() ?: return
        if (cam.follow.isBlank()) return
        val t = scene.find(cam.follow) ?: return
        camGo.setWorldPosition(t.worldX(), t.worldY())
        cam.targetX = t.worldX(); cam.targetY = t.worldY()
    }

    private fun updateCameraFollow(dt: Float) {
        val camGo = mainCamera() ?: return
        val cam = camGo.getAny<Camera2D>() ?: return
        if (cam.follow.isBlank()) return
        val t = scene.find(cam.follow) ?: return
        val k = if (cam.smoothing <= 0.001f) 1f else (1f - Math.exp((-cam.smoothing * dt).toDouble())).toFloat()
        var targetX = t.worldX() + cam.offsetX + cam.lookAheadX * t.scaleX * 0f
        var targetY = t.worldY() + cam.offsetY
        val rb = t.getAny<Rigidbody2D>()
        if (rb != null && cam.lookAheadX > 0f) targetX += rb.vx * cam.lookAheadX * 0.1f
        if (rb != null && cam.lookAheadY > 0f) targetY += rb.vy * cam.lookAheadY * 0.1f
        // dead zone: only follow when the target leaves it
        val w = camGo.computeWorld()
        if (cam.deadZoneX > 0f && kotlin.math.abs(targetX - w.tx) < cam.deadZoneX * 0.5f) targetX = w.tx
        if (cam.deadZoneY > 0f && kotlin.math.abs(targetY - w.ty) < cam.deadZoneY * 0.5f) targetY = w.ty
        var nx = w.tx + (targetX - w.tx) * k
        var ny = w.ty + (targetY - w.ty) * k
        // limits
        if (cam.limitWidth > 0f) {
            val half = gameView.halfW
            nx = nx.coerceIn(cam.limitX + half, maxOf(cam.limitX + half, cam.limitX + cam.limitWidth - half))
        }
        if (cam.limitHeight > 0f) {
            val half = gameView.halfH
            ny = ny.coerceIn(cam.limitY + half, maxOf(cam.limitY + half, cam.limitY + cam.limitHeight - half))
        }
        camGo.setWorldPosition(nx, ny)
        cam.targetX = targetX; cam.targetY = targetY
        if (cam.rotation != 0f) camGo.rotation = cam.rotation
    }

    private fun updateCameraShake(dt: Float) {
        val cam = mainCamera()?.getAny<Camera2D>() ?: return
        if (cam.shake <= 0f) return
        cam.shake = (cam.shake - cam.shakeDecay * dt * (1f + cam.shake)).coerceAtLeast(0f)
    }

    /** Recomputes [gameView] from the active camera (viewport size is set by the renderer). */
    fun updateGameView() {
        val camGo = mainCamera()
        if (camGo == null) {
            gameView.cx = 0f; gameView.cy = 0f; gameView.size = 5f
            return
        }
        val w = camGo.computeWorld()
        val cam = camGo.getAny<Camera2D>()!!
        gameView.cx = w.tx
        gameView.cy = w.ty
        gameView.size = cam.size
        gameView.rotation = cam.rotation
        cam.viewWidth = gameView.size * gameView.aspect
        cam.viewHeight = gameView.size
        cameraShakeOffset(cam)
        audio.listenerX = gameView.cx
        audio.listenerY = gameView.cy
        audio.listenerHalfWidth = gameView.halfW
    }

    /** Screen-shake offset applied to the rendered view (kept deterministic per frame). */
    fun cameraShakeOffset(cam: Camera2D): FloatArray {
        if (cam.shake <= 0.001f) return ZERO2
        val amp = cam.shake * 0.35f
        val t = (time * 37.0).toFloat()
        shakeBuf[0] = kotlin.math.sin(t * 3.1f) * amp
        shakeBuf[1] = kotlin.math.cos(t * 4.7f) * amp
        return shakeBuf
    }

    fun backgroundColor(): Int = mainCamera()?.getAny<Camera2D>()?.background ?: 0xFF14181F.toInt()

    // ---------------------------------------------------------------- particles / trails
    private fun updateParticles(dt: Float) {
        var alive = 0
        for (go in scene.objects) {
            val emitter = go.getAny<ParticleEmitter>() ?: continue
            if (go.destroyed) continue
            val sys = emitter.ensureSystem()
            val active = go.isActiveInHierarchy() && emitter.enabled
            if (active) {
                emitter.update(physics, scene, dt, go.computeWorld())
                spawnSubEmitters(emitter, go)
            } else {
                sys.emitting = false
                sys.update(dt)
            }
            if (emitter.destroyWhenFinished && active && emitter.isIdle()) go.destroyed = true
            alive += sys.count
        }
        activeParticles = alive
    }

    private fun spawnSubEmitters(emitter: ParticleEmitter, go: GameObject) {
        if (emitter.subEmitterSpawns.isEmpty()) return
        for (p in emitter.subEmitterSpawns) {
            val child = spawnPrefab(emitter.subEmitterPrefab, p[0], p[1])
            child?.getAny<ParticleEmitter>()?.let { it.burst() }
        }
        emitter.subEmitterSpawns.clear()
    }

    private fun updateTrails(dt: Float) {
        for (go in scene.objects) {
            if (go.destroyed || !go.isActiveInHierarchy()) continue
            val trail = go.getAny<Trail2D>() ?: continue
            val w = go.computeWorld()
            if (trail.emitting) trail.push(w.tx, w.ty, dt) else trail.update(dt)
        }
    }

    // ---------------------------------------------------------------- UI
    private fun updateUi(dt: Float, paused: Boolean) {
        input.feedUi(ui.input)
        ui.update(scene, dt, paused)
        ui.input.clearFrame()
        input.typed.setLength(0)
    }

    fun findComponentOwner(c: Component): GameObject? = scene.objects.firstOrNull { c in it.components }

    // ---------------------------------------------------------------- spawning / state
    /** Instantiates a prefab asset (`.prefab` JSON file) into the running scene. */
    fun spawnPrefab(name: String, x: Float, y: Float, parent: GameObject? = null): GameObject? {
        if (name.isBlank()) return null
        val prefab = loadPrefab(name) ?: return null
        val go = prefab.instantiate(scene, parent)
        go.x = x; go.y = y
        scene.updateTransforms()
        if (mode != Mode.EDIT) scripts.attach(go)
        return go
    }

    fun loadPrefab(name: String): Prefab? {
        prefabCache[name]?.let { return it }
        val raw = project.readAsset(name) ?: project.readAsset("prefabs/$name") ?: return null
        return try {
            val p = Prefab.fromJson(JSONObject(raw))
            prefabCache[name] = p
            p
        } catch (e: Exception) {
            log(2, "Prefab $name: ${e.message}")
            null
        }
    }

    fun clearPrefabCache() = prefabCache.clear()

    /** Spawns a self-contained particle effect object (used by scripts and the particles API). */
    fun spawnParticles(presetName: String, x: Float, y: Float, destroyAfter: Float = 0f): GameObject? {
        val preset = ParticlePresets.find(presetName) ?: return null
        val go = scene.create("FX_${preset.name}")
        go.tag = "FX"
        go.x = x; go.y = y
        val emitter = ParticleEmitter()
        go.add(emitter)
        emitter.applyPreset(preset.name)
        if (destroyAfter > 0f) {
            val life = Lifetime()
            life.lifetime = destroyAfter
            go.add(life)
        }
        return go
    }

    /** Serialises the current scene into a save slot. */
    fun saveState(slot: String): Boolean = try {
        project.writeState(slot, SceneSerializer.toJson(scene).toString())
        log(0, "Saved state: $slot")
        true
    } catch (e: Exception) {
        log(2, "Save failed: ${e.message}")
        false
    }

    /** Restores a scene from a save slot. */
    fun loadState(slot: String): Boolean {
        val raw = project.readState(slot) ?: return false
        return try {
            val s = SceneSerializer.fromJson(JSONObject(raw))
            endScene()
            scene = s
            listeners.forEach { it.onSceneReplaced() }
            if (mode == Mode.PLAY) beginScene()
            log(0, "Loaded state: $slot")
            true
        } catch (e: Exception) {
            log(2, "Load failed: ${e.message}")
            false
        }
    }

    fun hasState(slot: String) = project.hasState(slot)
    fun listStates() = project.listStates()

    /** Audio bus of an object's [AudioSource] (defaults to SFX). */
    fun audioBusOf(go: GameObject): Int = go.getAny<AudioSource>()?.bus ?: AudioSystem.Bus.SFX

    fun release() {
        synchronized(lock) {
            if (mode != Mode.EDIT) endScene()
            audio.release()
            scripts.end()
        }
    }


    fun statsText(): String =
        "fps=${fps.toInt()} objs=${scene.objects.size} bodies=$rigidBodyCount particles=$activeParticles " +
            "tasks=$activeTasks time=${"%.1f".format(time)}"

    private companion object {
        val ZERO2 = FloatArray(2)
        val shakeBuf = FloatArray(2)
    }
}
