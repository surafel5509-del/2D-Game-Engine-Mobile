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

    // physics
    private val rb3 get() = go.getAny<com.sengine.engine.core.Rigidbody3D>()
    fun getVx(): Double = (rb3?.vx ?: go.getAny<Rigidbody2D>()?.vx ?: 0f).toDouble()
    fun setVx(v: Double) { rb3?.let { it.vx = v.toFloat(); return }; go.getAny<Rigidbody2D>()?.vx = v.toFloat() }
    fun getVy(): Double = (rb3?.vy ?: go.getAny<Rigidbody2D>()?.vy ?: 0f).toDouble()
    fun setVy(v: Double) { rb3?.let { it.vy = v.toFloat(); return }; go.getAny<Rigidbody2D>()?.vy = v.toFloat() }
    fun getVz(): Double = (rb3?.vz ?: 0f).toDouble()
    fun setVz(v: Double) { rb3?.vz = v.toFloat() }
    fun isGrounded(): Boolean = rb3?.grounded ?: go.getAny<Rigidbody2D>()?.grounded ?: false
    fun addForce(fx: Double, fy: Double) {
        rb3?.let { it.vx += (fx / it.mass).toFloat(); it.vy += (fy / it.mass).toFloat(); return }
        val rb = go.getAny<Rigidbody2D>() ?: return
        rb.vx += (fx / rb.mass).toFloat(); rb.vy += (fy / rb.mass).toFloat()
    }
    fun addForce(fx: Double, fy: Double, fz: Double) {
        val rb = rb3 ?: return addForce(fx, fy)
        rb.vx += (fx / rb.mass).toFloat(); rb.vy += (fy / rb.mass).toFloat(); rb.vz += (fz / rb.mass).toFloat()
    }
    fun setVelocity(vx: Double, vy: Double) {
        rb3?.let { it.vx = vx.toFloat(); it.vy = vy.toFloat(); return }
        val rb = go.getAny<Rigidbody2D>() ?: return
        rb.vx = vx.toFloat(); rb.vy = vy.toFloat()
    }
    fun setVelocity(vx: Double, vy: Double, vz: Double) {
        val rb = rb3 ?: return setVelocity(vx, vy)
        rb.vx = vx.toFloat(); rb.vy = vy.toFloat(); rb.vz = vz.toFloat()
    }

    // ---- 3D transform
    fun getZ(): Double = go.z.toDouble()
    fun setZ(v: Double) { go.z = v.toFloat() }
    fun getRotX(): Double = go.rotX.toDouble()
    fun setRotX(v: Double) { go.rotX = v.toFloat() }
    fun getRotY(): Double = go.rotY.toDouble()
    fun setRotY(v: Double) { go.rotY = v.toFloat() }
    fun getRotZ(): Double = go.rotation.toDouble()
    fun setRotZ(v: Double) { go.rotation = v.toFloat() }
    fun getScaleZ(): Double = go.scaleZ.toDouble()
    fun setScaleZ(v: Double) { go.scaleZ = v.toFloat() }
    fun getWorldZ(): Double = go.computeWorld3()[14].toDouble()
    fun setPosition(x: Double, y: Double, z: Double) { go.x = x.toFloat(); go.y = y.toFloat(); go.z = z.toFloat() }
    fun move(dx: Double, dy: Double, dz: Double) { go.x += dx.toFloat(); go.y += dy.toFloat(); go.z += dz.toFloat() }
    fun rotate(rx: Double, ry: Double, rz: Double) { go.rotX += rx.toFloat(); go.rotY += ry.toFloat(); go.rotation += rz.toFloat() }
    fun distanceTo3(o: SObject): Double {
        val a = go.computeWorld3(); val b = o.go.computeWorld3()
        val dx = a[12] - b[12]; val dy = a[13] - b[13]; val dz = a[14] - b[14]
        return Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble())
    }
    /** Local forward (-Z) direction in world space as [x, y, z]. */
    fun forward(): Any? {
        val d = com.sengine.engine.math.Mat4.dir(go.computeWorld3(), 0f, 0f, -1f)
        return sys.newArray(listOf(d[0].toDouble(), d[1].toDouble(), d[2].toDouble()))
    }

    // ---- animation
    fun play(clip: String) {
        val a = go.getAny<com.sengine.engine.core.Animator>() ?: return
        if (a.current == clip && a.playing && !a.finished) return
        a.current = clip; a.time = 0f; a.frame = 0; a.finished = false; a.playing = true
    }
    fun stopAnimation() { go.getAny<com.sengine.engine.core.Animator>()?.playing = false }
    fun getAnimation(): String = go.getAny<com.sengine.engine.core.Animator>()?.current ?: ""
    fun isAnimationFinished(): Boolean = go.getAny<com.sengine.engine.core.Animator>()?.finished ?: true
    fun setAnimSpeed(v: Double) { go.getAny<com.sengine.engine.core.Animator>()?.speed = v.toFloat() }

    // ---- rendering
    fun setShaderParam(v: Double) {
        go.getAny<com.sengine.engine.core.MeshRenderer>()?.shaderParam = v.toFloat()
        go.getAny<SpriteRenderer>()?.shaderParam = v.toFloat()
    }
    fun setMeshColor(hex: String) {
        val c = try { Component.parseColor(hex) } catch (e: Exception) { return }
        go.getAny<com.sengine.engine.core.MeshRenderer>()?.color = c
    }
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
    private fun spawnGo(name: String, x: Float, y: Float, z: Float?): GameObject? {
        val template = engine.scene.find(name) ?: run { engine.log(1, "spawn: '$name' not found"); return null }
        val copy = engine.scene.duplicate(template, null)
        copy.active = true
        if (z != null) copy.setWorldPosition3(x, y, z) else copy.setWorldPosition(x, y)
        for (d in listOf(copy) + engine.scene.objects.filter { copy.isAncestorOf(it) }) {
            d.components.forEach { it.resetRuntime() }
        }
        engine.scene.updateTransforms()
        sys.attach(copy)
        return copy
    }
    fun spawn(name: String, x: Double, y: Double): Any? = spawnGo(name, x.toFloat(), y.toFloat(), null)?.let { sys.toJs(it) }
    fun spawn(name: String): Any? {
        val t = engine.scene.find(name) ?: return null
        val w = t.computeWorld()
        return spawn(name, w.tx.toDouble(), w.ty.toDouble())
    }
    fun spawn(name: String, x: Double, y: Double, z: Double): Any? = spawnGo(name, x.toFloat(), y.toFloat(), z.toFloat())?.let { sys.toJs(it) }
    fun shake(amount: Double) = engine.shake(amount.toFloat())
    /** 3D raycast against Collider3D objects; returns the first object hit or null. */
    fun raycast(ox: Double, oy: Double, oz: Double, dx: Double, dy: Double, dz: Double, maxDist: Double): Any? {
        val l = Math.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-9)
        return engine.physics3D.raycast(engine.scene, ox.toFloat(), oy.toFloat(), oz.toFloat(),
            (dx / l).toFloat(), (dy / l).toFloat(), (dz / l).toFloat(), maxDist.toFloat())?.let { sys.toJs(it) }
    }
    fun getCamera3D(): Any? = engine.mainCamera3D()?.let { sys.toJs(it) }
    fun getGravity3D(): Double = engine.scene.gravity3D.toDouble()
    fun setGravity3D(v: Double) { engine.scene.gravity3D = v.toFloat() }
    fun load(sceneName: String) = engine.requestLoadScene(sceneName)
    fun reload() = engine.requestLoadScene(engine.scene.name)
    fun getCamera(): Any? = engine.mainCamera()?.let { sys.toJs(it) }
    fun getGravityX(): Double = engine.scene.gravityX.toDouble()
    fun setGravityX(v: Double) { engine.scene.gravityX = v.toFloat() }
    fun getGravityY(): Double = engine.scene.gravityY.toDouble()
    fun setGravityY(v: Double) { engine.scene.gravityY = v.toFloat() }
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
