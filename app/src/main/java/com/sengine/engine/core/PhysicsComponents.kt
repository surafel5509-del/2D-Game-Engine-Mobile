package com.sengine.engine.core

import kotlin.math.abs
import kotlin.math.max

import com.sengine.engine.physics.Body2D
import com.sengine.engine.physics.CircleShape
import com.sengine.engine.physics.DistanceJoint2D
import com.sengine.engine.physics.HingeJoint2D
import com.sengine.engine.physics.Joint2D
import com.sengine.engine.physics.MotorJoint2D
import com.sengine.engine.physics.PhysicsWorld2D
import com.sengine.engine.physics.PolygonShape
import com.sengine.engine.physics.PrismaticJoint2D
import com.sengine.engine.physics.Shape2D
import com.sengine.engine.physics.SpringJoint2D
import com.sengine.engine.physics.WeldJoint2D
import com.sengine.engine.physics.WheelJoint2D

/**
 * Named collision layers. The engine uses bitmasks everywhere: a collider belongs to one layer
 * and chooses which layers it reacts to.
 */
object PhysicsLayers {
    val names = listOf(
        "Default", "Player", "Enemy", "Terrain", "Vehicle", "Projectile", "Item", "Sensor"
    )
    fun bit(index: Int) = 1 shl index.coerceIn(0, 30)
    fun indexOf(name: String) = names.indexOf(name).coerceAtLeast(0)
    const val ALL = -1
    val defaultMask = names.indices.fold(0) { acc, i -> acc or (1 shl i) }
}

/**
 * Rigidbody2D - the 2D physics body attached to a GameObject.
 * Supports dynamic, kinematic, static and character body types.
 */
class Rigidbody2D : Component() {
    override val type = "Rigidbody2D"

    /** 0 = Dynamic, 1 = Kinematic, 2 = Static, 3 = Character. */
    var bodyType = 0
    var mass = 1f
    var gravityScale = 1f
    var linearDamping = 0f
    var angularDamping = 0.05f
    var fixedRotation = false
    var continuous = false
    var allowSleep = true
    var jumpTolerance = 0.04f
    var startVx = 0f
    var startVy = 0f
    var startAngularVelocity = 0f

    /**
     * When true the body ignores one-way platforms (used for "press down to drop through").
     * The character controller toggles this automatically.
     */
    var dropThrough = false

    /** Runtime body handle (null until the first physics step). */
    var body: Body2D? = null
        private set

    internal fun attach(b: Body2D) {
        body = b
    }

    /** Runtime velocity - reading/writing this goes straight to the physics body. */
    var vx: Float
        get() = body?.vx ?: 0f
        set(value) { body?.let { it.vx = value; it.wake() } }

    var vy: Float
        get() = body?.vy ?: 0f
        set(value) { body?.let { it.vy = value; it.wake() } }

    var angularVelocity: Float
        get() = body?.let { Math.toDegrees(it.angularVelocity.toDouble()).toFloat() } ?: 0f
        set(value) {
            body?.let {
                it.angularVelocity = Math.toRadians(value.toDouble()).toFloat()
                it.wake()
            }
        }

    val grounded: Boolean get() = body?.grounded ?: false
    val sleeping: Boolean get() = body?.let { !it.awake } ?: false
    val speed: Float get() = body?.speed ?: 0f
    val groundNormalX: Float get() = body?.groundNormalX ?: 0f
    val groundNormalY: Float get() = body?.groundNormalY ?: 1f
    val massActual: Float get() = body?.mass ?: mass
    val inertialMass: Float get() = body?.mass ?: mass
    val kineticEnergy: Float get() = body?.let { 0.5f * it.mass * it.speedSq } ?: 0f

    fun addForce(fx: Float, fy: Float) = body?.applyForce(fx, fy) ?: Unit
    fun addForceAt(fx: Float, fy: Float, px: Float, py: Float) = body?.applyForceAt(fx, fy, px, py) ?: Unit
    fun addImpulse(ix: Float, iy: Float) = body?.applyImpulse(ix, iy) ?: Unit
    fun addImpulseAt(ix: Float, iy: Float, px: Float, py: Float) = body?.applyImpulseAt(ix, iy, px, py) ?: Unit
    fun addTorque(t: Float) = body?.applyTorque(t) ?: Unit
    fun addAngularImpulse(i: Float) = body?.applyAngularImpulse(i) ?: Unit
    fun wake() = body?.wake() ?: Unit
    fun sleep() = body?.sleep() ?: Unit

    /** Teleports the body (keeps previous transform for CCD). */
    fun teleport(x: Float, y: Float) {
        val b = body ?: return
        b.x = x; b.y = y; b.prevX = x; b.prevY = y
        b.syncToGameObject()
    }

    override fun props() = listOf(
        Prop.Choice("Body Type", BODY_TYPES, { bodyType }, { bodyType = it }, "Dynamic bodies are fully simulated, Kinematic ones are moved by code, Static ones never move."),
        Prop.F("Mass", { mass }, { mass = it.coerceAtLeast(0.001f) }, 0.1f, 0.001f, 10000f),
        Prop.F("Gravity Scale", { gravityScale }, { gravityScale = it }, 0.05f, -10f, 20f),
        Prop.F("Linear Damping", { linearDamping }, { linearDamping = it.coerceAtLeast(0f) }, 0.01f, 0f, 20f),
        Prop.F("Angular Damping", { angularDamping }, { angularDamping = it.coerceAtLeast(0f) }, 0.01f, 0f, 20f),
        Prop.B("Fixed Rotation", { fixedRotation }, { fixedRotation = it }, "Prevents the body from rotating (platformer characters)."),
        Prop.B("Continuous (CCD)", { continuous }, { continuous = it }, "Sweeps fast bodies so bullets never tunnel through walls."),
        Prop.B("Allow Sleep", { allowSleep }, { allowSleep = it }, "Sleeping bodies cost no simulation time."),
        Prop.F("Ground Tolerance", { jumpTolerance }, { jumpTolerance = it.coerceIn(0f, 0.3f) }, 0.005f, 0f, 0.3f),
        Prop.F("Start Velocity X", { startVx }, { startVx = it }),
        Prop.F("Start Velocity Y", { startVy }, { startVy = it }),
        Prop.F("Start Spin (deg/s)", { startAngularVelocity }, { startAngularVelocity = it }, 5f),
        Prop.Info("Velocity", { "${"%.2f".format(vx)}, ${"%.2f".format(vy)}" }),
        Prop.Info("Grounded", { grounded.toString() })
    )

    override fun resetRuntime() {
        body = null
    }

    companion object {
        val BODY_TYPES = listOf("Dynamic", "Kinematic", "Static", "Character")
    }
}

/**
 * Collider2D - convex 2D collision shape. Box, circle, capsule and convex polygon are
 * supported; all of them rotate with the object and participate in layers, sensors and one-way
 * platforms.
 */
class Collider2D : Component() {
    override val type = "Collider2D"

    /** 0 = Box, 1 = Circle, 2 = Capsule, 3 = Polygon. */
    var shape = 0
    var width = 1f
    var height = 1f
    var radius = 0.5f
    var offsetX = 0f
    var offsetY = 0f
    /** Collider rotation relative to the object, in degrees. */
    var rotation = 0f

    var isTrigger = false
    var oneWay = false
    var density = 1f
    var friction = 0.4f
    var restitution = 0f
    /** Surface material name, used by vehicles/characters for terrain interaction. */
    var material = "Default"

    /** Layer index (see [PhysicsLayers]). */
    var layer = 0
    /** Bitmask of the layers this collider reacts to. */
    var collisionMask = PhysicsLayers.defaultMask

    /** Custom polygon points, "x,y x,y ..." - used when [shape] is Polygon. */
    var points = "-0.5,-0.5 0.5,-0.5 0.5,0.5 -0.5,0.5"

    val layerMask: Int get() = PhysicsLayers.bit(layer)

    /**
     * Builds the physics shape. Collider dimensions are authored in the object's local space and are
     * scaled by the object transform just like a sprite is, so a 1x1 collider on a 4x6 object covers
     * the whole object. Pass the object scale to bake it in.
     */
    fun buildShape(scaleX: Float = 1f, scaleY: Float = 1f): Shape2D {
        val sx = if (scaleX == 0f) 1f else abs(scaleX)
        val sy = if (scaleY == 0f) 1f else abs(scaleY)
        return when (shape) {
            1 -> CircleShape((radius * max(sx, sy)).coerceAtLeast(0.001f))
            2 -> PolygonShape.capsule(
                (radius * max(sx, sy)).coerceAtLeast(0.001f),
                (height * sy).coerceAtLeast(radius * 2f * sy + 0.001f)
            )
            3 -> polygonPoints(sx, sy)?.let { PolygonShape(it) } ?: PolygonShape.ofSize(width * sx, height * sy)
            else -> PolygonShape.ofSize(
                (width * sx).coerceAtLeast(0.001f),
                (height * sy).coerceAtLeast(0.001f)
            )
        }
    }

    private fun polygonPoints(scaleX: Float = 1f, scaleY: Float = 1f): FloatArray? {
        val parts = points.trim().split(Regex("[\\s;]+")).filter { it.isNotBlank() }
        if (parts.size < 3) return null
        val out = FloatArray(parts.size * 2)
        parts.forEachIndexed { i, p ->
            val xy = p.split(',')
            if (xy.size != 2) return null
            out[i * 2] = (xy[0].trim().toFloatOrNull() ?: return null) * scaleX
            out[i * 2 + 1] = (xy[1].trim().toFloatOrNull() ?: return null) * scaleY
        }
        return out
    }

    /** World-space centre of the collider. */
    fun worldCenter(): FloatArray {
        val w = go.computeWorld()
        return floatArrayOf(w.mapX(offsetX, offsetY), w.mapY(offsetX, offsetY))
    }

    /** Bounding half extents in world units (used by the editor and simple checks). */
    fun worldHalfExtents(): FloatArray {
        val w = go.computeWorld()
        return when (shape) {
            1 -> floatArrayOf(radius * w.scaleX, radius * w.scaleY)
            2 -> floatArrayOf(radius * w.scaleX, radius * w.scaleY)
            else -> floatArrayOf(width * 0.5f * w.scaleX, height * 0.5f * w.scaleY)
        }
    }

    override fun props() = listOf(
        Prop.Choice("Shape", SHAPES, { shape }, { shape = it }),
        Prop.F("Width", { width }, { width = it.coerceAtLeast(0.001f) }, 0.05f, 0.001f, 500f),
        Prop.F("Height", { height }, { height = it.coerceAtLeast(0.001f) }, 0.05f, 0.001f, 500f),
        Prop.F("Radius", { radius }, { radius = it.coerceAtLeast(0.001f) }, 0.05f, 0.001f, 500f),
        Prop.S("Polygon Points", { points }, { points = it }, multiline = true),
        Prop.F("Offset X", { offsetX }, { offsetX = it }, 0.05f),
        Prop.F("Offset Y", { offsetY }, { offsetY = it }, 0.05f),
        Prop.F("Rotation", { rotation }, { rotation = it }, 1f, -360f, 360f),
        Prop.F("Density", { density }, { density = it.coerceAtLeast(0.001f) }, 0.1f, 0.001f, 100f),
        Prop.F("Friction", { friction }, { friction = it.coerceIn(0f, 2f) }, 0.05f, 0f, 2f),
        Prop.F("Restitution", { restitution }, { restitution = it.coerceIn(0f, 1f) }, 0.05f, 0f, 1f),
        Prop.B("Is Trigger (Sensor)", { isTrigger }, { isTrigger = it }, "Sensors report overlaps without pushing bodies."),
        Prop.B("One Way Platform", { oneWay }, { oneWay = it }, "Solid from above, pass-through from below."),
        Prop.S("Material", { material }, { material = it }),
        Prop.Choice("Layer", PhysicsLayers.names, { layer }, { layer = it }),
        Prop.Flags("Collides With", PhysicsLayers.defaultMask, { collisionMask }, { collisionMask = it }, PhysicsLayers.names)
    )

    companion object {
        val SHAPES = listOf("Box", "Circle", "Capsule", "Polygon")
        const val SHAPE_BOX = 0
        const val SHAPE_CIRCLE = 1
        const val SHAPE_CAPSULE = 2
        const val SHAPE_POLYGON = 3
    }
}

// ---------------------------------------------------------------------------- joints

/** Base class for joint components; resolves the two bodies each step. */
abstract class JointComponent : Component() {
    /** Name of the other object (empty = connect to the world / a static anchor). */
    var connectedTo = ""
    var autoConfigureAnchor = true
    var anchorX = 0f
    var anchorY = 0f
    /** Reaction force above which the joint breaks (0 = unbreakable). */
    var breakForce = 0f

    var joint: Joint2D? = null
        internal set

    override fun props() = listOf(
        Prop.S("Connected To", { connectedTo }, { connectedTo = it }, tooltip = "Name of the other object; leave empty for a static anchor."),
        Prop.B("Auto Anchor", { autoConfigureAnchor }, { autoConfigureAnchor = it }),
        Prop.F("Anchor X", { anchorX }, { anchorX = it }, 0.05f),
        Prop.F("Anchor Y", { anchorY }, { anchorY = it }, 0.05f),
        Prop.F("Break Force", { breakForce }, { breakForce = it.coerceAtLeast(0f) }, 10f, 0f, 100000f)
    )

    override fun resetRuntime() {
        joint = null
    }
}

class HingeJoint : JointComponent() {
    override val type = "HingeJoint"
    var enableMotor = false
    var motorSpeed = 0f
    var maxMotorTorque = 10f
    var enableLimit = false
    var lowerAngle = -180f
    var upperAngle = 180f

    override fun props() = super.props() + listOf(
        Prop.B("Motor", { enableMotor }, { enableMotor = it }),
        Prop.F("Motor Speed (deg/s)", { motorSpeed }, { motorSpeed = it }, 10f),
        Prop.F("Max Motor Torque", { maxMotorTorque }, { maxMotorTorque = it.coerceAtLeast(0f) }, 1f, 0f, 100000f),
        Prop.B("Use Limits", { enableLimit }, { enableLimit = it }),
        Prop.F("Lower Angle", { lowerAngle }, { lowerAngle = it }, 5f, -360f, 360f),
        Prop.F("Upper Angle", { upperAngle }, { upperAngle = it }, 5f, -360f, 360f),
        Prop.Info("Angle", { "%.1f".format(Math.toDegrees((joint?.let { it.b.angle - it.a.angle } ?: 0f).toDouble())) }),
        Prop.Info("Reaction", { "%.1f N".format(joint?.reactionForce ?: 0f) })
    )
}

class DistanceJoint : JointComponent() {
    override val type = "DistanceJoint"
    var length = 1f
    var autoLength = true
    var stiffness = 1f
    var damping = 0.2f
    var rope = false

    override fun props() = super.props() + listOf(
        Prop.B("Auto Length", { autoLength }, { autoLength = it }),
        Prop.F("Length", { length }, { length = it.coerceAtLeast(0.01f) }, 0.05f, 0.01f, 500f),
        Prop.F("Stiffness", { stiffness }, { stiffness = it.coerceIn(0.02f, 1f) }, 0.05f, 0.02f, 1f),
        Prop.F("Damping", { damping }, { damping = it.coerceIn(0f, 2f) }, 0.05f, 0f, 2f),
        Prop.B("Rope (pull only)", { rope }, { rope = it }),
        Prop.Info("Length Now", { "%.2f".format((joint as? DistanceJoint2D)?.currentLength() ?: length) })
    )
}

class SpringJoint : JointComponent() {
    override val type = "SpringJoint"
    var frequency = 3f
    var dampingRatio = 0.4f
    var restLength = 1f
    var autoLength = true

    override fun props() = super.props() + listOf(
        Prop.B("Auto Length", { autoLength }, { autoLength = it }),
        Prop.F("Rest Length", { restLength }, { restLength = it.coerceAtLeast(0.01f) }, 0.05f, 0.01f, 500f),
        Prop.F("Frequency (Hz)", { frequency }, { frequency = it.coerceIn(0.1f, 30f) }, 0.1f, 0.1f, 30f),
        Prop.F("Damping Ratio", { dampingRatio }, { dampingRatio = it.coerceIn(0f, 2f) }, 0.05f, 0f, 2f)
    )
}

class RopeJoint : JointComponent() {
    override val type = "RopeJoint"
    var maxLength = 2f
    var autoLength = true

    override fun props() = super.props() + listOf(
        Prop.B("Auto Length", { autoLength }, { autoLength = it }),
        Prop.F("Max Length", { maxLength }, { maxLength = it.coerceAtLeast(0.05f) }, 0.05f, 0.05f, 500f)
    )
}

class WheelJoint : JointComponent() {
    override val type = "WheelJoint"
    var axisX = 0f
    var axisY = 1f
    var springFrequency = 4.5f
    var springDampingRatio = 0.65f
    var enableMotor = false
    var motorSpeed = 0f
    var maxMotorTorque = 20f
    var enableLimit = false
    var lowerTranslation = 0f
    var upperTranslation = 0f

    override fun props() = super.props() + listOf(
        Prop.V2("Suspension Axis", { axisX }, { axisY }, { axisX = it }, { axisY = it }),
        Prop.F("Spring Frequency", { springFrequency }, { springFrequency = it.coerceIn(0.1f, 20f) }, 0.1f, 0.1f, 20f),
        Prop.F("Spring Damping", { springDampingRatio }, { springDampingRatio = it.coerceIn(0f, 2f) }, 0.05f, 0f, 2f),
        Prop.B("Motor", { enableMotor }, { enableMotor = it }),
        Prop.F("Motor Speed (deg/s)", { motorSpeed }, { motorSpeed = it }, 10f),
        Prop.F("Max Motor Torque", { maxMotorTorque }, { maxMotorTorque = it.coerceAtLeast(0f) }, 1f, 0f, 100000f),
        Prop.B("Use Limits", { enableLimit }, { enableLimit = it }),
        Prop.F("Lower Travel", { lowerTranslation }, { lowerTranslation = it }, 0.01f, -5f, 5f),
        Prop.F("Upper Travel", { upperTranslation }, { upperTranslation = it }, 0.01f, -5f, 5f),
        Prop.Info("Suspension", { (joint as? WheelJoint2D)?.debugInfo() ?: "-" })
    )
}

class WeldJoint : JointComponent() {
    override val type = "WeldJoint"
    override fun props() = super.props()
}

class PrismaticJoint : JointComponent() {
    override val type = "PrismaticJoint"
    var axisX = 1f
    var axisY = 0f
    var enableLimit = false
    var lowerTranslation = -1f
    var upperTranslation = 1f
    var enableMotor = false
    var motorSpeed = 0f
    var maxMotorForce = 100f

    override fun props() = super.props() + listOf(
        Prop.V2("Axis", { axisX }, { axisY }, { axisX = it }, { axisY = it }),
        Prop.B("Use Limits", { enableLimit }, { enableLimit = it }),
        Prop.F("Lower", { lowerTranslation }, { lowerTranslation = it }, 0.05f),
        Prop.F("Upper", { upperTranslation }, { upperTranslation = it }, 0.05f),
        Prop.B("Motor", { enableMotor }, { enableMotor = it }),
        Prop.F("Motor Speed", { motorSpeed }, { motorSpeed = it }, 0.1f),
        Prop.F("Max Motor Force", { maxMotorForce }, { maxMotorForce = it.coerceAtLeast(0f) }, 1f, 0f, 100000f)
    )
}

class MotorJoint : JointComponent() {
    override val type = "MotorJoint"
    var offsetX = 0f
    var offsetY = 0f
    var targetAngle = 0f
    var maxForce = 500f
    var maxTorque = 250f
    var correctionFactor = 0.3f

    override fun props() = super.props() + listOf(
        Prop.F("Target Offset X", { offsetX }, { offsetX = it }, 0.05f),
        Prop.F("Target Offset Y", { offsetY }, { offsetY = it }, 0.05f),
        Prop.F("Target Angle", { targetAngle }, { targetAngle = it }, 1f),
        Prop.F("Max Force", { maxForce }, { maxForce = it.coerceAtLeast(0f) }, 5f, 0f, 100000f),
        Prop.F("Max Torque", { maxTorque }, { maxTorque = it.coerceAtLeast(0f) }, 5f, 0f, 100000f),
        Prop.F("Correction", { correctionFactor }, { correctionFactor = it.coerceIn(0f, 1f) }, 0.05f, 0f, 1f)
    )
}

/** Helper used by the editor to show how many joints exist and by the runtime to query them. */
object JointFactory {
    fun create(component: JointComponent, a: Body2D, b: Body2D, world: PhysicsWorld2D): Joint2D? {
        val ax = component.go.computeWorld().mapX(component.anchorX, component.anchorY)
        val ay = component.go.computeWorld().mapY(component.anchorX, component.anchorY)
        val joint = when (component) {
            is HingeJoint -> HingeJoint2D(a, b, ax, ay).also {
                it.enableMotor = component.enableMotor
                it.motorSpeed = component.motorSpeed
                it.maxMotorTorque = component.maxMotorTorque
                it.enableLimit = component.enableLimit
                it.lowerAngle = component.lowerAngle
                it.upperAngle = component.upperAngle
            }
            is DistanceJoint -> {
                val len = if (component.autoLength) com.sengine.engine.math.M.dist(ax, ay, b.x, b.y) else component.length
                DistanceJoint2D(a, b, ax, ay, b.x, b.y).also {
                    it.length = len
                    it.stiffness = component.stiffness
                    it.damping = component.damping
                    it.rope = component.rope
                }
            }
            is SpringJoint -> {
                val len = if (component.autoLength) com.sengine.engine.math.M.dist(ax, ay, b.x, b.y) else component.restLength
                SpringJoint2D(a, b, ax, ay, b.x, b.y).also {
                    it.restLength = len
                    it.frequency = component.frequency
                    it.dampingRatio = component.dampingRatio
                }
            }
            is RopeJoint -> {
                val len = if (component.autoLength) com.sengine.engine.math.M.dist(ax, ay, b.x, b.y) else component.maxLength
                DistanceJoint2D(a, b, ax, ay, b.x, b.y).also {
                    it.length = len
                    it.rope = true
                    it.stiffness = 1f
                }
            }
            is WheelJoint -> WheelJoint2D(a, b, component.axisX, component.axisY, ax, ay, b.x, b.y).also {
                it.springFrequency = component.springFrequency
                it.springDampingRatio = component.springDampingRatio
                it.enableMotor = component.enableMotor
                it.motorSpeed = component.motorSpeed
                it.maxMotorTorque = component.maxMotorTorque
                it.enableLimit = component.enableLimit
                it.lowerTranslation = component.lowerTranslation
                it.upperTranslation = component.upperTranslation
            }
            is WeldJoint -> WeldJoint2D(a, b, ax, ay)
            is PrismaticJoint -> PrismaticJoint2D(a, b, ax, ay, component.axisX, component.axisY).also {
                it.enableLimit = component.enableLimit
                it.lowerTranslation = component.lowerTranslation
                it.upperTranslation = component.upperTranslation
                it.enableMotor = component.enableMotor
                it.motorSpeed = component.motorSpeed
                it.maxMotorForce = component.maxMotorForce
            }
            is MotorJoint -> MotorJoint2D(a, b).also {
                it.targetOffsetX = component.offsetX
                it.targetOffsetY = component.offsetY
                it.targetAngle = component.targetAngle
                it.maxForce = component.maxForce
                it.maxTorque = component.maxTorque
                it.correctionFactor = component.correctionFactor
            }
            else -> null
        }
        joint?.let {
            it.breakForce = component.breakForce
            component.joint = it
            world.addJoint(it)
        }
        return joint
    }

    val jointTypes = listOf(
        "HingeJoint", "DistanceJoint", "SpringJoint", "RopeJoint",
        "WheelJoint", "WeldJoint", "PrismaticJoint", "MotorJoint"
    )
}
