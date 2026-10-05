package com.sengine.ui

import android.view.MotionEvent
import android.view.ViewConfiguration
import com.sengine.engine.Engine
import com.sengine.engine.core.Camera3D
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Light
import com.sengine.engine.core.MeshRenderer
import com.sengine.engine.core.SpriteRenderer
import com.sengine.engine.core.TextRenderer
import com.sengine.engine.math.Mat4
import com.sengine.engine.physics.PhysicsWorld3D
import com.sengine.engine.render.EditorState
import com.sengine.engine.render.Meshes
import com.sengine.engine.render.Tool
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 3D scene-view touch handling: orbit, pan, zoom, ray picking and 3-axis gizmos. */
class Viewport3DController(
    private val act: EditorActivity,
    private val engine: Engine,
    private val ed: EditorState,
) {
    private enum class Op { NONE, PENDING, ORBIT, PAN, PINCH, MOVE, ROTATE, SCALE }

    var snap = false
    private var op = Op.NONE
    private val slop = ViewConfiguration.get(act).scaledTouchSlop.toFloat()
    private var downX = 0f; private var downY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var picked: GameObject? = null
    private var target: GameObject? = null

    // gizmo op state
    private var axis = 0
    private val startPos = FloatArray(3)
    private val startRot = FloatArray(3)
    private val startScale = FloatArray(3)
    private var axisScreenX = 0f; private var axisScreenY = 0f; private var axisScreenLen = 1f
    private var gizmoLen = 1f
    private var planeY = 0f
    private val planeStart = FloatArray(3)

    // pinch
    private var pinchDist = 1f
    private var pinchStartDist = 10f
    private var pinchMidX = 0f; private var pinchMidY = 0f

    private val v get() = ed.view3D

    fun onTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(e.x, e.y)
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2 && op !in listOf(Op.MOVE, Op.ROTATE, Op.SCALE)) startPinch(e)
            MotionEvent.ACTION_MOVE -> onMove(e)
            MotionEvent.ACTION_POINTER_UP -> if (op == Op.PINCH) {
                val keep = if (e.actionIndex == 0) 1 else 0
                lastX = e.getX(keep); lastY = e.getY(keep); op = Op.ORBIT
            }
            MotionEvent.ACTION_UP -> onUp(e.x, e.y)
            MotionEvent.ACTION_CANCEL -> { op = Op.NONE; ed.activeAxis = 0 }
        }
    }

    private fun onDown(x: Float, y: Float) {
        downX = x; downY = y; lastX = x; lastY = y
        op = Op.PENDING
        target = null
        synchronized(engine.lock) {
            ed.updateView3D()
            picked = pick(x, y)
            val sel = engine.scene.findById(ed.selectedId)
            if (sel != null && ed.tool != Tool.HAND) {
                val a = gizmoHit(sel, x, y)
                if (a != 0) beginOp(sel, a, x, y)
            }
        }
    }

    private fun beginOp(go: GameObject, a: Int, x: Float, y: Float) {
        act.history.record(ed.selectedId)
        target = go
        axis = a
        ed.activeAxis = a
        val w = go.computeWorld3()
        startPos[0] = w[12]; startPos[1] = w[13]; startPos[2] = w[14]
        startRot[0] = go.rotX; startRot[1] = go.rotY; startRot[2] = go.rotation
        startScale[0] = go.scaleX; startScale[1] = go.scaleY; startScale[2] = go.scaleZ
        gizmoLen = ed.gizmoLength3D(startPos[0], startPos[1], startPos[2])
        if (a in 1..3) {
            val o = v.project(startPos[0], startPos[1], startPos[2])
            val tip = v.project(startPos[0] + if (a == 1) gizmoLen else 0f, startPos[1] + if (a == 2) gizmoLen else 0f, startPos[2] + if (a == 3) gizmoLen else 0f)
            if (o != null && tip != null) {
                axisScreenX = tip[0] - o[0]; axisScreenY = tip[1] - o[1]
                axisScreenLen = hypot(axisScreenX, axisScreenY).coerceAtLeast(1f)
                axisScreenX /= axisScreenLen; axisScreenY /= axisScreenLen
            } else { axisScreenX = 1f; axisScreenY = 0f; axisScreenLen = 100f }
        }
        planeY = startPos[1]
        rayPlane(x, y, planeY)?.let { System.arraycopy(it, 0, planeStart, 0, 3) }
        op = when (ed.tool) {
            Tool.MOVE -> Op.MOVE
            Tool.ROTATE -> Op.ROTATE
            Tool.SCALE -> Op.SCALE
            Tool.HAND -> Op.ORBIT
        }
    }

    private fun onMove(e: MotionEvent) {
        if (op == Op.PINCH && e.pointerCount >= 2) { updatePinch(e); return }
        val x = e.x; val y = e.y
        if (op == Op.PENDING) {
            if (hypot(x - downX, y - downY) < slop) return
            val p = picked
            if (ed.tool == Tool.MOVE && p != null) {
                if (p.id != ed.selectedId) act.select(p.id)
                synchronized(engine.lock) { ed.updateView3D(); beginOp(p, 4, downX, downY) }
            } else op = Op.ORBIT
        }
        when (op) {
            Op.ORBIT -> {
                ed.yaw -= (x - lastX) * 0.3f
                ed.pitch = (ed.pitch + (y - lastY) * 0.3f).coerceIn(-89f, 89f)
            }
            Op.MOVE, Op.ROTATE, Op.SCALE -> synchronized(engine.lock) { applyOp(x, y) }
            else -> {}
        }
        lastX = x; lastY = y
    }

    private fun applyOp(x: Float, y: Float) {
        val go = target ?: return
        val dsx = x - downX; val dsy = y - downY
        val along = (dsx * axisScreenX + dsy * axisScreenY) / axisScreenLen * gizmoLen
        when (op) {
            Op.MOVE -> {
                var nx = startPos[0]; var ny = startPos[1]; var nz = startPos[2]
                when (axis) {
                    1 -> nx += along
                    2 -> ny += along
                    3 -> nz += along
                    else -> {
                        val p = rayPlane(x, y, planeY) ?: return
                        nx += p[0] - planeStart[0]; nz += p[2] - planeStart[2]
                    }
                }
                if (snap) { nx = snapTo(nx, 0.25f); ny = snapTo(ny, 0.25f); nz = snapTo(nz, 0.25f) }
                go.setWorldPosition3(nx, ny, nz)
            }
            Op.ROTATE -> {
                var d = dsx * 0.5f
                if (snap) d = snapTo(d, 15f)
                when (axis) {
                    1 -> go.rotX = norm(startRot[0] + d)
                    2 -> go.rotY = norm(startRot[1] + d)
                    else -> go.rotation = norm(startRot[2] + d)
                }
            }
            Op.SCALE -> {
                fun sc(s: Float, f: Float): Float { var r = s * f.coerceAtLeast(0.01f); if (snap) r = snapTo(r, 0.1f).coerceAtLeast(0.1f); return r }
                when (axis) {
                    1 -> go.scaleX = sc(startScale[0], 1f + along / gizmoLen)
                    2 -> go.scaleY = sc(startScale[1], 1f + along / gizmoLen)
                    3 -> go.scaleZ = sc(startScale[2], 1f + along / gizmoLen)
                    else -> {
                        val f = 1f + (dsx - dsy) / 300f
                        go.scaleX = sc(startScale[0], f); go.scaleY = sc(startScale[1], f); go.scaleZ = sc(startScale[2], f)
                    }
                }
            }
            else -> {}
        }
    }

    private fun onUp(x: Float, y: Float) {
        if (op == Op.PENDING && hypot(x - downX, y - downY) < slop) act.select(picked?.id ?: -1L)
        else if (op == Op.MOVE || op == Op.ROTATE || op == Op.SCALE) act.onObjectEdited()
        op = Op.NONE
        ed.activeAxis = 0
    }

    private fun startPinch(e: MotionEvent) {
        op = Op.PINCH
        pinchDist = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)).coerceAtLeast(1f)
        pinchStartDist = ed.distance
        pinchMidX = (e.getX(0) + e.getX(1)) / 2f; pinchMidY = (e.getY(0) + e.getY(1)) / 2f
    }

    private fun updatePinch(e: MotionEvent) {
        val d = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)).coerceAtLeast(1f)
        ed.distance = (pinchStartDist * pinchDist / d).coerceIn(0.5f, 800f)
        val mx = (e.getX(0) + e.getX(1)) / 2f; val my = (e.getY(0) + e.getY(1)) / 2f
        val dx = mx - pinchMidX; val dy = my - pinchMidY
        pinchMidX = mx; pinchMidY = my
        // pan the orbit target in the camera plane
        val scale = ed.distance * 0.0022f * (1080f / v.heightPx.coerceAtLeast(1))
        val yaw = Math.toRadians(ed.yaw.toDouble())
        val rx = Math.cos(yaw).toFloat(); val rz = -Math.sin(yaw).toFloat()
        val pitch = Math.toRadians(ed.pitch.toDouble())
        ed.orbitX -= rx * dx * scale
        ed.orbitZ -= rz * dx * scale
        ed.orbitY += Math.cos(pitch).toFloat() * dy * scale
        val f = Math.sin(pitch).toFloat() * dy * scale
        ed.orbitX += Math.sin(yaw).toFloat() * f
        ed.orbitZ += Math.cos(yaw).toFloat() * f
    }

    private fun norm(a: Float): Float { var r = a % 360f; if (r > 180f) r -= 360f; if (r < -180f) r += 360f; return r }
    private fun snapTo(v: Float, step: Float) = (v / step).roundToInt() * step

    private fun rayPlane(sx: Float, sy: Float, py: Float): FloatArray? {
        val r = v.ray(sx, sy)
        if (kotlin.math.abs(r[4]) < 1e-5f) return null
        val t = (py - r[1]) / r[4]
        if (t < 0f) return null
        return floatArrayOf(r[0] + r[3] * t, py, r[2] + r[5] * t)
    }

    // ------------------------------------------------------------------ hit testing

    private fun gizmoHit(go: GameObject, sx: Float, sy: Float): Int {
        val w = go.world3
        val x = w[12]; val y = w[13]; val z = w[14]
        val len = ed.gizmoLength3D(x, y, z)
        val o = v.project(x, y, z) ?: return 0
        val th = 28f * (v.heightPx / 1080f).coerceAtLeast(0.7f)
        if (hypot(sx - o[0], sy - o[1]) < th) return 4
        var best = 0; var bestD = th
        for (a in 1..3) {
            if (ed.tool == Tool.ROTATE) {
                // sample the ring
                for (i in 0 until 32) {
                    val ang = i * Math.PI * 2 / 32
                    val c = (Math.cos(ang) * len).toFloat(); val s = (Math.sin(ang) * len).toFloat()
                    val p = when (a) { 1 -> v.project(x, y + c, z + s); 2 -> v.project(x + c, y, z + s); else -> v.project(x + c, y + s, z) } ?: continue
                    val d = hypot(sx - p[0], sy - p[1])
                    if (d < bestD) { bestD = d; best = a }
                }
            } else {
                val tip = v.project(x + if (a == 1) len else 0f, y + if (a == 2) len else 0f, z + if (a == 3) len else 0f) ?: continue
                val d = segDist(sx, sy, o[0], o[1], tip[0], tip[1])
                if (d < bestD) { bestD = d; best = a }
            }
        }
        return best
    }

    private fun segDist(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 < 1e-6f) 0f else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.15f, 1.1f)
        return hypot(px - (ax + dx * t), py - (ay + dy * t))
    }

    fun pick(sx: Float, sy: Float): GameObject? {
        val r = v.ray(sx, sy)
        var best: GameObject? = null
        var bestT = Float.MAX_VALUE
        val inv = FloatArray(16)
        for (go in engine.scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            val w = go.world3
            val mr = go.get<MeshRenderer>()
            val bounds: Pair<FloatArray, FloatArray>? = when {
                mr != null -> {
                    val m = if (mr.mesh == MeshRenderer.MESHES.size - 1 && mr.model.isNotBlank()) Meshes.model(engine.project.assetFile(mr.model)) ?: Meshes.primitive(0) else Meshes.primitive(mr.mesh.coerceAtMost(7))
                    m.min to m.max
                }
                go.get<SpriteRenderer>() != null || go.get<TextRenderer>() != null -> floatArrayOf(-0.5f, -0.5f, -0.05f) to floatArrayOf(0.5f, 0.5f, 0.05f)
                else -> null
            }
            if (bounds != null) {
                if (!Mat4.invert(inv, w)) continue
                val lo = Mat4.point(inv, r[0], r[1], r[2])
                val ld = Mat4.dir(inv, r[3], r[4], r[5])
                val t = PhysicsWorld3D.rayAabb(lo[0], lo[1], lo[2], ld[0], ld[1], ld[2],
                    bounds.first[0], bounds.first[1], bounds.first[2], bounds.second[0], bounds.second[1], bounds.second[2]) ?: continue
                // t is in local-ray units; convert to world distance
                val hit = Mat4.point(w, lo[0] + ld[0] * t, lo[1] + ld[1] * t, lo[2] + ld[2] * t)
                val dist = sqrt((hit[0] - r[0]) * (hit[0] - r[0]) + (hit[1] - r[1]) * (hit[1] - r[1]) + (hit[2] - r[2]) * (hit[2] - r[2]))
                if (dist < bestT) { bestT = dist; best = go }
            } else {
                // icon objects (camera, light, empty): screen distance
                val p = v.project(w[12], w[13], w[14]) ?: continue
                val th = (if (go.get<Camera3D>() != null || go.get<Light>() != null) 40f else 26f) * (v.heightPx / 1080f).coerceAtLeast(0.7f)
                if (hypot(sx - p[0], sy - p[1]) < th) {
                    val dist = v.distanceTo(w[12], w[13], w[14])
                    if (dist < bestT) { bestT = dist; best = go }
                }
            }
        }
        return best
    }

    fun frame(go: GameObject?) {
        if (go == null) { ed.orbitX = 0f; ed.orbitY = 0f; ed.orbitZ = 0f; ed.distance = 14f; return }
        val w = go.computeWorld3()
        ed.orbitX = w[12]; ed.orbitY = w[13]; ed.orbitZ = w[14]
        ed.distance = (maxOf(Mat4.scaleOf(w, 0), Mat4.scaleOf(w, 1), Mat4.scaleOf(w, 2)) * 3f).coerceIn(3f, 200f)
    }
}
