package com.sengine.engine.math

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Pure-Kotlin column-major 4x4 matrix helpers (usable in JVM unit tests). */
object Mat4 {
    fun identity(m: FloatArray = FloatArray(16)): FloatArray {
        java.util.Arrays.fill(m, 0f); m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f; return m
    }

    /** out = a * b (out may alias neither) */
    fun mul(out: FloatArray, a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (c in 0 until 4) for (row in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += a[k * 4 + row] * b[c * 4 + k]
            r[c * 4 + row] = s
        }
        System.arraycopy(r, 0, out, 0, 16)
        return out
    }

    /** T * Ry * Rx * Rz * S  (angles in degrees) */
    fun trs(out: FloatArray, x: Float, y: Float, z: Float, rx: Float, ry: Float, rz: Float, sx: Float, sy: Float, sz: Float): FloatArray {
        val ax = Math.toRadians(rx.toDouble()); val ay = Math.toRadians(ry.toDouble()); val az = Math.toRadians(rz.toDouble())
        val cx = cos(ax).toFloat(); val sxn = sin(ax).toFloat()
        val cy = cos(ay).toFloat(); val syn = sin(ay).toFloat()
        val cz = cos(az).toFloat(); val szn = sin(az).toFloat()
        // R = Ry * Rx * Rz
        val r00 = cy * cz + syn * sxn * szn
        val r01 = -cy * szn + syn * sxn * cz
        val r02 = syn * cx
        val r10 = cx * szn
        val r11 = cx * cz
        val r12 = -sxn
        val r20 = -syn * cz + cy * sxn * szn
        val r21 = syn * szn + cy * sxn * cz
        val r22 = cy * cx
        out[0] = r00 * sx; out[1] = r10 * sx; out[2] = r20 * sx; out[3] = 0f
        out[4] = r01 * sy; out[5] = r11 * sy; out[6] = r21 * sy; out[7] = 0f
        out[8] = r02 * sz; out[9] = r12 * sz; out[10] = r22 * sz; out[11] = 0f
        out[12] = x; out[13] = y; out[14] = z; out[15] = 1f
        return out
    }

    fun perspective(out: FloatArray, fovYDeg: Float, aspect: Float, near: Float, far: Float): FloatArray {
        val f = 1f / tan(Math.toRadians(fovYDeg / 2.0)).toFloat()
        java.util.Arrays.fill(out, 0f)
        out[0] = f / aspect
        out[5] = f
        out[10] = (far + near) / (near - far)
        out[11] = -1f
        out[14] = 2f * far * near / (near - far)
        return out
    }

    fun ortho(out: FloatArray, l: Float, r: Float, b: Float, t: Float, n: Float, f: Float): FloatArray {
        java.util.Arrays.fill(out, 0f)
        out[0] = 2f / (r - l); out[5] = 2f / (t - b); out[10] = -2f / (f - n)
        out[12] = -(r + l) / (r - l); out[13] = -(t + b) / (t - b); out[14] = -(f + n) / (f - n); out[15] = 1f
        return out
    }

    fun lookAt(out: FloatArray, ex: Float, ey: Float, ez: Float, tx: Float, ty: Float, tz: Float, ux: Float = 0f, uy: Float = 1f, uz: Float = 0f): FloatArray {
        var fx = tx - ex; var fy = ty - ey; var fz = tz - ez
        var l = sqrt(fx * fx + fy * fy + fz * fz).coerceAtLeast(1e-6f)
        fx /= l; fy /= l; fz /= l
        var sx = fy * uz - fz * uy; var sy = fz * ux - fx * uz; var sz = fx * uy - fy * ux
        l = sqrt(sx * sx + sy * sy + sz * sz)
        if (l < 1e-6f) { sx = 1f; sy = 0f; sz = 0f } else { sx /= l; sy /= l; sz /= l }
        val vx = sy * fz - sz * fy; val vy = sz * fx - sx * fz; val vz = sx * fy - sy * fx
        out[0] = sx; out[1] = vx; out[2] = -fx; out[3] = 0f
        out[4] = sy; out[5] = vy; out[6] = -fy; out[7] = 0f
        out[8] = sz; out[9] = vz; out[10] = -fz; out[11] = 0f
        out[12] = -(sx * ex + sy * ey + sz * ez)
        out[13] = -(vx * ex + vy * ey + vz * ez)
        out[14] = fx * ex + fy * ey + fz * ez
        out[15] = 1f
        return out
    }

    fun invert(out: FloatArray, m: FloatArray): Boolean {
        val inv = FloatArray(16)
        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10]
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10]
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9]
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9]
        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10]
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10]
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9]
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9]
        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6]
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6]
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5]
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5]
        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6]
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6]
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5]
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5]
        var det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12]
        if (det == 0f || det.isNaN()) return false
        det = 1f / det
        for (i in 0 until 16) out[i] = inv[i] * det
        return true
    }

    fun transpose(out: FloatArray, m: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (i in 0 until 4) for (j in 0 until 4) r[j * 4 + i] = m[i * 4 + j]
        System.arraycopy(r, 0, out, 0, 16); return out
    }

    /** Transform point (w=1), returns xyz (perspective divided). */
    fun point(m: FloatArray, x: Float, y: Float, z: Float, out: FloatArray = FloatArray(3)): FloatArray {
        val w = m[3] * x + m[7] * y + m[11] * z + m[15]
        val iw = if (w != 0f) 1f / w else 1f
        out[0] = (m[0] * x + m[4] * y + m[8] * z + m[12]) * iw
        out[1] = (m[1] * x + m[5] * y + m[9] * z + m[13]) * iw
        out[2] = (m[2] * x + m[6] * y + m[10] * z + m[14]) * iw
        return out
    }

    /** Transform direction (w=0). */
    fun dir(m: FloatArray, x: Float, y: Float, z: Float, out: FloatArray = FloatArray(3)): FloatArray {
        out[0] = m[0] * x + m[4] * y + m[8] * z
        out[1] = m[1] * x + m[5] * y + m[9] * z
        out[2] = m[2] * x + m[6] * y + m[10] * z
        return out
    }

    fun scaleOf(m: FloatArray, axis: Int): Float {
        val i = axis * 4
        return sqrt(m[i] * m[i] + m[i + 1] * m[i + 1] + m[i + 2] * m[i + 2])
    }
}
