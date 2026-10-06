package com.sengine.engine.math

import kotlin.math.floor

/**
 * Tiny deterministic value noise + fBm. Used by particle turbulence, procedural terrain
 * (Hill Climb style maps), camera shake and 2D screen effects. Cheap enough for mobile CPUs.
 */
object Noise2D {
    private fun hash(x: Int, y: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 1442695040888963407L.toInt()
        h = (h xor (h shr 13)) * 1274126177
        h = h xor (h shr 16)
        return (h and 0x7FFFFFFF) / 2147483647f
    }

    private fun smooth(t: Float) = t * t * (3f - 2f * t)

    /** Value noise in [-1, 1]. */
    fun value(x: Float, y: Float, seed: Int = 0): Float {
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        val xf = x - xi
        val yf = y - yi
        val v00 = hash(xi, yi, seed)
        val v10 = hash(xi + 1, yi, seed)
        val v01 = hash(xi, yi + 1, seed)
        val v11 = hash(xi + 1, yi + 1, seed)
        val sx = smooth(xf)
        val sy = smooth(yf)
        val a = M.lerp(v00, v10, sx)
        val b = M.lerp(v01, v11, sx)
        return M.lerp(a, b, sy) * 2f - 1f
    }

    /** Fractal sum of [octaves] noise layers. */
    fun fbm(x: Float, y: Float, octaves: Int = 3, lacunarity: Float = 2f, gain: Float = 0.5f, seed: Int = 0): Float {
        var sum = 0f
        var amp = 1f
        var freq = 1f
        var norm = 0f
        for (i in 0 until octaves) {
            sum += value(x * freq, y * freq, seed + i * 31) * amp
            norm += amp
            amp *= gain
            freq *= lacunarity
        }
        return if (norm > 0f) sum / norm else 0f
    }

    /** Divergence-free-ish 2D curl field used for particle turbulence and wind. */
    fun curl(x: Float, y: Float, seed: Int = 0, out: FloatArray) {
        val e = 0.35f
        val n1 = value(x, y + e, seed)
        val n2 = value(x, y - e, seed)
        val n3 = value(x + e, y, seed)
        val n4 = value(x - e, y, seed)
        out[0] = (n1 - n2) / (2f * e)   // d/dy -> x
        out[1] = -(n3 - n4) / (2f * e)  // -d/dx -> y
    }
}
