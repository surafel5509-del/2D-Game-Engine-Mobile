package com.sengine.engine.math

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A mutable 2D vector. All engine vector math lives here - there is no 3D variant. */
class Vec2(var x: Float = 0f, var y: Float = 0f) {
    fun set(nx: Float, ny: Float): Vec2 { x = nx; y = ny; return this }
    fun set(o: Vec2): Vec2 { x = o.x; y = o.y; return this }
    fun copy() = Vec2(x, y)
    fun add(o: Vec2) { x += o.x; y += o.y }
    fun add(sx: Float, sy: Float) { x += sx; y += sy }
    fun sub(o: Vec2) { x -= o.x; y -= o.y }
    fun scale(s: Float) { x *= s; y *= s }
    fun dot(o: Vec2) = x * o.x + y * o.y
    fun cross(o: Vec2) = x * o.y - y * o.x
    fun length() = sqrt(x * x + y * y)
    fun lengthSq() = x * x + y * y
    fun normalize(): Vec2 {
        val l = length()
        if (l > 1e-8f) { x /= l; y /= l }
        return this
    }
    /** Rotates the vector by [deg] degrees. */
    fun rotate(deg: Float): Vec2 {
        val r = Math.toRadians(deg.toDouble())
        val cs = cos(r).toFloat(); val sn = sin(r).toFloat()
        val nx = x * cs - y * sn; val ny = x * sn + y * cs
        x = nx; y = ny
        return this
    }
    fun perp() = Vec2(-y, x)
    val angleDeg get() = Math.toDegrees(atan2(y, x).toDouble()).toFloat()
    override fun toString() = "($x, $y)"
    companion object {
        fun of(x: Float, y: Float) = Vec2(x, y)
        val ZERO get() = Vec2(0f, 0f)
        fun fromAngle(deg: Float, len: Float = 1f): Vec2 {
            val r = Math.toRadians(deg.toDouble())
            return Vec2(cos(r).toFloat() * len, sin(r).toFloat() * len)
        }
    }
}

/** Axis-aligned rectangle in world units. */
class Rect2(var x: Float = 0f, var y: Float = 0f, var w: Float = 0f, var h: Float = 0f) {
    val left get() = x
    val right get() = x + w
    val top get() = y
    val bottom get() = y + h
    val centerX get() = x + w * 0.5f
    val centerY get() = y + h * 0.5f

    fun set(nx: Float, ny: Float, nw: Float, nh: Float): Rect2 { x = nx; y = ny; w = nw; h = nh; return this }
    fun set(o: Rect2): Rect2 = set(o.x, o.y, o.w, o.h)
    fun containsX(px: Float) = px >= left && px <= right
    fun containsY(py: Float) = py >= top && py <= bottom
    fun contains(px: Float, py: Float) = containsX(px) && containsY(py)
    fun intersects(o: Rect2) = !(o.left > right || o.right < left || o.top > bottom || o.bottom < top)
    fun intersects(l: Float, t: Float, r: Float, b: Float) = !(l > right || r < left || t > bottom || b < top)
    fun union(o: Rect2): Rect2 {
        val l = min(left, o.left); val t = min(top, o.top)
        val r = max(right, o.right); val b = max(bottom, o.bottom)
        return Rect2(l, t, r - l, b - t)
    }
    fun grow(pad: Float): Rect2 = Rect2(x - pad, y - pad, w + pad * 2, h + pad * 2)
    fun expandToInclude(px: Float, py: Float) {
        val l = min(left, px); val t = min(top, py)
        val r = max(right, px); val b = max(bottom, py)
        x = l; y = t; w = r - l; h = b - t
    }
    val isEmpty get() = w <= 0f || h <= 0f
    fun copy() = Rect2(x, y, w, h)
    override fun toString() = "Rect2($x, $y, $w, $h)"
    companion object {
        fun invalid() = Rect2(0f, 0f, -1f, -1f)
        fun fromCenter(cx: Float, cy: Float, w: Float, h: Float) = Rect2(cx - w * 0.5f, cy - h * 0.5f, w, h)
    }
}

/** Scalar helpers shared by gameplay, physics, animation and the editor. */
object M {
    const val PI = Math.PI.toFloat()
    const val TAU = (Math.PI * 2).toFloat()
    const val DEG2RAD = (Math.PI / 180.0).toFloat()
    const val RAD2DEG = (180.0 / Math.PI).toFloat()
    const val EPS = 1e-6f

    fun clamp(v: Float, lo: Float, hi: Float) = if (v < lo) lo else if (v > hi) hi else v
    fun clamp01(v: Float) = clamp(v, 0f, 1f)
    fun clampI(v: Int, lo: Int, hi: Int) = if (v < lo) lo else if (v > hi) hi else v
    fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    fun lerpUnclamped(a: Float, b: Float, t: Float) = a + (b - a) * t
    fun invLerp(a: Float, b: Float, v: Float) = if (abs(b - a) < EPS) 0f else (v - a) / (b - a)
    fun remap(a: Float, b: Float, c: Float, d: Float, v: Float) = lerp(c, d, invLerp(a, b, v))

    /**
     * Frame-rate independent exponential smoothing. [rate] is roughly "how many times per second
     * the gap is closed"; 0 keeps the current value, large values converge instantly.
     */
    fun damp(current: Float, target: Float, rate: Float, dt: Float): Float =
        if (rate <= 0f) target else lerp(current, target, 1f - kotlin.math.exp(-rate * dt))

    fun moveTowards(current: Float, target: Float, maxDelta: Float): Float {
        val d = target - current
        return if (abs(d) <= maxDelta) target else current + maxDelta * (if (d > 0f) 1f else -1f)
    }

    fun smoothStep(t: Float): Float { val c = clamp01(t); return c * c * (3f - 2f * c) }
    fun smootherStep(t: Float): Float { val c = clamp01(t); return c * c * c * (c * (c * 6f - 15f) + 10f) }
    fun easeIn(t: Float) = clamp01(t).let { it * it }
    fun easeOut(t: Float) = clamp01(t).let { 1f - (1f - it) * (1f - it) }
    fun easeInOut(t: Float) = if (t < 0.5f) 2f * t * t else 1f - 2f * (1f - t) * (1f - t)
    fun pingPong(t: Float, length: Float): Float {
        if (length <= EPS) return 0f
        val l = t % (length * 2f)
        return if (l < 0f) l + length * 2f else if (l <= length) l else length * 2f - l
    }

    fun wrapAngle(deg: Float): Float {
        var a = deg % 360f
        if (a > 180f) a -= 360f
        if (a < -180f) a += 360f
        return a
    }
    fun deltaAngle(from: Float, to: Float) = wrapAngle(to - from)
    fun lerpAngle(from: Float, to: Float, t: Float) = from + deltaAngle(from, to) * t
    fun dampAngle(from: Float, to: Float, rate: Float, dt: Float) = from + deltaAngle(from, to) * (if (rate <= 0f) 1f else 1f - kotlin.math.exp(-rate * dt))

    fun dist(x1: Float, y1: Float, x2: Float, y2: Float) = sqrt(distSq(x1, y1, x2, y2))
    fun distSq(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1; val dy = y2 - y1
        return dx * dx + dy * dy
    }
    fun distPointToSegmentSq(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val lenSq = dx * dx + dy * dy
        var t = if (lenSq < EPS) 0f else ((px - ax) * dx + (py - ay) * dy) / lenSq
        t = clamp01(t)
        val cx = ax + dx * t; val cy = ay + dy * t
        return distSq(px, py, cx, cy)
    }
    fun pointInTriangle(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Boolean {
        val d1 = (px - bx) * (ay - by) - (ax - bx) * (py - by)
        val d2 = (px - cx) * (by - cy) - (bx - cx) * (py - cy)
        val d3 = (px - ax) * (cy - ay) - (cx - ax) * (py - ay)
        val hasNeg = d1 < 0f || d2 < 0f || d3 < 0f
        val hasPos = d1 > 0f || d2 > 0f || d3 > 0f
        return !(hasNeg && hasPos)
    }
    fun sign(v: Float) = if (v > 0f) 1f else if (v < 0f) -1f else 0f
    fun approachZero(v: Float, amount: Float) = moveTowards(v, 0f, amount)
    fun min(a: Float, b: Float, c: Float) = min(a, min(b, c))
    fun max(a: Float, b: Float, c: Float) = max(a, max(b, c))
    fun floorTo(v: Float, step: Float) = if (step <= EPS) v else floor(v / step) * step
    fun roundTo(v: Float, step: Float) = if (step <= EPS) v else (v / step).roundToInt() * step
    fun snap(v: Float, step: Float) = if (step <= EPS) v else (v / step).roundToInt() * step
    fun ceilI(v: Float) = ceil(v).toInt()
    fun floorI(v: Float) = floor(v).toInt()

    /** Converts HSV (h in degrees, s/v in 0..1) to packed ARGB. */
    fun hsvToArgb(h: Float, s: Float, v: Float, alpha: Int = 255): Int {
        val hh = ((h % 360f) + 360f) % 360f / 60f
        val i = floor(hh).toInt()
        val f = hh - i
        val p = v * (1f - s)
        val q = v * (1f - s * f)
        val t = v * (1f - s * (1f - f))
        val (r, g, b) = when (i) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
        return (clamp01(alpha / 255f) * 255f).toInt().coerceIn(0, 255) shl 24 or
            ((r * 255f).toInt().coerceIn(0, 255) shl 16) or
            ((g * 255f).toInt().coerceIn(0, 255) shl 8) or
            (b * 255f).toInt().coerceIn(0, 255)
    }
}
