package com.sengine.engine.physics

import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.core.Scene
import kotlin.math.sqrt
import kotlin.math.max
import kotlin.math.abs

/**
 * Raycast system for 2D physics.
 * Casts rays from a point in a direction and returns the first hit.
 * Supports box and circle colliders, collision layers, and closest-hit queries.
 */

data class RaycastHit(
    val gameObject: GameObject,
    val x: Float,
    val y: Float,
    val normalX: Float,
    val normalY: Float,
    val distance: Float,
    val fraction: Float // 0..1 along the ray
)

data class RaycastQuery(
    val originX: Float,
    val originY: Float,
    val dirX: Float,
    val dirY: Float,
    val maxDistance: Float = Float.MAX_VALUE,
    val layerMask: Int = -1, // -1 = all layers
    val excludeTriggers: Boolean = false,
    val excludeStatic: Boolean = false
)

object Raycaster {

    /** Cast a ray and return the closest hit, or null. */
    fun raycast(scene: Scene, query: RaycastQuery): RaycastHit? {
        val len = sqrt(query.dirX * query.dirX + query.dirY * query.dirY)
        if (len < 1e-8f) return null
        val dx = query.dirX / len
        val dy = query.dirY / len
        val endX = query.originX + dx * query.maxDistance
        val endY = query.originY + dy * query.maxDistance

        var closest: RaycastHit? = null
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            val col = go.getAny<Collider2D>() ?: continue
            if (query.excludeTriggers && col.isTrigger) continue
            val rb = go.getAny<Rigidbody2D>()
            if (query.excludeStatic && rb != null && rb.bodyType == 2) continue

            // Check collision layer
            val layer = getLayer(go)
            if (query.layerMask != -1 && (query.layerMask and (1 shl layer)) == 0) continue

            val w = go.computeWorld()
            val cx = w.mapX(col.offsetX, col.offsetY)
            val cy = w.mapY(col.offsetX, col.offsetY)
            val sx = w.scaleX
            val sy = w.scaleY

            val hit = if (col.shape == 1) {
                // Circle
                rayCircle(query.originX, query.originY, dx, dy, cx, cy, col.radius * max(sx, sy))
            } else {
                // Box (AABB approximation for raycast)
                val hw = col.width * sx * 0.5f
                val hh = col.height * sy * 0.5f
                rayBox(query.originX, query.originY, dx, dy, cx, cy, hw, hh)
            }

            if (hit != null) {
                val (frac, nx, ny) = hit
                val dist = frac * len
                if (dist <= query.maxDistance && (closest == null || dist < closest.distance)) {
                    closest = RaycastHit(
                        go,
                        query.originX + dx * dist,
                        query.originY + dy * dist,
                        nx, ny, dist, frac
                    )
                }
            }
        }
        return closest
    }

    /** Cast a ray and return all hits along its path. */
    fun raycastAll(scene: Scene, query: RaycastQuery): List<RaycastHit> {
        val len = sqrt(query.dirX * query.dirX + query.dirY * query.dirY)
        if (len < 1e-8f) return emptyList()
        val dx = query.dirX / len
        val dy = query.dirY / len

        val hits = ArrayList<RaycastHit>()
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            val col = go.getAny<Collider2D>() ?: continue
            if (query.excludeTriggers && col.isTrigger) continue

            val layer = getLayer(go)
            if (query.layerMask != -1 && (query.layerMask and (1 shl layer)) == 0) continue

            val w = go.computeWorld()
            val cx = w.mapX(col.offsetX, col.offsetY)
            val cy = w.mapY(col.offsetX, col.offsetY)
            val sx = w.scaleX
            val sy = w.scaleY

            val hit = if (col.shape == 1) {
                rayCircle(query.originX, query.originY, dx, dy, cx, cy, col.radius * max(sx, sy))
            } else {
                val hw = col.width * sx * 0.5f
                val hh = col.height * sy * 0.5f
                rayBox(query.originX, query.originY, dx, dy, cx, cy, hw, hh)
            }

            if (hit != null) {
                val (frac, nx, ny) = hit
                val dist = frac * len
                if (dist <= query.maxDistance) {
                    hits.add(RaycastHit(go, query.originX + dx * dist, query.originY + dy * dist, nx, ny, dist, frac))
                }
            }
        }
        return hits.sortedBy { it.distance }
    }

    /** Overlap circle: find all colliders within a radius. */
    fun overlapCircle(scene: Scene, cx: Float, cy: Float, radius: Float, layerMask: Int = -1): List<GameObject> {
        val result = ArrayList<GameObject>()
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            val col = go.getAny<Collider2D>() ?: continue
            val layer = getLayer(go)
            if (layerMask != -1 && (layerMask and (1 shl layer)) == 0) continue

            val w = go.computeWorld()
            val ox = w.mapX(col.offsetX, col.offsetY)
            val oy = w.mapY(col.offsetX, col.offsetY)
            val sx = w.scaleX
            val sy = w.scaleY

            val hit = if (col.shape == 1) {
                val r = col.radius * max(sx, sy)
                val ddx = ox - cx; val ddy = oy - cy
                sqrt(ddx * ddx + ddy * ddy) <= radius + r
            } else {
                val hw = col.width * sx * 0.5f
                val hh = col.height * sy * 0.5f
                // AABB vs circle overlap
                val closestX = cx.coerceIn(ox - hw, ox + hw)
                val closestY = cy.coerceIn(oy - hh, oy + hh)
                val ddx = cx - closestX; val ddy = cy - closestY
                sqrt(ddx * ddx + ddy * ddy) <= radius
            }
            if (hit) result.add(go)
        }
        return result
    }

    /** Overlap area (AABB): find all colliders within a rectangular area. */
    fun overlapArea(scene: Scene, minX: Float, minY: Float, maxX: Float, maxY: Float, layerMask: Int = -1): List<GameObject> {
        val result = ArrayList<GameObject>()
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            val col = go.getAny<Collider2D>() ?: continue
            val layer = getLayer(go)
            if (layerMask != -1 && (layerMask and (1 shl layer)) == 0) continue

            val w = go.computeWorld()
            val ox = w.mapX(col.offsetX, col.offsetY)
            val oy = w.mapY(col.offsetX, col.offsetY)
            val sx = w.scaleX
            val sy = w.scaleY

            val hit = if (col.shape == 1) {
                val r = col.radius * max(sx, sy)
                val closestX = ox.coerceIn(minX, maxX)
                val closestY = oy.coerceIn(minY, maxY)
                val ddx = ox - closestX; val ddy = oy - closestY
                ddx * ddx + ddy * ddy <= r * r
            } else {
                val hw = col.width * sx * 0.5f
                val hh = col.height * sy * 0.5f
                // AABB-AABB overlap
                abs(ox - (minX + maxX) * 0.5f) <= hw + (maxX - minX) * 0.5f &&
                    abs(oy - (minY + maxY) * 0.5f) <= hh + (maxY - minY) * 0.5f
            }
            if (hit) result.add(go)
        }
        return result
    }

    // --- Private ray-shape tests. Returns (fraction, normalX, normalY) or null. ---

    private fun rayCircle(ox: Float, oy: Float, dx: Float, dy: Float, cx: Float, cy: Float, r: Float): Triple<Float, Float, Float>? {
        val fx = ox - cx
        val fy = oy - cy
        val a = dx * dx + dy * dy
        val b = 2f * (fx * dx + fy * dy)
        val c = fx * fx + fy * fy - r * r
        val disc = b * b - 4 * a * c
        if (disc < 0f) return null
        val sq = sqrt(disc)
        var t = (-b - sq) / (2f * a)
        if (t < 0f) t = (-b + sq) / (2f * a)
        if (t < 0f || t > 1f) return null
        val hx = ox + dx * t - cx
        val hy = oy + dy * t - cy
        val len = sqrt(hx * hx + hy * hy)
        return if (len < 1e-8f) Triple(t, dx, dy) else Triple(t, hx / len, hy / len)
    }

    private fun rayBox(ox: Float, oy: Float, dx: Float, dy: Float, cx: Float, cy: Float, hw: Float, hh: Float): Triple<Float, Float, Float>? {
        val minX = cx - hw; val maxX = cx + hw
        val minY = cy - hh; val maxY = cy + hh
        var tmin = 0f
        var tmax = 1f
        var hitNormalX = 0f
        var hitNormalY = 0f

        // X slab
        if (abs(dx) < 1e-8f) {
            if (ox < minX || ox > maxX) return null
        } else {
            var t1 = (minX - ox) / dx
            var t2 = (maxX - ox) / dx
            var n1x = -1f; var n1y = 0f
            if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp; n1x = 1f }
            if (t1 > tmin) { tmin = t1; hitNormalX = n1x; hitNormalY = n1y }
            tmax = minOf(tmax, t2)
            if (tmin > tmax) return null
        }

        // Y slab
        if (abs(dy) < 1e-8f) {
            if (oy < minY || oy > maxY) return null
        } else {
            var t1 = (minY - oy) / dy
            var t2 = (maxY - oy) / dy
            var n1x = 0f; var n1y = -1f
            if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp; n1y = 1f }
            if (t1 > tmin) { tmin = t1; hitNormalX = n1x; hitNormalY = n1y }
            tmax = minOf(tmax, t2)
            if (tmin > tmax) return null
        }

        return Triple(tmin, hitNormalX, hitNormalY)
    }

    private fun getLayer(go: GameObject): Int {
        // Layer stored in tag or a custom property; default to 0
        return go.tag.hashCode() and 31
    }
}
