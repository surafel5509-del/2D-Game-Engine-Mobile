package com.sengine.engine.render

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Non-indexed triangle mesh: interleaved position(3) normal(3) uv(2). */
class Mesh(data: FloatArray) {
    val vertexCount = data.size / 8
    val buffer: FloatBuffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(data); position(0) }
    val min = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
    val max = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)

    init {
        for (i in 0 until vertexCount) for (k in 0 until 3) {
            val v = data[i * 8 + k]
            if (v < min[k]) min[k] = v
            if (v > max[k]) max[k] = v
        }
        if (vertexCount == 0) { min.fill(-0.5f); max.fill(0.5f) }
    }
}

/** Procedural primitive meshes (unit sized, centred) and a Wavefront OBJ loader. */
object Meshes {
    private val cache = HashMap<Int, Mesh>()
    private val models = HashMap<String, Pair<Long, Mesh?>>()

    fun primitive(kind: Int): Mesh = cache.getOrPut(kind) {
        Mesh(when (kind) {
            1 -> sphere(24, 16, 0.5f)
            2 -> plane()
            3 -> cylinder(24, 0.5f, 0.5f, true)
            4 -> cylinder(24, 0.5f, 0f, true)
            5 -> torus(28, 14, 0.35f, 0.15f)
            6 -> capsule(20, 0.25f)
            7 -> pyramid()
            else -> cube()
        })
    }

    fun model(file: File): Mesh? {
        val key = file.absolutePath
        val stamp = file.lastModified()
        models[key]?.let { if (it.first == stamp) return it.second }
        val m = try { loadObj(file.readText()) } catch (e: Exception) { null }
        models[key] = stamp to m
        return m
    }

    private class B {
        val d = ArrayList<Float>(4096)
        fun v(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, u: Float, vv: Float) {
            d.add(x); d.add(y); d.add(z); d.add(nx); d.add(ny); d.add(nz); d.add(u); d.add(vv)
        }
        fun arr() = d.toFloatArray()
    }

    fun cube(): FloatArray {
        val b = B()
        // each face: normal, and two tangent axes
        val faces = arrayOf(
            floatArrayOf(0f, 0f, 1f, 1f, 0f, 0f, 0f, 1f, 0f),
            floatArrayOf(0f, 0f, -1f, -1f, 0f, 0f, 0f, 1f, 0f),
            floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f),
            floatArrayOf(-1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f),
            floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, -1f),
            floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f),
        )
        for (f in faces) {
            val n = floatArrayOf(f[0], f[1], f[2]); val u = floatArrayOf(f[3], f[4], f[5]); val v = floatArrayOf(f[6], f[7], f[8])
            fun p(su: Float, sv: Float, tu: Float, tv: Float) = b.v(
                (n[0] + u[0] * su + v[0] * sv) * 0.5f, (n[1] + u[1] * su + v[1] * sv) * 0.5f, (n[2] + u[2] * su + v[2] * sv) * 0.5f,
                n[0], n[1], n[2], tu, tv)
            p(-1f, -1f, 0f, 1f); p(1f, -1f, 1f, 1f); p(1f, 1f, 1f, 0f)
            p(-1f, -1f, 0f, 1f); p(1f, 1f, 1f, 0f); p(-1f, 1f, 0f, 0f)
        }
        return b.arr()
    }

    fun plane(): FloatArray {
        val b = B()
        b.v(-0.5f, 0f, 0.5f, 0f, 1f, 0f, 0f, 1f); b.v(0.5f, 0f, 0.5f, 0f, 1f, 0f, 1f, 1f); b.v(0.5f, 0f, -0.5f, 0f, 1f, 0f, 1f, 0f)
        b.v(-0.5f, 0f, 0.5f, 0f, 1f, 0f, 0f, 1f); b.v(0.5f, 0f, -0.5f, 0f, 1f, 0f, 1f, 0f); b.v(-0.5f, 0f, -0.5f, 0f, 1f, 0f, 0f, 0f)
        return b.arr()
    }

    fun sphere(seg: Int, rings: Int, r: Float): FloatArray {
        val b = B()
        fun pt(i: Int, j: Int) {
            val th = j * Math.PI / rings
            val ph = i * 2 * Math.PI / seg
            val x = (sin(th) * cos(ph)).toFloat(); val y = cos(th).toFloat(); val z = (sin(th) * sin(ph)).toFloat()
            b.v(x * r, y * r, z * r, x, y, z, i.toFloat() / seg, j.toFloat() / rings)
        }
        for (j in 0 until rings) for (i in 0 until seg) {
            pt(i, j); pt(i + 1, j + 1); pt(i + 1, j)
            pt(i, j); pt(i, j + 1); pt(i + 1, j + 1)
        }
        return b.arr()
    }

    fun cylinder(seg: Int, rTop: Float, rBottom: Float, caps: Boolean): FloatArray {
        val b = B()
        val h = 0.5f
        val slope = (rBottom - rTop) / 1f
        for (i in 0 until seg) {
            val a0 = i * 2 * Math.PI / seg; val a1 = (i + 1) * 2 * Math.PI / seg
            val c0 = cos(a0).toFloat(); val s0 = sin(a0).toFloat(); val c1 = cos(a1).toFloat(); val s1 = sin(a1).toFloat()
            val nl = sqrt(1f + slope * slope)
            val ny = slope / nl
            val u0 = i.toFloat() / seg; val u1 = (i + 1f) / seg
            b.v(c0 * rBottom, -h, s0 * rBottom, c0 / nl, ny, s0 / nl, u0, 1f)
            b.v(c1 * rTop, h, s1 * rTop, c1 / nl, ny, s1 / nl, u1, 0f)
            b.v(c1 * rBottom, -h, s1 * rBottom, c1 / nl, ny, s1 / nl, u1, 1f)
            b.v(c0 * rBottom, -h, s0 * rBottom, c0 / nl, ny, s0 / nl, u0, 1f)
            b.v(c0 * rTop, h, s0 * rTop, c0 / nl, ny, s0 / nl, u0, 0f)
            b.v(c1 * rTop, h, s1 * rTop, c1 / nl, ny, s1 / nl, u1, 0f)
            if (caps) {
                if (rTop > 0f) {
                    b.v(0f, h, 0f, 0f, 1f, 0f, 0.5f, 0.5f)
                    b.v(c1 * rTop, h, s1 * rTop, 0f, 1f, 0f, 0.5f + c1 / 2, 0.5f + s1 / 2)
                    b.v(c0 * rTop, h, s0 * rTop, 0f, 1f, 0f, 0.5f + c0 / 2, 0.5f + s0 / 2)
                }
                if (rBottom > 0f) {
                    b.v(0f, -h, 0f, 0f, -1f, 0f, 0.5f, 0.5f)
                    b.v(c0 * rBottom, -h, s0 * rBottom, 0f, -1f, 0f, 0.5f + c0 / 2, 0.5f + s0 / 2)
                    b.v(c1 * rBottom, -h, s1 * rBottom, 0f, -1f, 0f, 0.5f + c1 / 2, 0.5f + s1 / 2)
                }
            }
        }
        return b.arr()
    }

    fun torus(seg: Int, sides: Int, R: Float, r: Float): FloatArray {
        val b = B()
        fun pt(i: Int, j: Int) {
            val u = i * 2 * Math.PI / seg; val v = j * 2 * Math.PI / sides
            val cx = cos(u).toFloat(); val cz = sin(u).toFloat()
            val nx = (cos(v) * cos(u)).toFloat(); val ny = sin(v).toFloat(); val nz = (cos(v) * sin(u)).toFloat()
            b.v(cx * R + nx * r, ny * r, cz * R + nz * r, nx, ny, nz, i.toFloat() / seg, j.toFloat() / sides)
        }
        for (i in 0 until seg) for (j in 0 until sides) {
            pt(i, j); pt(i + 1, j); pt(i + 1, j + 1)
            pt(i, j); pt(i + 1, j + 1); pt(i, j + 1)
        }
        return b.arr()
    }

    fun capsule(seg: Int, r: Float): FloatArray {
        val b = B()
        val half = 0.5f - r
        val rings = 12
        fun pt(i: Int, j: Int) {
            val th = j * Math.PI / rings
            val ph = i * 2 * Math.PI / seg
            val x = (sin(th) * cos(ph)).toFloat(); val y = cos(th).toFloat(); val z = (sin(th) * sin(ph)).toFloat()
            val off = if (j <= rings / 2) half else -half
            b.v(x * r, y * r + off, z * r, x, y, z, i.toFloat() / seg, j.toFloat() / rings)
        }
        for (j in 0 until rings) for (i in 0 until seg) {
            if (j == rings / 2 - 1 || j == rings / 2) { /* hemispheres meet around equator */ }
            pt(i, j); pt(i + 1, j + 1); pt(i + 1, j)
            pt(i, j); pt(i, j + 1); pt(i + 1, j + 1)
        }
        // body
        for (i in 0 until seg) {
            val a0 = i * 2 * Math.PI / seg; val a1 = (i + 1) * 2 * Math.PI / seg
            val c0 = cos(a0).toFloat(); val s0 = sin(a0).toFloat(); val c1 = cos(a1).toFloat(); val s1 = sin(a1).toFloat()
            b.v(c0 * r, -half, s0 * r, c0, 0f, s0, 0f, 1f); b.v(c1 * r, half, s1 * r, c1, 0f, s1, 1f, 0f); b.v(c1 * r, -half, s1 * r, c1, 0f, s1, 1f, 1f)
            b.v(c0 * r, -half, s0 * r, c0, 0f, s0, 0f, 1f); b.v(c0 * r, half, s0 * r, c0, 0f, s0, 0f, 0f); b.v(c1 * r, half, s1 * r, c1, 0f, s1, 1f, 0f)
        }
        return b.arr()
    }

    fun pyramid(): FloatArray {
        val b = B()
        val apex = floatArrayOf(0f, 0.5f, 0f)
        val base = arrayOf(floatArrayOf(-0.5f, -0.5f, 0.5f), floatArrayOf(0.5f, -0.5f, 0.5f), floatArrayOf(0.5f, -0.5f, -0.5f), floatArrayOf(-0.5f, -0.5f, -0.5f))
        for (i in 0 until 4) {
            val a = base[i]; val c = base[(i + 1) % 4]
            val n = normal(a, c, apex)
            b.v(a[0], a[1], a[2], n[0], n[1], n[2], 0f, 1f); b.v(c[0], c[1], c[2], n[0], n[1], n[2], 1f, 1f); b.v(apex[0], apex[1], apex[2], n[0], n[1], n[2], 0.5f, 0f)
        }
        for (t in arrayOf(intArrayOf(0, 3, 2), intArrayOf(0, 2, 1))) for (k in t) {
            val p = base[k]; b.v(p[0], p[1], p[2], 0f, -1f, 0f, (p[0] + 0.5f), (p[2] + 0.5f))
        }
        return b.arr()
    }

    private fun normal(a: FloatArray, b: FloatArray, c: FloatArray): FloatArray {
        val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
        val vx = c[0] - a[0]; val vy = c[1] - a[1]; val vz = c[2] - a[2]
        var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
        val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-8f)
        nx /= l; ny /= l; nz /= l
        return floatArrayOf(nx, ny, nz)
    }

    /** Minimal Wavefront OBJ parser (v / vt / vn / f, polygons are fanned). Model is normalised to unit size. */
    fun loadObj(text: String): Mesh {
        val pos = ArrayList<FloatArray>(); val uvs = ArrayList<FloatArray>(); val nrm = ArrayList<FloatArray>()
        val b = B()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val p = line.split(Regex("\\s+"))
            when (p[0]) {
                "v" -> pos.add(floatArrayOf(p[1].toFloat(), p[2].toFloat(), p[3].toFloat()))
                "vt" -> uvs.add(floatArrayOf(p[1].toFloat(), if (p.size > 2) p[2].toFloat() else 0f))
                "vn" -> nrm.add(floatArrayOf(p[1].toFloat(), p[2].toFloat(), p[3].toFloat()))
                "f" -> {
                    val idx = p.drop(1).map { v ->
                        val s = v.split('/')
                        fun ix(str: String?, size: Int): Int {
                            val i = str?.toIntOrNull() ?: return -1
                            return if (i < 0) size + i else i - 1
                        }
                        intArrayOf(ix(s.getOrNull(0), pos.size), ix(s.getOrNull(1), uvs.size), ix(s.getOrNull(2), nrm.size))
                    }
                    for (k in 1 until idx.size - 1) {
                        val tri = arrayOf(idx[0], idx[k], idx[k + 1])
                        val fn = normal(pos[tri[0][0]], pos[tri[1][0]], pos[tri[2][0]])
                        for (t in tri) {
                            val v = pos[t[0]]
                            val n = if (t[2] >= 0) nrm[t[2]] else fn
                            val uv = if (t[1] >= 0) uvs[t[1]] else floatArrayOf(0f, 0f)
                            b.v(v[0], v[1], v[2], n[0], n[1], n[2], uv[0], 1f - uv[1])
                        }
                    }
                }
            }
        }
        val d = b.arr()
        // normalise to fit a unit cube centred at origin
        val mn = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE); val mx = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in 0 until d.size / 8) for (k in 0 until 3) { mn[k] = minOf(mn[k], d[i * 8 + k]); mx[k] = maxOf(mx[k], d[i * 8 + k]) }
        val size = maxOf(mx[0] - mn[0], mx[1] - mn[1], mx[2] - mn[2]).coerceAtLeast(1e-6f)
        for (i in 0 until d.size / 8) for (k in 0 until 3) d[i * 8 + k] = (d[i * 8 + k] - (mn[k] + mx[k]) / 2f) / size
        return Mesh(d)
    }
}
