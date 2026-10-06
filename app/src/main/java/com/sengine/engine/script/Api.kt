package com.sengine.engine.script

import com.sengine.engine.Engine
import com.sengine.engine.anim.Animator
import com.sengine.engine.character.CharacterController2D
import com.sengine.engine.character.Ragdoll2D
import com.sengine.engine.core.AudioSource
import com.sengine.engine.core.Camera2D
import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.EventBus
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Health
import com.sengine.engine.core.PhysicsLayers
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.core.Scene
import com.sengine.engine.core.SpriteRenderer
import com.sengine.engine.core.TextRenderer
import com.sengine.engine.fx.ParticleEmitter
import com.sengine.engine.fx.Trail2D
import com.sengine.engine.math.Colors
import com.sengine.engine.math.Noise2D
import com.sengine.engine.ui.UiDialogs
import com.sengine.engine.ui.UiRuntime
import com.sengine.engine.vehicle.Vehicle2D
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.Scriptable
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The scripting surface exposed to JavaScript.
 *
 * Every class here is 2D: positions are (x, y), velocities are (vx, vy), angles are degrees around
 * the Z axis, cameras are 2D views and physics queries live in the XY plane. There is deliberately
 * no 3D API of any kind.
 *
 * Getter/setter pairs become JS properties: `getX()/setX()` -> `obj.x`.
 */

/** A game object ("node") handed to scripts as `self`. */
class SObject(private val go: GameObject, private val engine: Engine, private val sys: ScriptSystem) {

    // ---------------------------------------------------------------- identity
    fun getId(): Double = go.id.toDouble()
    fun getName(): String = go.name
    fun setName(v: String) { go.name = v }
    fun getTag(): String = go.tag
    fun setTag(v: String) { go.tag = v }
    fun getActive(): Boolean = go.active
    fun setActive(v: Boolean) { go.active = v }
    fun isActive(): Boolean = go.isActiveInHierarchy()
    fun getOrder(): Double = go.order.toDouble()
    fun setOrder(v: Double) { go.order = v.toInt() }
    fun getLayer(): String = go.sortingLayer
    fun setLayer(v: String) { go.sortingLayer = v }
    fun getComponents(): Any? = sys.newArray(go.components.map { it.type })
    fun hasComponent(type: String): Boolean = go.getByType(type) != null
    fun destroy() { go.destroyed = true }
    fun isAlive(): Boolean = !go.destroyed
    override fun toString(): String = "SObject(${go.name}#${go.id})"

    // ---------------------------------------------------------------- hierarchy
    fun getParent(): SObject? = go.parent?.let { sys.wrap(it) }
    fun getChildren(): Any? = sys.newArray(engine.scene.childrenOf(go).map { sys.wrap(it) })
    fun childCount(): Double = engine.scene.childrenOf(go).size.toDouble()
    fun findChild(name: String): SObject? = engine.scene.childrenOf(go).firstOrNull { it.name == name }?.let { sys.wrap(it) }
    fun getNameInScene(): String = go.name
    fun setParent(other: SObject) { go.parent = other.go }
    fun addGroup(g: String) { go.addGroup(g) }
    fun inGroup(g: String): Boolean = go.inGroup(g)

    // ---------------------------------------------------------------- transform (2D)
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
    fun getWorldX(): Double = go.worldX().toDouble()
    fun getWorldY(): Double = go.worldY().toDouble()
    fun getWorldRotation(): Double = go.worldRotation().toDouble()

    fun setPosition(x: Double, y: Double) { go.x = x.toFloat(); go.y = y.toFloat() }
    fun setWorldPosition(x: Double, y: Double) { go.setWorldPosition(x.toFloat(), y.toFloat()) }
    fun move(dx: Double, dy: Double) { go.x += dx.toFloat(); go.y += dy.toFloat() }
    fun rotate(deg: Double) { go.rotation += deg.toFloat() }
    fun setScale(s: Double) { go.scaleX = s.toFloat(); go.scaleY = s.toFloat() }
    fun flip(): Double {
        go.scaleX = -go.scaleX
        return go.scaleX.toDouble()
    }
    fun flipSpriteX() { go.getAny<SpriteRenderer>()?.let { it.flipX = !it.flipX }; go.scaleX = -go.scaleX }
    /** Points the object's local +X axis at a world position (sprite facing helpers). */
    fun lookAt(x: Double, y: Double) {
        val dx = x.toFloat() - go.worldX()
        val dy = y.toFloat() - go.worldY()
        go.rotation = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }
    fun distanceTo(o: SObject): Double {
        val dx = go.worldX() - o.go.worldX()
        val dy = go.worldY() - o.go.worldY()
        return sqrt((dx * dx + dy * dy).toDouble())
    }
    fun distanceToPoint(x: Double, y: Double): Double {
        val dx = go.worldX() - x.toFloat()
        val dy = go.worldY() - y.toFloat()
        return sqrt((dx * dx + dy * dy).toDouble())
    }
    fun angleTo(o: SObject): Double {
        val dx = o.go.worldX() - go.worldX()
        val dy = o.go.worldY() - go.worldY()
        return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
    }
    fun overlaps(other: SObject): Boolean = engine.physics.overlapObjects(go, other.go)

    // ---------------------------------------------------------------- physics (2D)
    private val rb get() = go.getAny<Rigidbody2D>()

    fun getVx(): Double = (rb?.vx ?: 0f).toDouble()
    fun setVx(v: Double) { rb?.vx = v.toFloat() }
    fun getVy(): Double = (rb?.vy ?: 0f).toDouble()
    fun setVy(v: Double) { rb?.vy = v.toFloat() }
    fun getAngularVelocity(): Double = (rb?.angularVelocity ?: 0f).toDouble()
    fun setAngularVelocity(v: Double) { rb?.angularVelocity = v.toFloat() }
    fun getSpeed(): Double = (rb?.speed ?: 0f).toDouble()
    fun isGrounded(): Boolean = rb?.grounded ?: false
    fun isSleeping(): Boolean = rb?.sleeping ?: false
    fun getMass(): Double = (rb?.mass ?: 1f).toDouble()
    fun setMass(v: Double) { rb?.mass = v.toFloat().coerceAtLeast(0.001f) }
    fun getGravityScale(): Double = (rb?.gravityScale ?: 1f).toDouble()
    fun setGravityScale(v: Double) { rb?.gravityScale = v.toFloat() }
    fun setBodyType(name: String) {
        val r = rb ?: return
        r.bodyType = when (name.lowercase()) {
            "static" -> 2
            "kinematic" -> 1
            "character" -> 3
            else -> 0
        }
    }
    fun addForce(fx: Double, fy: Double) { rb?.addForce(fx.toFloat(), fy.toFloat()) }
    fun addForceAt(fx: Double, fy: Double, px: Double, py: Double) { rb?.addForceAt(fx.toFloat(), fy.toFloat(), px.toFloat(), py.toFloat()) }
    fun addImpulse(ix: Double, iy: Double) { rb?.addImpulse(ix.toFloat(), iy.toFloat()) }
    fun addTorque(t: Double) { rb?.addTorque(t.toFloat()) }
    fun setVelocity(vx: Double, vy: Double) { rb?.let { it.vx = vx.toFloat(); it.vy = vy.toFloat() } }
    fun teleport(x: Double, y: Double) { rb?.teleport(x.toFloat(), y.toFloat()) ?: go.setWorldPosition(x.toFloat(), y.toFloat()) }
    fun wakeUp() { rb?.wake() }
    fun sleep() { rb?.sleep() }
    fun getFriction(): Double = (go.getAny<Collider2D>()?.friction ?: 0.4f).toDouble()
    fun setFriction(v: Double) { go.getAny<Collider2D>()?.friction = v.toFloat().coerceIn(0f, 4f) }
    fun getRestitution(): Double = (go.getAny<Collider2D>()?.restitution ?: 0f).toDouble()
    fun setRestitution(v: Double) { go.getAny<Collider2D>()?.restitution = v.toFloat().coerceIn(0f, 1f) }
    fun isTrigger(): Boolean = go.getAny<Collider2D>()?.isTrigger ?: false
    fun setTrigger(v: Boolean) { go.getAny<Collider2D>()?.isTrigger = v }
    fun getGroundNormalX(): Double = (rb?.groundNormalX ?: 0f).toDouble()
    fun getGroundNormalY(): Double = (rb?.groundNormalY ?: 1f).toDouble()
    fun isKinematic(): Boolean = rb?.bodyType == 1

    // ---------------------------------------------------------------- rendering
    private val sprite get() = go.getAny<SpriteRenderer>()

    fun getVisible(): Boolean = sprite?.visible ?: true
    fun setVisible(v: Boolean) {
        sprite?.visible = v
        go.getAny<TextRenderer>()?.visible = v
        go.getAny<ParticleEmitter>()?.visible = v
    }
    fun getColor(): String = Colors.toHex(sprite?.color ?: Colors.WHITE)
    fun setColor(hex: String) { sprite?.color = Colors.parse(hex, Colors.WHITE) }
    fun getAlpha(): Double = (Colors.alpha(sprite?.color ?: Colors.WHITE) / 255f).toDouble()
    fun setAlpha(a: Double) {
        val c = sprite?.color ?: Colors.WHITE
        sprite?.color = Colors.withAlpha(c, a.toFloat().coerceIn(0f, 1f))
    }
    fun getWidth(): Double = (sprite?.width ?: go.getAny<Collider2D>()?.width ?: 1f).toDouble()
    fun setWidth(v: Double) { sprite?.width = v.toFloat() }
    fun getHeight(): Double = (sprite?.height ?: go.getAny<Collider2D>()?.height ?: 1f).toDouble()
    fun setHeight(v: Double) { sprite?.height = v.toFloat() }
    fun setSize(w: Double, h: Double) { sprite?.let { it.width = w.toFloat(); it.height = h.toFloat() } }
    fun setTexture(name: String) { sprite?.let { it.texture = name; it.shape = 3 } }
    fun getTexture(): String = sprite?.texture ?: ""
    fun getFlipX(): Boolean = sprite?.flipX ?: false
    fun setFlipX(v: Boolean) { sprite?.flipX = v }
    fun getFlipY(): Boolean = sprite?.flipY ?: false
    fun setFlipY(v: Boolean) { sprite?.flipY = v }
    fun setFrame(x: Double, y: Double) { sprite?.let { it.frameX = x.toInt(); it.frameY = y.toInt() } }
    fun setShaderParam(v: Double) { sprite?.shaderParam = v.toFloat() }
    fun setShader(name: String) { sprite?.material = name }
    fun setAdditive(v: Boolean) { sprite?.additive = v }

    // ---------------------------------------------------------------- text / UI
    private val text get() = go.getAny<TextRenderer>()

    fun getText(): String = text?.text ?: ""
    fun setText(s: String) { text?.text = s; UiRuntime.setText(engine.scene, go.name, s) }
    fun setFont(name: String) { text?.font = name }
    fun setFontSize(v: Double) { text?.size = v.toFloat() }
    fun setTextColor(hex: String) { text?.color = Colors.parse(hex, Colors.WHITE) }
    fun setAlign(a: String) {
        text?.align = when (a.lowercase()) { "left" -> 0; "right" -> 2; else -> 1 }
    }
    fun setCanvasText(s: String) = UiRuntime.setText(engine.scene, go.name, s)
    fun setCanvasProgress(v: Double) = UiRuntime.setProgress(engine.scene, go.name, v.toFloat())
    fun setCanvasSlider(v: Double) = UiRuntime.setSlider(engine.scene, go.name, v.toFloat())
    fun setCanvasVisible(v: Boolean) = UiRuntime.setVisible(engine.scene, go.name, v)
    fun addListItem(item: String) = UiRuntime.addListItem(engine.scene, go.name, item)
    fun toast(message: String) = UiRuntime.canvasOf(engine.scene)?.let { UiRuntime.toast(engine.scene, it, message) }

    // ---------------------------------------------------------------- animation
    private val animator get() = go.getAny<Animator>()

    fun play(clip: String) { animator?.play(clip) }
    fun playState(state: String) { animator?.playState(state) }
    fun stopAnimation() { animator?.stop() }
    fun getAnimation(): String = animator?.currentName ?: ""
    fun getState(): String = animator?.currentStateName() ?: ""
    fun isAnimationFinished(): Boolean = animator?.finished ?: true
    fun setAnimSpeed(v: Double) { animator?.speed = v.toFloat() }
    fun isAnimPlaying(): Boolean = animator?.playing ?: false
    fun setAnimFloat(name: String, v: Double) { animator?.setFloat(name, v.toFloat()) }
    fun setAnimBool(name: String, v: Boolean) { animator?.setBool(name, v) }
    fun setAnimTrigger(name: String) { animator?.setTrigger(name) }
    fun getFrame(): Double = (animator?.frameIndex ?: 0).toDouble()

    // ---------------------------------------------------------------- gameplay components
    private val health get() = go.getAny<Health>()

    fun getHealth(): Double = (health?.health ?: 0f).toDouble()
    fun setHealth(v: Double) { health?.let { it.health = v.toFloat(); if (v > 0f) it.dead = false } }
    fun getMaxHealth(): Double = (health?.maxHealth ?: 0f).toDouble()
    fun damage(amount: Double, fromX: Double = 0.0, fromY: Double = 0.0): Boolean =
        health?.applyDamage(amount.toFloat(), fromX.toFloat(), fromY.toFloat(), engine.time.toFloat()) ?: false
    fun heal(amount: Double) { health?.heal(amount.toFloat()) }
    fun isDead(): Boolean = health?.let { it.dead || it.health <= 0f } ?: false
    fun kill() {
        val h = health ?: return
        h.health = 0f
        h.update(1f, go)
    }
    fun getTeam(): Double = (health?.team ?: 0).toDouble()
    fun setTeam(v: Double) { health?.team = v.toInt() }
    fun knockback(dirX: Double, dirY: Double, strength: Double) {
        go.getAny<CharacterController2D>()?.knockback(dirX.toFloat(), dirY.toFloat(), strength.toFloat())
            ?: rb?.let {
                val len = sqrt((dirX * dirX + dirY * dirY).coerceAtLeast(0.0001))
                it.addImpulse((dirX / len * strength).toFloat(), (dirY / len * strength).toFloat())
            }
    }
    fun isRagdoll(): Boolean = go.getAny<Ragdoll2D>() != null
    fun goLimp() { go.getAny<Ragdoll2D>()?.goLimp() }
    fun explode(force: Double) {
        val r = go.getAny<Ragdoll2D>() ?: return
        r.applyExplosion(go.worldX(), go.worldY(), force.toFloat(), 3f)
    }
    fun hasVehicle(): Boolean = go.getAny<Vehicle2D>() != null
    fun setVehicleControls(enabled: Boolean) { go.getAny<Vehicle2D>()?.controlsEnabled = enabled }
    fun getVehicleSpeedKmh(): Double = (go.getAny<Vehicle2D>()?.telemetry?.speedKmh ?: 0f).toDouble()
    fun isAirborne(): Boolean = go.getAny<Vehicle2D>()?.telemetry?.airborne ?: (go.getAny<Ragdoll2D>()?.let { false } ?: false)
    fun getThrottle(): Double = (go.getAny<Vehicle2D>()?.throttle ?: 0f).toDouble()
    fun setThrottle(v: Double) { go.getAny<Vehicle2D>()?.throttle = v.toFloat().coerceIn(-1f, 1f) }
    fun isBraking(): Boolean = go.getAny<Vehicle2D>()?.telemetry?.braking ?: false
    fun isFlipped(): Boolean = go.getAny<Vehicle2D>()?.telemetry?.flipped ?: false
    fun getEngineRpm(): Double = (go.getAny<Vehicle2D>()?.telemetry?.engineRpm ?: 0f).toDouble()
    fun getFuel(): Double = (go.getAny<Vehicle2D>()?.telemetry?.fuel ?: 0f).toDouble()
    fun refuel(amount: Double = 1.0) { go.getAny<Vehicle2D>()?.refuel(amount.toFloat()) }

    // ---------------------------------------------------------------- audio / fx
    fun playSound(name: String, volume: Double = 1.0): Double =
        engine.audio.play(name, volume.toFloat(), false, engine.audioBusOf(go)).toDouble()
    fun playSoundLoop(name: String, volume: Double = 1.0): Double =
        engine.audio.play(name, volume.toFloat(), true, engine.audioBusOf(go)).toDouble()
    fun playSoundAt(name: String, volume: Double = 1.0): Double =
        engine.audio.playAt(name, go.worldX(), go.worldY(), volume.toFloat()).toDouble()
    fun stopSound(handle: Double) = engine.audio.stop(handle.toInt())

    fun burst(count: Double = 0.0) {
        val e = go.getAny<ParticleEmitter>() ?: return
        if (count <= 0.0) e.burst() else e.burst(count.toInt())
    }
    fun setEmitterPreset(name: String) { go.getAny<ParticleEmitter>()?.applyPreset(name) }
    fun startEmitting() { go.getAny<ParticleEmitter>()?.startEmitting() }
    fun stopEmitting() { go.getAny<ParticleEmitter>()?.stopEmitting() }
    fun particleCount(): Double = (go.getAny<ParticleEmitter>()?.system?.count ?: 0).toDouble()
    fun clearTrail() { go.getAny<Trail2D>()?.clear() }
    fun attachTrail(t: Trail2D) {
        if (go.getAny<Trail2D>() == null) go.add(t)
    }
    fun setTrailWidth(v: Double) { go.getAny<Trail2D>()?.let { it.width = v.toFloat() } }
    fun setTrailColor(hex: String) { go.getAny<Trail2D>()?.let { it.color = Colors.parse(hex, Colors.WHITE) } }
    fun shake(amount: Double = 0.4) { engine.shake(amount.toFloat()) }

    // ---------------------------------------------------------------- events / misc
    fun emit(name: String, payload: Any? = null) { go.emit(name, payload) }
    fun send(message: String, arg: Any? = null): Any? = sys.sendMessage(go, message, sys.toJs(arg))
    fun on(name: String, fn: Function) {
        go.signal<Any?>(name).connect { payload -> sys.callFunction(fn, payload) }
    }
    fun onEvent(name: String, fn: Function) {
        EventBus.on(name) { payload -> sys.callFunction(fn, payload) }
    }
    fun emitEvent(name: String, payload: Any? = null) { EventBus.emit(name, payload) }
    fun id(): Double = go.id.toDouble()
    fun sameAs(other: SObject): Boolean = go === other.go
    override fun equals(other: Any?): Boolean = other is SObject && other.go === go
    override fun hashCode(): Int = go.id.hashCode()
}

/** Scene level API (`scene.*`). */
class SScene(private val engine: Engine, private val sys: ScriptSystem) {

    fun getName(): String = engine.scene.name
    fun getObjectCount(): Double = engine.scene.objects.count { !it.destroyed }.toDouble()
    fun getTime(): Double = engine.time
    fun getGravityX(): Double = engine.physics.gravityX.toDouble()
    fun getGravityY(): Double = engine.physics.gravityY.toDouble()
    fun setGravity(x: Double, y: Double) {
        engine.physics.gravityX = x.toFloat()
        engine.physics.gravityY = y.toFloat()
    }
    fun getAmbient(): String = Colors.toHex(engine.scene.ambient)
    fun setAmbient(hex: String) { engine.scene.ambient = Colors.parse(hex, engine.scene.ambient) }
    fun getTimeScale(): Double = engine.timeScale.toDouble()
    fun setTimeScale(v: Double) { engine.timeScale = v.toFloat().coerceIn(0f, 8f) }

    fun find(name: String): SObject? = engine.scene.find(name)?.let { sys.wrap(it) }
    fun findById(id: Double): SObject? = engine.scene.findById(id.toLong())?.let { sys.wrap(it) }
    fun findTag(tag: String): SObject? = engine.scene.findByTag(tag).firstOrNull()?.let { sys.wrap(it) }
    fun findAllTag(tag: String): Any? = sys.newArray(engine.scene.findByTag(tag).map { sys.wrap(it) })
    fun findByType(type: String): Any? = sys.newArray(engine.scene.findByType(type).map { sys.wrap(it) })
    fun allInGroup(group: String): Any? = sys.newArray(engine.scene.findAllInGroup(group).map { sys.wrap(it) })

    fun instantiate(prefabName: String, x: Double, y: Double): SObject? =
        engine.spawnPrefab(prefabName, x.toFloat(), y.toFloat())?.let { sys.wrap(it) }

    fun spawn(prefabName: String, x: Double, y: Double): SObject? = instantiate(prefabName, x, y)

    fun create(name: String): SObject? = sys.wrap(engine.scene.create(name))

    fun destroyAll(tag: String) {
        for (o in engine.scene.findByTag(tag)) o.destroyed = true
    }

    fun shake(amount: Double = 0.4) { engine.shake(amount.toFloat()) }

    fun reload() { engine.requestLoadScene(engine.scene.name) }
    fun load(name: String) { engine.requestLoadScene(name) }
    fun listScenes(): Any? = sys.newArray(engine.project.listScenes())

    fun save(slot: String = "quicksave") { engine.saveState(slot) }
    fun loadSlot(slot: String = "quicksave"): Boolean = engine.loadState(slot)

    fun emit(name: String, payload: Any? = null) { EventBus.emit(name, payload) }
    fun on(name: String, fn: Function) { EventBus.on(name) { payload -> sys.callFunction(fn, payload) } }
    fun log(msg: String) = engine.log(0, msg)
    fun camera(): SCamera = SCamera(engine)
    fun setCanvasText(name: String, value: String) = UiRuntime.setText(engine.scene, name, value)
    fun toast(message: String) = UiRuntime.canvasOf(engine.scene)?.let { UiRuntime.toast(engine.scene, it, message) }
    fun showDialog(title: String, message: String) {
        UiDialogs.show(engine.scene, UiRuntime.canvasOf(engine.scene) ?: engine.scene.create("Dialog"), title, message, listOf("OK"))
    }
}

/** 2D physics queries (`physics.*`). */
class SPhysics(private val engine: Engine, private val sys: ScriptSystem) {

    fun getGravityY(): Double = engine.physics.gravityY.toDouble()
    fun setGravityY(v: Double) { engine.physics.gravityY = v.toFloat() }

    /** Nearest hit along the segment, or null. */
    fun raycast(x1: Double, y1: Double, x2: Double, y2: Double): SObject? {
        val hit = engine.physics.rayCast(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat()) ?: return null
        return hit.body?.go?.let { sys.wrap(it) }
    }

    fun raycastPoint(x1: Double, y1: Double, x2: Double, y2: Double): Any? {
        val hit = engine.physics.rayCast(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat()) ?: return null
        val map = HashMap<String, Any?>()
        map["x"] = hit.pointX.toDouble()
        map["y"] = hit.pointY.toDouble()
        map["normalX"] = hit.normalX.toDouble()
        map["normalY"] = hit.normalY.toDouble()
        map["fraction"] = hit.fraction.toDouble()
        map["object"] = hit.body?.go?.let { sys.wrap(it) }
        return map
    }

    fun overlapCircle(x: Double, y: Double, radius: Double): Any? =
        sys.newArray(engine.physics.overlapCircle(x.toFloat(), y.toFloat(), radius.toFloat())
            .mapNotNull { it.go?.let { g -> sys.wrap(g) } })

    fun overlapBox(x: Double, y: Double, halfWidth: Double, halfHeight: Double): Any? =
        sys.newArray(engine.physics.overlapBox(x.toFloat(), y.toFloat(), halfWidth.toFloat(), halfHeight.toFloat())
            .mapNotNull { it.go?.let { g -> sys.wrap(g) } })

    fun circleCast(x1: Double, y1: Double, x2: Double, y2: Double, radius: Double): SObject? =
        engine.physics.circleCast(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), radius.toFloat())
            ?.go?.let { sys.wrap(it) }

    fun layerNames(): Any? = sys.newArray(PhysicsLayers.names.toList())
}

/** Input helpers (`input.*`). */
class SInput(private val engine: Engine) {
    private val input get() = engine.input

    fun getAxisX(): Double = input.axisHorizontal().toDouble()
    fun getAxisY(): Double = input.axisVertical().toDouble()
    fun getA(): Boolean = input.button("a")
    fun getB(): Boolean = input.button("b")
    fun getADown(): Boolean = input.buttonDown("a")
    fun getBDown(): Boolean = input.buttonDown("b")
    fun getTouching(): Boolean = input.touchCount > 0
    fun getTapped(): Boolean = input.tapped
    fun getDoubleTapped(): Boolean = input.doubleTapped
    fun getLongPressed(): Boolean = input.longPressed
    fun getTapX(): Double = input.touchX.toDouble()
    fun getTapY(): Double = input.touchY.toDouble()
    fun getTouchCount(): Double = input.touchCount.toDouble()
    fun getSwipeX(): Double = input.swipeDirX.toDouble()
    fun getSwipeY(): Double = input.swipeDirY.toDouble()
    fun getPinch(): Double = input.pinchDelta.toDouble()
    fun getGamepadX(): Double = input.padAxisX.toDouble()
    fun getGamepadY(): Double = input.padAxisY.toDouble()
    fun getKeyboardX(): Double = (if (input.isKeyDown(android.view.KeyEvent.KEYCODE_D)) 1.0 else 0.0) -
        (if (input.isKeyDown(android.view.KeyEvent.KEYCODE_A)) 1.0 else 0.0)
    fun getKeyboardY(): Double = (if (input.isKeyDown(android.view.KeyEvent.KEYCODE_W)) 1.0 else 0.0) -
        (if (input.isKeyDown(android.view.KeyEvent.KEYCODE_S)) 1.0 else 0.0)

    fun key(name: String): Boolean = input.isKeyDown(keyCode(name))
    fun keyDown(name: String): Boolean = input.wasPressed(keyCode(name))
    fun button(name: String): Boolean = input.button(name)
    fun buttonDown(name: String): Boolean = input.buttonDown(name)
    fun padButton(name: String): Boolean = input.padDown(keyCode(name))
    fun pointerX(index: Double = 0.0): Double = (input.pointer(index.toInt())?.x ?: -1f).toDouble()
    fun pointerY(index: Double = 0.0): Double = (input.pointer(index.toInt())?.y ?: -1f).toDouble()
    fun worldX(index: Double = 0.0): Double = (input.pointer(index.toInt())?.worldX ?: 0f).toDouble()
    fun worldY(index: Double = 0.0): Double = (input.pointer(index.toInt())?.worldY ?: 0f).toDouble()
    fun hasGamepad(): Boolean = input.gamepadConnected
    fun deviceName(): String = input.gamepadName

    private fun keyCode(name: String): Int = when (name.lowercase()) {
        "up" -> android.view.KeyEvent.KEYCODE_DPAD_UP
        "down" -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
        "left" -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
        "right" -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
        "space", "jump" -> android.view.KeyEvent.KEYCODE_SPACE
        "enter" -> android.view.KeyEvent.KEYCODE_ENTER
        "escape", "back" -> android.view.KeyEvent.KEYCODE_ESCAPE
        "shift" -> android.view.KeyEvent.KEYCODE_SHIFT_LEFT
        "a_button" -> android.view.KeyEvent.KEYCODE_BUTTON_A
        "b_button" -> android.view.KeyEvent.KEYCODE_BUTTON_B
        else -> if (name.length == 1 && name[0].isLetter()) {
            android.view.KeyEvent.KEYCODE_A + (name[0].lowercaseChar() - 'a')
        } else -1
    }
}

/** Audio API (`audio.*`). */
class SAudio(private val engine: Engine) {

    fun play(name: String): Double = engine.audio.play(name).toDouble()
    fun play(name: String, volume: Double): Double = engine.audio.play(name, volume.toFloat()).toDouble()
    fun play(name: String, volume: Double, loop: Boolean): Double =
        engine.audio.play(name, volume.toFloat(), loop).toDouble()
    fun playAt(name: String, x: Double, y: Double, volume: Double = 1.0): Double =
        engine.audio.playAt(name, x.toFloat(), y.toFloat(), volume.toFloat()).toDouble()
    fun music(name: String, volume: Double = 0.8, loop: Boolean = true): Double =
        engine.audio.music(name, volume.toFloat(), loop).toDouble()
    fun stopMusic(fade: Double = 0.4) = engine.audio.stopMusic(fade.toFloat())
    fun stop(handle: Double, fade: Double = 0.0) = engine.audio.stop(handle.toInt(), fade.toFloat())
    fun stopAll() = engine.audio.stopAll()
    fun pause() = engine.audio.pauseAll()
    fun resume() = engine.audio.resumeAll()
    fun setBusVolume(name: String, v: Double) = engine.audio.setBusVolume(com.sengine.engine.AudioSystem.Bus.indexOf(name), v.toFloat())
    fun busVolume(name: String): Double = engine.audio.busVolume(com.sengine.engine.AudioSystem.Bus.indexOf(name)).toDouble()
    fun setMuted(v: Boolean) { engine.audio.muted = v }
    fun beep() = engine.audio.beep()
    fun activeVoices(): Double = engine.audio.activeVoices().toDouble()
    fun isAvailable(): Boolean = engine.audio.available
}

/** Time API (`time.*`). */
class STime(private val engine: Engine) {
    fun getTime(): Double = engine.time
    fun getDelta(): Double = engine.lastDelta.toDouble()
    fun getFrame(): Double = engine.frame.toDouble()
    fun getFps(): Double = engine.fps.toDouble()
    fun getTimeScale(): Double = engine.timeScale.toDouble()
    fun setTimeScale(v: Double) { engine.timeScale = v.toFloat().coerceIn(0f, 8f) }
    fun isPlaying(): Boolean = engine.mode == Engine.Mode.PLAY
    fun getMode(): String = engine.mode.name.lowercase()
}

/** Camera API (`camera.*`). */
class SCamera(private val engine: Engine) {

    private fun cam() = engine.mainCamera()?.getAny<Camera2D>()

    fun getX(): Double = (engine.mainCamera()?.worldX() ?: 0f).toDouble()
    fun getY(): Double = (engine.mainCamera()?.worldY() ?: 0f).toDouble()
    fun setPosition(x: Double, y: Double) { engine.mainCamera()?.setWorldPosition(x.toFloat(), y.toFloat()) }
    fun getSize(): Double = (cam()?.size ?: 5f).toDouble()
    fun setSize(v: Double) { cam()?.size = v.toFloat().coerceIn(0.05f, 200f) }
    fun zoom(v: Double) { cam()?.size = (cam()?.size ?: 5f) / v.toFloat().coerceAtLeast(0.01f) }
    fun shake(amount: Double = 0.4) { engine.shake(amount.toFloat()) }
    fun follow(name: String) { cam()?.follow = name }
    fun stopFollow() { cam()?.follow = "" }
    fun getPixelPerfect(): Boolean = cam()?.pixelPerfect ?: false
    fun setPixelPerfect(v: Boolean) { cam()?.pixelPerfect = v }
    fun getBackground(): String = Colors.toHex(cam()?.background ?: 0)
    fun setBackground(hex: String) { cam()?.background = Colors.parse(hex, 0xFF14181F.toInt()) }
    fun postFx(name: String, intensity: Double = 1.0) {
        val index = com.sengine.engine.core.ComponentRegistry.POST_FX.indexOfFirst { it.equals(name, true) }
        cam()?.let { it.postFx = index.coerceAtLeast(0); it.postIntensity = intensity.toFloat() }
    }
    fun worldToScreenX(x: Double): Double = engine.gameView.worldToScreenX(x.toFloat()).toDouble()
    fun worldToScreenY(y: Double): Double = engine.gameView.worldToScreenY(y.toFloat()).toDouble()
}

/** UI API (`ui.*`) - drives the 2D canvas widgets at runtime. */
class SUi(private val engine: Engine, private val sys: ScriptSystem) {

    fun setText(objectName: String, value: String) = UiRuntime.setText(engine.scene, objectName, value)
    fun setProgress(objectName: String, value: Double) = UiRuntime.setProgress(engine.scene, objectName, value.toFloat())
    fun setSlider(objectName: String, value: Double) = UiRuntime.setSlider(engine.scene, objectName, value.toFloat())
    fun setVisible(objectName: String, visible: Boolean) = UiRuntime.setVisible(engine.scene, objectName, visible)
    fun addListItem(objectName: String, item: String) = UiRuntime.addListItem(engine.scene, objectName, item)
    fun toast(message: String) = UiRuntime.canvasOf(engine.scene)?.let { UiRuntime.toast(engine.scene, it, message) }

    /** Shows a modal dialog; [onResult] receives the pressed button index. */
    fun show(title: String, message: String, buttons: Any? = null, onResult: Function? = null): Boolean {
        val labels = when (buttons) {
            null -> listOf("OK")
            is NativeArray -> (0 until buttons.length).map { buttons.get(it).toString() }
            else -> listOf("OK")
        }
        val canvas = UiRuntime.canvasOf(engine.scene) ?: return false
        UiDialogs.show(engine.scene, canvas, title, message, labels) { index, _ ->
            sys.callFunction(onResult, index.toDouble())
        }
        return true
    }

    fun confirm(title: String, message: String, onYes: Function?): Boolean {
        val canvas = UiRuntime.canvasOf(engine.scene) ?: return false
        UiDialogs.show(engine.scene, canvas, title, message, listOf("Cancel", "OK")) { index, _ ->
            if (index == 1) sys.callFunction(onYes)
        }
        return true
    }

    fun hovered(): String = engine.ui.hoveredWidget?.go?.name ?: ""
    fun focused(): String = engine.ui.focusedWidget?.go?.name ?: ""
    fun isHovered(objectName: String): Boolean = engine.ui.hoveredWidget?.go?.name == objectName
    fun setFocus(objectName: String) {
        val target = engine.ui.currentWidgets().firstOrNull { it.go?.name == objectName }
        engine.ui.setFocus(target)
    }
    fun focusNext() = engine.ui.focusNext(1)
    fun focusPrevious() = engine.ui.focusNext(-1)
    fun widgetCount(): Double = engine.ui.widgetCount.toDouble()
}

/** Particle helpers (`particles.*`). */
class SParticles(private val engine: Engine, private val sys: ScriptSystem) {

    /** Names of every built-in effect preset (fire, smoke, explosion, rain, ...). */
    fun presets(): Any? = sys.newArray(com.sengine.engine.fx.ParticlePresets.names)

    fun hasPreset(name: String): Boolean = com.sengine.engine.fx.ParticlePresets.find(name) != null

    /** Spawns a particle object with the given preset at (x, y); returns it for further control. */
    fun play(presetName: String, x: Double, y: Double, destroyAfter: Double = 0.0): SObject? {
        val go = engine.spawnParticles(presetName, x.toFloat(), y.toFloat(), destroyAfter.toFloat()) ?: return null
        return sys.wrap(go)
    }

    fun burst(presetName: String, x: Double, y: Double, count: Double = 20.0): SObject? {
        val go = engine.spawnParticles(presetName, x.toFloat(), y.toFloat(), 3f) ?: return null
        go.getAny<ParticleEmitter>()?.burst(count.toInt())
        return sys.wrap(go)
    }

    fun trail(go: SObject, width: Double = 0.25, color: String = "#FFFFFFFF") {
        val t = Trail2D()
        t.width = width.toFloat()
        t.color = Colors.parse(color, Colors.WHITE)
        go.attachTrail(t)
    }

    fun clear(): Double {
        var n = 0
        for (o in engine.scene.objects) {
            o.getAny<ParticleEmitter>()?.let { it.system?.clear(); n++ }
        }
        return n.toDouble()
    }
}

/** Coroutine / timer API (`tasks.*`) implemented on the engine task scheduler. */
class STasks(private val engine: Engine, private val sys: ScriptSystem) {

    /** Runs [fn] once after [seconds]. */
    fun after(seconds: Double, fn: Function): String {
        val name = "after:${seconds}"
        engine.tasks.launch(name) {
            delay(seconds.toFloat())
            sys.callFunction(fn)
        }
        return name
    }

    /** Runs [fn] every [seconds] seconds until the task or the scene stops. */
    fun every(seconds: Double, fn: Function): String {
        val name = "every:${seconds}"
        engine.tasks.launch(name) {
            while (true) {
                delay(seconds.toFloat())
                sys.callFunction(fn)
            }
        }
        return name
    }

    /** Waits [frames] rendered frames (frame-accurate gameplay timing). */
    fun afterFrames(frames: Int, fn: Function): String {
        val name = "frames:$frames"
        engine.tasks.launch(name) {
            frames(frames)
            sys.callFunction(fn)
        }
        return name
    }

    /** Runs [fn] once the pump returns true (checked every frame, no polling cost in scripts). */
    fun waitUntil(fn: Function, action: Function): String {
        val name = "waitUntil"
        engine.tasks.launch(name) {
            waitUntil { sys.callFunction(fn) == true }
            sys.callFunction(action)
        }
        return name
    }

    /** Simple value tween: calls fn(value) each frame from a to b over [seconds]. */
    fun tween(a: Double, b: Double, seconds: Double, fn: Function, smooth: Boolean = true): String {
        val name = "tween"
        engine.tasks.launch(name) {
            val duration = seconds.toFloat().coerceAtLeast(0.001f)
            var t = 0f
            while (t < duration) {
                yield()
                t += engine.lastDelta
                val k = (t / duration).coerceIn(0f, 1f)
                val e = if (smooth) k * k * (3f - 2f * k) else k
                sys.callFunction(fn, a + (b - a) * e)
            }
            sys.callFunction(fn, b)
        }
        return name
    }

    fun cancelAll() = engine.tasks.cancelAll()
    fun active(): Double = engine.tasks.activeCount.toDouble()
}

/** Deterministic random helpers (`random.*`). */
class SRandom {
    private var rng = Random()

    fun seed(s: Double) { rng = Random(s.toLong()) }
    fun value(): Double = rng.nextDouble()
    fun range(a: Double, b: Double): Double = a + rng.nextDouble() * (b - a)
    fun int(a: Double, b: Double): Double = (a.toInt() + rng.nextInt((b.toInt() - a.toInt() + 1).coerceAtLeast(1))).toDouble()
    fun chance(p: Double): Boolean = rng.nextDouble() < p
    fun sign(): Double = if (rng.nextBoolean()) 1.0 else -1.0
    fun insideCircle(radius: Double): Any? {
        val a = rng.nextDouble() * Math.PI * 2
        val r = kotlin.math.sqrt(rng.nextDouble()) * radius
        return doubleArrayOf(cos(a) * r, sin(a) * r)
    }
    fun onRing(radius: Double): Any? {
        val a = rng.nextDouble() * Math.PI * 2
        return doubleArrayOf(cos(a) * radius, sin(a) * radius)
    }
}

/** Procedural noise (`noise.*`) for terrain, wind and camera shake. */
class SNoise {
    private var seed = 0
    fun setSeed(s: Double) { seed = s.toInt() }
    fun value(x: Double, y: Double): Double = Noise2D.value(x.toFloat(), y.toFloat(), seed).toDouble()
    fun fbm(x: Double, y: Double, octaves: Double = 3.0): Double =
        Noise2D.fbm(x.toFloat(), y.toFloat(), octaves.toInt().coerceIn(1, 8), 2f, 0.5f, seed).toDouble()
}

/** Save / load helpers (`save.*`) — serialises the live scene or named slots. */
class SSave(private val engine: Engine, private val sys: ScriptSystem) {
    private val values = HashMap<String, Any?>()

    fun set(key: String, value: Any?) { values[key] = value }
    fun get(key: String): Any? = values[key]
    fun getNumber(key: String, fallback: Double = 0.0): Double = (values[key] as? Number)?.toDouble() ?: fallback
    fun getString(key: String, fallback: String = ""): String = values[key]?.toString() ?: fallback
    fun has(key: String): Boolean = values.containsKey(key)
    fun remove(key: String) { values.remove(key) }
    fun clear() { values.clear() }
    fun keys(): Any? = sys.newArray(values.keys.toList())

    /** Writes the whole scene + this key/value bag into a slot file. */
    fun save(slot: String = "save1"): Boolean {
        engine.saveState(slot)
        engine.project.writeState("$slot.vars", serialize())
        return true
    }

    fun load(slot: String = "save1"): Boolean {
        val ok = engine.loadState(slot)
        deserialize(engine.project.readState("$slot.vars"))
        return ok
    }

    fun exists(slot: String = "save1"): Boolean = engine.project.hasState(slot)
    fun list(): Any? = sys.newArray(engine.project.listStates())

    private fun serialize(): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in values) {
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(k.replace("\"", "\\\"")).append("\":")
            when (v) {
                null -> sb.append("null")
                is Number, is Boolean -> sb.append(v.toString())
                else -> sb.append('"').append(v.toString().replace("\"", "\\\"")).append('"')
            }
        }
        return sb.append('}').toString()
    }

    private fun deserialize(raw: String?) {
        if (raw.isNullOrBlank()) return
        try {
            val o = org.json.JSONObject(raw)
            values.clear()
            for (key in o.keys()) values[key] = o.get(key)
        } catch (_: Exception) {
        }
    }
}

/** Console API (`console.*`) — real logging into the editor console. */
class SConsole(private val engine: Engine) {
    fun log(msg: Any?) = engine.log(0, msg?.toString() ?: "null")
    fun warn(msg: Any?) = engine.log(1, msg?.toString() ?: "null")
    fun error(msg: Any?) = engine.log(2, msg?.toString() ?: "null")
    fun clear() = engine.clearLogs()
}

/** Convenience wrapper kept for the editor console (`engine` object in scripts). */
class SEngineInfo(private val engine: Engine) {
    fun getFps(): Double = engine.fps.toDouble()
    fun getObjects(): Double = engine.scene.objects.count { !it.destroyed }.toDouble()
    fun getBodies(): Double = engine.rigidBodyCount.toDouble()
    fun getDrawCalls(): Double = engine.drawCalls.toDouble()
    fun getParticles(): Double = engine.activeParticles.toDouble()
    fun getMode(): String = engine.mode.name.lowercase()
    fun getProject(): String = engine.project.name
    fun isPaused(): Boolean = engine.mode == Engine.Mode.PAUSED
}
