package com.sengine.engine.math

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 2D affine transform.
 *
 * Maps `(x, y) -> (a*x + c*y + tx, b*x + d*y + ty)`, i.e. the matrix
 * ```
 * | a  c  tx |
 * | b  d  ty |
 * | 0  0   1 |
 * ```
 * The engine is strictly 2D: every transform in S Engine is one of these.
 */
class Affine(
    var a: Float = 1f, var b: Float = 0f,
    var c: Float = 0f, var d: Float = 1f,
    var tx: Float = 0f, var ty: Float = 0f
) {
    fun set(o: Affine): Affine {
        a = o.a; b = o.b; c = o.c; d = o.d; tx = o.tx; ty = o.ty; return this
    }

    fun set(a: Float, b: Float, c: Float, d: Float, tx: Float, ty: Float): Affine {
        this.a = a; this.b = b; this.c = c; this.d = d; this.tx = tx; this.ty = ty; return this
    }

    fun copy() = Affine(a, b, c, d, tx, ty)

    fun identity(): Affine { a = 1f; b = 0f; c = 0f; d = 1f; tx = 0f; ty = 0f; return this }

    fun setTRS(x: Float, y: Float, rotDeg: Float, sx: Float, sy: Float): Affine {
        val r = Math.toRadians(rotDeg.toDouble())
        val cs = cos(r).toFloat()
        val sn = sin(r).toFloat()
        a = cs * sx; b = sn * sx
        c = -sn * sy; d = cs * sy
        tx = x; ty = y
        return this
    }

    fun setTranslation(x: Float, y: Float): Affine {
        a = 1f; b = 0f; c = 0f; d = 1f; tx = x; ty = y
        return this
    }

    /** this = p * ch */
    fun setMul(p: Affine, ch: Affine): Affine {
        val na = p.a * ch.a + p.c * ch.b
        val nb = p.b * ch.a + p.d * ch.b
        val nc = p.a * ch.c + p.c * ch.d
        val nd = p.b * ch.c + p.d * ch.d
        val ntx = p.a * ch.tx + p.c * ch.ty + p.tx
        val nty = p.b * ch.tx + p.d * ch.ty + p.ty
        a = na; b = nb; c = nc; d = nd; tx = ntx; ty = nty
        return this
    }

    /** this = this * ch */
    fun mul(ch: Affine): Affine = setMul(copy(), ch)

    fun mapX(x: Float, y: Float) = a * x + c * y + tx
    fun mapY(x: Float, y: Float) = b * x + d * y + ty

    /** Applies only the linear part (ignores translation) - for directions/vectors. */
    fun dirX(x: Float, y: Float) = a * x + c * y
    fun dirY(x: Float, y: Float) = b * x + d * y

    fun inverted(): Affine? {
        val det = a * d - b * c
        if (det == 0f || det.isNaN()) return null
        val id = 1f / det
        val na = d * id
        val nb = -b * id
        val nc = -c * id
        val nd = a * id
        val ntx = -(na * tx + nc * ty)
        val nty = -(nb * tx + nd * ty)
        return Affine(na, nb, nc, nd, ntx, nty)
    }

    val scaleX: Float get() = sqrt(a * a + b * b)
    val scaleY: Float get() = sqrt(c * c + d * d)
    val rotationDeg: Float get() = Math.toDegrees(atan2(b, a).toDouble()).toFloat()

    /** True when this is a pure rotation + uniform scale + translation (no shear). */
    fun isConformal(eps: Float = 1e-4f): Boolean =
        kotlin.math.abs(a * c + b * d) < eps && kotlin.math.abs(scaleX - scaleY) < eps

    /** Column-major 4x4 for OpenGL (kept so 2D transforms can be fed straight to GLES). */
    fun toMat4(out: FloatArray) {
        java.util.Arrays.fill(out, 0f)
        out[0] = a; out[1] = b
        out[4] = c; out[5] = d
        out[10] = 1f
        out[12] = tx; out[13] = ty
        out[15] = 1f
    }

    override fun toString() = "Affine($a, $b, $c, $d, $tx, $ty)"

    companion object {
        fun trs(x: Float, y: Float, rotDeg: Float, sx: Float = 1f, sy: Float = 1f) =
            Affine().setTRS(x, y, rotDeg, sx, sy)
        fun translation(x: Float, y: Float) = Affine(1f, 0f, 0f, 1f, x, y)
        fun rotation(rotDeg: Float) = Affine().setTRS(0f, 0f, rotDeg, 1f, 1f)
        fun scale(sx: Float, sy: Float) = Affine(sx, 0f, 0f, sy, 0f, 0f)
    }
}
