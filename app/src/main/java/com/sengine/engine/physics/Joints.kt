package com.sengine.engine.physics

import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Rigidbody2D
import org.json.JSONObject
import kotlin.math.*

/**
 * Constraint-based 2D joint system.
 * Joints connect two bodies (or one body to a world anchor) and apply
 * positional corrections during the physics step.
 */
sealed class Joint {
    /** First body (or null if anchored to world) */
    var bodyA: GameObject? = null
    /** Second body (or null if anchored to world) */
    var bodyB: GameObject? = null
    /** Anchor offset on body A in local space */
    var anchorAx = 0f
    var anchorAy = 0f
    /** Anchor offset on body B in local space */
    var anchorBx = 0f
    var anchorBy = 0f
    /** If true, breaking force can destroy this joint */
    var breakable = false
    var breakForce = Float.MAX_VALUE
    var enabled = true
    var id = 0L

    /** Apply the constraint for one physics step. */
    abstract fun solve(world: PhysicsWorld, dt: Float)

    abstract fun toJson(): JSONObject

    companion object {
        fun fromJson(o: JSONObject): Joint? {
            val type = o.optString("jointType", "")
            val j: Joint = when (type) {
                "Distance" -> DistanceJoint().also { it.fromJson(o) }
                "Hinge" -> HingeJoint().also { it.fromJson(o) }
                "Spring" -> SpringJoint().also { it.fromJson(o) }
                "Wheel" -> WheelJoint().also { it.fromJson(o) }
                "Rope" -> RopeJoint().also { it.fromJson(o) }
                else -> return null
            }
            return j
        }
    }
}

fun Joint.anchorAWorld(): Pair<Float, Float> {
    val go = bodyA ?: return anchorAx to anchorAy
    val w = go.computeWorld()
    return w.mapX(anchorAx, anchorAy) to w.mapY(anchorAx, anchorAy)
}

fun Joint.anchorBWorld(): Pair<Float, Float> {
    val go = bodyB ?: return anchorBx to anchorBy
    val w = go.computeWorld()
    return w.mapX(anchorBx, anchorBy) to w.mapY(anchorBx, anchorBy)
}

fun Joint.worldAnchor(ax: Float, ay: Float): Pair<Float, Float> {
    val go = bodyA ?: return ax to ay
    val w = go.computeWorld()
    return w.mapX(ax, ay) to w.mapY(ax, ay)
}

private fun applyCorrection(go: GameObject?, dx: Float, dy: Float, factor: Float) {
    if (go == null || factor == 0f) return
    val rb = go.components.filterIsInstance<Rigidbody2D>().firstOrNull { it.bodyType == 0 } ?: return
    if (go.parent == null) {
        go.x += dx * factor
        go.y += dy * factor
    } else {
        val w = go.computeWorld()
        go.setWorldPosition(w.tx + dx * factor, w.ty + dy * factor)
    }
}

private fun invMass(go: GameObject?): Float {
    if (go == null) return 0f
    val rb = go.components.filterIsInstance<Rigidbody2D>().firstOrNull { it.bodyType == 0 } ?: return 0f
    return 1f / rb.mass
}

private fun velocity(go: GameObject?): Pair<Float, Float> {
    val rb = go?.components?.filterIsInstance<Rigidbody2D>()?.firstOrNull { it.bodyType == 0 } ?: return 0f to 0f
    return rb.vx to rb.vy
}

private fun addVelocity(go: GameObject?, vx: Float, vy: Float) {
    val rb = go?.components?.filterIsInstance<Rigidbody2D>()?.firstOrNull { it.bodyType == 0 } ?: return
    rb.vx += vx
    rb.vy += vy
}

private fun moveWorld(go: GameObject?, dx: Float, dy: Float) {
    if (go == null) return
    if (go.parent == null) {
        go.x += dx; go.y += dy
    } else {
        val w = go.computeWorld()
        go.setWorldPosition(w.tx + dx, w.ty + dy)
    }
}

/**
 * Distance Joint: maintains a fixed distance between two anchor points.
 * Used for chains, ropes, pendulums.
 */
class DistanceJoint : Joint() {
    var distance = 1f
    var stiffness = 0f // 0 = rigid, >0 = soft spring
    var damping = 0.5f

    override fun solve(world: PhysicsWorld, dt: Float) {
        if (!enabled) return
        val (axW, ayW) = anchorAWorld()
        val (bxW, byW) = anchorBWorld()
        val dx = bxW - axW
        val dy = byW - ayW
        val currentDist = sqrt(dx * dx + dy * dy)
        if (currentDist < 1e-6f) return

        val diff = currentDist - distance
        if (breakable && abs(diff) * stiffness > breakForce) { enabled = false; return }

        val ima = invMass(bodyA)
        val imb = invMass(bodyB)
        val total = ima + imb
        if (total == 0f) return

        val nx = dx / currentDist
        val ny = dy / currentDist

        if (stiffness <= 0f) {
            // Rigid constraint: positional correction
            val correction = diff / total
            moveWorld(bodyA, nx * correction * ima, ny * correction * ima)
            moveWorld(bodyB, -nx * correction * imb, -ny * correction * imb)

            // Velocity correction
            val va = velocity(bodyA)
            val vb = velocity(bodyB)
            val relVx = vb.first - va.first
            val relVy = vb.second - va.second
            val vn = relVx * nx + relVy * ny
            val j = vn / total
            addVelocity(bodyA, nx * j * ima, ny * j * ima)
            addVelocity(bodyB, -nx * j * imb, -ny * j * imb)
        } else {
            // Soft spring
            val force = -stiffness * diff
            val dampForce = -damping * ((velocity(bodyB).first - velocity(bodyA).first) * nx +
                    (velocity(bodyB).second - velocity(bodyA).second) * ny)
            val totalForce = (force + dampForce) * dt
            addVelocity(bodyA, -nx * totalForce * ima, -ny * totalForce * ima)
            addVelocity(bodyB, nx * totalForce * imb, ny * totalForce * imb)
        }
    }

    fun fromJson(o: JSONObject) {
        distance = o.optDouble("distance", 1.0).toFloat()
        stiffness = o.optDouble("stiffness", 0.0).toFloat()
        damping = o.optDouble("damping", 0.5).toFloat()
    }

    override fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("jointType", "Distance")
        o.put("distance", distance.toDouble())
        o.put("stiffness", stiffness.toDouble())
        o.put("damping", damping.toDouble())
        return o
    }
}

/**
 * Hinge Joint: allows rotation around an anchor point.
 * Used for doors, swinging objects, pendulums.
 */
class HingeJoint : Joint() {
    var enableLimit = false
    var lowerAngle = -45f // degrees
    var upperAngle = 45f  // degrees
    var enableMotor = false
    var motorSpeed = 0f // degrees/sec
    var maxMotorTorque = 10f

    // runtime
    var currentAngle = 0f

    override fun solve(world: PhysicsWorld, dt: Float) {
        if (!enabled) return
        val (axW, ayW) = anchorAWorld()
        val (bxW, byW) = anchorBWorld()

        // Positional correction: keep anchors aligned
        val dx = bxW - axW
        val dy = byW - ayW
        val ima = invMass(bodyA)
        val imb = invMass(bodyB)
        val total = ima + imb
        if (total == 0f) return

        moveWorld(bodyA, dx * (ima / total) * 0.8f, dy * (ima / total) * 0.8f)
        moveWorld(bodyB, -dx * (imb / total) * 0.8f, -dy * (imb / total) * 0.8f)

        // Angle limits
        if (enableLimit) {
            val angA = bodyA?.computeWorld()?.rotationDeg ?: 0f
            val angB = bodyB?.computeWorld()?.rotationDeg ?: 0f
            currentAngle = angB - angA
            // Normalize angle
            var rel = currentAngle
            while (rel > 180f) rel -= 360f
            while (rel < -180f) rel += 360f
            currentAngle = rel

            if (rel < lowerAngle) {
                val correction = (lowerAngle - rel) * 0.2f
                applyAngularCorrection(bodyA, -correction * ima / total)
                applyAngularCorrection(bodyB, correction * imb / total)
            } else if (rel > upperAngle) {
                val correction = (upperAngle - rel) * 0.2f
                applyAngularCorrection(bodyA, -correction * ima / total)
                applyAngularCorrection(bodyB, correction * imb / total)
            }
        }

        // Motor
        if (enableMotor) {
            val targetRad = Math.toRadians(motorSpeed.toDouble()).toFloat() * dt
            applyAngularCorrection(bodyA, -targetRad * 0.5f)
            applyAngularCorrection(bodyB, targetRad * 0.5f)
        }
    }

    private fun applyAngularCorrection(go: GameObject?, angleDeg: Float) {
        if (go == null) return
        go.rotation += angleDeg
    }

    fun fromJson(o: JSONObject) {
        enableLimit = o.optBoolean("enableLimit", false)
        lowerAngle = o.optDouble("lowerAngle", -45.0).toFloat()
        upperAngle = o.optDouble("upperAngle", 45.0).toFloat()
        enableMotor = o.optBoolean("enableMotor", false)
        motorSpeed = o.optDouble("motorSpeed", 0.0).toFloat()
        maxMotorTorque = o.optDouble("maxMotorTorque", 10.0).toFloat()
    }

    override fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("jointType", "Hinge")
        o.put("enableLimit", enableLimit)
        o.put("lowerAngle", lowerAngle.toDouble())
        o.put("upperAngle", upperAngle.toDouble())
        o.put("enableMotor", enableMotor)
        o.put("motorSpeed", motorSpeed.toDouble())
        o.put("maxMotorTorque", maxMotorTorque.toDouble())
        return o
    }
}

/**
 * Spring Joint: connects two points with a spring force.
 * Configurable stiffness and damping.
 */
class SpringJoint : Joint() {
    var restLength = 1f
    var stiffness = 50f // N/m
    var damping = 2f
    var minLength = 0f
    var maxLength = Float.MAX_VALUE

    override fun solve(world: PhysicsWorld, dt: Float) {
        if (!enabled) return
        val (axW, ayW) = anchorAWorld()
        val (bxW, byW) = anchorBWorld()
        val dx = bxW - axW
        val dy = byW - ayW
        val dist = sqrt(dx * dx + dy * dy)
        if (dist < 1e-6f) return

        val clampedDist = dist.coerceIn(minLength.coerceAtLeast(0.001f), maxLength)
        val stretch = clampedDist - restLength

        val nx = dx / dist
        val ny = dy / dist

        val va = velocity(bodyA)
        val vb = velocity(bodyB)
        val relVn = (vb.first - va.first) * nx + (vb.second - va.second) * ny

        val springForce = -stiffness * stretch
        val dampForce = -damping * relVn
        val totalForce = (springForce + dampForce) * dt

        val ima = invMass(bodyA)
        val imb = invMass(bodyB)

        addVelocity(bodyA, -nx * totalForce * ima, -ny * totalForce * ima)
        addVelocity(bodyB, nx * totalForce * imb, ny * totalForce * imb)

        if (breakable && abs(stretch) * stiffness > breakForce) { enabled = false }
    }

    fun fromJson(o: JSONObject) {
        restLength = o.optDouble("restLength", 1.0).toFloat()
        stiffness = o.optDouble("stiffness", 50.0).toFloat()
        damping = o.optDouble("damping", 2.0).toFloat()
        minLength = o.optDouble("minLength", 0.0).toFloat()
        maxLength = o.optDouble("maxLength", Float.MAX_VALUE.toDouble()).toFloat()
    }

    override fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("jointType", "Spring")
        o.put("restLength", restLength.toDouble())
        o.put("stiffness", stiffness.toDouble())
        o.put("damping", damping.toDouble())
        o.put("minLength", minLength.toDouble())
        o.put("maxLength", maxLength.toDouble())
        return o
    }
}

/**
 * Wheel Joint: allows a body to rotate freely while constraining its position
 * to a line (suspension axis). Used for vehicle wheels.
 */
class WheelJoint : Joint() {
    var suspensionStiffness = 100f
    var suspensionDamping = 5f
    var suspensionRestLength = 1f
    var maxSuspensionTravel = 0.5f
    var enableMotor = false
    var motorTorque = 0f
    var wheelRadius = 0.5f

    // Suspension direction in local space of body A (default: down)
    var suspensionDirX = 0f
    var suspensionDirY = -1f

    override fun solve(world: PhysicsWorld, dt: Float) {
        if (!enabled) return
        val (axW, ayW) = anchorAWorld()
        val (bxW, byW) = anchorBWorld()

        // Get suspension direction in world space
        val sdx = suspensionDirX
        val sdy = suspensionDirY
        val sLen = sqrt(sdx * sdx + sdy * sdy)
        if (sLen < 1e-6f) return
        val snx = sdx / sLen
        val sny = sdy / sLen

        // Project wheel position onto suspension axis
        val dx = bxW - axW
        val dy = byW - ayW
        val proj = dx * snx + dy * sny
        val compression = proj - suspensionRestLength

        // Clamp travel
        val clampedComp = compression.coerceIn(-maxSuspensionTravel, maxSuspensionTravel)

        // Spring force along suspension axis
        val va = velocity(bodyA)
        val vb = velocity(bodyB)
        val relVn = (vb.first - va.first) * snx + (vb.second - va.second) * sny

        val springForce = -suspensionStiffness * clampedComp
        val dampForce = -suspensionDamping * relVn
        val totalForce = (springForce + dampForce) * dt

        val ima = invMass(bodyA)
        val imb = invMass(bodyB)
        addVelocity(bodyA, -snx * totalForce * ima, -sny * totalForce * ima)
        addVelocity(bodyB, snx * totalForce * imb, sny * totalForce * imb)

        // Lateral constraint: remove motion perpendicular to suspension
        val lnx = -sny
        val lny = snx
        val lateralV = (vb.first - va.first) * lnx + (vb.second - va.second) * lny
        val latJ = lateralV / (ima + imb)
        addVelocity(bodyA, lnx * latJ * ima, lny * latJ * ima)
        addVelocity(bodyB, -lnx * latJ * imb, -lny * latJ * imb)

        // Motor torque
        if (enableMotor && bodyB != null) {
            val rb = bodyB!!.components.filterIsInstance<Rigidbody2D>().firstOrNull { it.bodyType == 0 }
            if (rb != null) {
                rb.vx += motorTorque * dt * lnx * -1f
                rb.vy += motorTorque * dt * lny * -1f
            }
        }
    }

    fun fromJson(o: JSONObject) {
        suspensionStiffness = o.optDouble("suspensionStiffness", 100.0).toFloat()
        suspensionDamping = o.optDouble("suspensionDamping", 5.0).toFloat()
        suspensionRestLength = o.optDouble("suspensionRestLength", 1.0).toFloat()
        maxSuspensionTravel = o.optDouble("maxSuspensionTravel", 0.5).toFloat()
        enableMotor = o.optBoolean("enableMotor", false)
        motorTorque = o.optDouble("motorTorque", 0.0).toFloat()
        wheelRadius = o.optDouble("wheelRadius", 0.5).toFloat()
    }

    override fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("jointType", "Wheel")
        o.put("suspensionStiffness", suspensionStiffness.toDouble())
        o.put("suspensionDamping", suspensionDamping.toDouble())
        o.put("suspensionRestLength", suspensionRestLength.toDouble())
        o.put("maxSuspensionTravel", maxSuspensionTravel.toDouble())
        o.put("enableMotor", enableMotor)
        o.put("motorTorque", motorTorque.toDouble())
        o.put("wheelRadius", wheelRadius.toDouble())
        return o
    }
}

/**
 * Rope Joint: constrains maximum distance between two points.
 * Bodies can come closer but not farther apart.
 */
class RopeJoint : Joint() {
    var maxLength = 2f
    var slack = 0f // Additional slack before constraint kicks in

    override fun solve(world: PhysicsWorld, dt: Float) {
        if (!enabled) return
        val (axW, ayW) = anchorAWorld()
        val (bxW, byW) = anchorBWorld()
        val dx = bxW - axW
        val dy = byW - ayW
        val dist = sqrt(dx * dx + dy * dy)
        val limit = maxLength + slack

        if (dist <= limit) return

        val nx = dx / dist
        val ny = dy / dist
        val excess = dist - limit

        val ima = invMass(bodyA)
        val imb = invMass(bodyB)
        val total = ima + imb
        if (total == 0f) return

        // Positional correction
        moveWorld(bodyA, nx * excess * (ima / total) * 0.8f, ny * excess * (ima / total) * 0.8f)
        moveWorld(bodyB, -nx * excess * (imb / total) * 0.8f, -ny * excess * (imb / total) * 0.8f)

        // Velocity correction (only remove separating velocity)
        val va = velocity(bodyA)
        val vb = velocity(bodyB)
        val relVn = (vb.first - va.first) * nx + (vb.second - va.second) * ny
        if (relVn > 0f) return // approaching, don't constrain

        val j = relVn / total
        addVelocity(bodyA, nx * j * ima, ny * j * ima)
        addVelocity(bodyB, -nx * j * imb, -ny * j * imb)

        if (breakable && excess * 100f > breakForce) { enabled = false }
    }

    fun fromJson(o: JSONObject) {
        maxLength = o.optDouble("maxLength", 2.0).toFloat()
        slack = o.optDouble("slack", 0.0).toFloat()
    }

    override fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("jointType", "Rope")
        o.put("maxLength", maxLength.toDouble())
        o.put("slack", slack.toDouble())
        return o
    }
}
