package com.sengine.engine.physics

import com.sengine.engine.core.GameObject
import com.sengine.engine.math.M
import com.sengine.engine.math.Rect2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A 2D rigid body. Bodies are owned by a [PhysicsWorld2D] and are synced back to their
 * [GameObject] transform after every step. All quantities are 2D: linear position/velocity,
 * a single Z-axis angle and angular velocity.
 */
class Body2D internal constructor(val go: GameObject, var type: Type) {

    enum class Type(val label: String) {
        STATIC("Static"),
        KINEMATIC("Kinematic"),
        DYNAMIC("Dynamic"),
        CHARACTER("Character");
    }

    var shape: Shape2D = Shapes.box(1f, 1f)
        internal set

    /** Collider offset in the object's local space. */
    var offsetX = 0f
    var offsetY = 0f

    /** Extra rotation of the collider relative to the object (degrees). */
    var offsetAngle = 0f

    // ---------------------------------------------------------------- transform
    var x = 0f; internal set
    var y = 0f; internal set

    /** Rotation in radians (Z axis only). */
    var angle = 0f
        set(value) {
            field = value
            cosA = cos(value)
            sinA = sin(value)
        }
    internal var cosA = 1f
    internal var sinA = 0f

    // previous transform, used for CCD and "teleport" detection
    internal var prevX = 0f
    internal var prevY = 0f
    internal var prevAngle = 0f

    // ---------------------------------------------------------------- dynamics
    var vx = 0f
    var vy = 0f
    var angularVelocity = 0f

    /** Accumulated force for the current step (cleared every step). */
    var forceX = 0f
    var forceY = 0f
    var torque = 0f

    var mass = 1f; private set
    var inertia = 1f; private set
    var invMass = 1f; private set
    var invInertia = 1f; private set

    var gravityScale = 1f
    var linearDamping = 0f
    var angularDamping = 0f
    var fixedRotation = false

    /** Smallest velocity change needed to wake a sleeping body. */
    var sleepThreshold = 0.05f
    var allowSleep = true
    var awake = true
        internal set
    internal var sleepTime = 0f

    /** Enables swept continuous collision detection for fast bodies. */
    var continuous = false

    /** Grounded state maintained by the world (normal pointing up in the last contacts). */
    var grounded = false
    var groundBody: Body2D? = null
    var groundNormalX = 0f
    var groundNormalY = 1f
    /** True once the world has initialised this body from its components. */
    internal var initialised = false

    /** Last frame's support velocity (moving platforms). */
    var groundVx = 0f
    var groundVy = 0f

    /** Extra downward tolerance before a body is considered airborne (platformer feel). */
    var groundTolerance = 0.04f

    // ---------------------------------------------------------------- material & filtering
    var friction = 0.4f
    var restitution = 0f
    var density = 1f

    /** Collision layer bits (1 shl layer). */
    var layer: Int = 1
    /** Mask of layers this body collides with. */
    var mask: Int = -1
    var isSensor = false
    var enabled = true

    /** One-way platform: collisions only resolve when coming from the "up" side. */
    var oneWay = false
    var oneWayNormalX = 0f
    var oneWayNormalY = 1f

    /** User data for gameplay code (weapon owner, damage, material name...). */
    var userData: Any? = null

    /** Object scale the collider was last built with (the shape itself is pre-scaled). */
    var shapeScaleX = 1f
    var shapeScaleY = 1f

    /** Effective collision radius of the (already scaled) shape. */
    val radius get() = if (shape is CircleShape) (shape as CircleShape).radius else shape.boundingRadius()

    /** Contact/broadphase bookkeeping. */
    internal val aabb = Rect2()
    internal var proxyId = -1
    internal val contacts = ArrayList<Contact2D>()
    internal val isBullet get() = continuous && type == Type.DYNAMIC

    val isStatic get() = type == Type.STATIC
    val isDynamic get() = type == Type.DYNAMIC
    val isKinematic get() = type == Type.KINEMATIC
    val isCharacter get() = type == Type.CHARACTER

    val speed get() = sqrt(vx * vx + vy * vy)
    val speedSq get() = vx * vx + vy * vy

    fun setTransform(nx: Float, ny: Float, angleRad: Float) {
        x = nx; y = ny; prevX = nx; prevY = ny
        angle = angleRad
        prevAngle = angleRad
    }

    fun setMass(m: Float) {
        mass = max(1e-4f, m)
        invMass = if (type == Type.STATIC || type == Type.KINEMATIC) 0f else 1f / mass
        inertia = mass
        invInertia = if (fixedRotation || invMass == 0f) 0f else 1f / inertia
    }

    fun setMassData(md: MassData) {
        mass = max(1e-4f, md.mass)
        inertia = max(1e-5f, md.inertia)
        val movable = type == Type.DYNAMIC
        invMass = if (movable) 1f / mass else 0f
        invInertia = if (movable && !fixedRotation) 1f / inertia else 0f
    }

    /** Changes the body type and refreshes mass/inertia accordingly. */
    fun changeType(t: Type) {
        if (t == type) return
        type = t
        val md = shape.computeMass(density)
        setMassData(md)
        if (t != Type.DYNAMIC) {
            vx = 0f; vy = 0f; angularVelocity = 0f
            forceX = 0f; forceY = 0f; torque = 0f
        }
        awake = true
    }

    /** Recomputes mass/inertia after the shape or density changed. */
    fun refreshMass() {
        val md = shape.computeMass(density)
        setMassData(md)
    }

    /**
     * Sets the body mass explicitly (Kg), keeping the shape's inertia scaled by the same factor.
     * A rigidbody with `mass > 0` is authoritative; a mass of 0 leaves the mass to the collider
     * density. Without this the `Rigidbody2D.mass` property had no effect at all.
     */
    fun overrideMass(m: Float) {
        val md = shape.computeMass(if (density > 0f) density else 1f)
        val scale = if (md.mass > 1e-6f) m / md.mass else 1f
        setMassData(MassData(max(1e-4f, m), max(1e-5f, md.inertia * scale)))
    }

    fun wake() {
        awake = true
        sleepTime = 0f
    }

    fun sleep() {
        if (type != Type.DYNAMIC) return
        awake = false
        vx = 0f; vy = 0f; angularVelocity = 0f
    }

    // ---------------------------------------------------------------- forces
    fun applyForce(fx: Float, fy: Float) {
        if (type != Type.DYNAMIC) return
        forceX += fx; forceY += fy
        wake()
    }

    fun applyForceAt(fx: Float, fy: Float, px: Float, py: Float) {
        if (type != Type.DYNAMIC) return
        forceX += fx; forceY += fy
        torque += (px - x) * fy - (py - y) * fx
        wake()
    }

    fun applyTorque(t: Float) {
        if (type != Type.DYNAMIC) return
        torque += t
        wake()
    }

    fun applyImpulse(ix: Float, iy: Float) {
        if (invMass == 0f) return
        vx += ix * invMass
        vy += iy * invMass
        wake()
    }

    fun applyImpulseAt(ix: Float, iy: Float, px: Float, py: Float) {
        if (invMass == 0f) return
        vx += ix * invMass
        vy += iy * invMass
        if (invInertia > 0f) angularVelocity += invInertia * ((px - x) * iy - (py - y) * ix)
        wake()
    }

    fun applyAngularImpulse(i: Float) {
        if (invInertia == 0f) return
        angularVelocity += i * invInertia
        wake()
    }

    /** Linear velocity at a world point (used by joints and friction). */
    fun velocityAt(px: Float, py: Float): FloatArray {
        val rx = px - x; val ry = py - y
        return floatArrayOf(vx - angularVelocity * ry, vy + angularVelocity * rx)
    }

    fun pointToLocal(px: Float, py: Float): FloatArray {
        val dx = px - x; val dy = py - y
        return floatArrayOf(dx * cosA + dy * sinA, -dx * sinA + dy * cosA)
    }

    fun pointToWorld(lx: Float, ly: Float): FloatArray =
        floatArrayOf(x + lx * cosA - ly * sinA, y + lx * sinA + ly * cosA)

    fun worldVertices(): FloatArray {
        val lv = shape.localVertices()
        val out = FloatArray(lv.size)
        var i = 0
        while (i < lv.size) {
            val lx = lv[i]; val ly = lv[i + 1]
            out[i] = x + lx * cosA - ly * sinA
            out[i + 1] = y + lx * sinA + ly * cosA
            i += 2
        }
        return out
    }

    /** Syncs the GameObject transform from the body (called after the physics step). */
    fun syncToGameObject() {
        val obj = go
        val lx = offsetX; val ly = offsetY
        val ox = lx * cosA - ly * sinA
        val oy = lx * sinA + ly * cosA
        val angleDeg = Math.toDegrees(angle.toDouble()).toFloat() - offsetAngle
        if (obj.parent == null) {
            obj.x = x - ox
            obj.y = y - oy
        } else {
            obj.setWorldPosition(x - ox, y - oy)
        }
        if (!fixedRotation) obj.rotation = angleDeg
    }

    /** Copies the GameObject transform into the body without touching velocities. */
    fun syncTransformFromGameObject() {
        val w = go.computeWorld()
        x = w.mapX(offsetX, offsetY)
        y = w.mapY(offsetX, offsetY)
        angle = Math.toRadians((w.rotationDeg + offsetAngle).toDouble()).toFloat()
        prevX = x; prevY = y; prevAngle = angle
        updateAabb()
    }

    /** Reads the GameObject transform into the body. */
    fun syncFromGameObject() {
        val w = go.computeWorld()
        val lx = offsetX; val ly = offsetY
        x = w.mapX(lx, ly)
        y = w.mapY(lx, ly)
        angle = Math.toRadians((w.rotationDeg + offsetAngle).toDouble()).toFloat()
        prevX = x; prevY = y; prevAngle = angle
        vx = 0f; vy = 0f; angularVelocity = 0f
    }

    internal fun updateAabb() {
        shape.computeAabb(x, y, angle, cosA, sinA, aabb)
    }

    override fun toString() = "Body2D(${go.name}, $type, ${shape.type})"

    companion object {
        internal fun typeOf(componentType: Int): Type = when (componentType) {
            1 -> Type.KINEMATIC
            2 -> Type.STATIC
            3 -> Type.CHARACTER
            else -> Type.DYNAMIC
        }
    }
}

/** A contact between two bodies (max 2 points, like a 2D manifold). */
class Contact2D(val a: Body2D, val b: Body2D) {
    var normalX = 0f
    var normalY = 0f
    var pointCount = 0
    val pointX = FloatArray(2)
    val pointY = FloatArray(2)
    val penetration = FloatArray(2)
    var touching = false
    var sensor = false
    /** Normal impulse applied in the last step (used by vehicle traction and damage systems). */
    var normalImpulse = 0f
    var tangentImpulse = 0f
    internal val normalImpulseAcc = FloatArray(2)
    internal val tangentImpulseAcc = FloatArray(2)
    internal val separation = FloatArray(2)
    internal var friction = 0.4f
    internal var restitution = 0f
    internal var persisted = false

    fun other(body: Body2D) = if (body === a) b else a
    fun key(): Long {
        val la = minOf(a.go.id, b.go.id)
        val hi = maxOf(a.go.id, b.go.id)
        return (la shl 32) or (hi and 0xFFFFFFFFL)
    }
}

/** Immutable snapshot handed to scripts/editor during contact callbacks. */
class ContactEvent(val self: GameObject, val other: GameObject, val contact: Contact2D) {
    val normalX get() = contact.normalX
    val normalY get() = contact.normalY
    val pointX get() = contact.pointX[0]
    val pointY get() = contact.pointY[0]
    val impulse get() = contact.normalImpulse
    val isSensor get() = contact.sensor
    val relativeSpeed: Float
        get() {
            val rvx = contact.b.vx - contact.a.vx
            val rvy = contact.b.vy - contact.a.vy
            return abs(rvx * contact.normalX + rvy * contact.normalY)
        }
}

/** Per-step statistics used by the profiler and the physics debug overlay. */
class PhysicsStats {
    var bodies = 0
    var dynamicBodies = 0
    var staticBodies = 0
    var sleepingBodies = 0
    var awakeBodies = 0
    var contacts = 0
    var sensors = 0
    var pairs = 0
    var broadphasePairs = 0
    var joints = 0
    var solverIterations = 0
    var broadphaseMs = 0f
    var narrowphaseMs = 0f
    var solverMs = 0f
    var stepMs = 0f
    var ccdHits = 0
    var stepsPerSecond = 0f

    fun reset() {
        bodies = 0; dynamicBodies = 0; staticBodies = 0; sleepingBodies = 0; awakeBodies = 0
        contacts = 0; sensors = 0; pairs = 0; broadphasePairs = 0; joints = 0
        ccdHits = 0
    }
}

internal fun aabbOverlap(a: Rect2, b: Rect2) =
    !(b.x > a.x + a.w || b.x + b.w < a.x || b.y > a.y + a.h || b.y + b.h < a.y)

internal fun M_abs(v: Float) = if (v < 0f) -v else v
