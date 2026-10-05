package com.sengine.engine.physics

import com.sengine.engine.core.Component
import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.Prop
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.core.Scene
import kotlin.math.*

/**
 * Character controller component for platformers, top-down games, etc.
 * Provides grounded detection, wall detection, step-up, and gravity control
 * without the complexity of a full rigidbody simulation.
 */
class CharacterBody : Component() {
    override val type = "CharacterBody"

    // Configuration
    var moveSpeed = 6f
    var jumpForce = 12f
    var gravityScale = 1f
    var maxFallSpeed = 30f
    var friction = 8f // ground deceleration
    var airFriction = 1f // air deceleration
    var jumpBufferTime = 0.12f // seconds
    var coyoteTime = 0.1f // seconds after leaving a ledge where you can still jump
    var skinWidth = 0.01f // penetration allowance
    var stepHeight = 0.3f // auto-step up small obstacles
    var wallSlideSpeed = 2f
    var wallJumpForceX = 8f
    var wallJumpForceY = 10f
    var wallClingDelay = 0.1f

    // Runtime
    var vx = 0f
    var vy = 0f
    var grounded = false
    var wallLeft = false
    var wallRight = false
    var onLadder = false
    var facingRight = true
    var jumpsLeft = 1
    var maxJumps = 1 // double-jump etc.

    // Internal timing
    var jumpBufferTimer = 0f
    var coyoteTimer = 0f
    var wallClingTimer = 0f
    private var lastGrounded = false

    override fun props() = listOf(
        Prop.F("Move Speed", { moveSpeed }, { moveSpeed = it.coerceAtLeast(0f) }),
        Prop.F("Jump Force", { jumpForce }, { jumpForce = it.coerceAtLeast(0f) }),
        Prop.F("Gravity Scale", { gravityScale }, { gravityScale = it }),
        Prop.F("Max Fall Speed", { maxFallSpeed }, { maxFallSpeed = it.coerceAtLeast(0f) }),
        Prop.F("Friction", { friction }, { friction = it.coerceAtLeast(0f) }),
        Prop.F("Air Friction", { airFriction }, { airFriction = it.coerceAtLeast(0f) }),
        Prop.F("Jump Buffer", { jumpBufferTime }, { jumpBufferTime = it.coerceIn(0f, 1f) }),
        Prop.F("Coyote Time", { coyoteTime }, { coyoteTime = it.coerceIn(0f, 1f) }),
        Prop.F("Step Height", { stepHeight }, { stepHeight = it.coerceIn(0f, 2f) }),
        Prop.F("Wall Slide Speed", { wallSlideSpeed }, { wallSlideSpeed = it.coerceAtLeast(0f) }),
        Prop.F("Wall Jump Force X", { wallJumpForceX }, { wallJumpForceX = it.coerceAtLeast(0f) }),
        Prop.F("Wall Jump Force Y", { wallJumpForceY }, { wallJumpForceY = it.coerceAtLeast(0f) }),
        Prop.I("Max Jumps", { maxJumps }, { maxJumps = it.coerceAtLeast(0) }),
    )

    override fun resetRuntime() {
        vx = 0f; vy = 0f
        grounded = false; wallLeft = false; wallRight = false
        onLadder = false; facingRight = true
        jumpsLeft = maxJumps
        jumpBufferTimer = 0f; coyoteTimer = 0f; wallClingTimer = 0f
    }

    /**
     * Move the character by horizontal input.
     * @param inputX -1..1 horizontal input
     */
    fun move(inputX: Float, dt: Float) {
        if (!enabled) return
        val targetVx = inputX * moveSpeed
        val frc = if (grounded) friction else airFriction
        vx += (targetVx - vx).coerceIn(-frc * dt * moveSpeed, frc * dt * moveSpeed)

        if (inputX > 0.01f) facingRight = true
        else if (inputX < -0.01f) facingRight = false
    }

    /**
     * Request a jump. Respects coyote time, jump buffer, and multi-jump.
     */
    fun jump() {
        if (!enabled) return
        jumpBufferTimer = jumpBufferTime
    }

    /**
     * Apply wall jump force.
     */
    fun wallJump(direction: Int) { // -1 = wall on left, 1 = wall on right
        if (!enabled) return
        vx = wallJumpForceX * -direction
        vy = wallJumpForceY
        grounded = false
        jumpsLeft = max(maxJumps - 1, 1)
    }

    /**
     * Update physics and collision for this frame.
     */
    fun updatePhysics(scene: Scene, dt: Float) {
        if (!enabled) return
        val go = gameObject
        lastGrounded = grounded

        // Gravity
        if (!onLadder) {
            vy += scene.gravityY * gravityScale * dt
            vy = vy.coerceAtLeast(-maxFallSpeed)
        } else {
            // Ladder: slow fall, allow vertical movement
            vy = vy.coerceIn(-moveSpeed, moveSpeed)
        }

        // Wall slide
        if (!grounded && (wallLeft || wallRight) && vy < 0f) {
            vy = max(vy, -wallSlideSpeed)
            wallClingTimer += dt
        } else {
            wallClingTimer = 0f
        }

        // Jump buffer countdown
        jumpBufferTimer -= dt

        // Coyote time
        if (grounded) {
            coyoteTimer = coyoteTime
            jumpsLeft = maxJumps
        } else {
            coyoteTimer -= dt
        }

        // Execute buffered jump
        if (jumpBufferTimer > 0f) {
            if (coyoteTimer > 0f || onLadder) {
                // Ground jump
                vy = jumpForce
                grounded = false
                coyoteTimer = 0f
                jumpBufferTimer = 0f
                jumpsLeft = max(maxJumps - 1, 0)
            } else if (wallLeft) {
                wallJump(-1)
                jumpBufferTimer = 0f
            } else if (wallRight) {
                wallJump(1)
                jumpBufferTimer = 0f
            } else if (jumpsLeft > 0) {
                // Air jump (double jump)
                vy = jumpForce * 0.85f
                jumpsLeft--
                jumpBufferTimer = 0f
            }
        }

        // Move and collide
        moveAndCollide(scene, vx * dt, vy * dt)

        // Update grounded state
        grounded = checkGrounded(scene)
        if (grounded && !lastGrounded) {
            jumpsLeft = maxJumps
        }

        // Check walls
        wallLeft = checkWall(scene, -1f, 0f)
        wallRight = checkWall(scene, 1f, 0f)
    }

    private fun moveAndCollide(scene: Scene, dx: Float, dy: Float) {
        val go = gameObject
        val col = go.getAny<Collider2D>() ?: return

        // Move horizontally
        if (dx != 0f) {
            go.x += dx
            if (checkCollision(scene)) {
                go.x -= dx
                // Try stepping up
                if (stepHeight > 0f && grounded) {
                    val savedY = go.y
                    go.y += stepHeight
                    go.x += dx
                    if (checkCollision(scene)) {
                        go.x -= dx
                        go.y = savedY
                    } else {
                        go.y -= stepHeight * 0.5f
                    }
                }
                vx = 0f
            }
        }

        // Move vertically
        if (dy != 0f) {
            go.y += dy
            if (checkCollision(scene)) {
                go.y -= dy
                if (dy < 0f) {
                    // Hit ground: snap down
                    grounded = true
                    vy = 0f
                } else {
                    // Hit ceiling
                    vy = 0f
                }
            }
        }
    }

    private fun checkCollision(scene: Scene): Boolean {
        val go = gameObject
        val col = go.getAny<Collider2D>() ?: return false
        val w = go.computeWorld()
        val cx = w.mapX(col.offsetX, col.offsetY)
        val cy = w.mapY(col.offsetX, col.offsetY)
        val sx = w.scaleX
        val sy = w.scaleY

        for (other in scene.objects) {
            if (other === go || !other.isActiveInHierarchy()) continue
            val otherCol = other.getAny<Collider2D>() ?: continue
            if (otherCol.isTrigger) continue
            val otherRb = other.getAny<Rigidbody2D>()
            // Only collide with static bodies or non-character bodies
            if (otherRb != null && otherRb.bodyType != 2 && other.get<CharacterBody>() == null) continue
            if (otherRb == null && other.get<CharacterBody>() == null) continue

            val ow = other.computeWorld()
            val ocx = ow.mapX(otherCol.offsetX, otherCol.offsetY)
            val ocy = ow.mapY(otherCol.offsetX, otherCol.offsetY)
            val osx = ow.scaleX
            val osy = ow.scaleY

            // AABB overlap
            if (col.shape == 0 && otherCol.shape == 0) {
                val hw = col.width * sx * 0.5f + skinWidth
                val hh = col.height * sy * 0.5f + skinWidth
                val ohw = otherCol.width * osx * 0.5f
                val ohh = otherCol.height * osy * 0.5f
                if (abs(cx - ocx) < hw + ohw && abs(cy - ocy) < hh + ohh) return true
            }
        }
        return false
    }

    private fun checkGrounded(scene: Scene): Boolean {
        val go = gameObject
        val col = go.getAny<Collider2D>() ?: return false
        val w = go.computeWorld()
        val cx = w.mapX(col.offsetX, col.offsetY)
        val cy = w.mapY(col.offsetX, col.offsetY)
        val sx = w.scaleX
        val sy = w.scaleY
        val hh = if (col.shape == 1) col.radius * max(sx, sy) else col.height * sy * 0.5f

        // Check slightly below
        val savedY = go.y
        go.y -= skinWidth * 2f
        val collides = checkCollision(scene)
        go.y = savedY
        return collides
    }

    private fun checkWall(scene: Scene, dirX: Float, dirY: Float): Boolean {
        val go = gameObject
        val col = go.getAny<Collider2D>() ?: return false
        val savedX = go.x
        go.x += dirX * skinWidth * 2f
        val collides = checkCollision(scene)
        go.x = savedX
        return collides
    }

    /** Set velocity directly. */
    fun setVelocity(newVx: Float, newVy: Float) {
        vx = newVx
        vy = newVy
    }

    /** Add an impulse to the character. */
    fun addImpulse(impulseX: Float, impulseY: Float) {
        vx += impulseX
        vy += impulseY
    }
}
