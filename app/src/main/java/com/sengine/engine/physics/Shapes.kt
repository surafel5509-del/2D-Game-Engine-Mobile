package com.sengine.engine.physics

import com.sengine.engine.math.M
import com.sengine.engine.math.Rect2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Convex 2D collision shape. Every shape is defined in *local* space around its centre of mass
 * and is transformed to world space on demand. Strictly 2D - there is no 3D shape, mesh or
 * volume anywhere in the engine.
 */
sealed class Shape2D {
    abstract val type: Int

    /** Local-space vertices for polygons (circle/capsule produce a polygon for queries). */
    abstract fun localVertices(): FloatArray

    abstract fun computeMass(density: Float): MassData

    /** World-space AABB into [out]. */
    fun computeAabb(cx: Float, cy: Float, angleRad: Float, cosA: Float, sinA: Float, out: Rect2) {
        val v = localVertices()
        if (v.isEmpty()) {
            // Vertex-less shapes (circles) have no local points: fall back to their bounding radius
            // so the broadphase AABB is always valid.
            val r = boundingRadius()
            out.set(cx - r, cy - r, r * 2f, r * 2f)
            return
        }
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var i = 0
        while (i < v.size) {
            val lx = v[i]; val ly = v[i + 1]
            val wx = cx + lx * cosA - ly * sinA
            val wy = cy + lx * sinA + ly * cosA
            if (wx < minX) minX = wx
            if (wy < minY) minY = wy
            if (wx > maxX) maxX = wx
            if (wy > maxY) maxY = wy
            i += 2
        }
        out.set(minX, minY, maxX - minX, maxY - minY)
    }

    /** Farthest point along [dir] - used by GJK-style queries and CCD. */
    open fun support(cx: Float, cy: Float, cosA: Float, sinA: Float, dx: Float, dy: Float, out: FloatArray) {
        val v = localVertices()
        var best = -Float.MAX_VALUE
        var bx = cx; var by = cy
        var i = 0
        while (i < v.size) {
            val wx = cx + v[i] * cosA - v[i + 1] * sinA
            val wy = cy + v[i] * sinA + v[i + 1] * cosA
            val d = wx * dx + wy * dy
            if (d > best) { best = d; bx = wx; by = wy }
            i += 2
        }
        out[0] = bx; out[1] = by
    }

    /** Radius of the bounding circle. */
    abstract fun boundingRadius(): Float

    /** Deepest penetration of [px],[py] (world) if inside, otherwise 0. Used by point queries. */
    abstract fun pointDepth(cx: Float, cy: Float, cosA: Float, sinA: Float, px: Float, py: Float): Float

    abstract fun clone(): Shape2D

    companion object {
        const val CIRCLE = 0
        const val BOX = 1
        const val POLYGON = 2
        const val CAPSULE = 3
    }
}

class MassData(var mass: Float = 1f, var inertia: Float = 1f, var centerX: Float = 0f, var centerY: Float = 0f)

class CircleShape(var radius: Float = 0.5f) : Shape2D() {
    override val type get() = Shape2D.CIRCLE
    override fun localVertices(): FloatArray = FloatArray(0)
    override fun boundingRadius() = radius

    override fun computeMass(density: Float): MassData {
        val m = density * M.PI * radius * radius
        return MassData(m, 0.5f * m * radius * radius)
    }

    override fun pointDepth(cx: Float, cy: Float, cosA: Float, sinA: Float, px: Float, py: Float): Float {
        val dx = px - cx; val dy = py - cy
        val d2 = dx * dx + dy * dy
        return if (d2 >= radius * radius) 0f else sqrt(radius * radius - d2)
    }

    override fun clone() = CircleShape(radius)
}

/** Convex polygon, vertices in local space, counter-clockwise. */
class PolygonShape(vertices: FloatArray) : Shape2D() {
    /** Local vertices (x0, y0, x1, y1, ...) */
    val vertices: FloatArray = vertices.copyOf()

    /** Outward normals for each edge. */
    val normals: FloatArray = FloatArray(vertices.size).also { n ->
        val count = vertices.size / 2
        for (i in 0 until count) {
            val j = (i + 1) % count
            val ex = vertices[j * 2] - vertices[i * 2]
            val ey = vertices[j * 2 + 1] - vertices[i * 2 + 1]
            val len = sqrt(ex * ex + ey * ey)
            if (len > 1e-8f) {
                n[i * 2] = ey / len
                n[i * 2 + 1] = -ex / len
            }
        }
    }

    val count get() = vertices.size / 2

    init {
        require(vertices.size >= 6) { "A polygon needs at least 3 vertices" }
    }

    override val type get() = Shape2D.POLYGON
    override fun localVertices() = vertices

    override fun boundingRadius(): Float {
        var r = 0f
        var i = 0
        while (i < vertices.size) {
            val d = vertices[i] * vertices[i] + vertices[i + 1] * vertices[i + 1]
            if (d > r) r = d
            i += 2
        }
        return sqrt(r)
    }

    /** Centroid of the polygon (area weighted). */
    fun centroid(out: FloatArray) {
        var cx = 0f; var cy = 0f; var area = 0f
        val n = count
        for (i in 0 until n) {
            val j = (i + 1) % n
            val x0 = vertices[i * 2]; val y0 = vertices[i * 2 + 1]
            val x1 = vertices[j * 2]; val y1 = vertices[j * 2 + 1]
            val cross = x0 * y1 - x1 * y0
            area += cross
            cx += (x0 + x1) * cross
            cy += (y0 + y1) * cross
        }
        if (abs(area) < 1e-8f) { out[0] = 0f; out[1] = 0f; return }
        out[0] = cx / (3f * area)
        out[1] = cy / (3f * area)
    }

    override fun computeMass(density: Float): MassData {
        var area = 0f
        var cx = 0f; var cy = 0f
        var inertia = 0f
        val n = count
        val k = 1f / 3f
        for (i in 0 until n) {
            val j = (i + 1) % n
            val x0 = vertices[i * 2]; val y0 = vertices[i * 2 + 1]
            val x1 = vertices[j * 2]; val y1 = vertices[j * 2 + 1]
            val cross = x0 * y1 - x1 * y0
            val triArea = 0.5f * cross
            area += triArea
            cx += triArea * k * (x0 + x1)
            cy += triArea * k * (y0 + y1)
            val intx2 = x0 * x0 + x1 * x0 + x1 * x1
            val inty2 = y0 * y0 + y1 * y0 + y1 * y1
            inertia += (0.25f * k * cross) * (intx2 + inty2)
        }
        val m = density * abs(area)
        // shift inertia to the centroid
        val ccx = if (abs(area) > 1e-8f) cx / area else 0f
        val ccy = if (abs(area) > 1e-8f) cy / area else 0f
        return MassData(m, density * abs(inertia), ccx, ccy)
    }

    override fun pointDepth(cx: Float, cy: Float, cosA: Float, sinA: Float, px: Float, py: Float): Float {
        val lx = (px - cx) * cosA + (py - cy) * sinA
        val ly = -(px - cx) * sinA + (py - cy) * cosA
        var deepest = Float.MAX_VALUE
        val n = count
        for (i in 0 until n) {
            val nx = normals[i * 2]; val ny = normals[i * 2 + 1]
            val d = nx * (lx - vertices[i * 2]) + ny * (ly - vertices[i * 2 + 1])
            if (d < deepest) deepest = d
        }
        return if (deepest >= 0f) 0f else -deepest
    }

    override fun clone() = PolygonShape(vertices)

    companion object {
        fun box(halfWidth: Float, halfHeight: Float) = PolygonShape(
            floatArrayOf(
                -halfWidth, -halfHeight,
                halfWidth, -halfHeight,
                halfWidth, halfHeight,
                -halfWidth, halfHeight
            )
        )

        /** Box from full (not half) extents - clearer name than an overload of [box]. */
        fun ofSize(width: Float, height: Float) = box(width * 0.5f, height * 0.5f)

        /** Regular polygon approximating a circle (for queries and CCD). */
        fun circle(radius: Float, segments: Int = 10): PolygonShape {
            val n = segments.coerceAtLeast(3)
            val v = FloatArray(n * 2)
            for (i in 0 until n) {
                val a = M.TAU * i / n
                v[i * 2] = cos(a) * radius
                v[i * 2 + 1] = sin(a) * radius
            }
            return PolygonShape(v)
        }

        /** Vertical capsule approximated as a convex polygon with rounded caps. */
        fun capsule(radius: Float, height: Float, segments: Int = 8): PolygonShape {
            val half = max(0f, height * 0.5f - radius)
            val n = segments.coerceAtLeast(3)
            val pts = ArrayList<Float>((n * 2 + 2) * 2)
            // top cap (from 0..pi), bottom cap (pi..2pi)
            for (i in 0..n) {
                val a = M.PI * i / n
                pts.add(cos(a) * radius); pts.add(half + sin(a) * radius)
            }
            for (i in 0..n) {
                val a = M.PI + M.PI * i / n
                pts.add(cos(a) * radius); pts.add(-half + sin(a) * radius)
            }
            return PolygonShape(pts.toFloatArray())
        }

        /** Convex hull of a point cloud (used by the editor's polygon tool and SVG import). */
        fun convexHull(points: FloatArray): PolygonShape? {
            val n = points.size / 2
            if (n < 3) return null
            val idx = (0 until n).sortedWith(compareBy({ points[it * 2] }, { points[it * 2 + 1] }))
            val hull = ArrayList<Int>()
            fun cross(o: Int, a: Int, b: Int): Float =
                (points[a * 2] - points[o * 2]) * (points[b * 2 + 1] - points[o * 2 + 1]) -
                    (points[a * 2 + 1] - points[o * 2 + 1]) * (points[b * 2] - points[o * 2])
            for (i in idx) {
                while (hull.size >= 2 && cross(hull[hull.size - 2], hull[hull.size - 1], i) <= 0f) hull.removeAt(hull.size - 1)
                hull.add(i)
            }
            val lower = hull.size + 1
            for (i in idx.reversed()) {
                while (hull.size >= lower && cross(hull[hull.size - 2], hull[hull.size - 1], i) <= 0f) hull.removeAt(hull.size - 1)
                hull.add(i)
            }
            if (hull.size < 4) return null
            val out = FloatArray((hull.size - 1) * 2)
            for (i in 0 until hull.size - 1) {
                out[i * 2] = points[hull[i] * 2]
                out[i * 2 + 1] = points[hull[i] * 2 + 1]
            }
            return PolygonShape(out)
        }
    }
}

/** Convenience factory used by components and the editor tools. */
object Shapes {
    fun box(width: Float, height: Float): Shape2D = PolygonShape.ofSize(max(0.001f, width), max(0.001f, height))
    fun circle(radius: Float): Shape2D = CircleShape(max(0.001f, radius))
    fun polygon(points: FloatArray): Shape2D = PolygonShape(points)
    fun capsule(radius: Float, height: Float): Shape2D = PolygonShape.capsule(radius, height)
}

/** Ray/shape query result. */
class RayHit(
    val body: Body2D?,
    val pointX: Float,
    val pointY: Float,
    val normalX: Float,
    val normalY: Float,
    /** Fraction of the cast distance (0..1). */
    val fraction: Float
) {
    val go get() = body?.go
}

class SweepHit(
    val body: Body2D?,
    val fraction: Float,
    val pointX: Float,
    val pointY: Float,
    val normalX: Float,
    val normalY: Float
) {
    val go get() = body?.go
}

internal fun clampF(v: Float, lo: Float, hi: Float) = min(max(v, lo), hi)
