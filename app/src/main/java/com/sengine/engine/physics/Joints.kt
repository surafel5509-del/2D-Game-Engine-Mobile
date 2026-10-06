package com.sengine.engine.physics

import com.sengine.engine.math.M
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 2D joints. Every joint connects two [Body2D]s and is solved with sequential impulses.
 * S Engine ships hinge (revolute), distance, spring, rope, wheel (with suspension), weld,
 * prismatic and motor joints - enough for vehicles, ragdolls, ropes, bridges and machinery.
 */
abstract class Joint2D(val a: Body2D, val b: Body2D) {
    var enabled = true

    /** When the reaction force exceeds this the joint breaks (0 = unbreakable). */
    var breakForce = 0f
    var collideConnected = false

    /** Last reaction force (N); used for breaking, telemetry and the debug overlay. */
    var reactionForce = 0f; protected set

    abstract fun solve(dt: Float)

    /** World-space anchor (for the editor gizmos). */
    open fun anchorWorld(): FloatArray = floatArrayOf(a.x, a.y)

    protected fun wake() { a.wake(); b.wake() }

    protected fun checkBreak() {
        if (breakForce > 0f && reactionForce > breakForce) enabled = false
    }

    /** Human readable state for the debug overlay / inspector. */
    open fun debugInfo(): String = "reaction=${"%.1f".format(reactionForce)}N"
}

/**
 * Bounded position correction along a direction: moves [a] by `+dir * error / invMassSum * invMassA`
 * and [b] by the opposite amount so the relative error shrinks. Position projection changes no
 * velocity, so it can never feed energy back into the simulation - this is what keeps joints tight
 * without the energy pump of a velocity-level Baumgarte bias.
 */
internal fun projectAlong(a: Body2D, b: Body2D, dirX: Float, dirY: Float, error: Float, maxCorrection: Float) {
    val invMassSum = a.invMass + b.invMass
    if (invMassSum <= 1e-9f || error == 0f || !error.isFinite()) return
    val amount = error.coerceIn(-maxCorrection, maxCorrection) / invMassSum
    a.x += dirX * amount * a.invMass
    a.y += dirY * amount * a.invMass
    b.x -= dirX * amount * b.invMass
    b.y -= dirY * amount * b.invMass
    a.updateAabb(); b.updateAabb()
}

/** Shared 2x2 point-to-point constraint maths used by hinge, weld, prismatic and motor joints. */
internal class PointConstraint {
    var rAx = 0f; var rAy = 0f
    var rBx = 0f; var rBy = 0f
    var impulseX = 0f; var impulseY = 0f

    private var m00 = 0f; private var m01 = 0f; private var m11 = 0f
    private var accumulatedBiasX = 0f; private var accumulatedBiasY = 0f
    private var softGamma = 0f

    fun prepare(a: Body2D, b: Body2D, anchorX: Float, anchorY: Float, softness: Float = 0f, dt: Float = 1f / 60f) {
        rAx = anchorX - a.x; rAy = anchorY - a.y
        rBx = anchorX - b.x; rBy = anchorY - b.y
        val imA = a.invMass; val imB = b.invMass
        val iiA = a.invInertia; val iiB = b.invInertia
        var k11 = imA + imB + iiA * rAy * rAy + iiB * rBy * rBy
        val k12 = -iiA * rAx * rAy - iiB * rBx * rBy
        var k22 = imA + imB + iiA * rAx * rAx + iiB * rBx * rBx
        softGamma = 0f
        if (softness > 0f) {
            // soft (spring) constraint: add gamma to the diagonal
            val k = softness
            softGamma = 1f / (dt * (k + dt * k * k))
            k11 += softGamma
            k22 += softGamma
        }
        val det = k11 * k22 - k12 * k12
        if (abs(det) < 1e-12f) {
            m00 = 0f; m01 = 0f; m11 = 0f
        } else {
            val inv = 1f / det
            m00 = k22 * inv
            m01 = -k12 * inv
            m11 = k11 * inv
        }
    }

    /**
     * Split-impulse position correction: removes [beta] of the anchor error (errX, errY) per step by
     * moving the bodies, without touching any velocity. A velocity-level Baumgarte bias is only safe
     * when a position solver also removes the error - otherwise the bias turns a persistent error
     * into a constant "conveyor" velocity that never decays (it used to make vehicles creep).
     */
    fun solvePosition(a: Body2D, b: Body2D, errX: Float, errY: Float, beta: Float, maxCorrection: Float = 0.02f) {
        val invMassSum = a.invMass + b.invMass
        if (invMassSum <= 1e-9f) return
        var cx = errX * beta / invMassSum
        var cy = errY * beta / invMassSum
        val mag = sqrt(cx * cx + cy * cy)
        if (mag > maxCorrection) {
            val sc = maxCorrection / mag
            cx *= sc; cy *= sc
        }
        a.x += cx * a.invMass
        a.y += cy * a.invMass
        b.x -= cx * b.invMass
        b.y -= cy * b.invMass
        a.updateAabb(); b.updateAabb()
    }


    /** Solves and applies the impulse (accumulated across iterations for stability). */
    fun solveVelocity(a: Body2D, b: Body2D) {
        val vax = a.vx - a.angularVelocity * rAy
        val vay = a.vy + a.angularVelocity * rAx
        val vbx = b.vx - b.angularVelocity * rBy
        val vby = b.vy + b.angularVelocity * rBx
        val cx = vbx - vax + softGamma * impulseX
        val cy = vby - vay + softGamma * impulseY
        val px = -(m00 * cx + m01 * cy)
        val py = -(m01 * cx + m11 * cy)
        impulseX += px; impulseY += py
        applyImpulse(a, b, px, py)
    }

    /** Computes the impulse needed to remove the relative velocity [cx],[cy], clamped to [maxImpulse]. */
    fun solveClamped(a: Body2D, b: Body2D, cx: Float, cy: Float, maxImpulse: Float, positionImpulse: Boolean = false) {
        var px = -(m00 * cx + m01 * cy)
        var py = -(m01 * cx + m11 * cy)
        val mag = sqrt(px * px + py * py)
        if (maxImpulse > 0f && mag > maxImpulse && mag > 1e-6f) {
            val s = maxImpulse / mag
            px *= s; py *= s
        }
        if (positionImpulse) {
            accumulatedBiasX += px; accumulatedBiasY += py
        }
        applyImpulse(a, b, px, py)
    }

    fun applyImpulse(a: Body2D, b: Body2D, px: Float, py: Float) {
        a.vx -= px * a.invMass
        a.vy -= py * a.invMass
        a.angularVelocity -= a.invInertia * (rAx * py - rAy * px)
        b.vx += px * b.invMass
        b.vy += py * b.invMass
        b.angularVelocity += b.invInertia * (rBx * py - rBy * px)
    }

    val impulseMagnitude get() = sqrt(impulseX * impulseX + impulseY * impulseY)
}

/**
 * Hinge / revolute joint: pins two bodies together at an anchor while letting them rotate.
 * Supports a motor and angular limits - the backbone of wheels, doors and ragdolls.
 */
class HingeJoint2D(
    a: Body2D,
    b: Body2D,
    anchorX: Float,
    anchorY: Float
) : Joint2D(a, b) {
    private val point = PointConstraint()

    var localAx: Float; var localAy: Float
    var localBx: Float; var localBy: Float

    var anchorX: Float; var anchorY: Float

    var enableMotor = false
    var motorSpeed = 0f
    var maxMotorTorque = 10f
    var enableLimit = false
    var lowerAngle = -180f
    var upperAngle = 180f
    var referenceAngle = 0f

    private var motorImpulse = 0f

    init {
        val la = a.pointToLocal(anchorX, anchorY)
        val lb = b.pointToLocal(anchorX, anchorY)
        localAx = la[0]; localAy = la[1]
        localBx = lb[0]; localBy = lb[1]
        this.anchorX = anchorX; this.anchorY = anchorY
        referenceAngle = b.angle - a.angle
    }

    override fun anchorWorld() = floatArrayOf(anchorX, anchorY)

    fun setAnchorWorld(wx: Float, wy: Float) {
        val la = a.pointToLocal(wx, wy)
        val lb = b.pointToLocal(wx, wy)
        localAx = la[0]; localAy = la[1]
        localBx = lb[0]; localBy = lb[1]
        wake()
    }

    override fun solve(dt: Float) {
        reactionForce = 0f
        if (!enabled) return
        val pa = a.pointToWorld(localAx, localAy)
        val pb = b.pointToWorld(localBx, localBy)
        anchorX = pa[0]; anchorY = pa[1]
        point.prepare(a, b, pa[0], pa[1], softness = 0.002f, dt = dt)
        point.solveVelocity(a, b)
        point.solvePosition(a, b, pb[0] - pa[0], pb[1] - pa[1], 0.25f)

        val invI = a.invInertia + b.invInertia
        if (invI > 1e-9f) {
            val relAngle = b.angle - a.angle
            val angle = Math.toDegrees(M.wrapAngle(relAngle - referenceAngle).toDouble()).toFloat()
            if (enableLimit) {
                if (angle <= lowerAngle) {
                    val c = Math.toRadians((angle - lowerAngle).toDouble()).toFloat()
                    val lambda = -((b.angularVelocity - a.angularVelocity) + c * 0.4f / dt) / invI
                    val clamped = max(lambda, 0f)
                    b.angularVelocity += clamped * b.invInertia
                    a.angularVelocity -= clamped * a.invInertia
                } else if (angle >= upperAngle) {
                    val c = Math.toRadians((angle - upperAngle).toDouble()).toFloat()
                    val lambda = -((b.angularVelocity - a.angularVelocity) + c * 0.4f / dt) / invI
                    val clamped = min(lambda, 0f)
                    b.angularVelocity += clamped * b.invInertia
                    a.angularVelocity -= clamped * a.invInertia
                }
            }
            if (enableMotor) {
                val cdot = b.angularVelocity - a.angularVelocity - Math.toRadians(motorSpeed.toDouble()).toFloat()
                val maxImpulse = maxMotorTorque * dt
                val lambda = (-cdot / invI).coerceIn(-maxImpulse, maxImpulse)
                val applied = M.clamp(motorImpulse + lambda, -maxImpulse, maxImpulse) - motorImpulse
                motorImpulse += applied
                b.angularVelocity += applied * b.invInertia
                a.angularVelocity -= applied * a.invInertia
            }
        }
        reactionForce = max(point.impulseMagnitude / max(dt, 1e-5f), abs(motorImpulse) / max(dt, 1e-5f))
        checkBreak()
    }
}

/**
 * Distance joint. `stiffness = 1` is a rigid rod, lower values give a spring, and
 * [rope] = true makes it pull-only (ropes, chains, grappling hooks).
 */
class DistanceJoint2D(
    a: Body2D,
    b: Body2D,
    anchorAX: Float,
    anchorAY: Float,
    anchorBX: Float,
    anchorBY: Float
) : Joint2D(a, b) {
    var localAx: Float; var localAy: Float
    var localBx: Float; var localBy: Float
    var length: Float = M.dist(anchorAX, anchorAY, anchorBX, anchorBY)
    /** 0 = fully soft (spring), 1 = rigid. */
    var stiffness = 1f
    /** Spring damping 0..1. */
    var damping = 0.2f
    /** Pull-only behaviour (rope/chain). */
    var rope = false
    var minLength = 0f

    private var impulse = 0f

    init {
        val la = a.pointToLocal(anchorAX, anchorAY)
        val lb = b.pointToLocal(anchorBX, anchorBY)
        localAx = la[0]; localAy = la[1]
        localBx = lb[0]; localBy = lb[1]
    }

    override fun anchorWorld(): FloatArray {
        val pa = a.pointToWorld(localAx, localAy)
        val pb = b.pointToWorld(localBx, localBy)
        return floatArrayOf((pa[0] + pb[0]) * 0.5f, (pa[1] + pb[1]) * 0.5f)
    }

    /** Current length, used by the tilemap/rope visualisers and telemetry. */
    fun currentLength(): Float {
        val pa = a.pointToWorld(localAx, localAy)
        val pb = b.pointToWorld(localBx, localBy)
        return M.dist(pa[0], pa[1], pb[0], pb[1])
    }

    override fun solve(dt: Float) {
        reactionForce = 0f
        if (!enabled) return
        val pa = a.pointToWorld(localAx, localAy)
        val pb = b.pointToWorld(localBx, localBy)
        var dx = pb[0] - pa[0]
        var dy = pb[1] - pa[1]
        var d = sqrt(dx * dx + dy * dy)
        if (d < 1e-5f) { dx = 0f; dy = 1f; d = 1e-5f }
        val nx = dx / d; val ny = dy / d
        val rAx = pa[0] - a.x; val rAy = pa[1] - a.y
        val rBx = pb[0] - b.x; val rBy = pb[1] - b.y
        val k = a.invMass + b.invMass +
            a.invInertia * (rAx * ny - rAy * nx) * (rAx * ny - rAy * nx) +
            b.invInertia * (rBx * ny - rBy * nx) * (rBx * ny - rBy * nx)
        if (k < 1e-9f) return
        val effectiveMass = 1f / k
        var c = d - length
        if (rope && c < 0f) c = 0f
        if (minLength > 0f && d < minLength) c = d - minLength
        val vax = a.vx - a.angularVelocity * rAy
        val vay = a.vy + a.angularVelocity * rAx
        val vbx = b.vx - b.angularVelocity * rBy
        val vby = b.vy + b.angularVelocity * rBx
        val cdot = (vbx - vax) * nx + (vby - vay) * ny
        val soft = stiffness < 0.999f
        var lambda = -effectiveMass * (cdot + c * 0.4f / dt)
        if (soft) lambda /= (1f + damping * 8f * dt)
        lambda *= stiffness.coerceIn(0.02f, 1f)
        val old = impulse
        impulse += lambda
        if (rope && impulse < 0f) impulse = 0f
        val applied = impulse - old
        val px = nx * applied; val py = ny * applied
        a.vx -= px * a.invMass; a.vy -= py * a.invMass
        a.angularVelocity -= a.invInertia * (rAx * py - rAy * px)
        b.vx += px * b.invMass; b.vy += py * b.invMass
        b.angularVelocity += b.invInertia * (rBx * py - rBy * px)
        reactionForce = abs(impulse) / max(dt, 1e-5f)
        checkBreak()
    }
}

/** Spring joint: a distance joint parameterised by frequency and damping ratio (real springs). */
class SpringJoint2D(
    a: Body2D, b: Body2D,
    anchorAX: Float, anchorAY: Float,
    anchorBX: Float, anchorBY: Float
) : Joint2D(a, b) {
    /** Frequency in Hz. */
    var frequency = 3f
    /** Damping ratio (0 = bouncy, 1 = critically damped). */
    var dampingRatio = 0.4f
    var restLength = M.dist(anchorAX, anchorAY, anchorBX, anchorBY)

    private val inner = DistanceJoint2D(a, b, anchorAX, anchorAY, anchorBX, anchorBY)

    init {
        inner.rope = false
    }

    override fun anchorWorld() = inner.anchorWorld()
    fun currentLength() = inner.currentLength()

    override fun solve(dt: Float) {
        if (!enabled) return
        // map frequency/damping to the distance joint's stiffness/damping
        val omega = M.TAU * frequency
        val stiffness = (omega * dt).coerceIn(0.02f, 1f)
        inner.stiffness = stiffness
        inner.damping = dampingRatio.coerceIn(0f, 2f)
        inner.length = restLength
        inner.solve(dt)
        reactionForce = inner.reactionForce
        checkBreak()
    }
}

/** Wheel joint: 2D wheel with suspension along an axis plus an optional drive motor. */
/**
 * Wheel joint: a motorised wheel attached to the chassis on a suspension axis (the backbone of the
 * vehicle system). The suspension is an *explicit* spring-damper (force = k*x - c*v, clamped to a
 * maximum) instead of an implicit soft constraint, which is unconditionally stable at any frame
 * rate and is what makes Hill-Climb-style terrain driving feel right. The lateral axis is a hard
 * constraint, and the drive motor is torque limited.
 */
class WheelJoint2D(
    a: Body2D,
    b: Body2D,
    axisX: Float = 0f,
    axisY: Float = 1f,
    anchorAX: Float = 0f,
    anchorAY: Float = 0f,
    anchorBX: Float = 0f,
    anchorBY: Float = 0f
) : Joint2D(a, b) {
    var localAx: Float; var localAy: Float
    var localBx: Float; var localBy: Float

    /** Suspension axis in body A's local space (usually (0,1) = up). */
    var axisX: Float = axisX
    var axisY: Float = axisY

    /**
     * Suspension spring frequency in Hz. The static sag is `gravity / (2*pi*f)^2`, so with the
     * default gravity of 30 units/s^2 a 4.5 Hz spring sags ~0.037 units - inside the travel limits
     * of a normal wheel. A softer spring bottoms out on its limits and the car rides on the hard
     * stop instead of its suspension.
     */
    var springFrequency = 4.5f
    var springDampingRatio = 0.65f

    /** Travel limits relative to the rest position; 0,0 disables them. */
    var lowerTranslation = 0f
    var upperTranslation = 0f
    var enableLimit = false

    var enableMotor = false
    /** Target wheel angular velocity in degrees/s. */
    var motorSpeed = 0f
    var maxMotorTorque = 20f

    /** Suspension rest travel (0 = the wheel hangs where it was built). */
    var restTranslation = 0f

    /** Current suspension travel (negative = compressed). */
    var translation = 0f; private set
    /** Suspension force from the last step (N). */
    var suspensionForce = 0f; private set
    /** Wheel slip in m/s (surface speed vs. body speed) - drives traction and effects. */
    var slipSpeed = 0f; private set
    /** Sideways (non-suspension) offset between the wheel and the chassis; debug/inspector value. */
    var lateralError = 0f; private set
    /** Sideways impulse applied in the last step (N.s); zero means the constraint is satisfied. */
    var lateralImpulse = 0f; private set
    /** Relative speed along the suspension axis from the last step (m/s). */
    var axialVelocity = 0f; private set

    private var perpImpulse = 0f
    private var motorImpulse = 0f
    private var springForce = 0f
    private var dampForce = 0f

    /**
     * Upper bound for how fast the suspension may compress or extend per step (world units/s).
     * The spring is solved in velocity space against the axis effective mass, so this is the only
     * clamp needed and a light wheel can never be launched by a force sized for the whole car.
     */
    var maxSuspensionSpeed = 4f

    init {
        val la = a.pointToLocal(anchorAX, anchorAY)
        val lb = b.pointToLocal(anchorBX, anchorBY)
        localAx = la[0]; localAy = la[1]
        localBx = lb[0]; localBy = lb[1]
    }

    override fun anchorWorld(): FloatArray = a.pointToWorld(localAx, localAy)

    override fun debugInfo() =
        "travel=${"%.3f".format(translation)} force=${"%.0f".format(suspensionForce)}N slip=${"%.2f".format(slipSpeed)}"

    override fun solve(dt: Float) {
        reactionForce = 0f
        suspensionForce = 0f
        if (!enabled || dt <= 0f) return
        val pa = a.pointToWorld(localAx, localAy)
        val pb = b.pointToWorld(localBx, localBy)
        val ax = axisX * a.cosA - axisY * a.sinA
        val ay = axisX * a.sinA + axisY * a.cosA
        val perpX = -ay
        val perpY = ax

        val rAx = pa[0] - a.x; val rAy = pa[1] - a.y
        val rBx = pb[0] - b.x; val rBy = pb[1] - b.y
        val dx = pb[0] - pa[0]
        val dy = pb[1] - pa[1]
        translation = dx * ax + dy * ay

        val vax = a.vx - a.angularVelocity * rAy
        val vay = a.vy + a.angularVelocity * rAx
        val vbx = b.vx - b.angularVelocity * rBy
        val vby = b.vy + b.angularVelocity * rBx
        val relAxial = (vbx - vax) * ax + (vby - vay) * ay

        // ---- lateral constraint: the wheel may only slide on its suspension axis
        val kPerp = a.invMass + b.invMass +
            a.invInertia * (rAx * perpY - rAy * perpX) * (rAx * perpY - rAy * perpX) +
            b.invInertia * (rBx * perpY - rBy * perpX) * (rBx * perpY - rBy * perpX)
        val cPerp = dx * perpX + dy * perpY
        lateralError = cPerp
        axialVelocity = if (relAxial.isFinite()) relAxial else 0f
        if (kPerp > 1e-9f) {
            val massPerp = 1f / kPerp
            val cdotP = (vbx - vax) * perpX + (vby - vay) * perpY
            // Pure velocity constraint (no bias): momentum is conserved exactly and the wheel can
            // never be pushed sideways by a stale position error. The drift that remains is removed
            // by the bounded position projection below.
            val lambda = -massPerp * cdotP
            perpImpulse += lambda
            lateralImpulse = lambda
            applyAxis(a, b, rAx, rAy, rBx, rBy, perpX, perpY, lambda)
            // +cPerp is the signed error along perp: passing it as the "error to remove" shrinks it.
            // Projection changes no velocity, so it cannot feed energy back; the bound keeps a wheel
            // that is being dragged sideways from snapping so hard that it tears off the chassis.
            projectAlong(a, b, perpX, perpY, cPerp * 0.4f, 0.08f)
        }

        // ---- suspension: damped spring solved in velocity space (semi-implicit)
        // travel is the current deviation from the rest position (negative = compressed). The
        // acceleration of the spring (a = -w^2 x - 2*zeta*w v) is integrated for one step into a
        // *target* relative axial velocity, and the impulse is computed from the axis effective
        // mass. Because the target velocity is bounded, the suspension can never inject more than
        // maxSuspensionSpeed into the wheel per step - an explicit force sized for the chassis mass
        // used to fling the (much lighter) wheel at 100 m/s and destroy every vehicle.
        val kAxis = a.invMass + b.invMass +
            a.invInertia * (rAx * ay - rAy * ax) * (rAx * ay - rAy * ax) +
            b.invInertia * (rBx * ay - rBy * ax) * (rBx * ay - rBy * ax)
        var axialVel = relAxial
        if (!axialVel.isFinite()) axialVel = 0f
        // Clamp (never discard) the travel: zeroing it in the old version silently disabled the
        // spring once a wheel was pushed out of range, so the wheel fell away for ever.
        var travel = (translation - restTranslation)
        if (!travel.isFinite()) travel = 0f
        travel = travel.coerceIn(-4f, 4f)
        if (kAxis > 1e-9f) {
            val massAxis = 1f / kAxis
            val omega = M.TAU * springFrequency.coerceIn(0.1f, 30f)
            var accel = -(omega * omega) * travel - 2f * springDampingRatio * omega * axialVel
            if (!accel.isFinite()) accel = 0f
            val targetVel = (axialVel + accel * dt).coerceIn(-maxSuspensionSpeed, maxSuspensionSpeed)
            val lambda = massAxis * (targetVel - axialVel)
            springForce = (omega * omega) * travel * massAxis
            dampForce = 2f * springDampingRatio * omega * axialVel * massAxis
            applyAxis(a, b, rAx, rAy, rBx, rBy, ax, ay, lambda)
            suspensionForce = abs(lambda) / dt
            slipSpeed = abs(axialVel)
        }

        // ---- travel limits: velocity clamp + bounded position projection
        // Sign convention: `translation` is the wheel's offset from the anchor along the axis, so
        // hitting the UPPER limit means the translation must stop growing (impulse <= 0) and hitting
        // the LOWER limit means it must stop shrinking (impulse >= 0). Getting this backwards makes
        // the limit push the wheel *away* from the chassis, one step at a time, forever.
        if (enableLimit && upperTranslation > lowerTranslation) {
            val mass = if (kPerp > 1e-9f) 1f / (a.invMass + b.invMass) else 0f
            when {
                // too far extended: stop the axial velocity (<= 0) and pull the wheel back down
                translation > upperTranslation -> {
                    applyAxis(a, b, rAx, rAy, rBx, rBy, ax, ay, min(-mass * axialVel, 0f))
                    projectAlong(a, b, ax, ay, translation - upperTranslation, 0.02f)
                }
                // too far compressed: stop the axial velocity (>= 0) and push the wheel back up
                translation < lowerTranslation -> {
                    applyAxis(a, b, rAx, rAy, rBx, rBy, ax, ay, max(-mass * axialVel, 0f))
                    projectAlong(a, b, ax, ay, lowerTranslation - translation, 0.02f)
                }
            }
        }

        // ---- drive motor (torque limited, applied to the wheel spin relative to the chassis)
        val invI = a.invInertia + b.invInertia
        if (enableMotor && invI > 1e-9f) {
            val target = Math.toRadians(motorSpeed.toDouble()).toFloat()
            val cdot = b.angularVelocity - a.angularVelocity - target
            val maxImpulse = maxMotorTorque * dt
            val desired = M.clamp(motorImpulse - cdot / invI, -maxImpulse, maxImpulse)
            val applied = desired - motorImpulse
            motorImpulse = desired
            b.angularVelocity += applied * b.invInertia
            a.angularVelocity -= applied * a.invInertia
        }

        if (!translation.isFinite() || abs(translation) > 6f) {
            // Re-seat a wheel that got pushed out of the world instead of poisoning the simulation:
            // snap the bodies back onto the suspension axis at the rest distance.
            translation = restTranslation
            perpImpulse = 0f
            motorImpulse = 0f
            val pa2 = a.pointToWorld(localAx, localAy)
            val pb2 = b.pointToWorld(localBx, localBy)
            val err = (pb2[0] - pa2[0]) * ax + (pb2[1] - pa2[1]) * ay - restTranslation
            b.x -= ax * err
            b.y -= ay * err
            b.updateAabb()
        }
        reactionForce = max(suspensionForce, abs(perpImpulse) / max(dt, 1e-5f))
        checkBreak()
    }

    /** Clears the accumulated state (used when a joint is re-created). */
    fun resetRuntimeState() {
        perpImpulse = 0f
        motorImpulse = 0f
        translation = restTranslation
        suspensionForce = 0f
    }

    /** The wheel stopped touching the ground: no suspension force is carried. */
    fun onWheelAirborne() { suspensionForce = 0f }

    private fun applyAxis(a: Body2D, b: Body2D, rAx: Float, rAy: Float, rBx: Float, rBy: Float, ax: Float, ay: Float, lambda: Float) {
        if (lambda == 0f || !lambda.isFinite()) return
        val px = ax * lambda; val py = ay * lambda
        a.vx -= px * a.invMass; a.vy -= py * a.invMass
        a.angularVelocity -= a.invInertia * (rAx * py - rAy * px)
        b.vx += px * b.invMass; b.vy += py * b.invMass
        b.angularVelocity += b.invInertia * (rBx * py - rBy * px)
    }
}

class WeldJoint2D(
    a: Body2D, b: Body2D, anchorX: Float, anchorY: Float
) : Joint2D(a, b) {
    private val point = PointConstraint()
    var localAx: Float; var localAy: Float
    var localBx: Float; var localBy: Float
    var anchorX: Float; var anchorY: Float
    var referenceAngle: Float

    private var angularImpulse = 0f

    init {
        val la = a.pointToLocal(anchorX, anchorY)
        val lb = b.pointToLocal(anchorX, anchorY)
        localAx = la[0]; localAy = la[1]
        localBx = lb[0]; localBy = lb[1]
        this.anchorX = anchorX; this.anchorY = anchorY
        referenceAngle = b.angle - a.angle
    }

    override fun anchorWorld() = floatArrayOf(anchorX, anchorY)

    override fun solve(dt: Float) {
        reactionForce = 0f
        if (!enabled) return
        val pa = a.pointToWorld(localAx, localAy)
        val pb = b.pointToWorld(localBx, localBy)
        anchorX = pa[0]; anchorY = pa[1]
        point.prepare(a, b, pa[0], pa[1], softness = 0.001f, dt = dt)
        point.solveVelocity(a, b)
        point.solvePosition(a, b, pb[0] - pa[0], pb[1] - pa[1], 0.3f)
        val invI = a.invInertia + b.invInertia
        if (invI > 1e-9f) {
            val c = M.wrapAngle(b.angle - a.angle - referenceAngle)
            val lambda = -((b.angularVelocity - a.angularVelocity) + c * 0.3f / dt) / invI
            angularImpulse += lambda
            b.angularVelocity += lambda * b.invInertia
            a.angularVelocity -= lambda * a.invInertia
        }
        reactionForce = max(point.impulseMagnitude, abs(angularImpulse)) / max(dt, 1e-5f)
        checkBreak()
    }
}

/** Prismatic joint: constrains motion to a line (elevators, pistons, drawers, sliders). */
class PrismaticJoint2D(
    a: Body2D, b: Body2D, anchorX: Float, anchorY: Float,
    axisX: Float = 1f, axisY: Float = 0f
) : Joint2D(a, b) {
    private val point = PointConstraint()
    var localAx: Float; var localAy: Float
    var localBx: Float; var localBy: Float
    var anchorX: Float; var anchorY: Float
    var axisX: Float = axisX; var axisY: Float = axisY

    var enableLimit = false
    var lowerTranslation = -1f
    var upperTranslation = 1f
    var enableMotor = false
    var motorSpeed = 0f
    var maxMotorForce = 100f
    var translation = 0f; private set

    private var motorImpulse = 0f
    private var angularImpulse = 0f

    init {
        val la = a.pointToLocal(anchorX, anchorY)
        val lb = b.pointToLocal(anchorX, anchorY)
        localAx = la[0]; localAy = la[1]
        localBx = lb[0]; localBy = lb[1]
        this.anchorX = anchorX; this.anchorY = anchorY
    }

    override fun anchorWorld() = floatArrayOf(anchorX, anchorY)

    override fun solve(dt: Float) {
        reactionForce = 0f
        if (!enabled) return
        val ax = axisX * a.cosA - axisY * a.sinA
        val ay = axisX * a.sinA + axisY * a.cosA
        val pa = a.pointToWorld(localAx, localAy)
        val pb = b.pointToWorld(localBx, localBy)
        val rAx = pa[0] - a.x; val rAy = pa[1] - a.y
        val rBx = pb[0] - b.x; val rBy = pb[1] - b.y
        val dx = pb[0] - pa[0]; val dy = pb[1] - pa[1]
        translation = dx * ax + dy * ay
        anchorX = pa[0]; anchorY = pa[1]

        // perpendicular + angular constraints
        val projX = pa[0] + ax * translation
        val projY = pa[1] + ay * translation
        val point = this.point
        point.prepare(a, b, projX, projY, softness = 0.001f, dt = dt)
        point.solveVelocity(a, b)
        point.solvePosition(a, b, pb[0] - projX, pb[1] - projY, 0.25f)
        val invI = a.invInertia + b.invInertia
        if (invI > 1e-9f) {
            val lambda = -((b.angularVelocity - a.angularVelocity) + (b.angle - a.angle) * 0.3f / dt) / invI
            angularImpulse += lambda
            b.angularVelocity += lambda * b.invInertia
            a.angularVelocity -= lambda * a.invInertia
        }

        // axial motor and limits
        val kAxial = a.invMass + b.invMass +
            a.invInertia * (rAx * ay - rAy * ax) * (rAx * ay - rAy * ax) +
            b.invInertia * (rBx * ay - rBy * ax) * (rBx * ay - rBy * ax)
        if (kAxial > 1e-9f) {
            val mass = 1f / kAxial
            val vax = a.vx - a.angularVelocity * rAy
            val vay = a.vy + a.angularVelocity * rAx
            val vbx = b.vx - b.angularVelocity * rBy
            val vby = b.vy + b.angularVelocity * rBx
            val cdot = (vbx - vax) * ax + (vby - vay) * ay
            if (enableLimit) {
                if (translation <= lowerTranslation) {
                    val lambda = max(-mass * (cdot + (translation - lowerTranslation) * 0.4f / dt), 0f)
                    applyAxis(a, b, rAx, rAy, rBx, rBy, ax, ay, lambda)
                } else if (translation >= upperTranslation) {
                    val lambda = min(-mass * (cdot + (translation - upperTranslation) * 0.4f / dt), 0f)
                    applyAxis(a, b, rAx, rAy, rBx, rBy, ax, ay, lambda)
                }
            }
            if (enableMotor) {
                val maxImpulse = maxMotorForce * dt
                val desired = M.clamp(motorImpulse - mass * (cdot - motorSpeed), -maxImpulse, maxImpulse)
                val applied = desired - motorImpulse
                motorImpulse = desired
                applyAxis(a, b, rAx, rAy, rBx, rBy, ax, ay, applied)
            }
        }
        reactionForce = max(point.impulseMagnitude, abs(angularImpulse)) / max(dt, 1e-5f)
        checkBreak()
    }

    private fun applyAxis(a: Body2D, b: Body2D, rAx: Float, rAy: Float, rBx: Float, rBy: Float, ax: Float, ay: Float, lambda: Float) {
        val px = ax * lambda; val py = ay * lambda
        a.vx -= px * a.invMass; a.vy -= py * a.invMass
        a.angularVelocity -= a.invInertia * (rAx * py - rAy * px)
        b.vx += px * b.invMass; b.vy += py * b.invMass
        b.angularVelocity += b.invInertia * (rBx * py - rBy * px)
    }
}

/**
 * Motor joint: drives body B towards a target offset relative to body A with limited force.
 * Ideal for moving platforms, cranes, elevators and "grab and drag" gameplay.
 */
class MotorJoint2D(a: Body2D, b: Body2D) : Joint2D(a, b) {
    private val point = PointConstraint()
    private var angularImpulse = 0f

    var targetOffsetX = 0f
    var targetOffsetY = 0f
    var targetAngle = 0f
    var maxForce = 500f
    var maxTorque = 250f
    var correctionFactor = 0.3f

    override fun solve(dt: Float) {
        reactionForce = 0f
        if (!enabled) return
        val tx = targetOffsetX * a.cosA - targetOffsetY * a.sinA
        val ty = targetOffsetX * a.sinA + targetOffsetY * a.cosA
        val desiredX = a.x + tx
        val desiredY = a.y + ty
        point.prepare(a, b, desiredX, desiredY, softness = 0f, dt = dt)
        point.solveClamped(a, b, (b.vx - a.vx) + (b.x - desiredX) * correctionFactor / dt,
            (b.vy - a.vy) + (b.y - desiredY) * correctionFactor / dt, maxForce * dt)
        val invI = a.invInertia + b.invInertia
        if (invI > 1e-9f) {
            val c = M.wrapAngle(b.angle - a.angle - Math.toRadians(targetAngle.toDouble()).toFloat())
            val lambda = -((b.angularVelocity - a.angularVelocity) + c * correctionFactor / dt) / invI
            val clamped = M.clamp(lambda, -maxTorque * dt, maxTorque * dt)
            angularImpulse += clamped
            b.angularVelocity += clamped * b.invInertia
            a.angularVelocity -= clamped * a.invInertia
        }
        reactionForce = max(point.impulseMagnitude, abs(angularImpulse)) / max(dt, 1e-5f)
        checkBreak()
    }
}
