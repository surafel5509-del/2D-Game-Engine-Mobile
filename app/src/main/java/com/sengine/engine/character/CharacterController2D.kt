package com.sengine.engine.character

import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.Component
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Prop
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.math.M
import com.sengine.engine.physics.PhysicsWorld2D
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Input consumed by the character controller (player, AI or replay). */
class CharacterInput {
    var moveX = 0f
    var moveY = 0f
    var jumpPressed = false
    var jumpHeld = false
    var runHeld = false
    var downHeld = false
    var upHeld = false
    var climbing = false
    var dashPressed = false

    fun clear() {
        moveX = 0f; moveY = 0f; jumpPressed = false; jumpHeld = false
        runHeld = false; downHeld = false; upHeld = false; climbing = false; dashPressed = false
    }
}

/** Result of a movement sweep (used for gameplay reactions like wall hits). */
class MoveResult {
    var hitX = false
    var hitY = false
    var hitCeiling = false
    var hitWall = false
    var groundNormalX = 0f
    var groundNormalY = 0f
    var wallX = 0f
    var wallY = 0f
    var movedX = 0f
    var movedY = 0f
}

/**
 * CharacterController2D - a full 2D character motor built on swept collisions:
 * acceleration/braking with air control, variable jump height, coyote time, jump buffering,
 * double jumps, wall slide + wall jump, slopes with a max angle, moving platform carrying,
 * ladders, one-way platform drop-through, knockback and ground snapping.
 *
 * It works with any collider shape (circle, box, capsule) and never touches 3D data.
 */
class CharacterController2D : Component() {
    override val type = "CharacterController2D"

    // ---- movement
    var moveSpeed = 6f
    var runMultiplier = 1.6f
    var acceleration = 60f
    var airAcceleration = 30f
    var deceleration = 70f
    var maxSlopeAngle = 50f
    var slideDownSlopes = true
    var groundSnapDistance = 0.12f
    var stepHeight = 0.25f

    // ---- jumping
    var jumpForce = 12f
    var jumpHeightVariation = 0.45f
    var maxJumps = 1
    var coyoteTime = 0.1f
    var jumpBufferTime = 0.12f
    var jumpCutMultiplier = 0.45f
    var gravityScale = 3.2f
    var maxFallSpeed = 26f
    var airControl = 0.75f

    // ---- wall interaction
    var wallSlide = true
    var wallSlideSpeed = 3f
    var wallJump = true
    var wallJumpForceX = 9f
    var wallJumpForceY = 12f
    var wallStickTime = 0.12f

    // ---- ladders / climbing
    var canClimb = true
    var climbSpeed = 4f
    var ladderTag = "Ladder"

    // ---- extras
    var dashSpeed = 16f
    var dashTime = 0.15f
    var dashCooldown = 0.6f
    var canDash = false
    var autoFlipSprite = true

    // ---- runtime state
    var input = CharacterInput()
    var vx = 0f
        private set
    var vy = 0f
        private set
    var grounded = false
        private set
    var onWall = 0
        private set
    var climbing = false
        private set
    var coyoteTimer = 0f
    private var jumpBuffer = 0f
    private var jumpsUsed = 0
    private var wallStickTimer = 0f
    private var dashTimer = 0f
    private var dashCooldownTimer = 0f
    private var platformDeltaX = 0f
    private var platformDeltaY = 0f
    private var wasOnPlatform = false
    private var oneWayIgnoreTimer = 0f
    private var facing = 1

    /** Values exposed to scripts/HUD. */
    val speed get() = sqrt(vx * vx + vy * vy)
    val isJumping get() = !grounded && vy > 0.5f
    val isFalling get() = !grounded && vy < -0.5f
    val facingRight get() = facing > 0
    val isDashing get() = dashTimer > 0f

    private val result = MoveResult()
    private val probe = FloatArray(2)

    override fun props() = listOf(
        Prop.F("Move Speed", { moveSpeed }, { moveSpeed = it.coerceAtLeast(0.01f) }, 0.25f, 0.01f, 100f),
        Prop.F("Run Multiplier", { runMultiplier }, { runMultiplier = it.coerceAtLeast(1f) }, 0.05f, 1f, 4f),
        Prop.F("Acceleration", { acceleration }, { acceleration = it.coerceAtLeast(1f) }, 5f, 1f, 500f),
        Prop.F("Air Acceleration", { airAcceleration }, { airAcceleration = it.coerceAtLeast(1f) }, 5f, 1f, 500f),
        Prop.F("Deceleration", { deceleration }, { deceleration = it.coerceAtLeast(1f) }, 5f, 1f, 500f),
        Prop.F("Max Slope Angle", { maxSlopeAngle }, { maxSlopeAngle = it.coerceIn(1f, 89f) }, 1f, 1f, 89f),
        Prop.B("Slide Down Steep Slopes", { slideDownSlopes }, { slideDownSlopes = it }),
        Prop.F("Step Height", { stepHeight }, { stepHeight = it.coerceIn(0f, 1f) }, 0.05f, 0f, 1f),
        Prop.F("Jump Force", { jumpForce }, { jumpForce = it.coerceAtLeast(0f) }, 0.5f, 0f, 60f),
        Prop.F("Jump Height Variation", { jumpHeightVariation }, { jumpHeightVariation = it.coerceIn(0f, 1f) }, 0.05f, 0f, 1f),
        Prop.I("Max Jumps", { maxJumps }, { maxJumps = it.coerceIn(1, 5) }, 1, 1, 5),
        Prop.F("Coyote Time", { coyoteTime }, { coyoteTime = it.coerceAtLeast(0f) }, 0.01f, 0f, 1f),
        Prop.F("Jump Buffer", { jumpBufferTime }, { jumpBufferTime = it.coerceAtLeast(0f) }, 0.01f, 0f, 1f),
        Prop.F("Jump Cut", { jumpCutMultiplier }, { jumpCutMultiplier = it.coerceIn(0f, 1f) }, 0.05f, 0f, 1f),
        Prop.F("Gravity Scale", { gravityScale }, { gravityScale = it.coerceAtLeast(0.01f) }, 0.1f, 0.01f, 30f),
        Prop.F("Max Fall Speed", { maxFallSpeed }, { maxFallSpeed = it.coerceAtLeast(0.1f) }, 1f, 0.1f, 200f),
        Prop.F("Air Control", { airControl }, { airControl = it.coerceIn(0f, 2f) }, 0.05f, 0f, 2f),
        Prop.B("Wall Slide", { wallSlide }, { wallSlide = it }),
        Prop.F("Wall Slide Speed", { wallSlideSpeed }, { wallSlideSpeed = it.coerceAtLeast(0.1f) }, 0.5f, 0.1f, 50f),
        Prop.B("Wall Jump", { wallJump }, { wallJump = it }),
        Prop.F("Wall Jump Force X", { wallJumpForceX }, { wallJumpForceX = it.coerceAtLeast(0f) }, 0.5f, 0f, 60f),
        Prop.F("Wall Jump Force Y", { wallJumpForceY }, { wallJumpForceY = it.coerceAtLeast(0f) }, 0.5f, 0f, 60f),
        Prop.B("Can Climb", { canClimb }, { canClimb = it }),
        Prop.F("Climb Speed", { climbSpeed }, { climbSpeed = it.coerceAtLeast(0.1f) }, 0.25f, 0.1f, 50f),
        Prop.S("Ladder Tag", { ladderTag }, { ladderTag = it }),
        Prop.B("Can Dash", { canDash }, { canDash = it }),
        Prop.F("Dash Speed", { dashSpeed }, { dashSpeed = it.coerceAtLeast(0f) }, 0.5f, 0f, 100f),
        Prop.F("Dash Time", { dashTime }, { dashTime = it.coerceAtLeast(0.01f) }, 0.01f, 0.01f, 2f),
        Prop.F("Dash Cooldown", { dashCooldown }, { dashCooldown = it.coerceAtLeast(0f) }, 0.05f, 0f, 5f),
        Prop.B("Auto Flip Sprite", { autoFlipSprite }, { autoFlipSprite = it }),
        Prop.Info("State", { stateText() })
    )

    private fun stateText(): String = when {
        climbing -> "Climbing"
        isDashing -> "Dashing"
        grounded -> "Grounded"
        onWall != 0 -> "Wall Slide"
        isRising() -> "Jumping"
        else -> "Falling"
    }

    private fun isRising() = vy > 0.5f

    override fun resetRuntime() {
        vx = 0f; vy = 0f
        grounded = false
        onWall = 0
        climbing = false
        coyoteTimer = 0f
        jumpBuffer = 0f
        jumpsUsed = 0
        wallStickTimer = 0f
        dashTimer = 0f
        dashCooldownTimer = 0f
        input.clear()
        facing = 1
    }

    /** Called by the engine every frame. */
    fun update(world: PhysicsWorld2D, scene: com.sengine.engine.core.Scene, dt: Float, gravityY: Float) {
        if (dt <= 0f) return
        val go = go
        val rb = go.get<Rigidbody2D>()
        val col = go.get<Collider2D>()
        val radius = if (col != null) {
            when (col.shape) {
                Collider2D.SHAPE_CIRCLE -> col.radius
                Collider2D.SHAPE_CAPSULE -> col.radius
                else -> max(0.05f, min(col.width, col.height) * 0.5f)
            }
        } else 0.3f

        // ---- timers
        if (coyoteTimer > 0f) coyoteTimer -= dt
        if (jumpBuffer > 0f) jumpBuffer -= dt
        if (wallStickTimer > 0f) wallStickTimer -= dt
        if (dashTimer > 0f) dashTimer -= dt
        if (dashCooldownTimer > 0f) dashCooldownTimer -= dt
        if (oneWayIgnoreTimer > 0f) oneWayIgnoreTimer -= dt

        // ---- ladder detection
        val onLadder = canClimb && isOnLadder(scene)
        if (onLadder && (input.upHeld || input.downHeld || climbing)) climbing = true
        if (!onLadder) climbing = false

        if (climbing) {
            updateClimb(dt)
        } else {
            // ---- horizontal movement
            val targetSpeed = input.moveX * moveSpeed * (if (input.runHeld) runMultiplier else 1f)
            val accel = if (grounded) acceleration else airAcceleration
            val decel = if (grounded) deceleration else acceleration * airControl
            if (abs(input.moveX) > 0.01f) {
                vx = M.moveTowards(vx, targetSpeed, accel * dt)
                facing = if (input.moveX > 0f) 1 else -1
            } else {
                vx = M.moveTowards(vx, 0f, decel * dt)
            }

            // ---- jumping
            if (input.jumpPressed) jumpBuffer = jumpBufferTime
            val canJump = (grounded || coyoteTimer > 0f) && jumpsUsed == 0 || jumpsUsed < maxJumps
            if (jumpBuffer > 0f && canJump) {
                if (onWall != 0 && !grounded && wallJump) {
                    vx = -onWall * wallJumpForceX
                    vy = wallJumpForceY
                    wallStickTimer = 0f
                    go.emit("wallJump", onWall)
                } else {
                    vy = jumpForce
                }
                grounded = false
                coyoteTimer = 0f
                jumpBuffer = 0f
                jumpsUsed++
                go.emit("jumped", jumpsUsed)
            }
            // variable jump height: releasing early cuts the upward velocity
            if (!input.jumpHeld && vy > 0f && jumpsUsed > 0) {
                vy *= (1f - (1f - jumpCutMultiplier) * (1f - M.clamp01(vy / max(jumpForce, 0.001f))))
                if (vy > jumpForce * jumpCutMultiplier) vy = jumpForce * jumpCutMultiplier
            }

            // ---- dash
            if (canDash && input.dashPressed && dashTimer <= 0f && dashCooldownTimer <= 0f) {
                dashTimer = dashTime
                dashCooldownTimer = dashCooldown + dashTime
                go.emit("dashed")
            }
            if (dashTimer > 0f) {
                vx = facing * dashSpeed
                vy = 0f
            } else {
                // ---- gravity
                var gravity = gravityY * gravityScale
                if (gravity > 0f) gravity = gravityY
                vy += gravity * dt
                if (vy < -maxFallSpeed) vy = -maxFallSpeed
            }

            // ---- wall slide
            if (onWall != 0 && !grounded && wallSlide && vy < 0f && !climbing) {
                vy = max(vy, -wallSlideSpeed)
            }
        }

        // ---- wall stick after a wall jump (brief loss of control, feels responsive)
        if (wallStickTimer > 0f) vx *= 0.2f
        // ---- drop through one-way platforms by pressing down
        val rbRef = go.get<Rigidbody2D>()
        if (grounded && input.downHeld && input.jumpPressed) {
            oneWayIgnoreTimer = 0.22f
            grounded = false
            jumpBuffer = 0f
            vy = -2f
        }
        rbRef?.dropThrough = oneWayIgnoreTimer > 0f

        // ---- integrate with swept collision
        var dx = vx * dt
        var dy = vy * dt
        // carry the character with a moving platform
        if (groundedPatience && (platformDeltaX != 0f || platformDeltaY != 0f)) {
            dx += platformDeltaX
            dy += platformDeltaY
        }
        val startX = go.worldX()
        val startY = go.worldY()
        var curX = startX
        var curY = startY

        moveSweep(world, radius, curX, curY, dx, dy, 4) { nx, ny, hit -> curX = nx; curY = ny; hit }
        go.setWorldPosition(curX, curY)
        val movedX = curX - startX
        val movedY = curY - startY

        // resolved velocities (so hitting a wall zeroes the speed)
        if (dt > 0f) {
            if (result.hitX) vx = movedX / dt
            if (result.hitY) vy = movedY / dt
        }

        // ---- ground check with a downward probe (plus snap)
        val wasGrounded = grounded
        grounded = false
        onWall = 0
        val probeLen = radius + groundSnapDistance
        val hit = world.circleCastIgnore(curX, curY, curX, curY - probeLen, radius * 0.98f, go, -1)
        if (hit != null) {
            val ny = hit.normalY
            val upDot = if (ny > 0f) ny else -ny
            // recompute the normal from the actual surface direction
            val groundN = surfaceNormal(world, curX, curY - probeLen * 0.5f, go)
            val slopeDeg = Math.toDegrees(kotlin.math.atan2(groundN[0].toDouble(), groundN[1].toDouble())).toFloat()
            if (abs(slopeDeg) <= maxSlopeAngle) {
                grounded = true
                rb?.body?.grounded = true
                jumpsUsed = 0
                if (!wasGrounded) {
                    go.emit("landed", vy)
                    vy = 0f
                }
                coyoteTimer = 0f
                result.groundNormalX = groundN[0]
                result.groundNormalY = groundN[1]
                // snap to the surface
                val gap = hit.fraction * probeLen
                if (gap > 0.001f && vy <= 0.01f) {
                    curY -= gap - 0.01f
                    go.setWorldPosition(curX, curY)
                }
                // slide down very steep slopes
                if (slideDownSlopes) {
                    val slopeSteepness = abs(groundN[0])
                    if (slopeSteepness > 0.1f && abs(input.moveX) < 0.01f && !climbing) {
                        vx += groundN[0] * 6f * dt
                    }
                }
            } else {
                // too steep: slide
                vx += groundN[0] * 8f * dt
            }
        }
        // Character bodies are moved by this controller instead of the solver, so the contact based
        // grounded flag never fires: publish the probe result on the body for scripts, telemetry and
        // `Rigidbody2D.grounded`.
        rb?.body?.grounded = grounded || climbing
        if (wasGrounded && !grounded && !climbing) coyoteTimer = coyoteTime

        // ---- wall detection
        if (!grounded && !climbing) {
            val wallHitR = world.circleCastIgnore(curX, curY, curX + radius + 0.06f, curY, radius * 0.95f, go, -1)
            val wallHitL = world.circleCastIgnore(curX, curY, curX - radius - 0.06f, curY, radius * 0.95f, go, -1)
            if (wallHitR != null && wallHitR.body?.go?.tag != ladderTag) onWall = 1
            else if (wallHitL != null && wallHitL.body?.go?.tag != ladderTag) onWall = -1
        }

        updatePlatformCarry(world, col, radius)

        if (autoFlipSprite) {
            val sr = go.get<com.sengine.engine.core.SpriteRenderer>()
            if (sr != null && abs(vx) > 0.2f) sr.flipX = vx < 0f
        }
    }

    private var groundedPatience = false

    /** True when the object is inside a ladder trigger (tag based). */
    private fun isOnLadder(scene: com.sengine.engine.core.Scene): Boolean {
        val x = go.worldX(); val y = go.worldY()
        for (other in scene.objects) {
            if (!other.isActiveInHierarchy() || other === go) continue
            if (other.tag != ladderTag) continue
            val col = other.get<Collider2D>() ?: continue
            val c = col.worldCenter()
            val hx = if (col.shape == Collider2D.SHAPE_CIRCLE) col.radius else col.width * 0.5f
            val hy = if (col.shape == Collider2D.SHAPE_CIRCLE) col.radius else col.height * 0.5f
            if (x >= c[0] - hx && x <= c[0] + hx && y >= c[1] - hy && y <= c[1] + hy) return true
        }
        return false
    }

    private fun updateClimb(dt: Float) {
        grounded = false
        jumpsUsed = 0
        val targetY = input.moveY * climbSpeed
        val targetX = input.moveX * climbSpeed * 0.6f
        vx = M.moveTowards(vx, targetX, acceleration * dt)
        vy = M.moveTowards(vy, targetY, acceleration * dt)
        if (input.jumpPressed) {
            climbing = false
            vy = jumpForce
            jumpsUsed = 1
        }
    }

    /** Sweeps the character circle along (dx, dy), sliding along obstacles. */
    private inline fun moveSweep(
        world: PhysicsWorld2D, radius: Float, startX: Float, startY: Float, dx: Float, dy: Float,
        iterations: Int, apply: (Float, Float, MoveResult) -> Unit
    ) {
        result.hitX = false
        result.hitY = false
        result.hitCeiling = false
        result.hitWall = false
        var remainingX = dx
        var remainingY = dy
        var curX = startX
        var curY = startY
        var i = 0
        while (i < iterations && (abs(remainingX) > 1e-5f || abs(remainingY) > 1e-5f)) {
            val hit = world.circleCastIgnore(curX, curY, curX + remainingX, curY + remainingY, radius * 0.98f, go, -1)
            if (hit == null) {
                curX += remainingX
                curY += remainingY
                break
            }
            val safe = max(0f, hit.fraction - 0.001f)
            curX += remainingX * safe
            curY += remainingY * safe
            val nx = hit.normalX
            val ny = hit.normalY
            val consumed = 1f - safe
            val remX = remainingX * consumed
            val remY = remainingY * consumed
            // project the remaining motion onto the surface
            val dot = remX * nx + remY * ny
            remainingX = remX - dot * nx
            remainingY = remY - dot * ny
            if (abs(nx) > abs(ny)) {
                if (nx * (dx) < 0f) result.hitX = true
                result.hitWall = true
                result.wallX = nx; result.wallY = ny
            } else {
                if (ny * dy < 0f) result.hitY = true
                if (ny < -0.5f) result.hitCeiling = true
            }
            // try to step up small ledges instead of stopping
            if (result.hitWall && stepHeight > 0f) {
                val stepHit = world.circleCastIgnore(curX, curY + stepHeight, curX, curY + stepHeight - 0.02f, radius * 0.98f, go, -1)
                if (stepHit == null) {
                    val freeUp = world.circleCastIgnore(curX, curY, curX, curY + stepHeight, radius * 0.98f, go, -1)
                    if (freeUp == null) {
                        curY += stepHeight * 0.98f
                        result.hitWall = false
                        result.hitX = false
                    }
                }
            }
            i++
        }
        result.movedX = curX - startX
        result.movedY = curY - startY
        apply(curX, curY, result)
    }

    /** Approximates the surface normal beneath the character with a few probes. */
    private fun surfaceNormal(world: PhysicsWorld2D, x: Float, y: Float, ignore: GameObject): FloatArray {
        val left = world.circleCastIgnore(x, y, x - 0.12f, y - 0.25f, 0.02f, ignore, -1)
        val right = world.circleCastIgnore(x, y, x + 0.12f, y - 0.25f, 0.02f, ignore, -1)
        val nl = left?.normalY ?: 1f
        val nr = right?.normalY ?: 1f
        val lx = left?.normalX ?: 0f
        val rx = right?.normalX ?: 0f
        var nx = (lx + rx) * 0.5f
        var ny = (nl + nr) * 0.5f
        val len = sqrt(nx * nx + ny * ny)
        if (len < 1e-4f) return floatArrayOf(0f, 1f)
        nx /= len; ny /= len
        return floatArrayOf(nx, ny)
    }

    /** Detects the moving platform under the character and tracks its delta for carrying. */
    private fun updatePlatformCarry(world: PhysicsWorld2D, col: Collider2D?, radius: Float) {
        platformDeltaX = 0f
        platformDeltaY = 0f
        groundedPatience = grounded
        if (!grounded) {
            wasOnPlatform = false
            return
        }
        val y = go.worldY()
        val hit = world.circleCastIgnore(go.worldX(), y, go.worldX(), y - radius - groundSnapDistance, radius * 0.98f, go, -1)
        val body = hit?.body
        if (body != null) {
            val mover = body.go.getAny<com.sengine.engine.core.MovingPlatform>()
            if (mover != null) {
                platformDeltaX = mover.deltaX
                platformDeltaY = mover.deltaY
                wasOnPlatform = true
                return
            }
            // kinematic body moved by a script/joint
            if (body.type == com.sengine.engine.physics.Body2D.Type.KINEMATIC) {
                platformDeltaX = body.vx * (1f / 60f)
                platformDeltaY = body.vy * (1f / 60f)
                wasOnPlatform = true
            }
        }
    }

    /** Applies an external knockback impulse (explosions, enemy hits). */
    fun knockback(dirX: Float, dirY: Float, strength: Float) {
        val len = sqrt(dirX * dirX + dirY * dirY)
        if (len < 1e-4f) return
        vx += dirX / len * strength
        vy += dirY / len * strength * 0.6f
        if (vy > 0f) jumpsUsed = max(jumpsUsed, 1)
        grounded = false
    }

    /** Teleports the character (respawn, checkpoints). */
    fun teleport(x: Float, y: Float) {
        go.setWorldPosition(x, y)
        vx = 0f; vy = 0f
        grounded = false
    }

    fun setMoveInput(axis: Float, jump: Boolean, jumpHeld: Boolean) {
        input.moveX = axis
        input.jumpPressed = jump
        input.jumpHeld = jumpHeld
    }

    // ---------------- helpers used by tests / AI
    /** Resets runtime state when play mode starts (called by the engine). */
    fun resetState() {
        vx = 0f; vy = 0f
        grounded = false
        onWall = 0
        climbing = false
        coyoteTimer = 0f
        jumpBuffer = 0f
        jumpsUsed = 0
        wallStickTimer = 0f
        dashTimer = 0f
        dashCooldownTimer = 0f
        platformDeltaX = 0f
        platformDeltaY = 0f
        wasOnPlatform = false
        oneWayIgnoreTimer = 0f
        facing = 1
        input = CharacterInput()
    }

    fun isOnFloor() = grounded
    fun state(): String = stateText()
}
