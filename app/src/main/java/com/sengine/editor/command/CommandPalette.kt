package com.sengine.editor.command

import com.sengine.editor.EditorState
import com.sengine.engine.core.*
import com.sengine.engine.scene.Scene

/**
 * Command Palette system - quick access to all editor actions via search.
 * Similar to VS Code's Ctrl+Shift+P command palette.
 */
class CommandPalette(private val editorState: EditorState) {
    private val commands = mutableListOf<Command>()
    private var filteredCommands = mutableListOf<Command>()
    private var searchQuery = ""
    private var selectedIndex = 0
    var isOpen = false

    data class Command(
        val id: String,
        val name: String,
        val category: String,
        val description: String = "",
        val shortcut: String = "",
        val action: () -> Unit
    )

    init {
        registerDefaultCommands()
    }

    private fun registerDefaultCommands() {
        // Scene commands
        register(Command("scene.new", "New Scene", "Scene", "Create a new empty scene") { newScene() })
        register(Command("scene.save", "Save Scene", "Scene", "Save current scene to file") { saveScene() })
        register(Command("scene.load", "Load Scene", "Scene", "Load a scene from file") { loadScene() })
        register(Command("scene.save_as", "Save Scene As...", "Scene", "Save scene with a new name") { saveSceneAs() })

        // GameObject commands
        register(Command("object.create_empty", "Create Empty GameObject", "GameObject") { createEmptyGO() })
        register(Command("object.create_sprite", "Create Sprite", "GameObject") { createSprite() })
        register(Command("object.create_text", "Create Text", "GameObject") { createText() })
        register(Command("object.create_particle", "Create Particle Emitter", "GameObject") { createParticle() })
        register(Command("object.create_ui", "Create UI Element", "GameObject") { createUI() })
        register(Command("object.duplicate", "Duplicate Selected", "GameObject", "Ctrl+D") { duplicateSelected() })
        register(Command("object.delete", "Delete Selected", "GameObject", "Del") { deleteSelected() })
        register(Command("object.rename", "Rename Selected", "GameObject", "F2") { renameSelected() })
        register(Command("object.group", "Group Selected", "GameObject", "Ctrl+G") { groupSelected() })

        // Component commands
        register(Command("component.add_rigidbody", "Add Rigidbody", "Component") { addComponent<Rigidbody>() })
        register(Command("component.add_collider", "Add Collider", "Component") { addComponent<Collider2D>() })
        register(Command("component.add_joint", "Add Joint", "Component") { addComponent<JointComponent>() })
        register(Command("component.add_audio", "Add AudioSource", "Component") { addComponent<AudioSource>() })
        register(Command("component.add_animation", "Add AnimationPlayer", "Component") { addComponent<AnimationPlayer>() })
        register(Command("component.add_particles", "Add ParticleEmitter", "Component") { addComponent<ParticleEmitter>() })
        register(Command("component.add_trigger", "Add TriggerZone", "Component") { addComponent<TriggerZone>() })
        register(Command("component.add_script", "Add Script", "Component") { addComponent<ScriptComponent>() })

        // Edit commands
        register(Command("edit.undo", "Undo", "Edit", "Ctrl+Z") { editorState.undoManager.undo() })
        register(Command("edit.redo", "Redo", "Edit", "Ctrl+Y") { editorState.undoManager.redo() })
        register(Command("edit.copy", "Copy", "Edit", "Ctrl+C") { copy() })
        register(Command("edit.paste", "Paste", "Edit", "Ctrl+V") { paste() })
        register(Command("edit.cut", "Cut", "Edit", "Ctrl+X") { cut() })
        register(Command("edit.select_all", "Select All", "Edit", "Ctrl+A") { selectAll() })

        // View commands
        register(Command("view.reset_camera", "Reset Camera", "View") { resetCamera() })
        register(Command("view.fit_all", "Fit All Objects", "View", "F") { fitAll() })
        register(Command("view.fit_selected", "Focus Selected", "View", "F") { focusSelected() })
        register(Command("view.toggle_grid", "Toggle Grid", "View", "G") { toggleGrid() })
        register(Command("view.toggle_snap", "Toggle Snap", "View") { toggleSnap() })

        // Tools commands
        register(Command("tools.vfx_editor", "Open VFX Editor", "Tools") { openVFXEditor() })
        register(Command("tools.animation_editor", "Open Animation Editor", "Tools") { openAnimationEditor() })
        register(Command("tools.tilemap_editor", "Open Tilemap Editor", "Tools") { openTilemapEditor() })
        register(Command("tools.physics_debug", "Toggle Physics Debug", "Tools") { togglePhysicsDebug() })
        register(Command("tools.profiler", "Toggle Profiler", "Tools") { toggleProfiler() })

        // Build commands
        register(Command("build.test", "Test Game", "Build", "F5") { testGame() })
        register(Command("build.apk", "Build APK", "Build") { buildAPK() })
    }

    fun register(command: Command) {
        commands.add(command)
        filteredCommands = commands.toMutableList()
    }

    fun open() {
        isOpen = true
        searchQuery = ""
        selectedIndex = 0
        filteredCommands = commands.toMutableList()
    }

    fun close() {
        isOpen = false
    }

    fun updateSearch(query: String) {
        searchQuery = query.lowercase()
        filteredCommands = commands.filter { cmd ->
            cmd.name.lowercase().contains(searchQuery) ||
            cmd.category.lowercase().contains(searchQuery) ||
            cmd.description.lowercase().contains(searchQuery)
        }.sortedBy { it.name }.toMutableList()
        selectedIndex = 0
    }

    fun moveSelection(delta: Int) {
        selectedIndex = (selectedIndex + delta).coerceIn(0, filteredCommands.size - 1)
    }

    fun executeSelected() {
        if (filteredCommands.isNotEmpty() && selectedIndex < filteredCommands.size) {
            filteredCommands[selectedIndex].action()
            close()
        }
    }

    fun getFilteredCommands(): List<Command> = filteredCommands
    fun getSelectedIndex(): Int = selectedIndex

    // Command implementations
    private fun newScene() {
        editorState.currentScene = Scene("New Scene")
        editorState.selectedGameObjects.clear()
    }

    private fun saveScene() {
        // Will be connected to file system
    }

    private fun loadScene() {
        // Will be connected to file system
    }

    private fun saveSceneAs() {
        // Will be connected to file system
    }

    private fun createEmptyGO() {
        val go = GameObject("GameObject")
        editorState.currentScene?.root?.addChild(go)
        editorState.selectedGameObjects.clear()
        editorState.selectedGameObjects.add(go.id)
    }

    private fun createSprite() {
        val go = GameObject("Sprite")
        go.addComponent(SpriteRenderer())
        editorState.currentScene?.root?.addChild(go)
        editorState.selectedGameObjects.clear()
        editorState.selectedGameObjects.add(go.id)
    }

    private fun createText() {
        val go = GameObject("Text")
        go.addComponent(TextRenderer())
        editorState.currentScene?.root?.addChild(go)
        editorState.selectedGameObjects.clear()
        editorState.selectedGameObjects.add(go.id)
    }

    private fun createParticle() {
        val go = GameObject("Particles")
        go.addComponent(ParticleEmitter())
        editorState.currentScene?.root?.addChild(go)
        editorState.selectedGameObjects.clear()
        editorState.selectedGameObjects.add(go.id)
    }

    private fun createUI() {
        val go = GameObject("UI")
        go.addComponent(UICanvas())
        editorState.currentScene?.root?.addChild(go)
        editorState.selectedGameObjects.clear()
        editorState.selectedGameObjects.add(go.id)
    }

    private fun duplicateSelected() {
        // Will be implemented
    }

    private fun deleteSelected() {
        val scene = editorState.currentScene ?: return
        val ids = editorState.selectedGameObjects.toList()
        ids.forEach { id ->
            scene.root.findChildRecursive { it.id == id }?.let { go ->
                go.parent?.removeChild(go)
            }
        }
        editorState.selectedGameObjects.clear()
    }

    private fun renameSelected() {
        // Will be connected to UI dialog
    }

    private fun groupSelected() {
        if (editorState.selectedGameObjects.size < 2) return
        val scene = editorState.currentScene ?: return
        val group = GameObject("Group")
        scene.root.addChild(group)
        val selected = editorState.selectedGameObjects.mapNotNull { id ->
            scene.root.findChildRecursive { it.id == id }
        }
        selected.forEach { go ->
            go.parent?.removeChild(go)
            group.addChild(go)
        }
        editorState.selectedGameObjects.clear()
        editorState.selectedGameObjects.add(group.id)
    }

    private inline fun <reified T : Component> addComponent() {
        val scene = editorState.currentScene ?: return
        val primary = editorState.selectedGameObjects.firstOrNull() ?: return
        val go = scene.root.findChildRecursive { it.id == primary } ?: return
        if (go.getComponent(T::class.java) == null) {
            go.addComponent(T::class.java.newInstance())
        }
    }

    private fun copy() { /* Will be implemented */ }
    private fun paste() { /* Will be implemented */ }
    private fun cut() { /* Will be implemented */ }
    private fun selectAll() {
        val scene = editorState.currentScene ?: return
        editorState.selectedGameObjects.clear()
        scene.root.forEachChild { go ->
            editorState.selectedGameObjects.add(go.id)
        }
    }

    private fun resetCamera() {
        editorState.cameraX = 0f
        editorState.cameraY = 0f
        editorState.cameraZoom = 1f
    }

    private fun fitAll() { /* Will be implemented */ }
    private fun focusSelected() { /* Will be implemented */ }
    private fun toggleGrid() { editorState.showGrid = !editorState.showGrid }
    private fun toggleSnap() { editorState.snapEnabled = !editorState.snapEnabled }
    private fun openVFXEditor() { /* Will be implemented */ }
    private fun openAnimationEditor() { /* Will be implemented */ }
    private fun openTilemapEditor() { /* Will be implemented */ }
    private fun togglePhysicsDebug() { editorState.physicsDebugEnabled = !editorState.physicsDebugEnabled }
    private fun toggleProfiler() { editorState.profilerEnabled = !editorState.profilerEnabled }
    private fun testGame() { /* Will be implemented */ }
    private fun buildAPK() { /* Will be implemented */ }
}
