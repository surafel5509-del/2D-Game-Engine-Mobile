package com.sengine.engine.physics

import com.sengine.engine.core.Collider3D
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Rigidbody3D
import com.sengine.engine.core.Scene
import com.sengine.engine.math.Mat4
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Impulse-based 3D physics with axis-aligned boxes and spheres.
 * Shares the collision/trigger listener with the 2D world.
 */
class PhysicsWorld3D {
    var listener: PhysicsWorld.Listener? = null
    private var accumulator = 0f
    private val fixedDt = 1f / 60f
    private var prevContacts = HashSet<Long>()
    private var prevTriggers = HashSet<Long>()

    private class Body(val go: GameObject, val rb: Rigidbody3D?, val col: Collider3D) {
        var cx = 0f; var cy = 0f; var cz = 0f
        var hx = 0f; var hy = 0f; var hz = 0f; var r = 0f
        val sphere get() = col.shape == 1
        val invMass get() = if (rb == null || rb.bodyType != 0) 0f else 1f / rb.mass
    }

    private class M(val nx: Float, val ny: Float, val nz: Float, val depth: Float)

    fun reset() { accumulator = 0f; prevContacts = HashSet(); prevTriggers = HashSet() }

    fun step(scene: Scene, dt: Float) {
        if (scene.objects.none { it.getAny<Collider3D>() != null || it.getAny<Rigidbody3D>() != null }) return
        accumulator += min(dt, 0.25f)
        var n = 0
        while (accumulator >= fixedDt && n < 5) { fixed(scene, fixedDt); accumulator -= fixedDt; n++ }
        if (n == 5) accumulator = 0f
    }

    private fun move(go: GameObject, dx: Float, dy: Float, dz: Float) {
        if (go.parent == null) { go.x += dx; go.y += dy; go.z += dz } else {
            val w = go.computeWorld3()
            go.setWorldPosition3(w[12] + dx, w[13] + dy, w[14] + dz)
        }
    }

    private fun fixed(scene: Scene, dt: Float) {
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            val rb = go.get<Rigidbody3D>() ?: continue
            rb.grounded = false
            when (rb.bodyType) {
                0 -> {
                    rb.vx += scene.gravityX * 0f
                    rb.vy += scene.gravity3D * rb.gravityScale * dt
                    if (rb.drag > 0f) { val k = max(0f, 1f - rb.drag * dt); rb.vx *= k; rb.vy *= k; rb.vz *= k }
                    move(go, rb.vx * dt, rb.vy * dt, rb.vz * dt)
                }
                1 -> move(go, rb.vx * dt, rb.vy * dt, rb.vz * dt)
                else -> {}
            }
        }
        val bodies = ArrayList<Body>()
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            val col = go.get<Collider3D>() ?: continue
            val b = Body(go, go.get(), col); refresh(b); bodies.add(b)
        }
        val contacts = HashSet<Long>()
        val triggers = HashSet<Long>()
        for (i in bodies.indices) for (j in i + 1 until bodies.size) {
            val a = bodies[i]; val b = bodies[j]
            val trig = a.col.isTrigger || b.col.isTrigger
            if (a.invMass == 0f && b.invMass == 0f && !trig) continue
            val m = collide(a, b) ?: continue
            val key = key(a.go.id, b.go.id)
            if (trig) {
                triggers.add(key)
                if (key !in prevTriggers) listener?.onTriggerEnter(a.go, b.go)
                continue
            }
            contacts.add(key)
            resolve(a, b, m)
            if (key !in prevContacts) listener?.onCollisionEnter(a.go, b.go)
        }
        for (k in prevTriggers) if (k !in triggers) {
            val a = scene.findById(k shr 32); val b = scene.findById(k and 0xFFFFFFFFL)
            if (a != null && b != null) listener?.onTriggerExit(a, b)
        }
        prevContacts = contacts; prevTriggers = triggers
    }

    private fun key(a: Long, b: Long): Long = (min(a, b) shl 32) or (max(a, b) and 0xFFFFFFFFL)

    private fun refresh(b: Body) {
        val w = b.go.computeWorld3()
        val p = Mat4.point(w, b.col.centerX, b.col.centerY, b.col.centerZ)
        b.cx = p[0]; b.cy = p[1]; b.cz = p[2]
        val sx = Mat4.scaleOf(w, 0); val sy = Mat4.scaleOf(w, 1); val sz = Mat4.scaleOf(w, 2)
        b.hx = b.col.sizeX * sx / 2; b.hy = b.col.sizeY * sy / 2; b.hz = b.col.sizeZ * sz / 2
        b.r = b.col.radius * max(sx, max(sy, sz))
    }

    private fun collide(a: Body, b: Body): M? = when {
        !a.sphere && !b.sphere -> boxBox(a, b)
        a.sphere && b.sphere -> sphereSphere(a, b)
        !a.sphere && b.sphere -> boxSphere(a, b)
        else -> boxSphere(b, a)?.let { M(-it.nx, -it.ny, -it.nz, it.depth) }
    }

    private fun boxBox(a: Body, b: Body): M? {
        val dx = b.cx - a.cx; val ox = a.hx + b.hx - abs(dx); if (ox <= 0f) return null
        val dy = b.cy - a.cy; val oy = a.hy + b.hy - abs(dy); if (oy <= 0f) return null
        val dz = b.cz - a.cz; val oz = a.hz + b.hz - abs(dz); if (oz <= 0f) return null
        return when {
            ox <= oy && ox <= oz -> M(if (dx < 0) -1f else 1f, 0f, 0f, ox)
            oy <= oz -> M(0f, if (dy < 0) -1f else 1f, 0f, oy)
            else -> M(0f, 0f, if (dz < 0) -1f else 1f, oz)
        }
    }

    private fun sphereSphere(a: Body, b: Body): M? {
        val dx = b.cx - a.cx; val dy = b.cy - a.cy; val dz = b.cz - a.cz
        val rs = a.r + b.r
        val d2 = dx * dx + dy * dy + dz * dz
        if (d2 >= rs * rs) return null
        val d = sqrt(d2)
        return if (d < 1e-5f) M(0f, 1f, 0f, rs) else M(dx / d, dy / d, dz / d, rs - d)
    }

    private fun boxSphere(box: Body, s: Body): M? {
        val px = s.cx.coerceIn(box.cx - box.hx, box.cx + box.hx)
        val py = s.cy.coerceIn(box.cy - box.hy, box.cy + box.hy)
        val pz = s.cz.coerceIn(box.cz - box.hz, box.cz + box.hz)
        val dx = s.cx - px; val dy = s.cy - py; val dz = s.cz - pz
        val d2 = dx * dx + dy * dy + dz * dz
        if (d2 > s.r * s.r) return null
        if (d2 < 1e-8f) {
            val ox = box.hx - abs(s.cx - box.cx); val oy = box.hy - abs(s.cy - box.cy); val oz = box.hz - abs(s.cz - box.cz)
            return when {
                ox <= oy && ox <= oz -> M(if (s.cx < box.cx) -1f else 1f, 0f, 0f, ox + s.r)
                oy <= oz -> M(0f, if (s.cy < box.cy) -1f else 1f, 0f, oy + s.r)
                else -> M(0f, 0f, if (s.cz < box.cz) -1f else 1f, oz + s.r)
            }
        }
        val d = sqrt(d2)
        return M(dx / d, dy / d, dz / d, s.r - d)
    }

    private fun vel(b: Body): FloatArray {
        val rb = b.rb
        return if (rb == null || rb.bodyType == 2) floatArrayOf(0f, 0f, 0f) else floatArrayOf(rb.vx, rb.vy, rb.vz)
    }

    private fun resolve(a: Body, b: Body, m: M) {
        val ia = a.invMass; val ib = b.invMass; val sum = ia + ib
        if (sum == 0f) return
        val corr = max(m.depth - 0.001f, 0f) / sum * 0.9f
        if (ia > 0f) move(a.go, -m.nx * corr * ia, -m.ny * corr * ia, -m.nz * corr * ia)
        if (ib > 0f) move(b.go, m.nx * corr * ib, m.ny * corr * ib, m.nz * corr * ib)
        if (m.ny < -0.5f) a.rb?.grounded = true
        if (m.ny > 0.5f) b.rb?.grounded = true
        val va = vel(a); val vb = vel(b)
        val rx = vb[0] - va[0]; val ry = vb[1] - va[1]; val rz = vb[2] - va[2]
        val vn = rx * m.nx + ry * m.ny + rz * m.nz
        if (vn > 0f) return
        val e = max(a.rb?.bounciness ?: 0f, b.rb?.bounciness ?: 0f)
        val j = -(1f + e) * vn / sum
        impulse(a, -j * m.nx, -j * m.ny, -j * m.nz)
        impulse(b, j * m.nx, j * m.ny, j * m.nz)
        // friction along tangential relative velocity
        var tx = rx - vn * m.nx; var ty = ry - vn * m.ny; var tz = rz - vn * m.nz
        val tl = sqrt(tx * tx + ty * ty + tz * tz)
        if (tl < 1e-5f) return
        tx /= tl; ty /= tl; tz /= tl
        val mu = sqrt((a.rb?.friction ?: 0.5f) * (b.rb?.friction ?: 0.5f))
        val jt = (-(rx * tx + ry * ty + rz * tz) / sum).coerceIn(-j * mu, j * mu)
        impulse(a, -jt * tx, -jt * ty, -jt * tz)
        impulse(b, jt * tx, jt * ty, jt * tz)
    }

    private fun impulse(b: Body, x: Float, y: Float, z: Float) {
        val inv = b.invMass
        if (inv == 0f) return
        val rb = b.rb ?: return
        rb.vx += x * inv; rb.vy += y * inv; rb.vz += z * inv
    }

    /** Ray cast against colliders; returns the hit object or null. */
    fun raycast(scene: Scene, ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, maxDist: Float): GameObject? {
        var best: GameObject? = null
        var bestT = maxDist
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            val col = go.get<Collider3D>() ?: continue
            val b = Body(go, null, col); refresh(b)
            val t = if (b.sphere) raySphere(ox, oy, oz, dx, dy, dz, b) else rayBox(ox, oy, oz, dx, dy, dz, b)
            if (t != null && t >= 0f && t < bestT) { bestT = t; best = go }
        }
        return best
    }

    private fun raySphere(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, b: Body): Float? {
        val lx = ox - b.cx; val ly = oy - b.cy; val lz = oz - b.cz
        val bb = lx * dx + ly * dy + lz * dz
        val c = lx * lx + ly * ly + lz * lz - b.r * b.r
        val disc = bb * bb - c
        if (disc < 0f) return null
        return -bb - sqrt(disc)
    }

    private fun rayBox(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, b: Body): Float? =
        rayAabb(ox, oy, oz, dx, dy, dz, b.cx - b.hx, b.cy - b.hy, b.cz - b.hz, b.cx + b.hx, b.cy + b.hy, b.cz + b.hz)

    companion object {
        fun rayAabb(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float,
                    minX: Float, minY: Float, minZ: Float, maxX: Float, maxY: Float, maxZ: Float): Float? {
            var tmin = -Float.MAX_VALUE; var tmax = Float.MAX_VALUE
            val o = floatArrayOf(ox, oy, oz); val d = floatArrayOf(dx, dy, dz)
            val mn = floatArrayOf(minX, minY, minZ); val mx = floatArrayOf(maxX, maxY, maxZ)
            for (i in 0 until 3) {
                if (abs(d[i]) < 1e-8f) { if (o[i] < mn[i] || o[i] > mx[i]) return null } else {
                    var t1 = (mn[i] - o[i]) / d[i]; var t2 = (mx[i] - o[i]) / d[i]
                    if (t1 > t2) { val t = t1; t1 = t2; t2 = t }
                    tmin = max(tmin, t1); tmax = min(tmax, t2)
                    if (tmin > tmax) return null
                }
            }
            if (tmax < 0f) return null
            return if (tmin >= 0f) tmin else tmax
        }
    }
}
