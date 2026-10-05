package com.sengine.engine.script

import com.sengine.engine.Engine
import com.sengine.engine.core.Camera2D
import com.sengine.engine.core.Component
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.ParticleEmitter
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.core.SpriteAnimator
import com.sengine.engine.core.SpriteRenderer
import com.sengine.engine.core.TextRenderer
import com.sengine.engine.physics.PhysicsWorld
import org.mozilla.javascript.Context
import kotlin.math.sqrt

/*
 * Objects exposed to JavaScript. Getter/setter pairs become JS properties
 * (e.g. getX()/setX() -> transform.x).
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

    // Physics forces are accumulated and integrated by the fixed-step solver.
    fun getVx(): Double = (go.getAny<Rigidbody2D>()?.vx ?: 0f).toDouble()
    fun setVx(v: Double) { go.getAny<Rigidbody2D>()?.vx = v.toFloat() }
    fun getVy(): Double = (go.getAny<Rigidbody2D>()?.vy ?: 0f).toDouble()
    fun setVy(v: Double) { go.getAny<Rigidbody2D>()?.vy = v.toFloat() }
    fun getAngularVelocity(): Double = (go.getAny<Rigidbody2D>()?.angularVelocity ?: 0f).toDouble()
    fun setAngularVelocity(v: Double) { go.getAny<Rigidbody2D>()?.angularVelocity = v.toFloat() }
    fun isGrounded(): Boolean = go.getAny<Rigidbody2D>()?.grounded ?: false
    fun addForce(fx: Double, fy: Double) { go.getAny<Rigidbody2D>()?.applyForce(fx.toFloat(), fy.toFloat()) }
    fun addImpulse(ix: Double, iy: Double) { go.getAny<Rigidbody2D>()?.applyImpulse(ix.toFloat(), iy.toFloat()) }
    fun addTorque(value: Double) { go.getAny<Rigidbody2D>()?.applyTorque(value.toFloat()) }
    fun addAngularImpulse(degreesPerSecond: Double) {
        go.getAny<Rigidbody2D>()?.applyAngularImpulse(degreesPerSecond.toFloat())
    }
    fun setVelocity(vx: Double, vy: Double) {
        val rb = go.getAny<Rigidbody2D>() ?: return
        rb.vx = vx.toFloat(); rb.vy = vy.toFloat()
    }
    fun overlaps(other: SObject): Boolean = engine.physics.areOverlapping(go, other.go)

    // Rendering and animation
    fun getText(): String = go.getAny<TextRenderer>()?.text ?: ""
    fun setText(v: Any?) { go.getAny<TextRenderer>()?.text = Context.toString(v) }
    fun getColor(): String {
        val c = go.getAny<SpriteRenderer>()?.color ?: go.getAny<TextRenderer>()?.color ?: -1
        return String.format("#%08X", c)
    }
    fun setColor(hex: String) {
        val c = try { Component.parseColor(hex) } catch (_: Exception) { return }
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
    fun getAnimationFrame(): Double = (go.getAny<SpriteAnimator>()?.frame ?: 0).toDouble()
    fun playAnimation() { go.getAny<SpriteAnimator>()?.play() }
    fun stopAnimation() { go.getAny<SpriteAnimator>()?.stop() }

    // Particles
    fun burst(n: Double) { go.getAny<ParticleEmitter>()?.let { it.pendingBurst += n.toInt().coerceAtLeast(0) } }
    fun setEmitting(v: Boolean) { go.getAny<ParticleEmitter>()?.emitting = v }

    // Camera
    fun getSize(): Double = (go.getAny<Camera2D>()?.size ?: 0f).toDouble()
    fun setSize(v: Double) { go.getAny<Camera2D>()?.size = v.toFloat() }
    fun shake(magnitude: Double, duration: Double) {
        go.getAny<Camera2D>()?.shake(magnitude.toFloat(), duration.toFloat())
    }

    // Hierarchy and lifecycle
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
    fun send(fn: String, arg: Any?): Any? = sys.sendMessage(go, fn, arg)
    fun send(fn: String): Any? = sys.sendMessage(go, fn, null)
    fun `is`(o: SObject?): Boolean = o != null && o.go === go
    override fun toString() = "GameObject(${go.name})"
}

class SScene(private val engine: Engine, private val sys: ScriptSystem) {
    fun getName(): String = engine.scene.name
    fun find(name: String): Any? = engine.scene.find(name)?.let { sys.toJs(it) }
    fun findAll(tag: String): Any? =
        sys.newArray(engine.scene.objects.filter { it.tag == tag && !it.destroyed && it.isActiveInHierarchy() }.map { sys.toJs(it) })
    fun count(tag: String): Double =
        engine.scene.objects.count { it.tag == tag && !it.destroyed && it.isActiveInHierarchy() }.toDouble()
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
        return sys.toJs(copy)
    }
    fun spawn(name: String): Any? {
        val template = engine.scene.find(name) ?: return null
        val w = template.computeWorld()
        return spawn(name, w.tx.toDouble(), w.ty.toDouble())
    }
    fun load(sceneName: String) = engine.requestLoadScene(sceneName)
    fun reload() = engine.requestLoadScene(engine.scene.name)
    fun getCamera(): Any? = engine.mainCamera()?.let { sys.toJs(it) }
    fun getGravityX(): Double = engine.scene.gravityX.toDouble()
    fun setGravityX(v: Double) { engine.scene.gravityX = v.toFloat() }
    fun getGravityY(): Double = engine.scene.gravityY.toDouble()
    fun setGravityY(v: Double) { engine.scene.gravityY = v.toFloat() }
}

class SPhysics(private val engine: Engine, private val sys: ScriptSystem) {
    fun raycast(x: Double, y: Double, dx: Double, dy: Double, maxDistance: Double): Any? =
        engine.physics.raycast(engine.scene, x.toFloat(), y.toFloat(), dx.toFloat(), dy.toFloat(), maxDistance.toFloat())
            ?.let { SPhysicsHit(it, sys) }

    fun raycast(x: Double, y: Double, dx: Double, dy: Double, maxDistance: Double, layerMask: Double): Any? =
        engine.physics.raycast(engine.scene, x.toFloat(), y.toFloat(), dx.toFloat(), dy.toFloat(), maxDistance.toFloat(), layerMask.toInt())
            ?.let { SPhysicsHit(it, sys) }

    fun circleCast(x: Double, y: Double, dx: Double, dy: Double, maxDistance: Double, radius: Double): Any? =
        engine.physics.circleCast(engine.scene, x.toFloat(), y.toFloat(), dx.toFloat(), dy.toFloat(), maxDistance.toFloat(), radius.toFloat())
            ?.let { SPhysicsHit(it, sys) }

    fun overlapCircle(x: Double, y: Double, radius: Double): Any? =
        sys.newArray(engine.physics.overlapCircle(engine.scene, x.toFloat(), y.toFloat(), radius.toFloat()).map { sys.toJs(it) })

    fun overlapArea(x: Double, y: Double, width: Double, height: Double): Any? =
        sys.newArray(engine.physics.overlapArea(engine.scene, x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat()).map { sys.toJs(it) })
}

class SPhysicsHit(private val hit: PhysicsWorld.RaycastHit, private val sys: ScriptSystem) {
    fun getGameObject(): Any? = sys.toJs(hit.gameObject)
    fun getPointX(): Double = hit.pointX.toDouble()
    fun getPointY(): Double = hit.pointY.toDouble()
    fun getNormalX(): Double = hit.normalX.toDouble()
    fun getNormalY(): Double = hit.normalY.toDouble()
    fun getDistance(): Double = hit.distance.toDouble()
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
