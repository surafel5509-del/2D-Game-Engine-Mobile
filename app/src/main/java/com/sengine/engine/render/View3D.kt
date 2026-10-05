package com.sengine.engine.render

import com.sengine.engine.math.Mat4
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Perspective camera used by the 3D renderer and editor picking. */
class View3D {
    val eye = FloatArray(3)
    val target = floatArrayOf(0f, 0f, -1f)
    val up = floatArrayOf(0f, 1f, 0f)
    var fov = 60f
    var near = 0.1f
    var far = 500f
    var widthPx = 1
    var heightPx = 1

    val view = FloatArray(16)
    val proj = FloatArray(16)
    val viewProj = FloatArray(16)
    val invViewProj = FloatArray(16)

    val aspect get() = widthPx.toFloat() / heightPx.coerceAtLeast(1)

    fun update() {
        Mat4.lookAt(view, eye[0], eye[1], eye[2], target[0], target[1], target[2], up[0], up[1], up[2])
        Mat4.perspective(proj, fov, aspect, near, far)
        Mat4.mul(viewProj, proj, view)
        Mat4.invert(invViewProj, viewProj)
    }

    /** yaw/pitch in degrees. */
    fun setOrbit(tx: Float, ty: Float, tz: Float, yaw: Float, pitch: Float, dist: Float) {
        val y = Math.toRadians(yaw.toDouble()); val p = Math.toRadians(pitch.toDouble())
        target[0] = tx; target[1] = ty; target[2] = tz
        eye[0] = tx + (dist * cos(p) * sin(y)).toFloat()
        eye[1] = ty + (dist * sin(p)).toFloat()
        eye[2] = tz + (dist * cos(p) * cos(y)).toFloat()
        up[0] = 0f; up[1] = 1f; up[2] = 0f
        near = (dist * 0.01f).coerceIn(0.01f, 0.5f); far = maxOf(500f, dist * 20f)
        update()
    }

    /** Uses a world matrix: camera looks down its local -Z, or at [lookAt] if given. */
    fun setFromWorld(w: FloatArray, lookAt: FloatArray?, jitterX: Float = 0f, jitterY: Float = 0f) {
        eye[0] = w[12] + jitterX; eye[1] = w[13] + jitterY; eye[2] = w[14]
        if (lookAt != null) {
            target[0] = lookAt[0] + jitterX; target[1] = lookAt[1] + jitterY; target[2] = lookAt[2]
            up[0] = 0f; up[1] = 1f; up[2] = 0f
        } else {
            val f = Mat4.dir(w, 0f, 0f, -1f)
            target[0] = eye[0] + f[0]; target[1] = eye[1] + f[1]; target[2] = eye[2] + f[2]
            val u = Mat4.dir(w, 0f, 1f, 0f)
            up[0] = u[0]; up[1] = u[1]; up[2] = u[2]
        }
        update()
    }

    /** Ray through a screen pixel: returns [ox,oy,oz,dx,dy,dz] (d normalised). */
    fun ray(sx: Float, sy: Float): FloatArray {
        val nx = sx / widthPx * 2f - 1f
        val ny = 1f - sy / heightPx * 2f
        val a = unproject(nx, ny, -1f)
        val b = unproject(nx, ny, 1f)
        var dx = b[0] - a[0]; var dy = b[1] - a[1]; var dz = b[2] - a[2]
        val l = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-8f)
        dx /= l; dy /= l; dz /= l
        return floatArrayOf(a[0], a[1], a[2], dx, dy, dz)
    }

    private fun unproject(x: Float, y: Float, z: Float): FloatArray {
        val m = invViewProj
        val ox = m[0] * x + m[4] * y + m[8] * z + m[12]
        val oy = m[1] * x + m[5] * y + m[9] * z + m[13]
        val oz = m[2] * x + m[6] * y + m[10] * z + m[14]
        val ow = m[3] * x + m[7] * y + m[11] * z + m[15]
        val iw = if (ow != 0f) 1f / ow else 1f
        return floatArrayOf(ox * iw, oy * iw, oz * iw)
    }

    /** World → screen pixels; returns null when behind the camera. */
    fun project(x: Float, y: Float, z: Float): FloatArray? {
        val m = viewProj
        val cx = m[0] * x + m[4] * y + m[8] * z + m[12]
        val cy = m[1] * x + m[5] * y + m[9] * z + m[13]
        val cw = m[3] * x + m[7] * y + m[11] * z + m[15]
        if (cw <= 1e-5f) return null
        return floatArrayOf((cx / cw * 0.5f + 0.5f) * widthPx, (1f - (cy / cw * 0.5f + 0.5f)) * heightPx)
    }

    fun distanceTo(x: Float, y: Float, z: Float): Float {
        val dx = x - eye[0]; val dy = y - eye[1]; val dz = z - eye[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
