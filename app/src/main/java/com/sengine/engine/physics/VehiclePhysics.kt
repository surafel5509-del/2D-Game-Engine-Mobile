package com.sengine.engine.physics

import com.sengine.engine.core.Component
import com.sengine.engine.core.Prop
import com.sengine.engine.core.Rigidbody2D
import org.json.JSONObject
import kotlin.math.*

/**
 * Vehicle physics component for cars, trucks, and other wheeled vehicles.
 * Works with Wheel components attached to child GameObjects.
 * Provides torque, braking, steering, and terrain interaction.
 */
class VehicleBody : Component() {
    override val type = "VehicleBody"

    // Chassis properties
    var chassisMass = 100f
    var maxSpeed = 30f
    var maxTorque = 50f
    var brakeTorque = 80f
    var steeringSpeed = 5f // how fast steering angle changes
    var maxSteeringAngle = 35f // degrees
    var downforce = 2f // extra downward force for grip
    var dragCoefficient = 0.1f
    var rollResistance = 0.02f

    // Runtime state
    var currentSpeed = 0f
    var steeringAngle = 0f
    var throttle = 0f // -1 to 1
    var brake = 0f // 0 to 1
    var steerInput = 0f // -1 to 1
    var isGrounded = false

    // Internal
    var drivingWheels = mutableListOf<WheelComponent>()
    var steeringWheels = mutableListOf<WheelComponent>()

    override fun props() = listOf(
        Prop.F("Chassis Mass", { chassisMass }, { chassisMass = it.coerceAtLeast(1f) }),
        Prop.F("Max Speed", { maxSpeed }, { maxSpeed = it.coerceAtLeast(0.1f) }),
        Prop.F("Max Torque", { maxTorque }, { maxTorque = it.coerceAtLeast(0f) }),
        Prop.F("Brake Torque", { brakeTorque }, { brakeTorque = it.coerceAtLeast(0f) }),
        Prop.F("Steering Speed", { steeringSpeed }, { steeringSpeed = it.coerceAtLeast(0.1f) }),
        Prop.F("Max Steering Angle", { maxSteeringAngle }, { maxSteeringAngle = it.coerceIn(0f, 90f) }),
        Prop.F("Downforce", { downforce }, { downforce = it.coerceAtLeast(0f) }),
        Prop.F("Drag Coefficient", { dragCoefficient }, { dragCoefficient = it.coerceAtLeast(0f) }),
        Prop.F("Roll Resistance", { rollResistance }, { rollResistance = it.coerceAtLeast(0f) }),
    )

    override fun resetRuntime() {
        currentSpeed = 0f
        steeringAngle = 0f
        throttle = 0f
        brake = 0f
        steerInput = 0f
        isGrounded = false
        drivingWheels.clear()
        steeringWheels.clear()
    }

    /**
     * Called each physics step. Applies engine forces, steering, and braking.
     */
    fun updatePhysics(dt: Float) {
        if (!enabled) return
        val rb = gameObject.get<Rigidbody2D>() ?: return
        if (rb.bodyType != 0) return

        val go = gameObject

        // Find wheel components in children
        drivingWheels.clear()
        steeringWheels.clear()
        // Wheels are registered externally via registerWheel

        // Steering
        val targetAngle = steerInput * maxSteeringAngle
        steeringAngle += (targetAngle - steeringAngle).coerceIn(-steeringSpeed * dt * 60f, steeringSpeed * dt * 60f)

        // Apply steering to steering wheels
        for (w in steeringWheels) {
            w.steerAngle = steeringAngle
        }

        // Calculate current speed
        currentSpeed = sqrt(rb.vx * rb.vx + rb.vy * rb.vy) * sign(rb.vx)

        // Check if grounded (any wheel touching ground)
        isGrounded = drivingWheels.any { it.isGrounded } || steeringWheels.any { it.isGrounded }

        if (isGrounded) {
            // Engine force
            val speedFactor = 1f - (abs(currentSpeed) / maxSpeed).coerceIn(0f, 1f)
            val engineForce = throttle * maxTorque * speedFactor

            // Apply driving force
            for (w in drivingWheels) {
                val forceDir = if (steeringAngle != 0f) {
                    val rad = Math.toRadians(steeringAngle.toDouble()).toFloat()
                    Pair(sin(rad), cos(rad))
                } else {
                    Pair(0f, 1f)
                }
                rb.vx += forceDir.second * engineForce * dt / chassisMass
            }

            // Braking
            if (brake > 0f) {
                val brakeForce = brake * brakeTorque
                val speed = sqrt(rb.vx * rb.vx + rb.vy * rb.vy)
                if (speed > 0.01f) {
                    val factor = (brakeForce * dt / chassisMass / speed).coerceAtMost(1f)
                    rb.vx *= (1f - factor)
                    rb.vy *= (1f - factor)
                } else {
                    rb.vx = 0f
                }
            }

            // Downforce
            rb.vy -= downforce * dt

            // Drag
            rb.vx *= (1f - dragCoefficient * dt)

            // Rolling resistance
            val speed2 = rb.vx * rb.vx + rb.vy * rb.vy
            if (speed2 > 0.001f) {
                val speed = sqrt(speed2)
                val rr = rollResistance * dt
                rb.vx *= (1f - rr / speed)
                rb.vy *= (1f - rr / speed)
            }
        }

        // Clamp to max speed
        val spd = sqrt(rb.vx * rb.vx + rb.vy * rb.vy)
        if (spd > maxSpeed) {
            val factor = maxSpeed / spd
            rb.vx *= factor
            rb.vy *= factor
        }
    }

    fun registerWheel(wheel: WheelComponent) {
        if (wheel.isDriveWheel) drivingWheels.add(wheel)
        if (wheel.isSteerWheel) steeringWheels.add(wheel)
    }

    fun unregisterWheel(wheel: WheelComponent) {
        drivingWheels.remove(wheel)
        steeringWheels.remove(wheel)
    }
}

/**
 * Wheel component for vehicle physics.
 * Attached to child GameObjects of a VehicleBody.
 * Handles suspension, ground contact, and friction.
 */
class WheelComponent : Component() {
    override val type = "Wheel"

    // Wheel configuration
    var wheelRadius = 0.4f
    var suspensionLength = 1f
    var suspensionStiffness = 80f
    var suspensionDamping = 8f
    var tireFriction = 0.8f
    var isDriveWheel = true
    var isSteerWheel = false

    // Runtime state
    var isGrounded = false
    var steerAngle = 0f // degrees
    var suspensionCompression = 0f // 0..1
    var groundContactX = 0f
    var groundContactY = 0f
    var groundNormalX = 0f
    var groundNormalY = 1f
    var rpm = 0f

    override fun props() = listOf(
        Prop.F("Wheel Radius", { wheelRadius }, { wheelRadius = it.coerceAtLeast(0.05f) }),
        Prop.F("Suspension Length", { suspensionLength }, { suspensionLength = it.coerceAtLeast(0.1f) }),
        Prop.F("Suspension Stiffness", { suspensionStiffness }, { suspensionStiffness = it.coerceAtLeast(1f) }),
        Prop.F("Suspension Damping", { suspensionDamping }, { suspensionDamping = it.coerceAtLeast(0f) }),
        Prop.F("Tire Friction", { tireFriction }, { tireFriction = it.coerceIn(0f, 2f) }),
        Prop.B("Is Drive Wheel", { isDriveWheel }, { isDriveWheel = it }),
        Prop.B("Is Steer Wheel", { isSteerWheel }, { isSteerWheel = it }),
    )

    override fun resetRuntime() {
        isGrounded = false
        steerAngle = 0f
        suspensionCompression = 0f
        rpm = 0f
    }

    /**
     * Perform suspension raycast and apply forces.
     * Called by the vehicle body during physics update.
     */
    fun updateSuspension(dt: Float, scene: com.sengine.engine.core.Scene) {
        if (!enabled) return

        val w = gameObject.computeWorld()
        val wheelX = w.tx
        val wheelY = w.ty

        // Cast ray downward for suspension
        val rayDir = Math.toRadians(steerAngle.toDouble()).toFloat()
        val rayX = sin(rayDir)
        val rayY = -cos(rayDir) // default suspension goes "down" relative to vehicle

        val hit = Raycaster.raycast(scene, RaycastQuery(
            wheelX, wheelY,
            rayX, rayY,
            suspensionLength + wheelRadius,
            excludeTriggers = true
        ))

        if (hit != null && hit.distance <= suspensionLength + wheelRadius) {
            isGrounded = true
            groundContactX = hit.x
            groundContactY = hit.y
            groundNormalX = hit.normalX
            groundNormalY = hit.normalY

            // Calculate compression
            val compressed = (suspensionLength + wheelRadius - hit.distance) / suspensionLength
            suspensionCompression = compressed.coerceIn(0f, 1f)

            // Apply spring force to vehicle body
            val rb = gameObject.parent?.components?.filterIsInstance<Rigidbody2D>()?.firstOrNull { it.bodyType == 0 }
            if (rb != null) {
                val springForce = suspensionStiffness * suspensionCompression
                val dampForce = suspensionDamping * rb.vy // damping opposes vertical motion
                val totalForce = springForce + dampForce

                // Apply force upward along ground normal
                rb.vy += groundNormalY * totalForce * dt / 10f
                rb.vx += groundNormalX * totalForce * dt / 10f

                // Lateral friction
                val lateralX = -groundNormalY
                val lateralY = groundNormalX
                val lateralV = rb.vx * lateralX + rb.vy * lateralY
                val frictionForce = -lateralV * tireFriction
                rb.vx += lateralX * frictionForce * dt
                rb.vy += lateralY * frictionForce * dt
            }
        } else {
            isGrounded = false
            suspensionCompression = 0f
        }
    }
}
