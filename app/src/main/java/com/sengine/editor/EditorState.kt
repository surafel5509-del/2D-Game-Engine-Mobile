package com.sengine.editor

import com.sengine.engine.core.GameObject
import com.sengine.engine.render.View2D
import com.sengine.engine.scene.Scene

/**
 * Comprehensive editor state for the S-Engine 2D editor.
 * Manages all editor UI state, selection, undo/redo, etc.
 */
class EditorState {
    // View state
    val view = View2D()
    var cameraX = 0f
    var cameraY = 0f
    var cameraZoom = 1f

    // Grid and snapping
    var showGrid = true
    var gridSize = 1f
    var snapEnabled = false
    var snapSize = 0.5f

    // Selection
    val selectedGameObjects = mutableSetOf<String>()
    var hoveredGameObjectId: String? = null

    // Scene
    var currentScene: Scene? = null

    // Undo/Redo
    val undoManager = UndoManager()

    // Debug flags
    var physicsDebugEnabled = false
    var profilerEnabled = false
    var audioDebugEnabled = false
    var particleDebugEnabled = false

    // Editor tools
    var currentTool = EditorTool.SELECT
    var showColliders = true
    var showTriggers = true

    // Clipboard
    private val clipboard = mutableListOf<GameObject>()

    // Multi-selection support
    fun selectObject(id: String, additive: Boolean = false) {
        if (!additive) {
            selectedGameObjects.clear()
        }
        selectedGameObjects.add(id)
    }

    fun deselectObject(id: String) {
        selectedGameObjects.remove(id)
    }

    fun clearSelection() {
        selectedGameObjects.clear()
    }

    fun isSelected(id: String): Boolean = selectedGameObjects.contains(id)

    fun getSelectedCount(): Int = selectedGameObjects.size

    // Clipboard operations
    fun copyToClipboard(gameObjects: List<GameObject>) {
        clipboard.clear()
        gameObjects.forEach { go ->
            clipboard.add(deepCopyGameObject(go))
        }
    }

    fun getClipboard(): List<GameObject> = clipboard

    private fun deepCopyGameObject(source: GameObject): GameObject {
        val copy = GameObject(source.name + "_copy")
        copy.x = source.x
        copy.y = source.y
        copy.rotation = source.rotation
        copy.scaleX = source.scaleX
        copy.scaleY = source.scaleY
        copy.tags.addAll(source.tags)

        // Copy components (simplified - would need proper serialization)
        source.components.forEach { comp ->
            try {
                val compCopy = comp.javaClass.newInstance()
                copy.addComponent(compCopy)
            } catch (e: Exception) {
                // Skip if can't copy
            }
        }

        // Copy children recursively
        source.children.forEach { child ->
            val childCopy = deepCopyGameObject(child)
            copy.addChild(childCopy)
        }

        return copy
    }
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

        // Limit stack size
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
