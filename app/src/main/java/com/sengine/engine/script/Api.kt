package com.sengine.engine.script

import com.sengine.engine.Engine
import com.sengine.engine.core.Camera2D
import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.Component
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.ParticleEmitter
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.core.SpriteRenderer
import com.sengine.engine.core.TextRenderer
import com.sengine.engine.physics.RaycastQuery
import com.sengine.engine.physics.Raycaster
import org.mozilla.javascript.Context
import kotlin.math.sqrt

/**
 * Objects exposed to JavaScript scripts.
 * These provide the scripting API for game logic.
 */

class SObject(private val go: GameObject, private val engine: Engine, private val sys: ScriptSystem) {
    fun getId(): Double = go.id.toDouble()
    fun getName(): String = go.name
    fun setName(v: String) { go.name = v }
    fun getTag(): String = go.tag
    fun setTag(v: String) { go.tag = v }
    fun getActive(): Boolean = go.active
    fun setActive(v: Boolean) { go.active = v }
    fun getOrder(): Double = go.order.toDouble()
    fun setOrder(v: Double) { go.order = v.toInt() }

    fun getX(): Double = go.x.toDouble()
    fun setX(v: Double) { go.x = v.toFloat() }
    fun getY(): Double = go.y.toDouble()
    fun setY(v: Double) { go.y = v.toFloat() }
    fun getRotation(): Double = go.rotation.toDouble()
    fun setRotation(v: Double) { go.rotation = v.toFloat() }
    fun getScaleX(): Double = go.scaleX.toDouble()
    fun setScaleX(v: Double) { go.scaleX = v.toFloat() }
    fun getScaleY(): Double = go.scaleY.toDouble()
    fun setScaleY(v: Double) { go.scaleY = v.toFloat() }
    fun getWorldX(): Double = go.computeWorld().tx.toDouble()
    fun getWorldY(): Double = go.computeWorld().ty.toDouble()

    fun setPosition(x: Double, y: Double) { go.x = x.toFloat(); go.y = y.toFloat() }
    fun setWorldPosition(x: Double, y: Double) { go.setWorldPosition(x.toFloat(), y.toFloat()) }
    fun move(dx: Double, dy: Double) { go.x += dx.toFloat(); go.y += dy.toFloat() }
    fun rotate(deg: Double) { go.rotation += deg.toFloat() }
    fun lookAt(x: Double, y: Double) {
        val dx = x.toFloat() - go.x
        val dy = y.toFloat() - go.y
        go.rotation = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }

    // physics
    fun getVx(): Double = (go.getAny<Rigidbody2D>()?.vx ?: 0f).toDouble()
    fun setVx(v: Double) { go.getAny<Rigidbody2D>()?.vx = v.toFloat() }
    fun getVy(): Double = (go.getAny<Rigidbody2D>()?.vy ?: 0f).toDouble()
    fun setVy(v: Double) { go.getAny<Rigidbody2D>()?.vy = v.toFloat() }
    fun isGrounded(): Boolean = go.getAny<Rigidbody2D>()?.grounded ?: false
    fun getMass(): Double = (go.getAny<Rigidbody2D>()?.mass ?: 1f).toDouble()
    fun setMass(v: Double) { go.getAny<Rigidbody2D>()?.mass = v.toFloat() }
    fun addForce(fx: Double, fy: Double) {
        val rb = go.getAny<Rigidbody2D>() ?: return
        rb.addForce(fx.toFloat(), fy.toFloat())
    }
    fun addImpulse(ix: Double, iy: Double) {
        val rb = go.getAny<Rigidbody2D>() ?: return
        rb.addImpulse(ix.toFloat(), iy.toFloat())
    }
    fun addTorque(t: Double) {
        go.getAny<Rigidbody2D>()?.addTorque(t.toFloat())
    }
    fun setVelocity(vx: Double, vy: Double) {
        val rb = go.getAny<Rigidbody2D>() ?: return
        rb.vx = vx.toFloat(); rb.vy = vy.toFloat()
    }
    fun getAngularVelocity(): Double = (go.getAny<Rigidbody2D>()?.angularVelocity ?: 0f).toDouble()
    fun setAngularVelocity(v: Double) { go.getAny<Rigidbody2D>()?.angularVelocity = v.toFloat() }
    fun setBodyType(t: Double) { go.getAny<Rigidbody2D>()?.bodyType = t.toInt() }
    fun setGravityScale(v: Double) { go.getAny<Rigidbody2D>()?.gravityScale = v.toFloat() }
    fun overlaps(other: SObject): Boolean {
        val a = go.computeWorld(); val b = other.go.computeWorld()
        val ca = go.getAny<Collider2D>(); val cb = other.go.getAny<Collider2D>()
        val aw = (ca?.width ?: 1f) * a.scaleX / 2; val ah = (ca?.height ?: 1f) * a.scaleY / 2
        val bw = (cb?.width ?: 1f) * b.scaleX / 2; val bh = (cb?.height ?: 1f) * b.scaleY / 2
        return kotlin.math.abs(a.tx - b.tx) < aw + bw && kotlin.math.abs(a.ty - b.ty) < ah + bh
    }

    // rendering
    fun getText(): String = go.getAny<TextRenderer>()?.text ?: ""
    fun setText(v: Any?) { go.getAny<TextRenderer>()?.text = Context.toString(v) }
    fun getColor(): String {
        val c = go.getAny<SpriteRenderer>()?.color ?: go.getAny<TextRenderer>()?.color ?: -1
        return String.format("#%08X", c)
    }
    fun setColor(hex: String) {
        val c = try { Component.parseColor(hex) } catch (e: Exception) { return }
        go.getAny<SpriteRenderer>()?.color = c
        go.getAny<TextRenderer>()?.color = c
    }
    fun getVisible(): Boolean = go.getAny<SpriteRenderer>()?.enabled ?: false
    fun setVisible(v: Boolean) {
        go.getAny<SpriteRenderer>()?.enabled = v
        go.getAny<TextRenderer>()?.enabled = v
    }
    fun setTexture(name: String) { go.getAny<SpriteRenderer>()?.texture = name }
    fun getFlipX(): Boolean = go.getAny<SpriteRenderer>()?.flipX ?: false
    fun setFlipX(v: Boolean) { go.getAny<SpriteRenderer>()?.flipX = v }

    // particles
    fun burst(n: Double) { go.getAny<ParticleEmitter>()?.let { it.pendingBurst += n.toInt() } }
    fun setEmitting(v: Boolean) { go.getAny<ParticleEmitter>()?.emitting = v }
    fun setParticleRate(v: Double) { go.getAny<ParticleEmitter>()?.rate = v.toFloat() }
    fun setParticleLifetime(v: Double) { go.getAny<ParticleEmitter>()?.lifetime = v.toFloat() }
    fun setParticleSpeed(v: Double) { go.getAny<ParticleEmitter>()?.speed = v.toFloat() }
    fun setParticleDirection(v: Double) { go.getAny<ParticleEmitter>()?.direction = v.toFloat() }
    fun setParticleSpread(v: Double) { go.getAny<ParticleEmitter>()?.spread = v.toFloat() }

    // camera
    fun getSize(): Double = (go.getAny<Camera2D>()?.size ?: 0f).toDouble()
    fun setSize(v: Double) { go.getAny<Camera2D>()?.size = v.toFloat() }

    // hierarchy & lifecycle
    fun getParent(): Any? = go.parent?.let { sys.toJs(it) }
    fun child(name: String): Any? = engine.scene.childrenOf(go).firstOrNull { it.name == name }?.let { sys.toJs(it) }
    fun destroy() { go.destroyed = true }
    fun hasComponent(type: String): Boolean = go.components.any { it.type.equals(type, true) }
    fun setComponentEnabled(type: String, v: Boolean) {
        go.components.filter { it.type.equals(type, true) }.forEach { it.enabled = v }
    }
    fun distanceTo(o: SObject): Double {
        val a = go.computeWorld(); val b = o.go.computeWorld()
        val dx = a.tx - b.tx; val dy = a.ty - b.ty
        return sqrt((dx * dx + dy * dy).toDouble())
    }
    fun distanceToPoint(x: Double, y: Double): Double {
        val w = go.computeWorld()
        val dx = w.tx - x.toFloat(); val dy = w.ty - y.toFloat()
        return sqrt((dx * dx + dy * dy).toDouble())
    }
    fun angleTo(o: SObject): Double {
        val a = go.computeWorld(); val b = o.go.computeWorld()
        return Math.toDegrees(kotlin.math.atan2((b.ty - a.ty).toDouble(), (b.tx - a.tx).toDouble()))
    }
    fun send(fn: String, arg: Any?): Any? = sys.sendMessage(go, fn, arg)
    fun send(fn: String): Any? = sys.sendMessage(go, fn, null)
    fun `is`(o: SObject?): Boolean = o != null && o.go === go

    // Animation
    fun playAnimation(name: String) {
        go.components.filterIsInstance<com.sengine.engine.animation.SpriteAnimator>().firstOrNull()?.play(name)
    }
    fun stopAnimation() {
        go.components.filterIsInstance<com.sengine.engine.animation.SpriteAnimator>().firstOrNull()?.stop()
    }

    override fun toString() = "GameObject(${go.name})"
}

class SScene(private val engine: Engine, private val sys: ScriptSystem) {
    fun getName(): String = engine.scene.name
    fun find(name: String): Any? = engine.scene.find(name)?.let { sys.toJs(it) }
    fun findAll(tag: String): Any? =
        sys.newArray(engine.scene.objects.filter { it.tag == tag && !it.destroyed && it.isActiveInHierarchy() }.map { sys.toJs(it) })
    fun findAllByType(type: String): Any? =
        sys.newArray(engine.scene.objects.filter { go -> go.components.any { it.type.equals(type, true) } && !go.destroyed }.map { sys.toJs(it) })
    fun count(tag: String): Double =
        engine.scene.objects.count { it.tag == tag && !it.destroyed && it.isActiveInHierarchy() }.toDouble()
    fun countAll(): Double = engine.scene.objects.count { !it.destroyed && it.isActiveInHierarchy() }.toDouble()
    fun spawn(name: String, x: Double, y: Double): Any? {
        val template = engine.scene.find(name) ?: run { engine.log(1, "spawn: '$name' not found"); return null }
        val copy = engine.scene.duplicate(template, null)
        copy.active = true
        copy.setWorldPosition(x.toFloat(), y.toFloat())
        for (d in listOf(copy) + engine.scene.objects.filter { copy.isAncestorOf(it) }) {
            d.components.forEach { it.resetRuntime() }
        }
        engine.scene.updateTransforms()
        sys.attach(copy)
        engine.signalBus.emit("object_spawned", copy.name)
        return sys.toJs(copy)
    }
    fun spawn(name: String): Any? {
        val t = engine.scene.find(name) ?: return null
        val w = t.computeWorld()
        return spawn(name, w.tx.toDouble(), w.ty.toDouble())
    }
    fun spawnFromPool(name: String, x: Double, y: Double): Any? {
        val obj = engine.objectPool.obtain(name) ?: return null
        obj.setWorldPosition(x.toFloat(), y.toFloat())
        return sys.toJs(obj)
    }
    fun releaseToPool(obj: SObject) {
        engine.objectPool.release(obj.go)
    }
    fun load(sceneName: String) = engine.requestLoadScene(sceneName)
    fun reload() = engine.requestLoadScene(engine.scene.name)
    fun getCamera(): Any? = engine.mainCamera()?.let { sys.toJs(it) }
    fun getGravityX(): Double = engine.scene.gravityX.toDouble()
    fun setGravityX(v: Double) { engine.scene.gravityX = v.toFloat() }
    fun getGravityY(): Double = engine.scene.gravityY.toDouble()
    fun setGravityY(v: Double) { engine.scene.gravityY = v.toFloat() }

    // Raycasting
    fun raycast(fromX: Double, fromY: Double, toX: Double, toY: Double, maxDist: Double): Any? {
        val dx = toX.toFloat() - fromX.toFloat()
        val dy = toY.toFloat() - fromY.toFloat()
        val hit = Raycaster.raycast(engine.scene, RaycastQuery(
            fromX.toFloat(), fromY.toFloat(), dx, dy, maxDist.toFloat()
        ))
        return hit?.gameObject?.let { sys.toJs(it) }
    }

    fun overlapCircle(x: Double, y: Double, radius: Double): Any? {
        val hits = Raycaster.overlapCircle(engine.scene, x.toFloat(), y.toFloat(), radius.toFloat())
        return sys.newArray(hits.map { sys.toJs(it) })
    }

    // Signals
    fun emit(eventName: String, data: Any?) {
        engine.signalBus.emit1(eventName, data)
    }
    fun on(eventName: String) {
        // Handled by ScriptSystem via signal connections
    }

    // Save/Load
    fun saveData(key: String, value: Any?) { engine.saveSystem.setData(key, value) }
    fun loadData(key: String): Any? = engine.saveSystem.getData(key)
    fun save(slot: Double) { engine.saveSystem.saveScene(slot.toInt()) }
    fun load(slot: Double): Boolean = engine.saveSystem.loadScene(slot.toInt()) }

    // Camera
    fun cameraShake(intensity: Double, duration: Double) {
        engine.cameraSystem.shake(intensity.toFloat(), duration.toFloat())
    }
    fun cameraZoom(size: Double) {
        engine.cameraSystem.zoom(size.toFloat())
    }
}

/** Plain public fields (Rhino exposes them with their exact names, e.g. input.aDown). */
class SInput(private val engine: Engine) {
    @JvmField var axisX = 0.0
    @JvmField var axisY = 0.0
    @JvmField var a = false
    @JvmField var b = false
    @JvmField var aDown = false
    @JvmField var bDown = false
    @JvmField var touching = false
    @JvmField var tapped = false
    @JvmField var touchX = 0.0
    @JvmField var touchY = 0.0

    fun sync() {
        val i = engine.input
        axisX = i.axisX.toDouble(); axisY = i.axisY.toDouble()
        a = i.a; b = i.b; aDown = i.aDown; bDown = i.bDown
        touching = i.touching; tapped = i.tapped
        touchX = i.touchX.toDouble(); touchY = i.touchY.toDouble()
    }
}

class STime(private val engine: Engine) {
    fun getTime(): Double = engine.time
    fun getDt(): Double = engine.deltaTime.toDouble()
    fun getFrame(): Double = engine.frame.toDouble()
    fun getFps(): Double = engine.fps.toDouble()
}

class SAudio(private val engine: Engine) {
    fun play(name: String) = engine.audio.play(name)
    fun play(name: String, volume: Double) = engine.audio.play(name, volume.toFloat())
    fun beep() = engine.audio.beep()
    fun stopAll() = engine.audio.stopAll()
}

class SConsole(private val engine: Engine) {
    fun log(o: Any?) = engine.log(0, Context.toString(o))
    fun warn(o: Any?) = engine.log(1, "⚠ " + Context.toString(o))
    fun error(o: Any?) = engine.log(2, "✖ " + Context.toString(o))
}

/** Physics query API exposed to scripts. */
class SPhysics(private val engine: Engine) {
    fun raycast(ox: Double, oy: Double, dx: Double, dy: Double, maxDist: Double): Any? {
        val hit = Raycaster.raycast(engine.scene, RaycastQuery(
            ox.toFloat(), oy.toFloat(), dx.toFloat(), dy.toFloat(), maxDist.toFloat()
        )) ?: return null
        val result = org.mozilla.javascript.Context.getCurrentContext().newObject(
            engine.scripts.globalScope
        )
        result.put("hit", result, true)
        result.put("x", result, hit.x.toDouble())
        result.put("y", result, hit.y.toDouble())
        result.put("normalX", result, hit.normalX.toDouble())
        result.put("normalY", result, hit.normalY.toDouble())
        result.put("distance", result, hit.distance.toDouble())
        result.put("gameObject", result, engine.scripts.toJs(hit.gameObject))
        return result
    }

    fun overlapCircle(cx: Double, cy: Double, radius: Double): Any? {
        val hits = Raycaster.overlapCircle(engine.scene, cx.toFloat(), cy.toFloat(), radius.toFloat())
        return engine.scripts.newArray(hits.map { engine.scripts.toJs(it) })
    }

    fun overlapArea(minX: Double, minY: Double, maxX: Double, maxY: Double): Any? {
        val hits = Raycaster.overlapArea(engine.scene, minX.toFloat(), minY.toFloat(), maxX.toFloat(), maxY.toFloat())
        return engine.scripts.newArray(hits.map { engine.scripts.toJs(it) })
    }
}
