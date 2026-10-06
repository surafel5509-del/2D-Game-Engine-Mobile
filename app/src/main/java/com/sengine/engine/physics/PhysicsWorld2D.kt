package com.sengine.engine.physics

import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.core.JointComponent
import com.sengine.engine.core.JointFactory
import com.sengine.engine.core.Scene
import com.sengine.engine.core.SpriteRenderer
import com.sengine.engine.math.M
import com.sengine.engine.math.Rect2
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Impulse based 2D physics world with:
 * rotating convex shapes (SAT + face clipping manifolds), sequential impulse solver with warm
 * starting and Baumgarte bias, sleeping, continuous collision detection for fast bodies,
 * sensors/triggers, collision layers, joints and a full 2D query API (ray casts, shape casts,
 * overlap tests).
 *
 * The whole package is 2D only - there is no 3D physics anywhere in S Engine.
 */
class PhysicsWorld2D {

    interface Listener {
        fun onCollisionEnter(a: GameObject, b: GameObject, contact: Contact2D)
        fun onCollisionStay(a: GameObject, b: GameObject, contact: Contact2D) {}
        fun onCollisionExit(a: GameObject, b: GameObject) {}
        fun onTriggerEnter(a: GameObject, b: GameObject, contact: Contact2D)
        fun onTriggerStay(a: GameObject, b: GameObject, contact: Contact2D) {}
        fun onTriggerExit(a: GameObject, b: GameObject) {}
    }

    var listener: Listener? = null

    var gravityX = 0f
    var gravityY = -30f

    /** Solver iterations - 8 is plenty for platformers, 12-16 for stacks/vehicles. */
    var velocityIterations = 10
    var enableSleeping = true
    var timeScale = 1f
    /** Fixed simulation rate. */
    var fixedTimeStep = 1f / 60f
    var maxSubSteps = 5
    /** Uniform grid cell size for the broadphase (world units). */
    var broadphaseCellSize = 2f
    /**
     * Velocity ceilings (world units/s and rad/s). A physical limit keeps a deep penetration or a
     * bad joint solve from launching bodies at absurd speeds (and from reaching Infinity/NaN).
     * Sized well above any gameplay speed: 200 u/s is 720 km/h at 1 m per unit.
     */
    var maxLinearSpeed = 200f
    var maxAngularSpeed = 60f
    /** Baumgarte position correction factor (0..1). */
    var positionCorrection = 0.25f
    var slop = 0.005f
    var maxCorrection = 0.15f

    val bodies = ArrayList<Body2D>()
    val joints = ArrayList<Joint2D>()
    val stats = PhysicsStats()

    /** Removes a body (called when its object is destroyed). */
    fun removeBody(body: Body2D) {
        bodies.remove(body)
        bodyByObject.remove(body.go.id)
        joints.removeAll { it.a === body || it.b === body }
    }

    /** Bodies indexed by GameObject id. */
    private val bodyByObject = HashMap<Long, Body2D>()
    private var accumulator = 0f
    private var stepCount = 0L

    // contact sets for enter/exit events
    private val activeContacts = HashSet<Long>()
    private val activeSensors = HashSet<Long>()
    private val prevContacts = HashSet<Long>()
    private val prevSensors = HashSet<Long>()

    // broadphase scratch
    private val grid = HashMap<Long, MutableList<Body2D>>()
    private val candidatePairs = ArrayList<LongArray>()

    /** Set to true by the editor to draw contact points (filled during the step). */
    val debugContacts = ArrayList<FloatArray>(64)

    // ------------------------------------------------------------------ lifecycle
    fun reset() {
        bodies.clear()
        joints.clear()
        bodyByObject.clear()
        activeContacts.clear(); activeSensors.clear()
        prevContacts.clear(); prevSensors.clear()
        accumulator = 0f
        stepCount = 0
        stats.reset()
        debugContacts.clear()
    }

    fun bodyOf(go: GameObject): Body2D? = bodyByObject[go.id]

    /** One simulation step, driven by the engine. */
    fun step(scene: Scene, dt: Float) = step(scene, dt, gravityX, gravityY)

    fun step(scene: Scene, dt: Float, gx: Float, gy: Float) {
        gravityX = gx
        gravityY = gy
        val scaled = (dt * timeScale).coerceAtMost(0.25f)
        accumulator += scaled
        var steps = 0
        while (accumulator >= fixedTimeStep && steps < maxSubSteps) {
            fixedStep(scene, fixedTimeStep)
            accumulator -= fixedTimeStep
            steps++
        }
        if (steps >= maxSubSteps) accumulator = 0f
        stats.stepsPerSecond = if (scaled > 0f) steps / scaled else 0f
    }

    /** Runs a single simulation step regardless of the accumulator (used by tests and the editor). */
    fun stepOnce(scene: Scene, dt: Float = fixedTimeStep) = fixedStep(scene, dt)

    private fun fixedStep(scene: Scene, dt: Float) {
        val t0 = System.nanoTime()
        syncBodies(scene)
        syncJoints(scene)
        clearGrounded()
        stepCount++

        // ---- integrate velocities
        for (b in bodies) {
            if (!b.awake || b.type != Body2D.Type.DYNAMIC) continue
            b.vx += (gravityX * b.gravityScale + b.forceX * b.invMass) * dt
            b.vy += (gravityY * b.gravityScale + b.forceY * b.invMass) * dt
            if (b.angularDamping > 0f) b.angularVelocity *= max(0f, 1f - b.angularDamping * dt)
            if (b.linearDamping > 0f) {
                val k = max(0f, 1f - b.linearDamping * dt)
                b.vx *= k; b.vy *= k
            }
            b.forceX = 0f; b.forceY = 0f; b.torque = 0f
            b.torque *= max(0f, 1f - b.angularDamping * dt)
        }

        // ---- continuous collision detection for bullets (sub-stepped integration)
        applyCcd(dt)

        // ---- velocity ceilings (keep a bad contact or joint solve from launching bodies)
        if (maxLinearSpeed > 0f || maxAngularSpeed > 0f) {
            for (b in bodies) {
                if (b.type != Body2D.Type.DYNAMIC || !b.awake) continue
                if (maxLinearSpeed > 0f) {
                    val sp2 = b.vx * b.vx + b.vy * b.vy
                    val mx = maxLinearSpeed * maxLinearSpeed
                    if (sp2 > mx) {
                        val scale = maxLinearSpeed / sqrt(sp2)
                        b.vx *= scale; b.vy *= scale
                    }
                }
                if (maxAngularSpeed > 0f && abs(b.angularVelocity) > maxAngularSpeed) {
                    b.angularVelocity = if (b.angularVelocity > 0f) maxAngularSpeed else -maxAngularSpeed
                }
            }
        }

        // ---- integrate positions (character bodies are moved by their own controller)
        for (b in bodies) {
            if (b.type == Body2D.Type.CHARACTER) {
                b.syncTransformFromGameObject()
                b.grounded = b.grounded // set by the character controller
                continue
            }
            if (!b.awake) continue
            if (b.type == Body2D.Type.STATIC) continue
            b.prevX = b.x; b.prevY = b.y; b.prevAngle = b.angle
            b.x += b.vx * dt
            b.y += b.vy * dt
            b.angle += b.angularVelocity * dt
            b.updateAabb()
        }

        val t1 = System.nanoTime()

        // ---- broadphase
        buildBroadphase()
        val t2 = System.nanoTime()

        // ---- narrowphase + solver
        val contacts = ArrayList<Contact2D>(64)
        generateContacts(contacts)
        val t2b = System.nanoTime()
        prepareContacts(contacts, dt)
        solve(contacts)
        solvePositions(contacts)

        val t3 = System.nanoTime()

        // ---- joints (an active joint keeps both of its bodies awake, like Box2D: otherwise a
        // sleeping wheel would ignore its own motor and the car would never start moving)
        for (j in joints) if (j.enabled) {
            j.a.wake()
            j.b.wake()
            j.solve(dt)
        }

        // ---- sleeping
        updateSleeping(dt)

        // ---- events + write-back
        publishEvents(contacts)
        for (b in bodies) {
            if (b.type == Body2D.Type.STATIC || b.type == Body2D.Type.CHARACTER) continue
            b.syncToGameObject()
        }
        val t4 = System.nanoTime()

        stats.broadphaseMs = stats.broadphaseMs * 0.9f + (t2 - t1) / 1e6f * 0.1f
        stats.narrowphaseMs = stats.narrowphaseMs * 0.9f + (t2b - t2) / 1e6f * 0.1f
        stats.solverMs = stats.solverMs * 0.9f + (t3 - t2b) / 1e6f * 0.1f
        stats.stepMs = stats.stepMs * 0.9f + (t4 - t0) / 1e6f * 0.1f
        stats.solverIterations = velocityIterations
    }

    // ------------------------------------------------------------------ body <-> scene sync
    private var debugDrawContacts = false

    private fun syncBodies(scene: Scene) {
        stats.reset()
        // remove bodies whose object or component disappeared
        var i = 0
        while (i < bodies.size) {
            val b = bodies[i]
            val alive = scene.objects.contains(b.go) && b.go.isActiveInHierarchy() && b.go.get<Collider2D>() != null
            if (!alive) {
                bodyByObject.remove(b.go.id)
                bodies.removeAt(i)
                continue
            }
            i++
        }
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            val col = go.get<Collider2D>() ?: continue
            val rb = go.get<Rigidbody2D>()
            val existing = bodyByObject[go.id]
            val body = existing ?: Body2D(go, Body2D.Type.DYNAMIC).also {
                bodyByObject[go.id] = it
                bodies.add(it)
            }
            val shape = col.buildShape()
            val old = body.shape
            val shapeChanged = old.type != shape.type ||
                (old is PolygonShape && shape is PolygonShape && !old.vertices.contentEquals(shape.vertices)) ||
                (old is CircleShape && shape is CircleShape && old.radius != shape.radius)
            if (shapeChanged) {
                body.shape = shape
            }
            body.enabled = true
            body.isSensor = col.isTrigger
            body.friction = col.friction
            body.restitution = col.restitution
            body.density = col.density
            body.layer = col.layerMask
            body.mask = col.collisionMask
            body.oneWay = col.oneWay
            body.offsetX = col.offsetX
            body.offsetY = col.offsetY
            body.offsetAngle = col.rotation
            body.type = when (rb?.bodyType ?: 2) {
                0 -> Body2D.Type.DYNAMIC
                1 -> Body2D.Type.KINEMATIC
                3 -> Body2D.Type.CHARACTER
                else -> Body2D.Type.STATIC
            }
            if (rb != null) {
                body.gravityScale = rb.gravityScale
                body.linearDamping = rb.linearDamping
                body.angularDamping = rb.angularDamping
                body.fixedRotation = rb.fixedRotation
                body.continuous = rb.continuous
                body.allowSleep = rb.allowSleep
                body.groundTolerance = rb.jumpTolerance
            }
            if (shapeChanged || !body.initialised) {
                body.refreshMass()
                body.syncFromGameObject()
                if (rb != null) {
                    body.vx = rb.startVx
                    body.vy = rb.startVy
                    body.angularVelocity = Math.toRadians(rb.startAngularVelocity.toDouble()).toFloat()
                }
                body.initialised = true
                body.wake()
            }
            rb?.attach(body)
            stats.bodies++
            when (body.type) {
                Body2D.Type.DYNAMIC -> stats.dynamicBodies++
                Body2D.Type.STATIC -> stats.staticBodies++
                else -> {}
            }
            if (!body.awake) stats.sleepingBodies++ else stats.awakeBodies++
        }
        stats.joints = joints.count { it.enabled }
    }

    // ------------------------------------------------------------------ joints from components
    /** Synthetic static anchor bodies used by joints whose "Connected To" is empty. */
    private val anchorBodies = HashMap<Long, Body2D>()
    private var nextAnchorId = -1L

    private fun anchorBody(id: Long): Body2D {
        anchorBodies[id]?.let { return it }
        val go = GameObject(id, "__joint_anchor_$id")
        val body = Body2D(go, Body2D.Type.STATIC)
        body.shape = Shapes.box(0.1f, 0.1f)
        body.refreshMass()
        body.initialised = true
        anchorBodies[id] = body
        return body
    }

    private fun syncJoints(scene: Scene) {
        // drop joints whose bodies disappeared
        var i = 0
        while (i < joints.size) {
            val j = joints[i]
            val alive = bodies.contains(j.a) && bodies.contains(j.b)
            if (!alive) {
                joints.removeAt(i)
                continue
            }
            i++
        }
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            val rb = go.get<Rigidbody2D>() ?: continue
            val bodyA = bodyByObject[go.id] ?: continue
            for (c in go.components) {
                if (c !is JointComponent || !c.enabled) continue
                val existing = c.joint
                if (existing != null && joints.contains(existing) && existing.a === bodyA) continue
                if (existing != null) joints.remove(existing)
                val target: Body2D
                val otherName = c.connectedTo
                if (otherName.isBlank()) {
                    // joint to a static anchor at the component's anchor point
                    val ax = go.computeWorld().mapX(c.anchorX, c.anchorY)
                    val ay = go.computeWorld().mapY(c.anchorX, c.anchorY)
                    val id = ANCHOR_IDS.computeIfAbsent(go.id to c.type) { nextAnchorId-- }
                    val anchor = anchorBody(id)
                    anchor.x = ax; anchor.y = ay
                    anchor.updateAabb()
                    target = anchor
                } else {
                    val other = scene.find(otherName) ?: continue
                    target = bodyByObject[other.id] ?: continue
                }
                JointFactory.create(c, bodyA, target, this)
            }
        }
        stats.joints = joints.count { it.enabled }
    }

    // ------------------------------------------------------------------ broadphase
    private fun buildBroadphase() {
        grid.clear()
        candidatePairs.clear()
        for (b in bodies) {
            if (!b.enabled) continue
            b.updateAabb()
            val x0 = M.floorI(b.aabb.left / broadphaseCellSize)
            val x1 = M.floorI(b.aabb.right / broadphaseCellSize)
            val y0 = M.floorI(b.aabb.top / broadphaseCellSize)
            val y1 = M.floorI(b.aabb.bottom / broadphaseCellSize)
            if ((x1 - x0 + 1) * (y1 - y0 + 1) > 4096) {
                // huge body: put in a single "overflow" cell keyed by its proxy id
                grid.getOrPut(OVERFLOW_KEY) { ArrayList() }.add(b)
                continue
            }
            for (cx in x0..x1) for (cy in y0..y1) {
                grid.getOrPut(cellKey(cx, cy)) { ArrayList(4) }.add(b)
            }
        }
        // collect unique pairs from the grid
        val seen = HashSet<Long>()
        for (list in grid.values) {
            if (list.size < 2) continue
            for (i in 0 until list.size - 1) {
                val a = list[i]
                for (j in i + 1 until list.size) {
                    val b = list[j]
                    if (a === b) continue
                    if (skipPair(a, b)) continue
                    val key = pairKey(a.go.id, b.go.id)
                    if (seen.add(key)) candidatePairs.add(longArrayOf(key, a.go.id, b.go.id))
                }
            }
        }
        // bodies in the overflow cell need pairs against everything
        val overflow = grid[OVERFLOW_KEY]
        if (overflow != null) {
            for (a in overflow) for (b in bodies) {
                if (a === b || skipPair(a, b)) continue
                val key = pairKey(a.go.id, b.go.id)
                if (seen.add(key)) candidatePairs.add(longArrayOf(key, a.go.id, b.go.id))
            }
        }
        stats.broadphasePairs = candidatePairs.size
    }

    private fun skipPair(a: Body2D, b: Body2D): Boolean {
        if ((a.layer and b.mask) == 0) return true
        if ((b.layer and a.mask) == 0) return true
        if (a.isStatic && b.isStatic) return true
        if (!a.awake && !b.awake && a.isDynamic && b.isDynamic) return true
        return false
    }

    private fun cellKey(cx: Int, cy: Int): Long =
        (cx.toLong() shl 32) xor (cy.toLong() and 0xFFFFFFFFL)

    // ------------------------------------------------------------------ narrowphase
    private fun generateContacts(out: MutableList<Contact2D>) {
        for (p in candidatePairs) {
            val a = bodyByObject[p[1]] ?: continue
            val b = bodyByObject[p[2]] ?: continue
            if (!aabbOverlap(a.aabb, b.aabb)) continue
            val sensor = a.isSensor || b.isSensor
            val manifold = collide(a, b) ?: continue
            val c = Contact2D(a, b)
            c.sensor = sensor
            c.normalX = manifold.nx
            c.normalY = manifold.ny
            c.pointCount = manifold.count
            for (i in 0 until manifold.count) {
                c.pointX[i] = manifold.px[i]
                c.pointY[i] = manifold.py[i]
                c.penetration[i] = manifold.pen[i]
                c.separation[i] = -manifold.pen[i]
            }
            // one-way platforms behave as sensors when approached from the wrong side
            if (!sensor && (a.oneWay || b.oneWay)) {
                if (!oneWayAllows(a, b, c)) continue
            }
            out.add(c)
        }
    }

    /**
     * A one-way platform only blocks movement coming from the side its normal points to
     * (i.e. landing on top of it). Jumping up from below passes straight through.
     */
    private fun oneWayAllows(a: Body2D, b: Body2D, c: Contact2D): Boolean {
        val platform = if (a.oneWay) a else b
        val mover = if (platform === a) b else a
        // direction from the platform towards the other body
        val dirX = if (platform === a) c.normalX else -c.normalX
        val dirY = if (platform === a) c.normalY else -c.normalY
        if (dirX * platform.oneWayNormalX + dirY * platform.oneWayNormalY < 0.25f) return false
        // the other body must be moving into the platform (or resting on it)
        val vn = mover.vx * platform.oneWayNormalX + mover.vy * platform.oneWayNormalY
        if (vn > 0.25f) return false
        // "press down to drop through" is expressed with the dropThrough flag
        val rb = mover.go.get<Rigidbody2D>()
        if (rb != null && rb.dropThrough) return false
        return true
    }

    private class Manifold(val nx: Float, val ny: Float, val count: Int, val px: FloatArray, val py: FloatArray, val pen: FloatArray)

    private val mPointX = FloatArray(2)
    private val mPointY = FloatArray(2)
    private val mPen = FloatArray(2)

    private fun emptyManifold() = Manifold(0f, 1f, 0, FloatArray(2), FloatArray(2), FloatArray(2))

    private fun circleManifold(a: Body2D, b: Body2D, caRa: Float, rb: Float): Manifold? {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val r = caRa + rb
        val d2 = dx * dx + dy * dy
        if (d2 >= r * r) return null
        val d = sqrt(d2)
        val nx: Float; val ny: Float
        if (d < 1e-6f) { nx = 0f; ny = 1f } else { nx = dx / d; ny = dy / d }
        mPointX[0] = a.x + nx * caRa
        mPointY[0] = a.y + ny * caRa
        mPen[0] = r - d
        return Manifold(nx, ny, 1, mPointX.copyOf(), mPointY.copyOf(), mPen.copyOf())
    }

    private fun collideCirclePolygon(circle: Body2D, cr: Float, poly: Body2D, flip: Boolean): Manifold? {
        val lx = (circle.x - poly.x) * poly.cosA + (circle.y - poly.y) * poly.sinA
        val ly = -(circle.x - poly.x) * poly.sinA + (circle.y - poly.y) * poly.cosA
        val shape = poly.shape as PolygonShape
        val verts = shape.vertices
        val n = shape.count
        var bestD = -Float.MAX_VALUE
        var bestI = 0
        for (i in 0 until n) {
            val nx = shape.normals[i * 2]; val ny = shape.normals[i * 2 + 1]
            val d = nx * (lx - verts[i * 2]) + ny * (ly - verts[i * 2 + 1])
            if (d > cr) return null
            if (d > bestD) { bestD = d; bestI = i }
        }
        val faceX = shape.normals[bestI * 2]
        val faceY = shape.normals[bestI * 2 + 1]
        var localNx = 0f; var localNy = 0f; var pen = 0f
        if (bestD < 1e-6f) {
            // centre inside the polygon: push out along the closest face
            localNx = faceX; localNy = faceY
            pen = cr - bestD
        } else {
            val v1x = verts[bestI * 2]; val v1y = verts[bestI * 2 + 1]
            val j = (bestI + 1) % n
            val v2x = verts[j * 2]; val v2y = verts[j * 2 + 1]
            val e = floatArrayOf(v2x - v1x, v2y - v1y)
            val t = M.clamp01(((lx - v1x) * e[0] + (ly - v1y) * e[1]) / (e[0] * e[0] + e[1] * e[1]))
            val closestX = v1x + e[0] * t
            val closestY = v1y + e[1] * t
            var dx = lx - closestX
            var dy = ly - closestY
            val d2 = dx * dx + dy * dy
            if (d2 > cr * cr) return null
            val d = sqrt(d2)
            if (d < 1e-6f) { localNx = faceX; localNy = faceY; pen = cr }
            else { localNx = dx / d; localNy = dy / d; pen = cr - d }
        }
        // to world
        val wnx = localNx * poly.cosA - localNy * poly.sinA
        val wny = localNx * poly.sinA + localNy * poly.cosA
        val pxW = circle.x - wnx * cr
        val pyW = circle.y - wny * cr
        // [wnx, wny] points from the polygon towards the circle; the manifold normal must point
        // from the first body towards the second, so it flips when the polygon is the first body.
        val nx = if (flip) wnx else -wnx
        val ny = if (flip) wny else -wny
        mPointX[0] = pxW; mPointY[0] = pyW; mPen[0] = pen
        return Manifold(nx, ny, 1, mPointX.copyOf(), mPointY.copyOf(), mPen.copyOf())
    }

    /** SAT + face clipping for convex polygons (Box2D-style, gives 2-point manifolds). */
    private fun collidePolygons(a: Body2D, b: Body2D): Manifold? {
        val pa = a.shape as PolygonShape
        val pb = b.shape as PolygonShape
        var edgeA = 0
        val sepA = findMaxSeparation(pa, a, pb, b, { edgeA = it })
        if (sepA > 0f) return null
        var edgeB = 0
        val sepB = findMaxSeparation(pb, b, pa, a, { edgeB = it })
        if (sepB > 0f) return null

        val flip = sepB > 0.98f * sepA + 0.001f
        val poly1 = if (flip) pb else pa
        val body1 = if (flip) b else a
        val poly2 = if (flip) pa else pb
        val body2 = if (flip) a else b
        val edge1 = if (flip) edgeB else edgeA

        // reference face
        val count1 = poly1.count
        val v1 = poly1.vertices
        val i1 = edge1
        val i2 = (edge1 + 1) % count1
        val v1x = body1.x + v1[i1 * 2] * body1.cosA - v1[i1 * 2 + 1] * body1.sinA
        val v1y = body1.y + v1[i1 * 2] * body1.sinA + v1[i1 * 2 + 1] * body1.cosA
        val v2x = body1.x + v1[i2 * 2] * body1.cosA - v1[i2 * 2 + 1] * body1.sinA
        val v2y = body1.y + v1[i2 * 2] * body1.sinA + v1[i2 * 2 + 1] * body1.cosA
        var refNx = v2y - v1y
        var refNy = -(v2x - v1x)
        val len = sqrt(refNx * refNx + refNy * refNy)
        if (len < 1e-8f) return null
        refNx /= len; refNy /= len
        val tangentX = v2x - v1x
        val tangentY = v2y - v1y

        // find the incident face on poly2 (most anti-parallel to the reference normal)
        val count2 = poly2.count
        val v2v = poly2.vertices
        var incidentEdge = 0
        var minDot = Float.MAX_VALUE
        for (i in 0 until count2) {
            val nx = poly2.normals[i * 2] * body2.cosA - poly2.normals[i * 2 + 1] * body2.sinA
            val ny = poly2.normals[i * 2] * body2.sinA + poly2.normals[i * 2 + 1] * body2.cosA
            val d = nx * refNx + ny * refNy
            if (d < minDot) { minDot = d; incidentEdge = i }
        }
        val j1 = incidentEdge
        val j2 = (incidentEdge + 1) % count2
        val clipX = FloatArray(2)
        val clipY = FloatArray(2)
        clipX[0] = body2.x + v2v[j1 * 2] * body2.cosA - v2v[j1 * 2 + 1] * body2.sinA
        clipY[0] = body2.y + v2v[j1 * 2] * body2.sinA + v2v[j1 * 2 + 1] * body2.cosA
        clipX[1] = body2.x + v2v[j2 * 2] * body2.cosA - v2v[j2 * 2 + 1] * body2.sinA
        clipY[1] = body2.y + v2v[j2 * 2] * body2.sinA + v2v[j2 * 2 + 1] * body2.cosA

        // Clip against the two side planes of the reference face. The planes keep the part of the
        // incident edge whose tangent projection lies between v1 and v2, so their normals point
        // away from the face (outwards along the tangent) - a non-unit normal is fine here because
        // the clip test and its interpolation are scale invariant.
        var points = 2
        val side1x = -tangentX; val side1y = -tangentY
        val side1offset = side1x * v1x + side1y * v1y
        points = clipSegment(clipX, clipY, points, side1x, side1y, side1offset)
        if (points < 2) return null
        val side2x = tangentX; val side2y = tangentY
        val side2offset = side2x * v2x + side2y * v2y
        points = clipSegment(clipX, clipY, points, side2x, side2y, side2offset)
        if (points < 2) return null

        // keep points below the reference face
        var outCount = 0
        for (i in 0 until points) {
            val sep = refNx * (clipX[i] - v1x) + refNy * (clipY[i] - v1y)
            if (sep <= 0f) {
                mPointX[outCount] = clipX[i]
                mPointY[outCount] = clipY[i]
                mPen[outCount] = -sep
                outCount++
            }
        }
        if (outCount == 0) return null
        val nx = if (flip) -refNx else refNx
        val ny = if (flip) -refNy else refNy
        return Manifold(nx, ny, outCount, mPointX.copyOf(), mPointY.copyOf(), mPen.copyOf())
    }

    private fun clipSegment(px: FloatArray, py: FloatArray, count: Int, nx: Float, ny: Float, offset: Float): Int {
        val d0 = nx * px[0] + ny * py[0] - offset
        val d1 = nx * px[1] + ny * py[1] - offset
        var out = 0
        val ox = FloatArray(2)
        val oy = FloatArray(2)
        if (d0 <= 0f) { ox[out] = px[0]; oy[out] = py[0]; out++ }
        if (d1 <= 0f) { ox[out] = px[1]; oy[out] = py[1]; out++ }
        if (d0 * d1 < 0f && out < 2) {
            val t = d0 / (d0 - d1)
            ox[out] = px[0] + (px[1] - px[0]) * t
            oy[out] = py[0] + (py[1] - py[0]) * t
            out++
        }
        for (i in 0 until out) { px[i] = ox[i]; py[i] = oy[i] }
        return out
    }

    /**
     * SAT edge separation (Box2D style): for every face of A, the signed distance of the *closest*
     * vertex of B to that face plane. A positive result means the shapes are separated along that
     * axis; the largest value over A's faces is the best separating axis.
     */
    private fun findMaxSeparation(polyA: PolygonShape, bodyA: Body2D, polyB: PolygonShape, bodyB: Body2D, edgeOut: (Int) -> Unit): Float {
        // B's vertices expressed in A's local frame
        val dx = bodyB.x - bodyA.x
        val dy = bodyB.y - bodyA.y
        val originX = dx * bodyA.cosA + dy * bodyA.sinA
        val originY = -dx * bodyA.sinA + dy * bodyA.cosA
        val cosRel = bodyA.cosA * bodyB.cosA + bodyA.sinA * bodyB.sinA
        val sinRel = -bodyA.sinA * bodyB.cosA + bodyA.cosA * bodyB.sinA
        val vb = polyB.vertices
        var best = -Float.MAX_VALUE
        var bestIndex = 0
        val n = polyA.count
        for (i in 0 until n) {
            val nx = polyA.normals[i * 2]
            val ny = polyA.normals[i * 2 + 1]
            val vx = polyA.vertices[i * 2]
            val vy = polyA.vertices[i * 2 + 1]
            var minD = Float.MAX_VALUE
            var k = 0
            while (k < vb.size) {
                val wx = originX + vb[k] * cosRel - vb[k + 1] * sinRel
                val wy = originY + vb[k] * sinRel + vb[k + 1] * cosRel
                val d = nx * (wx - vx) + ny * (wy - vy)
                if (d < minD) minD = d
                if (minD < best) break // this face cannot win any more
                k += 2
            }
            if (minD > best) { best = minD; bestIndex = i }
        }
        edgeOut(bestIndex)
        return best
    }

    /** Main dispatcher: returns a manifold or null when the shapes do not overlap. */
    private fun collide(a: Body2D, b: Body2D): Manifold? {
        val ta = a.shape.type
        val tb = b.shape.type
        return when {
            ta == Shape2D.CIRCLE && tb == Shape2D.CIRCLE -> {
                val ma = circleManifold(a, b, (a.shape as CircleShape).radius, (b.shape as CircleShape).radius)
                ma
            }
            ta == Shape2D.CIRCLE -> collideCirclePolygon(a, (a.shape as CircleShape).radius, b, false)
            tb == Shape2D.CIRCLE -> collideCirclePolygon(b, (b.shape as CircleShape).radius, a, true)
            else -> collidePolygons(a, b)
        }
    }

    // ------------------------------------------------------------------ solver
    private class ConstraintPoint {
        var rx = 0f; var ry = 0f
        var massNormal = 0f; var massTangent = 0f
        var bias = 0f
        var separation = 0f
        var normalImpulse = 0f
        var tangentImpulse = 0f
        var targetNormalImpulse = 0f
    }

    private val scratchPoints = Array(2) { ConstraintPoint() }

    private fun prepareContacts(contacts: List<Contact2D>, dt: Float) {
        for (c in contacts) {
            c.friction = sqrt(c.a.friction * c.b.friction)
            c.restitution = max(c.a.restitution, c.b.restitution)
            c.normalImpulse = 0f
            c.tangentImpulse = 0f
            if (c.sensor) continue
            val a = c.a
            val b = c.b
            val invMassSum = a.invMass + b.invMass
            val useInvInertia = a.invInertia > 0f || b.invInertia > 0f
            for (i in 0 until c.pointCount) {
                val p = scratchPoints[i]
                p.rx = c.pointX[i] - a.x; p.ry = c.pointY[i] - a.y
                val r2x = c.pointX[i] - b.x; val r2y = c.pointY[i] - b.y
                var rnA = p.rx * c.normalY - p.ry * c.normalX
                var rnB = r2x * c.normalY - r2y * c.normalX
                var kNormal = invMassSum + a.invInertia * rnA * rnA + b.invInertia * rnB * rnB
                if (kNormal < 1e-9f) kNormal = 1e-9f
                p.massNormal = 1f / kNormal
                val tx = -c.normalY
                val ty = c.normalX
                rnA = p.rx * ty - p.ry * tx
                rnB = r2x * ty - r2y * tx
                var kTangent = invMassSum + a.invInertia * rnA * rnA + b.invInertia * rnB * rnB
                if (kTangent < 1e-9f) kTangent = 1e-9f
                p.massTangent = 1f / kTangent
                // Penetration is resolved by [solvePositions] directly on the transforms, so the
                // velocity constraint stays pure: no Baumgarte energy injection, resting bodies end
                // the step with ~zero velocity (which also lets them sleep).
                p.separation = max(-c.penetration[i], -maxCorrection)
                p.bias = 0f
                p.normalImpulse = c.normalImpulseAcc[i]
                p.tangentImpulse = c.tangentImpulseAcc[i]
                p.targetNormalImpulse = 0f
            }
            // warm start
            for (i in 0 until c.pointCount) {
                val p = scratchPoints[i]
                val ix = c.normalX * p.normalImpulse + (-c.normalY) * p.tangentImpulse
                val iy = c.normalY * p.normalImpulse + c.normalX * p.tangentImpulse
                applyImpulseAt(a, -ix, -iy, c.pointX[i], c.pointY[i])
                applyImpulseAt(b, ix, iy, c.pointX[i], c.pointY[i])
            }
        }
    }

    private fun solve(contacts: List<Contact2D>) {
        for (iter in 0 until velocityIterations) {
            for (c in contacts) {
                if (c.sensor) continue
                val a = c.a
                val b = c.b
                val nx = c.normalX
                val ny = c.normalY
                val tx = -ny
                val ty = nx
                for (i in 0 until c.pointCount) {
                    val p = scratchPoints[i]
                    val r2x = c.pointX[i] - b.x; val r2y = c.pointY[i] - b.y
                    // relative velocity at the contact point (b relative to a)
                    var dvx = b.vx - b.angularVelocity * r2y - (a.vx - a.angularVelocity * p.ry)
                    var dvy = b.vy + b.angularVelocity * r2x - (a.vy + a.angularVelocity * p.rx)
                    // friction
                    val vt = dvx * tx + dvy * ty
                    var lambdaT = -p.massTangent * vt
                    val maxFriction = c.friction * p.normalImpulse
                    val newT = M.clamp(p.tangentImpulse + lambdaT, -maxFriction, maxFriction)
                    lambdaT = newT - p.tangentImpulse
                    p.tangentImpulse = newT
                    var ix = tx * lambdaT
                    var iy = ty * lambdaT
                    applyImpulseAt(a, -ix, -iy, c.pointX[i], c.pointY[i])
                    applyImpulseAt(b, ix, iy, c.pointX[i], c.pointY[i])
                    // normal
                    dvx = b.vx - b.angularVelocity * r2y - (a.vx - a.angularVelocity * p.ry)
                    dvy = b.vy + b.angularVelocity * r2x - (a.vy + a.angularVelocity * p.rx)
                    val vn = dvx * nx + dvy * ny
                    var lambda = -p.massNormal * (vn - p.bias)
                    val newN = max(p.normalImpulse + lambda, 0f)
                    lambda = newN - p.normalImpulse
                    p.normalImpulse = newN
                    ix = nx * lambda; iy = ny * lambda
                    applyImpulseAt(a, -ix, -iy, c.pointX[i], c.pointY[i])
                    applyImpulseAt(b, ix, iy, c.pointX[i], c.pointY[i])
                }
            }
        }
        // restitution pass (applied once, after the main solve, to avoid jitter)
        for (c in contacts) {
            if (c.sensor) continue
            if (c.restitution <= 0.01f) continue
            val a = c.a
            val b = c.b
            for (i in 0 until c.pointCount) {
                val p = scratchPoints[i]
                val r2x = c.pointX[i] - b.x; val r2y = c.pointY[i] - b.y
                val dvx = b.vx - b.angularVelocity * r2y - (a.vx - a.angularVelocity * p.ry)
                val dvy = b.vy + b.angularVelocity * r2x - (a.vy + a.angularVelocity * p.rx)
                val vn = dvx * c.normalX + dvy * c.normalY
                if (vn > 0f) continue
                val invSum = a.invMass + b.invMass
                if (invSum <= 0f) continue
                val j = -c.restitution * vn / invSum
                val ix = c.normalX * j; val iy = c.normalY * j
                applyImpulseAt(a, -ix, -iy, c.pointX[i], c.pointY[i])
                applyImpulseAt(b, ix, iy, c.pointX[i], c.pointY[i])
            }
        }
        // write back accumulated impulses and stats
        for (c in contacts) {
            var totalN = 0f
            var totalT = 0f
            for (i in 0 until c.pointCount) {
                c.normalImpulseAcc[i] = if (c.sensor) 0f else scratchPoints[i].normalImpulse
                c.tangentImpulseAcc[i] = if (c.sensor) 0f else scratchPoints[i].tangentImpulse
                totalN += c.normalImpulseAcc[i]
                totalT += abs(c.tangentImpulseAcc[i])
            }
            c.normalImpulse = totalN
            c.tangentImpulse = totalT
            c.touching = totalN > 0f || c.sensor
        }
        stats.contacts = contacts.count { !it.sensor && it.touching }
        stats.sensors = contacts.count { it.sensor }
        stats.pairs = contacts.size
    }

    /**
     * Positional (non-linear Gauss-Seidel) correction: pushes overlapping bodies apart along the
     * contact normal, split by inverse mass. Runs after the velocity solve so visible penetration is
     * removed without adding energy to the simulation.
     */
    private fun solvePositions(contacts: List<Contact2D>) {
        for (c in contacts) {
            if (c.sensor || c.pointCount == 0) continue
            val a = c.a
            val b = c.b
            val invSum = a.invMass + b.invMass
            if (invSum <= 0f) continue
            var deepest = 0f
            for (i in 0 until c.pointCount) if (c.penetration[i] > deepest) deepest = c.penetration[i]
            val push = min(positionCorrection * (deepest - slop), maxCorrection)
            if (push <= 0f) continue
            val nx = c.normalX
            val ny = c.normalY
            b.x += nx * push * (b.invMass / invSum)
            b.y += ny * push * (b.invMass / invSum)
            a.x -= nx * push * (a.invMass / invSum)
            a.y -= ny * push * (a.invMass / invSum)
            a.updateAabb()
            b.updateAabb()
        }
    }

    private fun applyImpulseAt(b: Body2D, ix: Float, iy: Float, px: Float, py: Float) {
        if (b.invMass == 0f) return
        b.vx += ix * b.invMass
        b.vy += iy * b.invMass
        if (b.invInertia > 0f) {
            b.angularVelocity += b.invInertia * ((px - b.x) * iy - (py - b.y) * ix)
        }
    }

    // ------------------------------------------------------------------ sleeping
    private fun updateSleeping(dt: Float) {
        if (!enableSleeping) {
            for (b in bodies) b.awake = true
            return
        }
        for (b in bodies) {
            if (b.type != Body2D.Type.DYNAMIC || !b.allowSleep) {
                b.awake = true
                continue
            }
            val linear = b.speedSq
            val angular = b.angularVelocity * b.angularVelocity
            val th = b.sleepThreshold
            if (linear < th * th && angular < th * th) {
                b.sleepTime += dt
                if (b.sleepTime > 0.6f) b.sleep()
            } else {
                b.sleepTime = 0f
                b.awake = true
            }
        }
    }

    // ------------------------------------------------------------------ continuous collision detection
    private fun applyCcd(dt: Float) {
        for (b in bodies) {
            if (!b.isBullet || !b.awake) continue
            val dx = b.vx * dt
            val dy = b.vy * dt
            val travel2 = dx * dx + dy * dy
            val radius = b.shape.boundingRadius()
            if (travel2 < radius * radius * 0.25f) continue
            // sub-step the motion, testing against other bodies each micro-step
            val steps = min(8, max(2, (sqrt(travel2) / max(radius * 0.5f, 0.01f)).toInt() + 1))
            var px = b.x - dx
            var py = b.y - dy
            for (s in 1..steps) {
                val t = s.toFloat() / steps
                val nx = b.x - dx + dx * t
                val ny = b.y - dy + dy * t
                if (overlapAny(b, nx, ny)) {
                    b.x = px
                    b.y = py
                    b.vx *= 0.5f
                    b.vy *= 0.5f
                    stats.ccdHits++
                    break
                }
                px = nx; py = ny
            }
        }
    }

    private fun overlapAny(self: Body2D, x: Float, y: Float): Boolean {
        val savedX = self.x
        val savedY = self.y
        self.x = x; self.y = y
        self.updateAabb()
        var hit = false
        for (other in bodies) {
            if (other === self || !other.enabled) continue
            if (other.isStatic || other.isKinematic) {
                if (skipPair(self, other)) continue
                if (!aabbOverlap(self.aabb, other.aabb)) continue
                if (collide(self, other) != null) { hit = true; break }
            }
        }
        self.x = savedX; self.y = savedY
        self.updateAabb()
        return hit
    }

    // ------------------------------------------------------------------ events
    private fun publishEvents(contacts: List<Contact2D>) {
        val listener = this.listener
        prevContacts.clear(); prevContacts.addAll(activeContacts)
        prevSensors.clear(); prevSensors.addAll(activeSensors)
        activeContacts.clear(); activeSensors.clear()
        val stayContacts = ArrayList<Contact2D>()
        val staySensors = ArrayList<Contact2D>()
        for (c in contacts) {
            val key = c.key()
            if (c.sensor) {
                activeSensors.add(key)
                if (key in prevSensors) staySensors.add(c) else listener?.onTriggerEnter(c.a.go, c.b.go, c)
                // wake sleeping bodies when a sensor overlaps them
                if (!c.a.awake) c.a.wake()
                if (!c.b.awake) c.b.wake()
            } else {
                activeContacts.add(key)
                updateGrounded(c)
                if (key in prevContacts) stayContacts.add(c) else listener?.onCollisionEnter(c.a.go, c.b.go, c)
            }
        }
        for (c in stayContacts) listener?.onCollisionStay(c.a.go, c.b.go, c)
        for (c in staySensors) listener?.onTriggerStay(c.a.go, c.b.go, c)
        if (listener != null) {
            for (key in prevContacts) if (key !in activeContacts) {
                val a = bodyByObject[key shr 32]?.go
                val b = bodyByObject[key and 0xFFFFFFFFL]?.go
                if (a != null && b != null) listener.onCollisionExit(a, b)
            }
            for (key in prevSensors) if (key !in activeSensors) {
                val a = bodyByObject[key shr 32]?.go
                val b = bodyByObject[key and 0xFFFFFFFFL]?.go
                if (a != null && b != null) listener.onTriggerExit(a, b)
            }
        }
        if (debugDrawContacts) {
            debugContacts.clear()
            for (c in contacts) {
                for (i in 0 until c.pointCount) debugContacts.add(floatArrayOf(c.pointX[i], c.pointY[i], c.normalX, c.normalY))
            }
        }
    }

    private fun updateGrounded(c: Contact2D) {
        // figure out which body is on top of which
        val a = c.a
        val b = c.b
        // The manifold normal points from [Contact2D.a] towards [Contact2D.b]; markGrounded wants
        // the normal pointing from the ground up towards the body, hence the per-branch sign.
        if (a.type == Body2D.Type.STATIC || a.type == Body2D.Type.KINEMATIC) {
            markGrounded(b, c, c.normalX, c.normalY, a)
        }
        if (b.type == Body2D.Type.STATIC || b.type == Body2D.Type.KINEMATIC) {
            markGrounded(a, c, -c.normalX, -c.normalY, b)
        }
        if (a.isDynamic && b.isDynamic) {
            markGrounded(b, c, c.normalX, c.normalY, a)
            markGrounded(a, c, -c.normalX, -c.normalY, b)
        }
    }

    private fun markGrounded(body: Body2D, c: Contact2D, upX: Float, upY: Float, ground: Body2D) {
        if (body.type == Body2D.Type.STATIC) return
        // The contact normal points from the ground towards the body when upY > 0.
        if (upY > 0.45f) {
            body.grounded = true
            if (body.groundBody == null || upY > body.groundNormalY) {
                body.groundNormalX = upX
                body.groundNormalY = upY
                body.groundBody = ground
                body.groundVx = ground.vx
                body.groundVy = ground.vy
            }
        } else if (body.groundBody == null) {
            body.groundNormalX = upX
            body.groundNormalY = upY
        }
    }

    /** Clears per-step grounded flags before integration (called automatically by [fixedStep]). */
    fun clearGrounded() {
        for (b in bodies) { b.grounded = false; b.groundBody = null }
    }

    // ------------------------------------------------------------------ joints
    fun addJoint(j: Joint2D): Joint2D {
        joints.add(j)
        return j
    }

    fun removeJoint(j: Joint2D) {
        joints.remove(j)
    }

    fun clearJoints() {
        joints.clear()
    }

    // ------------------------------------------------------------------ queries
    /** Casts a ray, returning the closest hit (or null). */
    fun rayCast(x1: Float, y1: Float, x2: Float, y2: Float, mask: Int = -1, includeSensors: Boolean = false): RayHit? {
        var best: RayHit? = null
        for (b in bodies) {
            if (!b.enabled) continue
            if (b.isSensor && !includeSensors) continue
            if ((b.layer and mask) == 0) continue
            val hit = rayCastBody(b, x1, y1, x2, y2) ?: continue
            if (best == null || hit.fraction < best.fraction) best = hit
        }
        return best
    }

    /** Returns every body hit by the ray, sorted front to back. */
    fun rayCastAll(x1: Float, y1: Float, x2: Float, y2: Float, mask: Int = -1, includeSensors: Boolean = false): List<RayHit> {
        val out = ArrayList<RayHit>()
        for (b in bodies) {
            if (!b.enabled) continue
            if (b.isSensor && !includeSensors) continue
            if ((b.layer and mask) == 0) continue
            rayCastBody(b, x1, y1, x2, y2)?.let { out.add(it) }
        }
        out.sortBy { it.fraction }
        return out
    }

    fun raycastHit(x1: Float, y1: Float, x2: Float, y2: Float, mask: Int = -1): Boolean =
        rayCast(x1, y1, x2, y2, mask) != null

    private fun rayCastBody(b: Body2D, x1: Float, y1: Float, x2: Float, y2: Float): RayHit? {
        val dx = x2 - x1
        val dy = y2 - y1
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-8f) return null
        return when (val s = b.shape) {
            is CircleShape -> rayCircle(b, s.radius, x1, y1, dx, dy, len)
            is PolygonShape -> rayPolygon(b, s, x1, y1, dx, dy, len)
            else -> null
        }
    }

    private fun rayCircle(b: Body2D, r: Float, x1: Float, y1: Float, dx: Float, dy: Float, len: Float): RayHit? {
        val cx = x1 - b.x
        val cy = y1 - b.y
        val a = dx * dx + dy * dy
        val bb = 2f * (cx * dx + cy * dy)
        val c = cx * cx + cy * cy - r * r
        val disc = bb * bb - 4f * a * c
        if (disc < 0f) return null
        val sq = sqrt(disc)
        var t = (-bb - sq) / (2f * a)
        if (t < 0f) t = (-bb + sq) / (2f * a)
        if (t < 0f || t > 1f) return null
        val px = x1 + dx * t
        val py = y1 + dy * t
        var nx = (px - b.x) / r
        var ny = (py - b.y) / r
        val l = sqrt(nx * nx + ny * ny)
        if (l > 1e-6f) { nx /= l; ny /= l } else { nx = -dx / len; ny = -dy / len }
        return RayHit(b, px, py, nx, ny, t)
    }

    private fun rayPolygon(b: Body2D, s: PolygonShape, x1: Float, y1: Float, dx: Float, dy: Float, len: Float): RayHit? {
        // transform the ray into the polygon's local space and clip against each edge
        val lx1 = (x1 - b.x) * b.cosA + (y1 - b.y) * b.sinA
        val ly1 = -(x1 - b.x) * b.sinA + (y1 - b.y) * b.cosA
        val ldx = dx * b.cosA + dy * b.sinA
        val ldy = -dx * b.sinA + dy * b.cosA
        var tMin = 0f
        var tMax = 1f
        var hitNormalIndex = -1
        val n = s.count
        for (i in 0 until n) {
            val nx = s.normals[i * 2]
            val ny = s.normals[i * 2 + 1]
            val denom = nx * ldx + ny * ldy
            val dist = nx * (lx1 - s.vertices[i * 2]) + ny * (ly1 - s.vertices[i * 2 + 1])
            if (abs(denom) < 1e-8f) {
                if (dist > 0f) return null
                continue
            }
            val t = -dist / denom
            if (denom < 0f) {
                if (t > tMin) { tMin = t; hitNormalIndex = i }
            } else {
                if (t < tMax) tMax = t
            }
            if (tMin > tMax) return null
        }
        if (hitNormalIndex < 0) return null
        val px = x1 + dx * tMin
        val py = y1 + dy * tMin
        val lnx = s.normals[hitNormalIndex * 2]
        val lny = s.normals[hitNormalIndex * 2 + 1]
        val nx = lnx * b.cosA - lny * b.sinA
        val ny = lnx * b.sinA + lny * b.cosA
        return RayHit(b, px, py, nx, ny, tMin)
    }

    /** Overlap query: all bodies whose shape overlaps the circle. */
    fun overlapCircle(x: Float, y: Float, radius: Float, mask: Int = -1, includeSensors: Boolean = false): List<Body2D> {
        val out = ArrayList<Body2D>()
        for (b in bodies) {
            if (!b.enabled) continue
            if (b.isSensor && !includeSensors) continue
            if ((b.layer and mask) == 0) continue
            if (overlapsCircle(b, x, y, radius)) out.add(b)
        }
        return out
    }

    fun overlapsCircle(b: Body2D, x: Float, y: Float, radius: Float): Boolean {
        return when (val s = b.shape) {
            is CircleShape -> {
                val dx = x - b.x; val dy = y - b.y
                val r = radius + s.radius
                dx * dx + dy * dy <= r * r
            }
            is PolygonShape -> circlePolyOverlap(b, s, x, y, radius)
            else -> false
        }
    }

    private fun circlePolyOverlap(b: Body2D, s: PolygonShape, x: Float, y: Float, radius: Float): Boolean {
        val lx = (x - b.x) * b.cosA + (y - b.y) * b.sinA
        val ly = -(x - b.x) * b.sinA + (y - b.y) * b.cosA
        val n = s.count
        for (i in 0 until n) {
            val nx = s.normals[i * 2]; val ny = s.normals[i * 2 + 1]
            val d = nx * (lx - s.vertices[i * 2]) + ny * (ly - s.vertices[i * 2 + 1])
            if (d > 0f) {
                // outside this edge - check the distance to the edge segment
                val j = (i + 1) % n
                val v1x = s.vertices[i * 2]; val v1y = s.vertices[i * 2 + 1]
                val v2x = s.vertices[j * 2]; val v2y = s.vertices[j * 2 + 1]
                val ex = v2x - v1x; val ey = v2y - v1y
                val el2 = ex * ex + ey * ey
                val t = if (el2 < 1e-8f) 0f else M.clamp01(((lx - v1x) * ex + (ly - v1y) * ey) / el2)
                val cx = v1x + ex * t; val cy = v1y + ey * t
                val dx = lx - cx; val dy = ly - cy
                if (dx * dx + dy * dy > radius * radius) return false
            }
        }
        return true
    }

    /** Overlap query with a box (rotated). */
    fun overlapBox(x: Float, y: Float, halfW: Float, halfH: Float, angleRad: Float = 0f, mask: Int = -1): List<Body2D> {
        val out = ArrayList<Body2D>()
        val cosA = kotlin.math.cos(angleRad); val sinA = kotlin.math.sin(angleRad)
        for (b in bodies) {
            if (!b.enabled || (b.layer and mask) == 0) continue
            if (boxesOverlap(b, x, y, cosA, sinA, halfW, halfH)) out.add(b)
        }
        return out
    }

    /** Simple rotated box vs shape overlap (SAT with the query box's axes + the body's axes). */
    private fun boxesOverlap(b: Body2D, x: Float, y: Float, cosA: Float, sinA: Float, hw: Float, hh: Float): Boolean {
        // approximate non-polygon shapes with their bounding circle first
        val r = b.shape.boundingRadius()
        if (M.distSq(x, y, b.x, b.y) > (r + hw + hh) * (r + hw + hh)) return false
        return when (val s = b.shape) {
            is CircleShape -> {
                val dx = b.x - x; val dy = b.y - y
                val localX = dx * cosA + dy * sinA
                val localY = -dx * sinA + dy * cosA
                val cx = M.clamp(localX, -hw, hw)
                val cy = M.clamp(localY, -hh, hh)
                val ddx = localX - cx; val ddy = localY - cy
                ddx * ddx + ddy * ddy <= s.radius * s.radius
            }
            is PolygonShape -> {
                val verts = b.worldVertices()
                // SAT over the query box axes
                var minB = Float.MAX_VALUE; var maxB = -Float.MAX_VALUE
                var minB2 = Float.MAX_VALUE; var maxB2 = -Float.MAX_VALUE
                var i = 0
                while (i < verts.size) {
                    val lx = (verts[i] - x) * cosA + (verts[i + 1] - y) * sinA
                    val ly = -(verts[i] - x) * sinA + (verts[i + 1] - y) * cosA
                    minB = min(minB, lx); maxB = max(maxB, lx)
                    minB2 = min(minB2, ly); maxB2 = max(maxB2, ly)
                    i += 2
                }
                if (minB > hw || maxB < -hw || minB2 > hh || maxB2 < -hh) return false
                // SAT over the polygon axes
                val n = s.count
                for (k in 0 until n) {
                    val nx = s.normals[k * 2] * b.cosA - s.normals[k * 2 + 1] * b.sinA
                    val ny = s.normals[k * 2] * b.sinA + s.normals[k * 2 + 1] * b.cosA
                    var minA = Float.MAX_VALUE; var maxA = -Float.MAX_VALUE
                    var j = 0
                    while (j < verts.size) {
                        val d = nx * (verts[j] - x) + ny * (verts[j + 1] - y)
                        minA = min(minA, d); maxA = max(maxA, d)
                        j += 2
                    }
                    val centerA = nx * (b.x - x) + ny * (b.y - y)
                    if (minA > 0f || maxA < 0f) return false
                    if (centerA > r + hw + hh) return false
                }
                true
            }
            else -> false
        }
    }

    /**
     * Shape cast ("swept" query): moves a circle from (x1,y1) to (x2,y2) and reports the first hit.
     * Used by the character controller, dash moves and laser-style weapons.
     */
    fun circleCast(x1: Float, y1: Float, x2: Float, y2: Float, radius: Float, mask: Int = -1, includeSensors: Boolean = false): SweepHit? {
        val dx = x2 - x1
        val dy = y2 - y1
        val steps = max(4, (sqrt(dx * dx + dy * dy) / max(radius * 0.5f, 0.02f)).toInt().coerceAtMost(64))
        var lo = 0f
        var hi = 1f
        var hitBody: Body2D? = null
        for (s in 1..steps) {
            val t = s.toFloat() / steps
            val px = x1 + dx * t
            val py = y1 + dy * t
            var found: Body2D? = null
            for (b in bodies) {
                if (!b.enabled) continue
                if (b.isSensor && !includeSensors) continue
                if ((b.layer and mask) == 0) continue
                if (overlapsCircle(b, px, py, radius)) { found = b; break }
            }
            if (found != null) { hi = t; hitBody = found; break }
            lo = t
        }
        if (hitBody == null) return null
        // binary refine
        repeat(6) {
            val mid = (lo + hi) * 0.5f
            val px = x1 + dx * mid
            val py = y1 + dy * mid
            var any = false
            for (b in bodies) {
                if (!b.enabled || (b.isSensor && !includeSensors) || (b.layer and mask) == 0) continue
                if (overlapsCircle(b, px, py, radius)) { any = true; break }
            }
            if (any) hi = mid else lo = mid
        }
        val px = x1 + dx * hi
        val py = y1 + dy * hi
        // approximate normal from the hit body's centre
        var nx = px - hitBody.x
        var ny = py - hitBody.y
        val l = sqrt(nx * nx + ny * ny)
        if (l > 1e-6f) { nx /= l; ny /= l }
        return SweepHit(hitBody, hi, px, py, nx, ny)
    }

    /** Shape cast that ignores a specific body (typical for the character's own collider). */
    fun circleCastIgnore(
        x1: Float, y1: Float, x2: Float, y2: Float, radius: Float,
        ignore: GameObject?, mask: Int = -1, includeSensors: Boolean = false
    ): SweepHit? {
        val dx = x2 - x1
        val dy = y2 - y1
        val steps = max(4, (sqrt(dx * dx + dy * dy) / max(radius * 0.5f, 0.02f)).toInt().coerceAtMost(64))
        var hit: Body2D? = null
        var lo = 0f
        var hi = 1f
        for (s in 1..steps) {
            val t = s.toFloat() / steps
            val px = x1 + dx * t
            val py = y1 + dy * t
            val b = firstOverlap(px, py, radius, ignore, mask, includeSensors)
            if (b != null) { hi = t; hit = b; break }
            lo = t
        }
        val hitBody = hit ?: return null
        repeat(6) {
            val mid = (lo + hi) * 0.5f
            val px = x1 + dx * mid
            val py = y1 + dy * mid
            if (firstOverlap(px, py, radius, ignore, mask, includeSensors) != null) hi = mid else lo = mid
        }
        val px = x1 + dx * hi
        val py = y1 + dy * hi
        var nx = px - hitBody.x
        var ny = py - hitBody.y
        val l = sqrt(nx * nx + ny * ny)
        if (l > 1e-6f) { nx /= l; ny /= l }
        return SweepHit(hitBody, hi, px, py, nx, ny)
    }

    private fun firstOverlap(x: Float, y: Float, radius: Float, ignore: GameObject?, mask: Int, includeSensors: Boolean): Body2D? {
        for (b in bodies) {
            if (!b.enabled) continue
            if (ignore != null && b.go === ignore) continue
            if (b.isSensor && !includeSensors) continue
            if ((b.layer and mask) == 0) continue
            if (overlapsCircle(b, x, y, radius)) return b
        }
        return null
    }

    /** Bodies whose AABB contains the point (fast path for picking / editors). */
    /** True when the colliders (or bodies) of two objects overlap - 2D AABB test. */
    fun overlapObjects(a: GameObject, b: GameObject): Boolean {
        val ra = objectBounds(a) ?: return false
        val rb = objectBounds(b) ?: return false
        return aabbOverlap(ra, rb)
    }

    /** World-space AABB of an object's collider (or its sprite/transform fallback). */
    fun objectBounds(go: GameObject): Rect2? {
        val col = go.components.firstOrNull { it is Collider2D } as? Collider2D
        val w = go.computeWorld()
        if (col != null) {
            val c = col.worldCenter()
            val he = col.worldHalfExtents()
            return Rect2(c[0] - he[0], c[1] - he[1], he[0] * 2f, he[1] * 2f)
        }
        val sprite = go.components.firstOrNull { it is SpriteRenderer } as? SpriteRenderer ?: return null
        val hw = kotlin.math.abs(sprite.width * go.scaleX) * 0.5f
        val hh = kotlin.math.abs(sprite.height * go.scaleY) * 0.5f
        return Rect2(w.tx - hw, w.ty - hh, hw * 2f, hh * 2f)
    }

    fun bodiesInRect(x: Float, y: Float, w: Float, h: Float, mask: Int = -1): List<Body2D> =
        bodies.filter { it.enabled && (it.layer and mask) != 0 && aabbOverlap(it.aabb, Rect2(x, y, w, h)) }

    fun enableDebugContacts(enabled: Boolean) {
        debugDrawContacts = enabled
        if (!enabled) debugContacts.clear()
    }

    companion object {
        private const val OVERFLOW_KEY = Long.MAX_VALUE

        /** (objectId, joint type) -> synthetic anchor body id. */
        private val ANCHOR_IDS = HashMap<Pair<Long, String>, Long>()

        fun pairKey(a: Long, b: Long): Long {
            val lo = min(a, b); val hi = max(a, b)
            return (lo shl 32) or (hi and 0xFFFFFFFFL)
        }
    }
}

