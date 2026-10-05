package com.sengine.engine.physics

import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Joint2D
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.core.Scene
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Deterministic fixed-step 2D physics for boxes and circles. The broad phase is
 * sweep-and-prune, collision filters are symmetric, rotated boxes use SAT, and
 * optional adaptive substeps reduce tunnelling for fast continuous bodies.
 *
 * This is a compact mobile-oriented solver, not a replacement for Box2D's full
 * contact manifold/joint solver. It intentionally stays strictly 2D.
 */
class PhysicsWorld {

    interface Listener {
        fun onCollisionEnter(a: GameObject, b: GameObject)
        fun onCollisionExit(a: GameObject, b: GameObject) {}
        fun onTriggerEnter(a: GameObject, b: GameObject)
        fun onTriggerExit(a: GameObject, b: GameObject)
    }

    data class Statistics(
        val bodies: Int = 0,
        val candidatePairs: Int = 0,
        val contacts: Int = 0,
        val triggers: Int = 0,
        val fixedSteps: Int = 0,
        val droppedSteps: Int = 0,
        val joints: Int = 0,
    )

    data class RaycastHit(
        val gameObject: GameObject,
        val pointX: Float,
        val pointY: Float,
        val normalX: Float,
        val normalY: Float,
        val distance: Float,
    )

    @Volatile var statistics = Statistics()
        private set

    var listener: Listener? = null
    private var accumulator = 0f
    private val fixedDt = 1f / 60f
    private val maxFixedSteps = 8

    private data class PairKey(val first: Long, val second: Long) {
        companion object {
            fun of(a: Long, b: Long) = if (a <= b) PairKey(a, b) else PairKey(b, a)
        }
    }

    private class Body(
        val go: GameObject,
        val rb: Rigidbody2D?,
        val col: Collider2D,
        var cx: Float = 0f,
        var cy: Float = 0f,
        var hw: Float = 0f,
        var hh: Float = 0f,
        var radius: Float = 0f,
        var ux: Float = 1f,
        var uy: Float = 0f,
        var vx: Float = 0f,
        var vy: Float = 1f,
        var minX: Float = 0f,
        var maxX: Float = 0f,
        var minY: Float = 0f,
        var maxY: Float = 0f,
    ) {
        val isCircle get() = col.shape == 1
        val invMass: Float
            get() = if (rb == null || rb.bodyType != 0) 0f else 1f / rb.mass.coerceAtLeast(0.001f)
    }

    private var previousContacts = HashSet<PairKey>()
    private var previousTriggers = HashSet<PairKey>()

    fun reset() {
        accumulator = 0f
        previousContacts.clear()
        previousTriggers.clear()
        statistics = Statistics()
    }

    fun step(scene: Scene, dt: Float) {
        if (dt <= 0f) return
        accumulator += dt.coerceAtMost(0.25f)
        var steps = 0
        var dropped = 0
        while (accumulator >= fixedDt && steps < maxFixedSteps) {
            fixedStep(scene, fixedDt)
            accumulator -= fixedDt
            steps++
        }
        if (accumulator >= fixedDt) {
            dropped = (accumulator / fixedDt).toInt()
            accumulator %= fixedDt
        }
        val old = statistics
        statistics = old.copy(fixedSteps = steps, droppedSteps = dropped)
    }

    private fun fixedStep(scene: Scene, dt: Float) {
        val continuous = scene.objects.asSequence()
            .filter { it.isActiveInHierarchy() }
            .mapNotNull { go ->
                val rb = go.get<Rigidbody2D>() ?: return@mapNotNull null
                val col = go.get<Collider2D>() ?: return@mapNotNull null
                if (!rb.continuous || rb.bodyType != 0) return@mapNotNull null
                val w = go.computeWorld()
                val featureSize = if (col.shape == 1) col.radius.coerceAtLeast(0.001f) * max(w.scaleX, w.scaleY) * 2f
                else min(col.width.coerceAtLeast(0.01f) * w.scaleX, col.height.coerceAtLeast(0.01f) * w.scaleY)
                val ax = rb.vx + scene.gravityX * rb.gravityScale
                val ay = rb.vy + scene.gravityY * rb.gravityScale
                val speed = sqrt(ax * ax + ay * ay)
                (speed * dt / max(featureSize * 0.5f, 0.02f)).toInt() + 1
            }
            .maxOrNull() ?: 1
        val substeps = continuous.coerceIn(1, 8)
        val subDt = dt / substeps
        for (go in scene.objects) go.get<Rigidbody2D>()?.grounded = false

        var contactsAtEnd = HashSet<PairKey>()
        var triggersAtEnd = HashSet<PairKey>()
        val seenContacts = HashSet<PairKey>()
        val seenTriggers = HashSet<PairKey>()
        val activeJoints = scene.objects.filter { it.isActiveInHierarchy() && it.get<Joint2D>() != null }
        val nonCollidingJointPairs = HashSet<PairKey>()
        for (go in activeJoints) {
            val joint = go.get<Joint2D>() ?: continue
            if (joint.collideConnected) continue
            val other = if (joint.connectedBody.isBlank()) null else scene.find(joint.connectedBody)
            if (other != null && other !== go) nonCollidingJointPairs.add(PairKey.of(go.id, other.id))
        }
        applySpringForces(scene, dt)
        var bodyCount = 0
        var candidateCount = 0

        repeat(substeps) {
            integrate(scene, subDt)
            solveHardJoints(scene)
            val bodies = collectBodies(scene)
            bodyCount = bodies.size
            val contacts = HashSet<PairKey>()
            val triggers = HashSet<PairKey>()
            candidateCount += detectAndResolve(bodies, contacts, triggers, seenContacts, seenTriggers, nonCollidingJointPairs)
            contactsAtEnd = contacts
            triggersAtEnd = triggers
        }

        val endedContacts = (previousContacts - contactsAtEnd) + (seenContacts - previousContacts - contactsAtEnd)
        for (key in endedContacts) {
            val a = scene.findById(key.first)
            val b = scene.findById(key.second)
            if (a != null && b != null) listener?.onCollisionExit(a, b)
        }
        val endedTriggers = (previousTriggers - triggersAtEnd) + (seenTriggers - previousTriggers - triggersAtEnd)
        for (key in endedTriggers) {
            val a = scene.findById(key.first)
            val b = scene.findById(key.second)
            if (a != null && b != null) listener?.onTriggerExit(a, b)
        }
        previousContacts = contactsAtEnd
        previousTriggers = triggersAtEnd
        for (go in scene.objects) go.get<Rigidbody2D>()?.clearForces()
        statistics = statistics.copy(
            bodies = bodyCount,
            candidatePairs = candidateCount,
            contacts = contactsAtEnd.size,
            triggers = triggersAtEnd.size,
            joints = activeJoints.size,
        )
    }

    /** Applies critically-damped spring forces once per fixed step; substeps integrate the same force. */
    private fun applySpringForces(scene: Scene, dt: Float) {
        for (owner in scene.objects) {
            if (!owner.isActiveInHierarchy()) continue
            val joint = owner.get<Joint2D>() ?: continue
            if (joint.jointType != 2 || joint.frequency <= 0f || dt <= 0f) continue
            val connected = if (joint.connectedBody.isBlank()) null else scene.find(joint.connectedBody)
            if (joint.connectedBody.isNotBlank() && (connected == null || !connected.isActiveInHierarchy())) continue
            if (connected === owner) continue
            val rbA = owner.get<Rigidbody2D>()
            val rbB = connected?.get<Rigidbody2D>()
            val invA = inverseMass(rbA)
            val invB = inverseMass(rbB)
            val invSum = invA + invB
            if (invSum <= 0f) continue
            val (ax, ay) = worldAnchor(owner, joint.anchorX, joint.anchorY)
            val (bx, by) = if (connected == null) joint.connectedAnchorX to joint.connectedAnchorY
                else worldAnchor(connected, joint.connectedAnchorX, joint.connectedAnchorY)
            val dx = bx - ax; val dy = by - ay
            val distance = sqrt(dx * dx + dy * dy)
            if (distance < 1e-5f && joint.length <= 0f) continue
            val nx = if (distance < 1e-5f) 1f else dx / distance
            val ny = if (distance < 1e-5f) 0f else dy / distance
            val (avx, avy) = anchorVelocity(owner, rbA, ax, ay)
            val (bvx, bvy) = if (connected == null) 0f to 0f else anchorVelocity(connected, rbB, bx, by)
            val relativeSpeed = (bvx - avx) * nx + (bvy - avy) * ny
            val effectiveMass = 1f / invSum
            val omega = (2.0 * Math.PI * joint.frequency).toFloat()
            val stiffness = effectiveMass * omega * omega
            val damping = 2f * joint.dampingRatio * effectiveMass * omega
            val force = (stiffness * (distance - joint.length) + damping * relativeSpeed)
                .coerceIn(-joint.maxForce, joint.maxForce)
            applyForceAt(owner, rbA, ax, ay, nx * force, ny * force)
            if (connected != null) applyForceAt(connected, rbB, bx, by, -nx * force, -ny * force)
        }
    }

    /** Position/velocity projection for distance and pin constraints. */
    private fun solveHardJoints(scene: Scene) {
        repeat(3) {
            for (owner in scene.objects) {
                if (!owner.isActiveInHierarchy()) continue
                val joint = owner.get<Joint2D>() ?: continue
                if (joint.jointType == 2) continue
                val connected = if (joint.connectedBody.isBlank()) null else scene.find(joint.connectedBody)
                if (joint.connectedBody.isNotBlank() && (connected == null || !connected.isActiveInHierarchy())) continue
                if (connected === owner) continue
                val rbA = owner.get<Rigidbody2D>()
                val rbB = connected?.get<Rigidbody2D>()
                val invA = inverseMass(rbA)
                val invB = inverseMass(rbB)
                val invSum = invA + invB
                if (invSum <= 0f) continue
                val (ax, ay) = worldAnchor(owner, joint.anchorX, joint.anchorY)
                val (bx, by) = if (connected == null) joint.connectedAnchorX to joint.connectedAnchorY
                    else worldAnchor(connected, joint.connectedAnchorX, joint.connectedAnchorY)
                val dx = bx - ax; val dy = by - ay
                val distance = sqrt(dx * dx + dy * dy)
                val (avx, avy) = anchorVelocity(owner, rbA, ax, ay)
                val (bvx, bvy) = if (connected == null) 0f to 0f else anchorVelocity(connected, rbB, bx, by)
                val targetLength = if (joint.jointType == 1) 0f else joint.length.coerceAtLeast(0f)
                var nx: Float
                var ny: Float
                if (distance > 1e-5f) { nx = dx / distance; ny = dy / distance }
                else if (targetLength > 0f) { nx = 1f; ny = 0f }
                else {
                    val rvx = bvx - avx; val rvy = bvy - avy
                    val speed = sqrt(rvx * rvx + rvy * rvy)
                    if (speed < 1e-5f) continue
                    nx = rvx / speed; ny = rvy / speed
                }
                val error = distance - targetLength
                if (abs(error) > 0.0005f) {
                    val correction = error * 0.75f / invSum
                    if (invA > 0f) moveWorld(owner, nx * correction * invA, ny * correction * invA)
                    if (invB > 0f && connected != null) moveWorld(connected, -nx * correction * invB, -ny * correction * invB)
                }
                val relSpeed = (bvx - avx) * nx + (bvy - avy) * ny
                if (abs(relSpeed) > 1e-5f) {
                    val impulse = relSpeed / invSum
                    if (invA > 0f && rbA != null) { rbA.vx += nx * impulse * invA; rbA.vy += ny * impulse * invA }
                    if (invB > 0f && rbB != null) { rbB.vx -= nx * impulse * invB; rbB.vy -= ny * impulse * invB }
                }
            }
        }
    }

    private fun inverseMass(rb: Rigidbody2D?): Float =
        if (rb == null || rb.bodyType != 0) 0f else 1f / rb.mass.coerceAtLeast(0.001f)

    private fun worldAnchor(go: GameObject, x: Float, y: Float): Pair<Float, Float> {
        val world = go.computeWorld()
        return world.mapX(x, y) to world.mapY(x, y)
    }

    private fun anchorVelocity(go: GameObject, rb: Rigidbody2D?, x: Float, y: Float): Pair<Float, Float> {
        if (rb == null || rb.bodyType == 2) return 0f to 0f
        val world = go.computeWorld()
        val rx = x - world.tx; val ry = y - world.ty
        val omega = Math.toRadians(rb.angularVelocity.toDouble()).toFloat()
        return (rb.vx - omega * ry) to (rb.vy + omega * rx)
    }

    private fun applyForceAt(go: GameObject, rb: Rigidbody2D?, x: Float, y: Float, fx: Float, fy: Float) {
        if (rb == null || rb.bodyType != 0) return
        rb.applyForce(fx, fy)
        val world = go.computeWorld()
        rb.applyTorque((x - world.tx) * fy - (y - world.ty) * fx)
    }

    private fun integrate(scene: Scene, dt: Float) {
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            val rb = go.get<Rigidbody2D>() ?: continue
            when (rb.bodyType) {
                0 -> {
                    val mass = rb.mass.coerceAtLeast(0.001f)
                    rb.vx += (scene.gravityX * rb.gravityScale + rb.forceX / mass) * dt
                    rb.vy += (scene.gravityY * rb.gravityScale + rb.forceY / mass) * dt
                    val inertia = momentOfInertia(go, rb)
                    if (inertia > 0f) rb.angularVelocity += Math.toDegrees((rb.torque / inertia * dt).toDouble()).toFloat()
                    if (rb.drag > 0f) {
                        val k = exp(-rb.drag * dt)
                        rb.vx *= k; rb.vy *= k
                    }
                    if (rb.angularDrag > 0f) rb.angularVelocity *= exp(-rb.angularDrag * dt)
                    moveWorld(go, rb.vx * dt, rb.vy * dt)
                    go.rotation += rb.angularVelocity * dt
                }
                1 -> {
                    moveWorld(go, rb.vx * dt, rb.vy * dt)
                    go.rotation += rb.angularVelocity * dt
                }
                else -> Unit
            }
        }
    }

    private fun momentOfInertia(go: GameObject, rb: Rigidbody2D): Float {
        val col = go.get<Collider2D>() ?: return rb.mass.coerceAtLeast(0.001f)
        val w = go.computeWorld()
        val mass = rb.mass.coerceAtLeast(0.001f)
        val inertia = if (col.shape == 1) {
            val radius = col.radius.coerceAtLeast(0.001f) * max(w.scaleX, w.scaleY)
            0.5f * mass * radius * radius
        } else {
            val width = col.width * w.scaleX
            val height = col.height * w.scaleY
            mass * (width * width + height * height) / 12f
        }
        return inertia.coerceAtLeast(0.0001f)
    }

    private fun collectBodies(scene: Scene): MutableList<Body> {
        val bodies = ArrayList<Body>()
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            val col = go.get<Collider2D>() ?: continue
            val body = Body(go, go.get<Rigidbody2D>(), col)
            refresh(body)
            bodies.add(body)
        }
        bodies.sortBy { it.minX }
        return bodies
    }

    private fun refresh(b: Body) {
        val w = b.go.computeWorld()
        b.cx = w.mapX(b.col.offsetX, b.col.offsetY)
        b.cy = w.mapY(b.col.offsetX, b.col.offsetY)
        if (b.isCircle) {
            b.radius = b.col.radius.coerceAtLeast(0.001f) * max(w.scaleX, w.scaleY)
            b.hw = b.radius; b.hh = b.radius
            b.minX = b.cx - b.radius; b.maxX = b.cx + b.radius
            b.minY = b.cy - b.radius; b.maxY = b.cy + b.radius
        } else {
            val radians = Math.toRadians(w.rotationDeg.toDouble())
            b.ux = cos(radians).toFloat(); b.uy = sin(radians).toFloat()
            b.vx = -b.uy; b.vy = b.ux
            b.hw = b.col.width.coerceAtLeast(0.01f) * w.scaleX * 0.5f
            b.hh = b.col.height.coerceAtLeast(0.01f) * w.scaleY * 0.5f
            val ex = abs(b.ux) * b.hw + abs(b.vx) * b.hh
            val ey = abs(b.uy) * b.hw + abs(b.vy) * b.hh
            b.minX = b.cx - ex; b.maxX = b.cx + ex
            b.minY = b.cy - ey; b.maxY = b.cy + ey
        }
    }

    /** Sweep-and-prune broad phase; narrow-phase remains order-stable. */
    private fun detectAndResolve(
        sortedBodies: List<Body>,
        contacts: MutableSet<PairKey>,
        triggers: MutableSet<PairKey>,
        seenContacts: MutableSet<PairKey>,
        seenTriggers: MutableSet<PairKey>,
        nonCollidingJointPairs: Set<PairKey>,
    ): Int {
        val active = ArrayList<Body>()
        var candidates = 0
        for (b in sortedBodies) {
            var index = active.size - 1
            while (index >= 0) {
                if (active[index].maxX < b.minX) active.removeAt(index)
                index--
            }
            for (a in active) {
                if (a.maxY < b.minY || b.maxY < a.minY) continue
                if (PairKey.of(a.go.id, b.go.id) in nonCollidingJointPairs) continue
                if (!layersAllow(a.col, b.col)) continue
                if (a.invMass == 0f && b.invMass == 0f && !a.col.isTrigger && !b.col.isTrigger) continue
                candidates++
                val manifold = collide(a, b) ?: continue
                val key = PairKey.of(a.go.id, b.go.id)
                if (a.col.isTrigger || b.col.isTrigger) {
                    triggers.add(key)
                    if (key !in previousTriggers && seenTriggers.add(key)) listener?.onTriggerEnter(a.go, b.go)
                } else {
                    contacts.add(key)
                    if (key !in previousContacts && seenContacts.add(key)) listener?.onCollisionEnter(a.go, b.go)
                    resolve(a, b, manifold)
                    refresh(a); refresh(b)
                }
            }
            active.add(b)
        }
        return candidates
    }

    private fun layersAllow(a: Collider2D, b: Collider2D): Boolean =
        (a.collisionMask and b.collisionLayer) != 0 && (b.collisionMask and a.collisionLayer) != 0

    private class Manifold(val nx: Float, val ny: Float, val depth: Float)

    /** Normal points from a to b. */
    private fun collide(a: Body, b: Body): Manifold? = when {
        !a.isCircle && !b.isCircle -> boxBox(a, b)
        a.isCircle && b.isCircle -> circleCircle(a, b)
        !a.isCircle && b.isCircle -> boxCircle(a, b)
        else -> boxCircle(b, a)?.let { Manifold(-it.nx, -it.ny, it.depth) }
    }

    private fun boxBox(a: Body, b: Body): Manifold? {
        val dx = b.cx - a.cx
        val dy = b.cy - a.cy
        var bestOverlap = Float.POSITIVE_INFINITY
        var bestX = 0f
        var bestY = 0f
        val axes = arrayOf(a.ux to a.uy, a.vx to a.vy, b.ux to b.uy, b.vx to b.vy)
        for ((ax, ay) in axes) {
            val centerDistance = dx * ax + dy * ay
            val ra = a.hw * abs(a.ux * ax + a.uy * ay) + a.hh * abs(a.vx * ax + a.vy * ay)
            val rb = b.hw * abs(b.ux * ax + b.uy * ay) + b.hh * abs(b.vx * ax + b.vy * ay)
            val overlap = ra + rb - abs(centerDistance)
            if (overlap <= 0f) return null
            if (overlap < bestOverlap) {
                bestOverlap = overlap
                val sign = if (centerDistance < 0f) -1f else 1f
                bestX = ax * sign; bestY = ay * sign
            }
        }
        return Manifold(bestX, bestY, bestOverlap)
    }

    private fun circleCircle(a: Body, b: Body): Manifold? {
        val dx = b.cx - a.cx
        val dy = b.cy - a.cy
        val sum = a.radius + b.radius
        val distanceSq = dx * dx + dy * dy
        if (distanceSq >= sum * sum) return null
        val distance = sqrt(distanceSq)
        return if (distance < 1e-5f) Manifold(0f, 1f, sum)
        else Manifold(dx / distance, dy / distance, sum - distance)
    }

    private fun boxCircle(box: Body, circle: Body): Manifold? {
        val dx = circle.cx - box.cx
        val dy = circle.cy - box.cy
        val localX = dx * box.ux + dy * box.uy
        val localY = dx * box.vx + dy * box.vy
        val qx = localX.coerceIn(-box.hw, box.hw)
        val qy = localY.coerceIn(-box.hh, box.hh)
        val px = box.cx + box.ux * qx + box.vx * qy
        val py = box.cy + box.uy * qx + box.vy * qy
        var nx = circle.cx - px
        var ny = circle.cy - py
        val d2 = nx * nx + ny * ny
        if (d2 > circle.radius * circle.radius) return null
        if (d2 < 1e-8f) {
            val edgeX = box.hw - abs(localX)
            val edgeY = box.hh - abs(localY)
            if (edgeX < edgeY) {
                val sign = if (localX < 0f) -1f else 1f
                return Manifold(box.ux * sign, box.uy * sign, edgeX + circle.radius)
            }
            val sign = if (localY < 0f) -1f else 1f
            return Manifold(box.vx * sign, box.vy * sign, edgeY + circle.radius)
        }
        val d = sqrt(d2)
        nx /= d; ny /= d
        return Manifold(nx, ny, circle.radius - d)
    }

    private fun resolve(a: Body, b: Body, manifold: Manifold) {
        val ia = a.invMass
        val ib = b.invMass
        val sum = ia + ib
        if (sum == 0f) return

        val correction = max(manifold.depth - 0.001f, 0f) * 0.8f / sum
        if (ia > 0f) moveWorld(a.go, -manifold.nx * correction * ia, -manifold.ny * correction * ia)
        if (ib > 0f) moveWorld(b.go, manifold.nx * correction * ib, manifold.ny * correction * ib)

        // The solver convention has positive Y upward. A negative contact normal
        // means the first body is supported by the second body.
        if (manifold.ny < -0.5f) a.rb?.grounded = true
        if (manifold.ny > 0.5f) b.rb?.grounded = true

        val avx = a.rb?.takeIf { it.bodyType != 2 }?.vx ?: 0f
        val avy = a.rb?.takeIf { it.bodyType != 2 }?.vy ?: 0f
        val bvx = b.rb?.takeIf { it.bodyType != 2 }?.vx ?: 0f
        val bvy = b.rb?.takeIf { it.bodyType != 2 }?.vy ?: 0f
        val rvx = bvx - avx
        val rvy = bvy - avy
        val normalVelocity = rvx * manifold.nx + rvy * manifold.ny
        if (normalVelocity > 0f) return

        val restitution = max(a.rb?.bounciness ?: 0f, b.rb?.bounciness ?: 0f).coerceIn(0f, 1f)
        val impulseMagnitude = -(1f + restitution) * normalVelocity / sum
        applyImpulse(a, -impulseMagnitude * manifold.nx, -impulseMagnitude * manifold.ny)
        applyImpulse(b, impulseMagnitude * manifold.nx, impulseMagnitude * manifold.ny)

        val tx = -manifold.ny
        val ty = manifold.nx
        val tangentVelocity = rvx * tx + rvy * ty
        var tangentImpulse = -tangentVelocity / sum
        val friction = sqrt(max(0f, a.rb?.friction ?: 0.4f) * max(0f, b.rb?.friction ?: 0.4f))
        val maximumFriction = abs(impulseMagnitude) * friction
        tangentImpulse = tangentImpulse.coerceIn(-maximumFriction, maximumFriction)
        applyImpulse(a, -tangentImpulse * tx, -tangentImpulse * ty)
        applyImpulse(b, tangentImpulse * tx, tangentImpulse * ty)
    }

    private fun applyImpulse(body: Body, impulseX: Float, impulseY: Float) {
        val inverseMass = body.invMass
        val rb = body.rb ?: return
        if (inverseMass == 0f) return
        rb.vx += impulseX * inverseMass
        rb.vy += impulseY * inverseMass
    }

    private fun moveWorld(go: GameObject, dx: Float, dy: Float) {
        val w = go.computeWorld()
        go.setWorldPosition(w.tx + dx, w.ty + dy)
    }

    fun areOverlapping(first: GameObject, second: GameObject): Boolean {
        if (first === second || !first.isActiveInHierarchy() || !second.isActiveInHierarchy()) return false
        val aCol = first.get<Collider2D>() ?: return false
        val bCol = second.get<Collider2D>() ?: return false
        if (!layersAllow(aCol, bCol)) return false
        val a = Body(first, first.get<Rigidbody2D>(), aCol)
        val b = Body(second, second.get<Rigidbody2D>(), bCol)
        refresh(a); refresh(b)
        if (a.maxX < b.minX || b.maxX < a.minX || a.maxY < b.minY || b.maxY < a.minY) return false
        return collide(a, b) != null
    }

    /** First active collider containing the world point, in reverse scene draw order. */
    fun overlapPoint(scene: Scene, x: Float, y: Float, layerMask: Int = -1): GameObject? {
        for (go in scene.objects.asReversed()) {
            if (!go.isActiveInHierarchy()) continue
            val col = go.get<Collider2D>() ?: continue
            if ((col.collisionLayer and layerMask) == 0) continue
            val body = Body(go, go.get<Rigidbody2D>(), col)
            refresh(body)
            if (contains(body, x, y)) return go
        }
        return null
    }

    fun overlapCircle(scene: Scene, x: Float, y: Float, radius: Float, layerMask: Int = -1): List<GameObject> {
        val r = radius.coerceAtLeast(0f)
        val out = ArrayList<GameObject>()
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            val col = go.get<Collider2D>() ?: continue
            if ((col.collisionLayer and layerMask) == 0) continue
            val body = Body(go, go.get<Rigidbody2D>(), col)
            refresh(body)
            val hit = if (body.isCircle) {
                val dx = body.cx - x; val dy = body.cy - y
                dx * dx + dy * dy <= (body.radius + r) * (body.radius + r)
            } else {
                val dx = x - body.cx; val dy = y - body.cy
                val lx = (dx * body.ux + dy * body.uy).coerceIn(-body.hw, body.hw)
                val ly = (dx * body.vx + dy * body.vy).coerceIn(-body.hh, body.hh)
                val qx = dx - body.ux * lx - body.vx * ly
                val qy = dy - body.uy * lx - body.vy * ly
                qx * qx + qy * qy <= r * r
            }
            if (hit) out.add(go)
        }
        return out
    }

    fun overlapArea(scene: Scene, x: Float, y: Float, width: Float, height: Float, layerMask: Int = -1): List<GameObject> {
        val halfW = abs(width) * 0.5f
        val halfH = abs(height) * 0.5f
        val out = ArrayList<GameObject>()
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            val col = go.get<Collider2D>() ?: continue
            if ((col.collisionLayer and layerMask) == 0) continue
            val body = Body(go, go.get<Rigidbody2D>(), col)
            refresh(body)
            if (body.maxX < x - halfW || body.minX > x + halfW || body.maxY < y - halfH || body.minY > y + halfH) continue
            val hit = if (body.isCircle) {
                val qx = body.cx.coerceIn(x - halfW, x + halfW)
                val qy = body.cy.coerceIn(y - halfH, y + halfH)
                val dx = body.cx - qx; val dy = body.cy - qy
                dx * dx + dy * dy <= body.radius * body.radius
            } else {
                val dx = body.cx - x; val dy = body.cy - y
                val axes = arrayOf(1f to 0f, 0f to 1f, body.ux to body.uy, body.vx to body.vy)
                axes.all { (ax, ay) ->
                    val distance = abs(dx * ax + dy * ay)
                    val queryRadius = halfW * abs(ax) + halfH * abs(ay)
                    val bodyRadius = body.hw * abs(body.ux * ax + body.uy * ay) +
                        body.hh * abs(body.vx * ax + body.vy * ay)
                    distance <= queryRadius + bodyRadius
                }
            }
            if (hit) out.add(go)
        }
        return out
    }

    /** Raycast against the nearest enabled collider. Direction need not be normalized. */
    fun raycast(
        scene: Scene,
        originX: Float,
        originY: Float,
        directionX: Float,
        directionY: Float,
        maxDistance: Float,
        layerMask: Int = -1,
        ignoreId: Long = -1L,
    ): RaycastHit? = cast(scene, originX, originY, directionX, directionY, maxDistance, 0f, layerMask, ignoreId)

    /** Sweeps a circle along a ray; box corner expansion is conservative. */
    fun circleCast(
        scene: Scene,
        originX: Float,
        originY: Float,
        directionX: Float,
        directionY: Float,
        maxDistance: Float,
        radius: Float,
        layerMask: Int = -1,
        ignoreId: Long = -1L,
    ): RaycastHit? = cast(scene, originX, originY, directionX, directionY, maxDistance, radius.coerceAtLeast(0f), layerMask, ignoreId)

    private fun cast(
        scene: Scene,
        originX: Float,
        originY: Float,
        directionX: Float,
        directionY: Float,
        maxDistance: Float,
        castRadius: Float,
        layerMask: Int,
        ignoreId: Long,
    ): RaycastHit? {
        if (maxDistance < 0f) return null
        val length = sqrt(directionX * directionX + directionY * directionY)
        if (length < 1e-7f) return null
        val dx = directionX / length
        val dy = directionY / length
        var nearest: RaycastHit? = null
        for (go in scene.objects) {
            if (go.id == ignoreId || !go.isActiveInHierarchy()) continue
            val col = go.get<Collider2D>() ?: continue
            if ((col.collisionLayer and layerMask) == 0) continue
            val body = Body(go, go.get<Rigidbody2D>(), col)
            refresh(body)
            val hit = if (body.isCircle) {
                rayCircle(body, originX, originY, dx, dy, maxDistance, castRadius)
            } else {
                rayBox(body, originX, originY, dx, dy, maxDistance, castRadius)
            } ?: continue
            if (nearest == null || hit.distance < nearest.distance) nearest = hit
        }
        return nearest
    }

    private fun rayCircle(body: Body, ox: Float, oy: Float, dx: Float, dy: Float, maxDistance: Float, inflate: Float): RaycastHit? {
        val radius = body.radius + inflate
        val mx = ox - body.cx
        val my = oy - body.cy
        val c = mx * mx + my * my - radius * radius
        var distance: Float
        if (c <= 0f) distance = 0f
        else {
            val b = mx * dx + my * dy
            val discriminant = b * b - c
            if (discriminant < 0f) return null
            distance = -b - sqrt(discriminant)
            if (distance < 0f) distance = -b + sqrt(discriminant)
            if (distance < 0f || distance > maxDistance) return null
        }
        val px = ox + dx * distance
        val py = oy + dy * distance
        var nx = px - body.cx
        var ny = py - body.cy
        val n = sqrt(nx * nx + ny * ny)
        if (n > 1e-7f) { nx /= n; ny /= n } else { nx = -dx; ny = -dy }
        return RaycastHit(body.go, px, py, nx, ny, distance)
    }

    private fun rayBox(body: Body, ox: Float, oy: Float, dx: Float, dy: Float, maxDistance: Float, inflate: Float): RaycastHit? {
        val relX = ox - body.cx
        val relY = oy - body.cy
        val px = relX * body.ux + relY * body.uy
        val py = relX * body.vx + relY * body.vy
        val vx = dx * body.ux + dy * body.uy
        val vy = dx * body.vx + dy * body.vy
        val halfW = body.hw + inflate
        val halfH = body.hh + inflate
        var enter = 0f
        var exit = maxDistance
        var normalX = 0f
        var normalY = 0f

        fun slab(position: Float, velocity: Float, halfSize: Float, axisX: Float, axisY: Float): Boolean {
            if (abs(velocity) < 1e-8f) return position in -halfSize..halfSize
            var t1 = (-halfSize - position) / velocity
            var t2 = (halfSize - position) / velocity
            var sign = -1f
            if (t1 > t2) { val swap = t1; t1 = t2; t2 = swap; sign = 1f }
            if (t1 > enter) {
                enter = t1
                normalX = axisX * sign
                normalY = axisY * sign
            }
            exit = min(exit, t2)
            return enter <= exit
        }

        if (!slab(px, vx, halfW, body.ux, body.uy)) return null
        if (!slab(py, vy, halfH, body.vx, body.vy)) return null
        if (exit < 0f || enter > maxDistance) return null
        val distance = enter.coerceAtLeast(0f)
        if (distance == 0f && normalX == 0f && normalY == 0f) { normalX = -dx; normalY = -dy }
        return RaycastHit(body.go, ox + dx * distance, oy + dy * distance, normalX, normalY, distance)
    }

    private fun contains(body: Body, x: Float, y: Float): Boolean {
        val dx = x - body.cx; val dy = y - body.cy
        if (body.isCircle) return dx * dx + dy * dy <= body.radius * body.radius
        return abs(dx * body.ux + dy * body.uy) <= body.hw && abs(dx * body.vx + dy * body.vy) <= body.hh
    }
}
