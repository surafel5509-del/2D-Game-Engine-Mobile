package com.sengine.engine.math

/**
 * A 2D animation curve: a list of keyframes with a value and an interpolation mode.
 * Used by particles (size/alpha/velocity over lifetime), animation timelines and easing.
 */
class CurveKey(
    var t: Float,
    var value: Float,
    /** 0 = linear, 1 = smooth (ease in/out), 2 = constant (step), 3 = bezier in/out handles. */
    var mode: Int = 0,
    var inTangent: Float = 0f,
    var outTangent: Float = 0f
)

class AnimationCurve {
    val keys = ArrayList<CurveKey>()

    constructor()
    constructor(vararg values: Float) {
        // evenly spaced keys across 0..1
        if (values.size == 1) keys.add(CurveKey(0f, values[0]))
        else values.forEachIndexed { i, v -> keys.add(CurveKey(i / (values.size - 1f), v)) }
    }

    val isEmpty get() = keys.isEmpty()
    val startTime get() = keys.firstOrNull()?.t ?: 0f
    val endTime get() = keys.lastOrNull()?.t ?: 1f

    fun add(t: Float, value: Float, mode: Int = 0): AnimationCurve {
        keys.add(CurveKey(t, value, mode))
        keys.sortBy { it.t }
        return this
    }

    fun clear(): AnimationCurve { keys.clear(); return this }

    /** Samples the curve at [t]; keys are expected to be sorted by time. */
    fun evaluate(t: Float): Float {
        if (keys.isEmpty()) return 0f
        if (keys.size == 1) return keys[0].value
        if (t <= keys[0].t) return keys[0].value
        val last = keys[keys.size - 1]
        if (t >= last.t) return last.value
        var i = 0
        while (i < keys.size - 2 && keys[i + 1].t <= t) i++
        val a = keys[i]
        val b = keys[i + 1]
        val span = b.t - a.t
        val u = if (span <= M.EPS) 0f else (t - a.t) / span
        return when (b.mode) {
            2 -> a.value
            1 -> M.lerp(a.value, b.value, M.smoothStep(u))
            3 -> {
                // cubic bezier with the two tangents as handles (normalised slope terms)
                val p0 = a.value; val p3 = b.value
                val p1 = p0 + a.outTangent * span
                val p2 = p3 - b.inTangent * span
                val mu = 1f - u
                mu * mu * mu * p0 + 3f * mu * mu * u * p1 + 3f * mu * u * u * p2 + u * u * u * p3
            }
            else -> M.lerp(a.value, b.value, u)
        }
    }

    /** A constant curve - handy default. */
    companion object {
        fun constant(v: Float) = AnimationCurve(v)
        fun linear(from: Float, to: Float) = AnimationCurve().add(0f, from).add(1f, to)
        /** Sample a curve from a compact spec string: "0:1, 0.5:2, 1:0" (falls back to [fallback]). */
        fun parse(spec: String, fallback: Float): AnimationCurve {
            val c = AnimationCurve()
            for (part in spec.split(',')) {
                val kv = part.trim().split(':')
                if (kv.size == 2) {
                    val t = kv[0].trim().toFloatOrNull() ?: continue
                    val v = kv[1].trim().toFloatOrNull() ?: continue
                    c.add(t, v)
                }
            }
            if (c.isEmpty) c.add(0f, fallback)
            return c
        }
        fun toSpec(c: AnimationCurve): String =
            c.keys.joinToString(", ") { "${fmt(it.t)}:${fmt(it.value)}" }

        private fun fmt(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()
    }
}

class GradientStop(var t: Float, var color: Int)

/**
 * Colour gradient with packed ARGB stops. Used by particles, lights, trails and the editor
 * colour ramp widgets.
 */
class ColorGradient {
    val stops = ArrayList<GradientStop>()

    constructor()
    constructor(vararg colors: Int) {
        if (colors.size == 1) stops.add(GradientStop(0f, colors[0]))
        else colors.forEachIndexed { i, c -> stops.add(GradientStop(i / (colors.size - 1f), c)) }
    }

    val isEmpty get() = stops.isEmpty()

    fun add(t: Float, color: Int): ColorGradient {
        stops.add(GradientStop(t, color))
        stops.sortBy { it.t }
        return this
    }

    fun clear(): ColorGradient { stops.clear(); return this }

    /** Samples at [t] (0..1) with linear interpolation between stops. */
    fun evaluate(t: Float): Int {
        if (stops.isEmpty()) return 0xFFFFFFFF.toInt()
        if (stops.size == 1) return stops[0].color
        if (t <= stops[0].t) return stops[0].color
        val last = stops[stops.size - 1]
        if (t >= last.t) return last.color
        var i = 0
        while (i < stops.size - 2 && stops[i + 1].t <= t) i++
        val a = stops[i]
        val b = stops[i + 1]
        val span = b.t - a.t
        val u = if (span <= M.EPS) 0f else (t - a.t) / span
        return lerpArgb(a.color, b.color, u)
    }

    fun copy(): ColorGradient {
        val g = ColorGradient()
        stops.forEach { g.stops.add(GradientStop(it.t, it.color)) }
        return g
    }

    /** "0.0:#FFAA00, 1.0:#FF0000" */
    fun toSpec(): String = stops.joinToString(", ") { "${it.t}:${Colors.toHex(it.color)}" }

    companion object {
        fun lerpArgb(c1: Int, c2: Int, t: Float): Int {
            val a = M.lerp(((c1 ushr 24) and 0xFF).toFloat(), ((c2 ushr 24) and 0xFF).toFloat(), t).toInt()
            val r = M.lerp(((c1 shr 16) and 0xFF).toFloat(), ((c2 shr 16) and 0xFF).toFloat(), t).toInt()
            val g = M.lerp(((c1 shr 8) and 0xFF).toFloat(), ((c2 shr 8) and 0xFF).toFloat(), t).toInt()
            val b = M.lerp((c1 and 0xFF).toFloat(), (c2 and 0xFF).toFloat(), t).toInt()
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        fun of(vararg stops: Pair<Float, Int>): ColorGradient {
            val g = ColorGradient()
            stops.forEach { g.add(it.first, it.second) }
            return g
        }

        fun parse(spec: String, fallback: Int = 0xFFFFFFFF.toInt()): ColorGradient {
            val g = ColorGradient()
            for (part in spec.split(',')) {
                val kv = part.trim().split(':')
                if (kv.size == 2) {
                    val t = kv[0].trim().toFloatOrNull() ?: continue
                    g.add(t, Colors.parse(kv[1].trim()))
                }
            }
            if (g.isEmpty) g.add(0f, fallback)
            return g
        }
    }
}

/** Packed ARGB helpers. S Engine uses `Int` colours everywhere (single 2D colour space). */
object Colors {
    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()
    const val CLEAR = 0x00000000
    const val RED = 0xFFFF3B30.toInt()
    const val GREEN = 0xFF34C759.toInt()
    const val BLUE = 0xFF0A84FF.toInt()
    const val YELLOW = 0xFFFFD60A.toInt()

    fun parse(s: String, fallback: Int = WHITE): Int {
        var h = s.trim().removePrefix("#")
        return try {
            if (h.length == 6) h = "FF$h"
            if (h.length != 8) fallback else h.toLong(16).toInt()
        } catch (_: Exception) {
            fallback
        }
    }

    fun toHex(c: Int): String = String.format("#%08X", c)

    fun rgba(r: Int, g: Int, b: Int, a: Int = 255): Int =
        (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun withAlpha(c: Int, a: Float): Int = (c and 0x00FFFFFF) or (M.clamp01(a).times(255f).toInt().coerceIn(0, 255) shl 24)
    fun withAlphaI(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)
    fun alpha(c: Int) = (c ushr 24) and 0xFF
    fun red(c: Int) = (c shr 16) and 0xFF
    fun green(c: Int) = (c shr 8) and 0xFF
    fun blue(c: Int) = c and 0xFF
    fun multiply(c: Int, other: Int): Int = rgba(
        red(c) * red(other) / 255, green(c) * green(other) / 255,
        blue(c) * blue(other) / 255, alpha(c) * alpha(other) / 255
    )
    fun scale(c: Int, f: Float, alphaScale: Float = 1f): Int = rgba(
        (red(c) * f).toInt(), (green(c) * f).toInt(), (blue(c) * f).toInt(),
        (alpha(c) * alphaScale).toInt()
    )
    /** 0xRRGGBB text (no alpha) - used by Android views and the editor theme. */
    fun toRgbHex(c: Int): String = String.format("#%06X", c and 0xFFFFFF)

    /** Blends two ARGB colours (used by trails, gradients and the UI hover animations). */
    fun lerpArgb(c1: Int, c2: Int, t: Float): Int = ColorGradient.lerpArgb(c1, c2, t)
}
