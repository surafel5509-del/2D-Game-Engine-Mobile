package com.sengine.engine.render

import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Scene
import com.sengine.engine.core.SceneSerializer

enum class Tool { HAND, MOVE, ROTATE, SCALE }

/** State shared between the editor UI thread and the GL thread. */
class EditorState {
    val view = View2D()
    @Volatile var selectedId = -1L
    @Volatile var tool = Tool.MOVE
    @Volatile var showGrid = true
    @Volatile var showColliders = true
    @Volatile var activeAxis = 0 // 0 none, 1 x, 2 y, 3 free

    // Extended editor state
    var cameraX = 0f
    var cameraY = 0f
    var cameraZoom = 1f
    var gridSize = 1f
    var snapEnabled = false
    var snapSize = 0.5f
    var hoveredGameObjectId: Long? = null
    var currentScene: Scene? = null
    val undoManager = UndoManager()
    var physicsDebugEnabled = false
    var profilerEnabled = false
    var audioDebugEnabled = false
    var particleDebugEnabled = false
    var currentTool = EditorTool.SELECT
    var showTriggers = true

    // Multi-selection support
    val selectedGameObjects = mutableSetOf<Long>()
    private val clipboardJson = mutableListOf<org.json.JSONObject>()

    fun selectObject(id: Long, additive: Boolean = false) {
        if (!additive) {
            selectedGameObjects.clear()
        }
        selectedGameObjects.add(id)
        selectedId = id
    }

    fun deselectObject(id: Long) {
        selectedGameObjects.remove(id)
        if (selectedId == id) {
            selectedId = selectedGameObjects.firstOrNull() ?: -1L
        }
    }

    fun clearSelection() {
        selectedGameObjects.clear()
        selectedId = -1L
    }

    fun isSelected(id: Long): Boolean = selectedGameObjects.contains(id)
    fun getSelectedCount(): Int = selectedGameObjects.size

    // Clipboard operations
    fun copyToClipboard(gameObjects: List<GameObject>) {
        clipboardJson.clear()
        gameObjects.forEach { go ->
            clipboardJson.add(SceneSerializer.objectToJson(go))
        }
    }

    fun getClipboardJson(): List<org.json.JSONObject> = clipboardJson

    fun pasteFromClipboard(scene: Scene) {
        clipboardJson.forEach { json ->
            val go = scene.create(json.optString("name", "GameObject"))
            SceneSerializer.applyObjectJson(go, json)
        }
    }

    /** Gizmo length in world units. */
    fun gizmoLength() = 90f * (view.heightPx / 1080f).coerceAtLeast(0.6f) / view.pixelsPerUnit
}

enum class EditorTool {
    SELECT, MOVE, ROTATE, SCALE, HAND
}

/**
 * Undo/Redo manager for editor operations
 */
class UndoManager {
    private val undoStack = mutableListOf<UndoableAction>()
    private val redoStack = mutableListOf<UndoableAction>()
    private val maxStackSize = 100

    interface UndoableAction {
        fun execute()
        fun undo()
        fun description(): String
    }

    fun execute(action: UndoableAction) {
        action.execute()
        undoStack.add(action)
        redoStack.clear()
        if (undoStack.size > maxStackSize) {
            undoStack.removeAt(0)
        }
    }

    fun undo() {
        if (undoStack.isNotEmpty()) {
            val action = undoStack.removeAt(undoStack.size - 1)
            action.undo()
            redoStack.add(action)
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            val action = redoStack.removeAt(redoStack.size - 1)
            action.execute()
            undoStack.add(action)
        }
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun getUndoDescription(): String? = undoStack.lastOrNull()?.description()
    fun getRedoDescription(): String? = redoStack.lastOrNull()?.description()

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    fun getStackSize(): Pair<Int, Int> = undoStack.size to redoStack.size
}
