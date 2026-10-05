package com.sengine.engine.render

enum class Tool { HAND, MOVE, ROTATE, SCALE }

/** State shared between the editor UI thread and the GL thread. */
class EditorState {
    val view = View2D()
    val view3D = View3D()
    @Volatile var selectedId = -1L
    @Volatile var tool = Tool.MOVE
    @Volatile var showGrid = true
    @Volatile var showColliders = true
    @Volatile var showProfiler = false
    @Volatile var activeAxis = 0 // 0 none, 1 x, 2 y, 3 free (2D) / 3 z, 4 free (3D)

    // 3D editor orbit camera
    @Volatile var mode3D = false
    @Volatile var orbitX = 0f
    @Volatile var orbitY = 0f
    @Volatile var orbitZ = 0f
    @Volatile var yaw = 35f
    @Volatile var pitch = 28f
    @Volatile var distance = 14f

    fun updateView3D() = view3D.setOrbit(orbitX, orbitY, orbitZ, yaw, pitch, distance)

    /** Gizmo length in world units. */
    fun gizmoLength() = 90f * (view.heightPx / 1080f).coerceAtLeast(0.6f) / view.pixelsPerUnit

    /** 3D gizmo length at a world point (constant screen size). */
    fun gizmoLength3D(x: Float, y: Float, z: Float) = view3D.distanceTo(x, y, z) * 0.16f
}
