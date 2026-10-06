package com.sengine.engine.vehicle

import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.Component
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.JointComponent
import com.sengine.engine.core.Prop
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.core.Scene
import com.sengine.engine.core.WheelJoint
import com.sengine.engine.math.M
import com.sengine.engine.physics.Body2D
import com.sengine.engine.physics.PhysicsWorld2D
import com.sengine.engine.physics.WheelJoint2D
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One wheel of a [Vehicle2D]: created as a child object with a circle collider + wheel joint. */
class WheelDef(
    val offsetX: Float,
    val offsetY: Float,
    val radius: Float,
    val drive: Boolean,
    val steer: Boolean = false,
    val friction: Float = 1.6f,
    val suspensionFrequency: Float = 4.5f,
    val suspensionDamping: Float = 0.55f
)

/** Runtime telemetry exposed to scripts and the HUD. */
class VehicleTelemetry {
    var speed = 0f
    var speedKmh = 0f
    var engineRpm = 0f
    var throttle = 0f
    var braking = false
    var groundedWheels = 0
    var airborne = false
    var wheelSlip = 0f
    var fuel = 1f
    var flipped = false
    var distanceTravelled = 0f
    var airTime = 0f
    var suspensionLoad = 0f
    /** Torque currently delivered to the driven wheels (Nm); 0 while braking. */
    var wheelTorque = 0f
    /** Mean tyre radius of the built wheels (world units). */
    var wheelRadius = 0.3f
    /** Fraction of wheels touching the ground (0..1) - drives the available grip. */
    var grip = 0f
    /** Traction force currently applied at the contact patches (N). */
    var driveForce = 0f
    /** Wheel spin the driver is asking for (degrees/second, negative = clockwise/forward). */
    var commandedWheelSpin = 0f
}

/**
 * Vehicle2D - a complete 2D vehicle built on wheel joints: chassis + wheels with real suspension
 * springs/dampers, engine torque curve, traction and slip, brakes, air control (rotate in the
 * air), automatic stabilization, terrain interaction through collider materials, fuel and
 * telemetry. This is exactly the model needed for Hill Climb Racing style gameplay.
 */
class Vehicle2D : Component() {
    override val type = "Vehicle2D"

    /**
     * Wheel definition: `x,y,radius[,drive][,steer][,friction][,freq][,damping]` separated by ';'.
     * Example (a jeep): `-0.75,-0.35,0.32,drive;0.75,-0.35,0.32,drive`.
     */
    var wheels = "-0.75,-0.35,0.32,drive;0.75,-0.35,0.32,drive"

    /**
     * Peak motor torque applied to driven wheels (Nm). Keep it close to the torque the tyres can
     * actually take (grip force * wheel radius) - a torque far above that only spins the chassis
     * through the motor's reaction, which is what made vehicles undrivable before.
     */
    var maxMotorTorque = 26f
    /** Maximum wheel spin (degrees/second) - the vehicle's top speed. */
    var maxWheelSpeed = 1100f
    /** Torque reduction as speed rises (0 = constant torque, 1 = strong falloff). */
    var torqueFalloff = 0.55f
    /** Reverse gear torque multiplier. */
    var reverseFactor = 0.6f
    /** Braking torque (Nm). */
    var brakeTorque = 18f
    /** Rolling resistance when there is no input (Nm). */
    var engineBrakeTorque = 3f
    /** Extra grip multiplier applied to driven wheels under throttle. */
    var traction = 1.15f
    /** Grip multiplier when the wheel is spinning fast (0 = no slip, 1 = realistic slip). */
    var slipInfluence = 0.35f
    /** Torque used to rotate the chassis while airborne (Nm). */
    var airControlTorque = 8f
    /** Automatic stabilization towards upright while airborne (0 = off). */
    var stabilization = 0.35f
    /** Extra torque that helps recover from a flip (Hill Climb style, Nm). */
    var flipRecoveryTorque = 14f
    var flipRecoveryDelay = 0.6f
    /** Downward force applied at high speed to keep the car planted. */
    var downforce = 0.35f
    /**
     * Drives the tyres by commanding their spin rate (the standard arcade 2D vehicle model). With
     * this off, the drive runs physically through the wheel joint motors, which is more realistic
     * but only works with carefully tuned torque - too much flips the chassis, too little spins the
     * tyres without ever moving the car.
     */
    var wheelSpinControl = false

    // ---- fuel
    var useFuel = false
    var fuelCapacity = 100f
    var fuelConsumption = 2.2f
    /** Distance-based fuel pickups can add via refuel(). */

    // ---- feel
    var autoFlipChassisSprite = false
    var maxSpeedClamp = 0f
    var wheelSortOrder = -1

    // ---- runtime
    val telemetry = VehicleTelemetry()
    var chassis: Body2D? = null
        private set
    val wheelBodies = ArrayList<Body2D>()
    val wheelJoints = ArrayList<WheelJoint2D>()
    val wheelObjects = ArrayList<GameObject>()
    /** Wheel joint components live on the chassis (the suspension axis is in chassis space). */
    val jointComponents = ArrayList<WheelJoint>()
    private val driven = ArrayList<Boolean>()
    private var setup = false
    private var airborneTime = 0f
    private var flippedTime = 0f
    private var startX = 0f
    private var lastX = 0f

    /** Driver input. */
    var throttle = 0f
    var brake = false
    var rotateInput = 0f
    var handbrake = false

    // ---- cached per-step values for the physics world to consume
    var desiredMotorSpeed = 0f

    override fun props() = listOf(
        Prop.S("Wheels", { wheels }, { wheels = it }, multiline = true,
            tooltip = "x,y,radius[,drive][,steer][,friction][,freq][,damping] per wheel, separated by ';'"),
        Prop.F("Max Motor Torque", { maxMotorTorque }, { maxMotorTorque = it.coerceAtLeast(0f) }, 5f, 0f, 100000f),
        Prop.F("Max Wheel Speed", { maxWheelSpeed }, { maxWheelSpeed = it.coerceAtLeast(1f) }, 10f, 1f, 100000f),
        Prop.F("Torque Falloff", { torqueFalloff }, { torqueFalloff = it.coerceIn(0f, 1f) }, 0.05f, 0f, 1f),
        Prop.F("Reverse Factor", { reverseFactor }, { reverseFactor = it.coerceIn(0f, 2f) }, 0.05f, 0f, 2f),
        Prop.F("Brake Torque", { brakeTorque }, { brakeTorque = it.coerceAtLeast(0f) }, 5f, 0f, 100000f),
        Prop.F("Engine Brake", { engineBrakeTorque }, { engineBrakeTorque = it.coerceAtLeast(0f) }, 5f, 0f, 100000f),
        Prop.F("Traction", { traction }, { traction = it.coerceIn(0.1f, 4f) }, 0.05f, 0.1f, 4f),
        Prop.F("Slip Influence", { slipInfluence }, { slipInfluence = it.coerceIn(0f, 1f) }, 0.05f, 0f, 1f),
        Prop.B("Wheel Spin Control", { wheelSpinControl }, { wheelSpinControl = it },
            "Drive by commanding the tyre spin rate (arcade). Off = drive through the wheel joint motors."),
        Prop.F("Air Control Torque", { airControlTorque }, { airControlTorque = it.coerceAtLeast(0f) }, 5f, 0f, 100000f),
        Prop.F("Stabilization", { stabilization }, { stabilization = it.coerceIn(0f, 2f) }, 0.05f, 0f, 2f),
        Prop.F("Flip Recovery Torque", { flipRecoveryTorque }, { flipRecoveryTorque = it.coerceAtLeast(0f) }, 5f, 0f, 100000f),
        Prop.F("Flip Recovery Delay", { flipRecoveryDelay }, { flipRecoveryDelay = it.coerceAtLeast(0f) }, 0.05f, 0f, 10f),
        Prop.F("Downforce", { downforce }, { downforce = it.coerceIn(0f, 5f) }, 0.05f, 0f, 5f),
        Prop.B("Use Fuel", { useFuel }, { useFuel = it }),
        Prop.F("Fuel Capacity", { fuelCapacity }, { fuelCapacity = it.coerceAtLeast(1f) }, 5f, 1f, 100000f),
        Prop.F("Fuel Consumption", { fuelConsumption }, { fuelConsumption = it.coerceAtLeast(0f) }, 0.1f, 0f, 1000f),
        Prop.F("Max Speed Clamp", { maxSpeedClamp }, { maxSpeedClamp = it.coerceAtLeast(0f) }, 0.5f, 0f, 1000f),
        Prop.B("Auto Flip Chassis", { autoFlipChassisSprite }, { autoFlipChassisSprite = it }),
        Prop.I("Wheel Sort Order", { wheelSortOrder }, { wheelSortOrder = it }),
        Prop.Info("Speed", { "%.1f km/h".format(telemetry.speedKmh) }),
        Prop.Info("Grounded Wheels", { telemetry.groundedWheels.toString() }),
        Prop.Info("Airborne", { telemetry.airborne.toString() }),
        Prop.Info("Fuel", { "%.0f%%".format(telemetry.fuel * 100f) })
    )

    override fun resetRuntime() {
        setup = false
        chassis = null
        wheelBodies.clear(); wheelJoints.clear(); wheelObjects.clear(); jointComponents.clear(); driven.clear()
        telemetry.fuel = 1f
        telemetry.distanceTravelled = 0f
        airborneTime = 0f
        flippedTime = 0f
        setup = false
    }

    /** Parses the wheel specification. */
    fun parseWheels(): List<WheelDef> {
        val out = ArrayList<WheelDef>()
        for (raw in wheels.split(';', '\n')) {
            val parts = raw.split(',').map { it.trim() }
            if (parts.size < 3) continue
            val x = parts[0].toFloatOrNull() ?: continue
            val y = parts[1].toFloatOrNull() ?: continue
            val r = parts[2].toFloatOrNull() ?: continue
            var drive = false; var steer = false
            var friction = 1.6f; var freq = 4.5f; var damp = 0.55f
            for (i in 3 until parts.size) {
                val p = parts[i].lowercase()
                when {
                    p == "drive" || p == "d" -> drive = true
                    p == "steer" || p == "s" -> steer = true
                    p.startsWith("friction=") || p.startsWith("mu=") || p.startsWith("f=") ->
                        friction = p.substringAfter('=').toFloatOrNull() ?: friction
                    p.startsWith("freq=") -> freq = p.substringAfter('=').toFloatOrNull() ?: freq
                    p.startsWith("damp=") -> damp = p.substringAfter('=').toFloatOrNull() ?: damp
                    p == "front" -> drive = true
                    else -> p.toFloatOrNull()?.let { friction = it }
                }
            }
            out.add(WheelDef(x, y, r, drive, steer, friction, freq, damp))
        }
        return out
    }

    /**
     * Builds the wheels (objects, colliders, rigidbodies and wheel joints).
     * Called by the engine when the scene starts; safe to call again after reset.
     */
    fun build(scene: Scene, world: PhysicsWorld2D) {
        if (isBuilt()) return
        val rb = go.getAny<Rigidbody2D>() ?: return
        if (rb.bodyType == 2) rb.bodyType = 0 // a vehicle chassis is always dynamic
        // make sure the chassis has a collider so it can be hit
        val defs = parseWheels()
        if (defs.isEmpty()) return
        val holder = scene.create("${go.name}_wheels", go)
        holder.tag = "Vehicle"
        // Wheel offsets are authored in world units from the chassis origin, so a scaled chassis must
        // not stretch them: divide out the inherited scale and neutralise it on the wheel itself. A
        // scaled chassis used to pull its wheels up until the body rode on its belly and the tyres
        // could no longer drive anything.
        val parentWorld = go.computeWorld()
        val invScaleX = if (abs(parentWorld.scaleX) < 1e-4f) 1f else 1f / parentWorld.scaleX
        val invScaleY = if (abs(parentWorld.scaleY) < 1e-4f) 1f else 1f / parentWorld.scaleY
        for ((index, d) in defs.withIndex()) {
            val wheel = scene.create("Wheel${index}", holder)
            wheel.x = d.offsetX * invScaleX
            wheel.y = d.offsetY * invScaleY
            wheel.scaleX = invScaleX
            wheel.scaleY = invScaleY
            wheel.tag = "Wheel"
            val wrb = Rigidbody2D()
            wrb.bodyType = 0
            wrb.mass = 0.35f
            wrb.angularDamping = 0.02f
            // A wheel lives in permanent contact with the ground, so it is not a bullet: running
            // continuous collision detection on it fights the suspension every frame.
            wrb.continuous = false
            wheel.add(wrb)
            val wcol = Collider2D()
            wcol.shape = Collider2D.SHAPE_CIRCLE
            wcol.radius = d.radius
            wcol.density = 0.8f
            wcol.friction = d.friction * traction
            wcol.restitution = 0.02f
            wcol.material = "Rubber"
            wheel.add(wcol)
            val spr = com.sengine.engine.core.SpriteRenderer()
            spr.shape = 1
            spr.width = d.radius * 2f
            spr.height = d.radius * 2f
            spr.color = 0xFF2B3038.toInt()
            wheel.order = wheelSortOrder
            wheel.add(spr)
            // The wheel joint is attached to the CHASSIS: the suspension axis is expressed in
            // chassis space and the wheel is the connected body.
            val joint = WheelJoint()
            joint.connectedTo = wheel.name
            joint.axisX = 0f; joint.axisY = 1f
            joint.springFrequency = d.suspensionFrequency
            joint.springDampingRatio = d.suspensionDamping
            joint.enableMotor = d.drive
            joint.maxMotorTorque = maxMotorTorque / max(1f, defs.count { it.drive }.toFloat())
            joint.enableLimit = true
            // The wheel must be able to extend by the static sag (gravity / omega^2) plus room for
            // bumps, and compress until the chassis would touch the tyre.
            joint.lowerTranslation = -d.radius * 0.75f
            joint.upperTranslation = d.radius * 0.5f
            joint.anchorX = d.offsetX
            joint.anchorY = d.offsetY
            go.add(joint)
            jointComponents.add(joint)
            wheelObjects.add(wheel)
            driven.add(d.drive)
        }
        setup = true
        startX = go.worldX()
        lastX = startX
    }

    private fun isBuilt(): Boolean = setup && wheelObjects.isNotEmpty() && wheelObjects.all { sceneContains(it) }

    private fun sceneContains(o: GameObject): Boolean = !o.destroyed

    /** Called every frame by the engine before the physics step. */
    /** Rebuilds wheels/joints when play mode starts (scene hierarchy is stable by then). */
    fun refresh(scene: Scene, world: PhysicsWorld2D) {
        build(scene, world)
    }

    /** True while player/AI input drives the vehicle. */
    var controlsEnabled = true

    fun update(world: PhysicsWorld2D, dt: Float) {
        val rbChassis = go.get<Rigidbody2D>() ?: return
        chassis = rbChassis.body ?: world.bodyOf(go) ?: return
        if (!setup || wheelBodies.isEmpty()) refreshHandles(world)
        if (!setup) return

        var groundedWheels = 0
        var totalSlip = 0f
        var suspensionLoad = 0f
        for (i in wheelBodies.indices) {
            val wb = wheelBodies[i]
            val joint = wheelJoints[i]
            if (wb.grounded) groundedWheels++
            suspensionLoad += joint.suspensionForce
            // slip: how much the wheel's surface speed differs from the ground speed
            val chassisSpeed = chassis!!.vx
            val wheelSurfaceSpeed = wb.angularVelocity * ((wb.shape as? com.sengine.engine.physics.CircleShape)?.radius ?: 0.1f)
            totalSlip += abs(wheelSurfaceSpeed - chassisSpeed).coerceAtMost(20f)
        }
        telemetry.groundedWheels = groundedWheels
        telemetry.airborne = groundedWheels == 0
        telemetry.wheelSlip = if (wheelBodies.isEmpty()) 0f else totalSlip / wheelBodies.size
        telemetry.suspensionLoad = suspensionLoad
        telemetry.speed = chassis!!.vx
        telemetry.speedKmh = abs(chassis!!.vx) * 3.6f
        telemetry.throttle = throttle
        telemetry.braking = brake
        telemetry.flipped = abs(M.wrapAngle(Math.toDegrees(chassis!!.angle.toDouble()).toFloat())) > 110f

        if (telemetry.airborne) {
            airborneTime += dt
            telemetry.airTime = airborneTime
        } else {
            airborneTime = 0f
            telemetry.airTime = 0f
        }
        telemetry.distanceTravelled += abs(chassis!!.x - lastX)
        lastX = chassis!!.x

        // ---- fuel
        if (useFuel) {
            val burn = fuelConsumption * dt * (0.25f + abs(throttle))
            telemetry.fuel = (telemetry.fuel - burn / max(1f, fuelCapacity)).coerceAtLeast(0f)
            if (telemetry.fuel <= 0f) {
                throttle = 0f
                go.emit("outOfFuel")
            }
        }

        // ---- engine: motor speed from throttle with torque falloff
        // Engine load is read from the driven wheels' actual spin, so the falloff works in the
        // same unit as maxWheelSpeed (degrees/second) instead of mixing degrees with metres.
        val maxSpin = maxWheelSpeed.coerceAtLeast(1f)
        var spinSum = 0f
        for (i in wheelBodies.indices) spinSum += abs(wheelBodies[i].angularVelocity) * 57.29578f
        val wheelSpinDeg = if (wheelBodies.isEmpty()) 0f else spinSum / wheelBodies.size
        val rpmRatio = M.clamp01(wheelSpinDeg / maxSpin)
        telemetry.engineRpm = rpmRatio * 8000f
        val torqueFactor = if (useFuel && telemetry.fuel <= 0f) 0f else (1f - torqueFalloff * rpmRatio)
        val reverse = throttle < 0f
        val effectiveTorque = maxMotorTorque * torqueFactor * (if (reverse) reverseFactor else 1f)

        // ---- drive, brakes and engine braking
        // The drive torque of a wheel becomes a traction force at its contact patch plus a pitching
        // reaction around the centre of mass. Applying that one force AT THE CONTACT PATCH gives both
        // effects exactly (the offset below the centre of mass is the wheelie torque), which is far
        // more predictable than relying on the motor/reaction pair of the joint through the solver:
        // the driven wheel is left to roll freely, so tyre friction cannot fight the drive torque.
        val driveCount = max(1f, driven.count { it }.toFloat())
        val groundRatio = if (wheelBodies.isEmpty()) 0f else groundedWheels.toFloat() / wheelBodies.size
        val gripThrust = maxMotorTorque * 4f   // full-throttle thrust ceiling before grip loss
        var driveThrust = 0f
        if (abs(throttle) > 0.01f && !brake && !handbrake) {
            val torque = effectiveTorque
            val radius = averageWheelRadius()
            if (radius > 1e-4f) {
                driveThrust = (torque / radius).coerceAtMost(gripThrust) * groundRatio * M.sign(throttle)
            }
        }
        var braking = false
        for (i in wheelJoints.indices) {
            val joint = wheelJoints[i]
            val isDriven = driven.getOrElse(i) { false }
            if (!isDriven) continue
            if (brake) {
                joint.enableMotor = true
                joint.motorSpeed = 0f
                joint.maxMotorTorque = brakeTorque / driveCount
                braking = true
            } else if (handbrake) {
                joint.enableMotor = true
                joint.motorSpeed = 0f
                joint.maxMotorTorque = brakeTorque * 1.6f / driveCount
                braking = true
            } else if (abs(throttle) > 0.01f) {
                // Driving: the wheel spin is commanded directly (see the servo below) instead of
                // through the joint motor, because a motor torque small enough not to spin the
                // chassis through its reaction can never break tyre traction either.
                joint.enableMotor = false
                joint.motorSpeed = 0f
                joint.maxMotorTorque = 0f
            } else {
                joint.enableMotor = true
                joint.motorSpeed = 0f
                joint.maxMotorTorque = engineBrakeTorque / driveCount
            }
        }
        // Traction: the driven tyres are spun to the commanded rate and the tyre friction under that
        // spin IS the drive force - it pushes the car, it makes the wheels break traction on loose
        // ground and, acting one wheel radius below the centre of mass, it pitches the car up on
        // launch. This is the arcade vehicle model used by 2D car games; it stays stable because the
        // spin servo cannot apply a reaction torque to the chassis, while a joint motor strong enough
        // to drive the car always could (and would flip it over).
        telemetry.commandedWheelSpin = 0f
        if (groundRatio > 0f && abs(throttle) > 0.01f && !brake && !handbrake) {
            telemetry.commandedWheelSpin = -throttle * maxSpin
        }
        if (driveThrust != 0f) {
            // The traction force goes on the chassis, one wheel radius below its centre of mass (that
            // offset is the launch wheelie). Putting it on the tyre instead looks equivalent, but the
            // tyre is held by the suspension's side constraint, which absorbs the impulse.
            val radius = averageWheelRadius()
            chassis!!.applyForce(driveThrust, 0f)
            chassis!!.applyTorque(driveThrust * radius)
        }
        if (wheelSpinControl && driveThrust != 0f) {
            // Visual/arcade tyre spin: the wheels are shown turning at the commanded rate while the
            // force above does the driving. With this off the tyres are left to the contact solver.
            val targetSpin = Math.toRadians(telemetry.commandedWheelSpin.toDouble()).toFloat()
            for (i in wheelBodies.indices) {
                if (!driven.getOrElse(i) { false }) continue
                val wb = wheelBodies[i]
                wb.angularVelocity = targetSpin
                wb.wake()
            }
        }
        telemetry.wheelTorque = effectiveTorque * abs(throttle) * (if (braking) 0f else 1f)
        telemetry.braking = braking
        telemetry.wheelRadius = averageWheelRadius()
        telemetry.grip = groundRatio
        telemetry.driveForce = driveThrust

        // ---- traction: modulate wheel friction with throttle and slip
        for (i in wheelBodies.indices) {
            val wb = wheelBodies[i]
            val base = wb.go.get<Collider2D>()?.friction ?: 1.6f
            val slip = M.clamp01(telemetry.wheelSlip / 8f)
            wb.friction = base * (1f - slipInfluence * slip)
        }

        // ---- air control and stabilization
        if (telemetry.airborne) {
            if (abs(rotateInput) > 0.01f) {
                chassis!!.applyTorque(-rotateInput * airControlTorque)
            }
            if (stabilization > 0f) {
                val angle = M.wrapAngle(Math.toDegrees(chassis!!.angle.toDouble()).toFloat())
                val target = if (angle > 90f || angle < -90f) (if (angle > 0f) 180f else -180f) else 0f
                val err = M.wrapAngle(target - angle)
                val k = stabilization * 12f
                chassis!!.torque -= err * k - chassis!!.angularVelocity * stabilization * 6f
            }
        } else {
            // downforce keeps the car planted at speed
            if (downforce > 0f) {
                val force = abs(chassis!!.vx) * downforce * 40f
                chassis!!.applyForceAt(0f, -force, chassis!!.x, chassis!!.y)
            }
        }

        // ---- flip recovery
        if (telemetry.flipped) {
            flippedTime += dt
            if (flippedTime > flipRecoveryDelay) {
                val angle = M.wrapAngle(Math.toDegrees(chassis!!.angle.toDouble()).toFloat())
                val dir = if (angle > 0f) -1f else 1f
                chassis!!.applyTorque(dir * flipRecoveryTorque)
                if (telemetry.airborne) chassis!!.applyForce(0f, 6f)
            }
        } else {
            flippedTime = 0f
        }

        // ---- speed clamp / auto flip sprite
        if (maxSpeedClamp > 0f && abs(chassis!!.vx) > maxSpeedClamp) {
            chassis!!.vx = M.sign(chassis!!.vx) * maxSpeedClamp
        }
        if (autoFlipChassisSprite) {
            val spr = go.get<com.sengine.engine.core.SpriteRenderer>()
            if (spr != null && abs(chassis!!.vx) > 0.5f) spr.flipX = chassis!!.vx < 0f
        }
    }

    /** Mean tyre radius of the built wheels (used to turn motor torque into thrust). */
    private fun averageWheelRadius(): Float {
        if (wheelBodies.isEmpty()) return 0f
        var sum = 0f
        for (b in wheelBodies) sum += (b.shape as? com.sengine.engine.physics.CircleShape)?.radius ?: 0.3f
        return sum / wheelBodies.size
    }

    private fun refreshHandles(world: PhysicsWorld2D) {
        wheelBodies.clear()
        wheelJoints.clear()
        for (o in wheelObjects) {
            val b = world.bodyOf(o)
            if (b != null) wheelBodies.add(b)
        }
        for (c in jointComponents) {
            val j = c.joint
            if (j is WheelJoint2D) wheelJoints.add(j)
        }
        if (wheelObjects.isNotEmpty() && wheelBodies.size == wheelObjects.size && wheelJoints.size == wheelObjects.size) {
            setup = true
        }
    }

    fun refuel(amount: Float) {
        telemetry.fuel = (telemetry.fuel + amount / max(1f, fuelCapacity)).coerceIn(0f, 1f)
    }

    fun isAirborne() = telemetry.airborne
    fun totalWheels() = wheelObjects.size
}
