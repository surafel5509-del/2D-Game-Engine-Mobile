package com.sengine.engine.render

/**
 * Tools available in the 2D viewport. Every tool is implemented by
 * `ui/ViewportController.kt` - there are no decorative buttons.
 */
enum class Tool(val label: String) {
    SELECT("Select"),
    MOVE("Move"),
    ROTATE("Rotate"),
    SCALE("Scale"),
    HAND("Pan"),
    RECT("Rect Tool"),
    CIRCLE("Circle Tool"),
    TILE_PAINT("Tile Paint"),
    TILE_ERASE("Tile Erase"),
    TILE_FILL("Tile Fill"),
    TILE_RECT("Tile Rect"),
    POLYGON("Polygon Tool"),
    SPAWN("Spawn Point"),
    ZOOM("Zoom");

    companion object {
        val labels = entries.map { it.label }
    }
}

/** What the inspector shows when the editor has a selection. */
enum class SelectionKind { NONE, OBJECT, TILESET, ASSET, SCENE_SETTINGS }

/**
 * Editor state shared between the Android UI thread and the GL render thread.
 * Strictly 2D: there is no orbit camera, no 3D gizmo mode and no mesh editing state.
 */
class EditorState {

    val view = View2D()
    @Volatile var selectedId = -1L
    @Volatile var selectedIds: LongArray = LongArray(0)
    @Volatile var tool = Tool.MOVE
    @Volatile var selectionKind = SelectionKind.NONE

    // overlay toggles
    @Volatile var showGrid = true
    @Volatile var showColliders = true
    @Volatile var showGizmos = true
    @Volatile var showProfiler = false
    @Volatile var showLightGizmos = false
    @Volatile var showTileGrid = false
    @Volatile var showDebugInfo = false

    /** Grid size in world units and snapping. */
    @Volatile var gridSize = 1f
    @Volatile var snapToGrid = false
    @Volatile var snapAngle = 15f

    /** Axis constraint while dragging: 0 = free, 1 = X only, 2 = Y only. */
    @Volatile var activeAxis = 0

    // ---- viewport interaction state (written by the Android view, read by the GL thread)
    @Volatile var dragging = false
    @Volatile var dragStartWorldX = 0f
    @Volatile var dragStartWorldY = 0f
    @Volatile var dragWorldX = 0f
    @Volatile var dragWorldY = 0f
    @Volatile var marqueeActive = false
    @Volatile var marqueeX0 = 0f
    @Volatile var marqueeY0 = 0f
    @Volatile var marqueeX1 = 0f
    @Volatile var marqueeY1 = 0f

    // ---- tilemap editing
    @Volatile var brushTile = 0
    @Volatile var brushSize = 1
    @Volatile var brushLayer = 0
    @Volatile var tilemapPreviewLayer = true

    // ---- animation preview (animation editor)
    @Volatile var previewClip = ""
    @Volatile var previewTime = 0f
    @Volatile var previewPlaying = false
    @Volatile var previewLoop = true

    // ---- particle preview (particle editor)
    @Volatile var previewEmitterId = -1L
    @Volatile var previewBurstRequested = false

    /** Gizmo length in world units so it stays a constant size on screen. */
    fun gizmoLength() = 90f * (view.heightPx / 1080f).coerceAtLeast(0.6f) / view.pixelsPerUnit

    fun select(id: Long) {
        selectedId = id
        selectedIds = if (id < 0) LongArray(0) else longArrayOf(id)
        selectionKind = if (id < 0) SelectionKind.NONE else SelectionKind.OBJECT
    }

    fun selectMany(ids: LongArray) {
        selectedIds = ids
        selectedId = ids.firstOrNull() ?: -1L
        selectionKind = if (ids.isEmpty()) SelectionKind.NONE else SelectionKind.OBJECT
    }

    fun clearSelection() = select(-1L)

    fun isSelected(id: Long) = selectedIds.contains(id)

    /** Snaps a world position to the grid when snapping is enabled. */
    fun snap(x: Float, y: Float): FloatArray {
        if (!snapToGrid || gridSize <= 0.0001f) return floatArrayOf(x, y)
        return floatArrayOf(
            Math.round(x / gridSize) * gridSize,
            Math.round(y / gridSize) * gridSize
        )
    }

    /** Applies the axis constraint used while dragging a gizmo. */
    fun constrainAxis(x0: Float, y0: Float, x1: Float, y1: Float): FloatArray = when (activeAxis) {
        1 -> floatArrayOf(x1, y0)
        2 -> floatArrayOf(x0, y1)
        else -> floatArrayOf(x1, y1)
    }

    fun snapAngle(angle: Float): Float =
        if (snapToGrid && snapAngle > 0f) Math.round(angle / snapAngle) * snapAngle else angle

    fun snapshot(): String =
        "tool=${tool.label} sel=${selectedId} grid=$showGrid colliders=$showColliders profiler=$showProfiler"
}
