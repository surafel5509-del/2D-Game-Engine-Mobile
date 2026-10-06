package com.sengine.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.sengine.engine.Engine
import com.sengine.engine.blueprint.Blueprint
import com.sengine.engine.blueprint.BlueprintCompiler
import com.sengine.engine.core.Camera2D
import com.sengine.engine.core.ComponentRegistry
import com.sengine.engine.core.GameObject
import com.sengine.engine.fx.ParticleEmitter
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.core.Scene
import com.sengine.engine.core.ScriptComponent
import com.sengine.engine.core.SceneSerializer
import com.sengine.engine.core.SpriteRenderer
import com.sengine.engine.tilemap.TilemapRenderer
import com.sengine.engine.math.Colors
import com.sengine.engine.render.EditorState
import com.sengine.engine.render.Tool
import com.sengine.project.ClipLibrary
import com.sengine.project.Project
import com.sengine.project.ProjectManager
import com.sengine.project.ScriptTemplates
import java.io.File

/**
 * The 2D editor.
 *
 * Layout: top command bar (play/stop/pause/undo/redo/save/tools), left hierarchy, centre GL
 * viewport with gizmos, right inspector, bottom console/profiler. Every button performs a real
 * operation on the live scene; there are no decorative controls.
 *
 * Strictly 2D: one orthographic viewport, 2D tools only, no 3D camera or mesh editing anywhere.
 */
class EditorActivity : Activity(), InspectorPanel.Host, ViewportController.Callbacks {

    private lateinit var project: Project
    private lateinit var engine: Engine
    private lateinit var history: History
    private lateinit var state: EditorState
    private lateinit var viewport: ViewportController
    private lateinit var inspector: InspectorPanel
    private lateinit var hierarchy: HierarchyAdapter
    private lateinit var hierarchyList: android.widget.ListView
    private lateinit var console: TextView
    private lateinit var titleBar: TextView
    private lateinit var profiler: TextView
    private lateinit var modeButton: TextView
    private lateinit var sceneLabel: TextView
    private lateinit var toolButtons: MutableMap<Tool, TextView>
    private lateinit var leftPanel: View
    private lateinit var rightPanel: View
    private lateinit var bottomPanel: View
    private var gridLabel: TextView? = null
    private var snapCheck: android.widget.CheckBox? = null
    private var dirty = false
    private var searchQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        project = intent.getStringExtra("projectDir")?.let { Project(File(it)) }
            ?: ProjectManager.open(this, intent.getStringExtra("project") ?: "")
        val sceneName = intent.getStringExtra("scene")
            ?: project.listScenes().firstOrNull() ?: project.startScene
        val scene = if (project.sceneExists(sceneName)) project.loadScene(sceneName) else Scene(sceneName.ifBlank { "Main" })

        state = EditorState()
        engine = Engine(project, scene).apply {
            editor = state
            clipLoader = { ClipLibrary.clip(project, it) }
        }
        history = History(engine)
        inspector = InspectorPanel(this, engine, this)

        val root = vbox().apply { setBackgroundColor(C.BG) }
        root.addView(commandBar(), lp(MATCH, WRAP))
        root.addView(toolStrip(), lp(MATCH, WRAP))

        val middle = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        // ---- left: hierarchy + assets
        val left = vbox().apply {
            background = round(C.PANEL, dp(6).toFloat())
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        left.addView(section("Hierarchy", "＋", { addObjectMenu() }))
        hierarchy = HierarchyAdapter(
            this,
            rows = { engine.scene.hierarchy().map { HierarchyAdapter.Row(it.first, it.second) } },
            onSelect = { go, multi -> select(go, multi) },
            onToggleVisible = { go -> go.active = !go.active; onStructureChanged(); onEdited(true) },
            onLongPress = { go -> objectMenu(go) }
        )
        hierarchyList = android.widget.ListView(this).apply {
            adapter = hierarchy
            divider = null
            dividerHeight = 0
            setBackgroundColor(0x00000000)
        }
        left.addView(hierarchyList, lp(MATCH, 0, 0.62f))
        left.addView(section("Assets", "＋", { importAssetDialog() }))
        left.addView(assetList(), lp(MATCH, 0, 0.38f))
        leftPanel = left

        // ---- centre: viewport
        val centre = FrameLayout(this)
        viewport = ViewportController(this, engine, state, this)
        centre.addView(viewport, FrameLayout.LayoutParams(MATCH, MATCH))
        val hud = label("", 11f, 0x99FFFFFF.toInt()).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(0x66000000)
        }
        centre.addView(hud, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START))
        modeButton = button("▶ Play", C.GREEN, 0xFFFFFFFF.toInt()) { togglePlay() }.apply {
            layoutParams = FrameLayout.LayoutParams(dp(96), dp(38), Gravity.TOP or Gravity.END).also {
                it.setMargins(0, dp(8), dp(8), 0)
            }
        }
        centre.addView(modeButton)
        val zoomRow = hbox().apply {
            setBackgroundColor(0x66000000)
            setPadding(dp(6), dp(4), dp(6), dp(4))
            addView(button("＋", 0x00000000) { viewport.zoomBy(1.25f) })
            addView(button("−", 0x00000000) { viewport.zoomBy(0.8f) })
            addView(button("⌖", 0x00000000) {
                state.selectedId.takeIf { it >= 0 }?.let { engine.scene.findById(it) }?.let { viewport.focus(it) }
            })
            addView(button("1:1", 0x00000000) { viewport.resetZoom() })
        }
        centre.addView(zoomRow, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).also {
            it.setMargins(0, dp(54), dp(8), 0)
        })
        centre.addView(hud, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START))
        hudId = hud
        val bottom = vbox().apply {
            setBackgroundColor(0xCC15181E.toInt())
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        profiler = label("", 11f, C.GREEN).apply { typeface = Typeface.MONOSPACE }
        bottom.addView(profiler)
        console = label("", 11f, C.DIM).apply {
            typeface = Typeface.MONOSPACE
            maxLines = 4
        }
        bottom.addView(console)
        bottom.visibility = View.GONE
        bottomPanel = bottom
        centre.addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        middle.addView(left, lp(dp(230), MATCH))
        middle.addView(centre, lp(0, MATCH, 1f))
        rightPanel = inspector.view().apply {
            layoutParams = lp(dp(300), MATCH)
        }
        middle.addView(rightPanel)
        root.addView(middle, lp(MATCH, 0, 1f))
        setContentView(root)

        engine.listeners.add(object : Engine.Listener {
            override fun onLog(level: Int, message: String) = runOnUiThread { appendConsole(level, message) }
            override fun onModeChanged(mode: Engine.Mode) = runOnUiThread { updateModeUi(mode) }
            override fun onSceneReplaced() = runOnUiThread { onStructureChanged() }
            override fun onSelectionChanged() = runOnUiThread { inspector.rebuild() }
        })
        profilerTick.post(profilerRunnable)
        updateModeUi(engine.mode)
        onStructureChanged()
        inspector.rebuild()
        toast("Editing ${project.name} · scene $sceneName")
    }

    private lateinit var hudId: TextView

    // ---------------------------------------------------------------- chrome
    private fun commandBar(): View = hbox().apply {
        setBackgroundColor(C.HEADER)
        setPadding(dp(8), dp(6), dp(8), dp(6))
        addView(button("‹") { finish() })
        titleBar = label("S Engine 2D · ${project.name}", 15f, C.TEXT, bold = true).apply {
            setPadding(dp(10), 0, dp(10), 0)
        }
        addView(titleBar)
        sceneLabel = label("", 12f, C.DIM)
        addView(sceneLabel)
        addView(spacer())
        addView(button("↶ Undo") { undo() })
        addView(button("↷ Redo") { redo() }.apply { layoutParams = lp(WRAP, WRAP).margins(dp(4), 0, 0, 0) })
        addView(button("☰ Scenes") { scenesDialog() }.apply { layoutParams = lp(WRAP, WRAP).margins(dp(4), 0, 0, 0) })
        addView(button("⚙ Settings") { settingsDialog() }.apply { layoutParams = lp(WRAP, WRAP).margins(dp(4), 0, 0, 0) })
        addView(button("Console") { bottomPanel.visibility = if (bottomPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
            .apply { layoutParams = lp(WRAP, WRAP).margins(dp(4), 0, 0, 0) })
        addView(button("Save", C.ACCENT, 0xFFFFFFFF.toInt()) { saveScene() }.apply { layoutParams = lp(WRAP, WRAP).margins(dp(4), 0, 0, 0) })
        addView(button("Build APK") {
            startActivity(Intent(this@EditorActivity, BuildActivity::class.java).putExtra("project", project.name))
        }.apply { layoutParams = lp(WRAP, WRAP).margins(dp(4), 0, 0, 0) })
        addView(button("⤓ File") { fileMenu() }.apply { layoutParams = lp(WRAP, WRAP).margins(dp(4), 0, 0, 0) })
    }

    private fun toolStrip(): View {
        toolButtons = HashMap()
        return hbox().apply {
            setBackgroundColor(0xFF26292E.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(4))
            for (tool in Tool.entries) {
                val b = toolButton(toolIcon(tool), tool.label, tool == state.tool) { setTool(tool) }
                toolButtons[tool] = b
                addView(b)
            }
            addView(spacer())
            snapCheck = android.widget.CheckBox(this@EditorActivity).apply {
                text = "Snap"
                setTextColor(C.TEXT)
                buttonTintList = android.content.res.ColorStateList.valueOf(C.ACCENT)
                isChecked = state.snapToGrid
                setOnCheckedChangeListener { _, v -> state.snapToGrid = v }
            }
            addView(snapCheck)
            gridLabel = label("Grid ${fmt(state.gridSize)}", 12f, C.DIM).apply { setPadding(dp(6), 0, dp(6), 0) }
            addView(gridLabel)
            addView(button("−") { setGrid(state.gridSize / 2f) })
            addView(button("+") { setGrid(state.gridSize * 2f) })
            addView(button("Grid: ${if (state.showGrid) "on" else "off"}") {
                state.showGrid = !state.showGrid
                toast("Grid ${if (state.showGrid) "shown" else "hidden"}")
            }.apply { layoutParams = lp(WRAP, WRAP).margins(dp(6), 0, 0, 0) })
            addView(button("Gizmos") { state.showGizmos = !state.showGizmos })
            addView(button("Colliders") { state.showColliders = !state.showColliders })
            addView(button("Lights") { state.showLightGizmos = !state.showLightGizmos })
        }
    }

    private fun assetList(): View {
        val list = android.widget.ListView(this).apply {
            divider = null
            dividerHeight = 0
            setBackgroundColor(0x00000000)
            adapter = object : android.widget.BaseAdapter() {
                override fun getCount() = assetNames().size
                override fun getItem(position: Int) = assetNames()[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup?): View {
                    val name = assetNames().getOrNull(position) ?: ""
                    val row = (convertView as? LinearLayout) ?: hbox().apply {
                        setPadding(dp(4), dp(4), dp(4), dp(4))
                    }
                    row.removeAllViews()
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val glyph = when (ext) {
                        "png", "jpg", "jpeg", "webp", "svg" -> "▧"
                        "wav", "ogg", "mp3" -> "♪"
                        "js" -> "{}"
                        "bp" -> "⧉"
                        "anim", "json" -> "▶"
                        "mat", "glsl" -> "✦"
                        "prefab" -> "◆"
                        "tilemap" -> "▦"
                        else -> "◦"
                    }
                    row.addView(label(glyph, 13f, C.ACCENT).apply { layoutParams = lp(dp(22), WRAP); gravity = Gravity.CENTER })
                    row.addView(label(name, 12f, C.TEXT), lp(0, WRAP, 1f))
                    row.isClickable = true
                    row.setOnClickListener { assetMenu(name) }
                    return row
                }
            }
        }
        return list
    }

    private fun assetNames(): List<String> =
        project.listAssets().filter { searchQuery.isBlank() || it.contains(searchQuery, true) }

    // ---------------------------------------------------------------- tools
    private fun setTool(tool: Tool) {
        state.tool = tool
        for ((t, b) in toolButtons) b.setActive(t == tool)
        hudId.text = tool.label
        toast(tool.label)
    }

    private fun setGrid(size: Float) {
        state.gridSize = size.coerceIn(0.0625f, 64f)
        gridLabel?.text = "Grid ${fmt(state.gridSize)}"
    }

    // ---------------------------------------------------------------- mode
    private fun togglePlay() {
        when (engine.mode) {
            Engine.Mode.EDIT -> {
                engine.play()
                toast("Playing - tap ❚❚ to pause, ■ to stop")
            }
            Engine.Mode.PLAY -> engine.pause()
            Engine.Mode.PAUSED -> engine.resume()
        }
    }

    private fun updateModeUi(mode: Engine.Mode) {
        modeButton.text = when (mode) {
            Engine.Mode.EDIT -> "▶ Play"
            Engine.Mode.PLAY -> "❚❚ Pause"
            Engine.Mode.PAUSED -> "▶ Resume"
        }
        modeButton.setButtonColor(if (mode == Engine.Mode.EDIT) C.GREEN else C.YELLOW)
        modeButton.setTextColor(if (mode == Engine.Mode.EDIT) 0xFFFFFFFF.toInt() else 0xFF202020.toInt())
        if (mode != Engine.Mode.EDIT) {
            modeButton.setOnClickListener { togglePlay() }
            modeButton.setOnLongClickListener { engine.stop(); true }
        } else {
            modeButton.setOnClickListener { togglePlay() }
            modeButton.setOnLongClickListener(null)
        }
    }

    // ---------------------------------------------------------------- hierarchy
    private fun select(go: GameObject, multi: Boolean) {
        if (multi) {
            val ids = state.selectedIds.toMutableList()
            if (go.id in ids) ids.remove(go.id) else ids.add(go.id)
            state.selectMany(ids.toLongArray())
        } else {
            state.select(go.id)
        }
        hierarchy.setSelection(state.selectedIds.toSet())
        inspector.rebuild()
    }

    private fun addObjectMenu() {
        val items = arrayOf(
            "Empty object", "Sprite", "Camera", "Text", "Particle emitter", "Point light",
            "Physics box", "Physics circle", "Character", "Vehicle", "UI canvas", "Tilemap"
        )
        AlertDialog.Builder(this).setTitle("Add object").setItems(items) { _, which ->
            val go = when (which) {
                0 -> engine.scene.create(engine.scene.uniqueName("Object"))
                1 -> engine.scene.create(engine.scene.uniqueName("Sprite")).also { it.add(SpriteRenderer()) }
                2 -> engine.scene.create(engine.scene.uniqueName("Camera")).also { it.add(Camera2D()) }
                3 -> engine.scene.create(engine.scene.uniqueName("Text")).also {
                    it.add(com.sengine.engine.core.TextRenderer())
                }
                4 -> engine.scene.create(engine.scene.uniqueName("Particles")).also { it.add(ParticleEmitter()) }
                5 -> engine.scene.create(engine.scene.uniqueName("Light")).also { it.add(com.sengine.engine.lighting.Light2D()) }
                6 -> engine.scene.create(engine.scene.uniqueName("Box")).also {
                    it.add(SpriteRenderer()); it.add(Rigidbody2D()); it.add(com.sengine.engine.core.Collider2D())
                }
                7 -> engine.scene.create(engine.scene.uniqueName("Circle")).also {
                    val s = SpriteRenderer(); s.shape = SpriteRenderer.SHAPE_CIRCLE; it.add(s)
                    it.add(Rigidbody2D())
                    val c = com.sengine.engine.core.Collider2D(); c.shape = com.sengine.engine.core.Collider2D.SHAPE_CIRCLE
                    it.add(c)
                }
                8 -> engine.scene.create(engine.scene.uniqueName("Player")).also {
                    val s = SpriteRenderer(); s.shape = SpriteRenderer.SHAPE_CAPSULE; s.width = 0.7f; s.height = 1.6f
                    it.add(s)
                    it.add(com.sengine.engine.character.CharacterController2D())
                    it.add(com.sengine.engine.core.Health())
                    val c = com.sengine.engine.core.Collider2D(); c.shape = com.sengine.engine.core.Collider2D.SHAPE_CAPSULE
                    c.width = 0.7f; c.height = 1.6f
                    it.add(c)
                    it.tag = "Player"
                }
                9 -> engine.scene.create(engine.scene.uniqueName("Vehicle")).also {
                    it.add(SpriteRenderer())
                    it.add(Rigidbody2D())
                    it.add(com.sengine.engine.core.Collider2D())
                    it.add(com.sengine.engine.vehicle.Vehicle2D())
                }
                10 -> engine.scene.create(engine.scene.uniqueName("UI")).also {
                    it.add(com.sengine.engine.ui.UiCanvas())
                }
                else -> engine.scene.create(engine.scene.uniqueName("Tilemap")).also { it.add(TilemapRenderer()) }
            }
            // place new objects at the viewport centre so they are visible immediately
            go.x = state.view.cx
            go.y = state.view.cy
            select(go, false)
            onStructureChanged()
            onEdited(true)
        }.show()
    }

    private fun objectMenu(go: GameObject) {
        val items = arrayOf(
            "Rename", "Duplicate", "Add child", "Focus", "Add script", "Add physics", "Save as prefab", "Delete"
        )
        AlertDialog.Builder(this).setTitle(go.name).setItems(items) { _, which ->
            when (which) {
                0 -> inputDialog("Rename", go.name) { n -> if (n.isNotBlank()) { go.name = n; onStructureChanged() } }
                1 -> { val c = engine.scene.duplicate(go); select(c, false); onStructureChanged(); onEdited(true) }
                2 -> {
                    val child = engine.scene.create(engine.scene.uniqueName("Child"), go)
                    select(child, false)
                    onStructureChanged()
                    onEdited(true)
                }
                3 -> viewport.focus(go)
                4 -> {
                    val scripts = project.listAssets(com.sengine.engine.core.AssetKind.SCRIPT)
                    AlertDialog.Builder(this)
                        .setTitle("Attach script")
                        .setItems((scripts + "New script…").toTypedArray()) { _, i ->
                            if (i == scripts.size) {
                                inputDialog("Script name", "Behaviour.js") { n ->
                                    val name = if (n.endsWith(".js")) n else "$n.js"
                                    project.writeAsset(name, ScriptTemplates.basic())
                                    attachScript(go, name)
                                }
                            } else attachScript(go, scripts[i])
                        }.show()
                }
                5 -> {
                    if (go.getAny<Rigidbody2D>() == null) go.add(Rigidbody2D())
                    if (go.getAny<com.sengine.engine.core.Collider2D>() == null) go.add(com.sengine.engine.core.Collider2D())
                    onEdited(true)
                    inspector.rebuild()
                }
                6 -> savePrefab(go)
                else -> {
                    engine.scene.remove(go)
                    state.clearSelection()
                    onStructureChanged()
                    onEdited(true)
                }
            }
        }.show()
    }

    private fun attachScript(go: GameObject, asset: String) {
        val existing = go.getAny<ScriptComponent>() ?: ScriptComponent().also { go.add(it) }
        existing.script = asset
        onEdited(true)
        inspector.rebuild()
        toast("Attached $asset")
    }

    private fun savePrefab(go: GameObject) {
        inputDialog("Prefab name", go.name) { raw ->
            val name = if (raw.endsWith(".prefab")) raw else "$raw.prefab"
            val prefab = com.sengine.engine.core.Prefab.fromObject(go, raw)
            project.writeAsset(name, prefab.toJson().toString(2))
            engine.clearPrefabCache()
            toast("Saved $name - spawn it with scene.instantiate(\"$name\", x, y)")
        }
    }

    private fun importAssetDialog() {
        @Suppress("DEPRECATION")
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_IMPORT
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        val name = queryName(uri) ?: "asset_${System.currentTimeMillis()}"
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                project.assetFile(name).outputStream().use { input.copyTo(it) }
            }
            toast("Imported $name")
            onStructureChanged()
        } catch (e: Exception) {
            toast("Import failed: ${e.message}")
        }
    }

    private fun queryName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else uri.lastPathSegment
        }
    } catch (e: Exception) {
        uri.lastPathSegment
    }

    private fun assetMenu(name: String) {
        val ext = name.substringAfterLast('.', "").lowercase()
        val actions = ArrayList<String>()
        actions.add("Open")
        when (ext) {
            "js", "bp" -> actions.add("Edit")
            "anim", "json" -> actions.add("Open animation editor")
            "png", "jpg", "jpeg", "webp", "svg" -> actions.add("Create sprite sheet clip")
            "mat" -> actions.add("Edit shader")
        }
        actions.add("Rename")
        actions.add("Delete")
        AlertDialog.Builder(this).setTitle(name).setItems(actions.toTypedArray()) { _, which ->
            val action = actions[which]
            when {
                action == "Edit" -> {
                    if (ext == "bp") startActivity(
                        Intent(this, BlueprintEditorActivity::class.java)
                            .putExtra("project", project.name).putExtra("asset", name)
                    ) else startActivity(
                        Intent(this, ScriptEditorActivity::class.java)
                            .putExtra("project", project.name).putExtra("asset", name)
                    )
                }
                action == "Open animation editor" -> startActivity(
                    Intent(this, AnimationEditorActivity::class.java).putExtra("project", project.name)
                )
                action == "Create sprite sheet clip" -> inputDialog("Clip name", name.substringBeforeLast('.')) { raw ->
                    ClipLibrary.createGridClip(project, raw.ifBlank { "Clip" }, name, 4, 4, 12f)
                    toast("Created ${raw}.anim (4x4 grid) - open the animation editor to tune it")
                }
                action == "Edit shader" -> shaderEditor(name)
                action == "Rename" -> inputDialog("Rename asset", name) { raw ->
                    if (raw.isNotBlank() && raw != name) {
                        project.assetFile(name).renameTo(project.assetFile(raw))
                        onStructureChanged()
                    }
                }
                action == "Delete" -> confirmDialog("Delete $name?", "The file is removed from the project.") {
                    project.assetFile(name).delete()
                    onStructureChanged()
                }
                action == "Open" -> {
                    val ref = if (ext == "prefab") name else name
                    toast("Use this asset from the inspector ($ref)")
                }
            }
        }.show()
    }

    private fun shaderEditor(name: String) {
        val current = project.readAsset(name)
            ?: com.sengine.engine.render.Material2D.preset("sprite").toJson().toString(2)
        val editor = field(current, multiline = true).apply {
            typeface = Typeface.MONOSPACE
            layoutParams = lp(MATCH, dp(320))
        }
        val box = vbox().apply { setPadding(dp(12), dp(8), dp(12), 0) }
        box.addView(label("Edit the material JSON (shader, floats, colors, textures) or paste a GLSL fragment.", 11f, C.DIM))
        box.addView(editor)
        AlertDialog.Builder(this)
            .setTitle(name)
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                project.writeAsset(name, editor.text.toString())
                toast("Saved $name")
            }
            .setNeutralButton("GLSL template") { _, _ ->
                editor.setText(com.sengine.engine.render.MaterialLibrary(project).newShaderTemplate())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- scenes, settings, files
    private fun scenesDialog() {
        val scenes = project.listScenes()
        val items = scenes + listOf("＋ New scene…", "⧉ Duplicate current", "🗑 Delete current")
        AlertDialog.Builder(this).setTitle("Scenes (${project.name})").setItems(items.toTypedArray()) { _, which ->
            when {
                which < scenes.size -> {
                    if (dirty) saveScene()
                    val scene = project.loadScene(scenes[which])
                    engine.post {
                        engine.replaceScene(scene)
                        engine.play()
                    }
                }
                which == scenes.size -> inputDialog("New scene name", "Level2") { raw ->
                    val n = raw.trim()
                    if (n.isBlank()) return@inputDialog
                    if (project.sceneExists(n)) return@inputDialog toast("Scene exists")
                    val fresh = Scene(n)
                    project.saveScene(fresh)
                    toast("Created $n")
                }
                which == scenes.size + 1 -> inputDialog("Duplicate as", engine.scene.name + " Copy") { raw ->
                    val n = raw.trim().ifBlank { engine.scene.name + " Copy" }
                    val copy = SceneSerializer.fromJson(SceneSerializer.toJson(engine.scene))
                    copy.name = n
                    project.saveScene(copy)
                    toast("Duplicated as $n")
                }
                else -> confirmDialog("Delete scene ${engine.scene.name}?", "This cannot be undone.") {
                    project.deleteScene(engine.scene.name)
                    val next = project.listScenes().firstOrNull()
                    if (next != null) engine.replaceScene(project.loadScene(next))
                    toast("Deleted")
                }
            }
        }.show()
    }

    private fun settingsDialog() {
        val box = vbox().apply { setPadding(dp(14), dp(8), dp(14), 0) }
        box.addView(label("Project name: ${project.name}", 13f, C.TEXT, bold = true))
        box.addView(label("Start scene", 12f, C.DIM).apply { setPadding(0, dp(10), 0, 0) })
        val scenes = project.listScenes().ifEmpty { listOf(project.startScene) }
        var startIndex = scenes.indexOf(project.startScene).coerceAtLeast(0)
        box.addView(choice(scenes, startIndex) { startIndex = it })
        box.addView(label("Orientation", 12f, C.DIM).apply { setPadding(0, dp(10), 0, 0) })
        var orientation = project.orientation
        box.addView(choice(listOf("Landscape", "Portrait"), orientation) { orientation = it })
        box.addView(label("Default gravity Y", 12f, C.DIM).apply { setPadding(0, dp(10), 0, 0) })
        var gravity = engine.scene.gravityY
        box.addView(context(gravity))
        val gravityField = box.getChildAt(box.childCount - 1) as android.widget.LinearLayout
        (gravityField.getChildAt(0) as android.widget.EditText).setText(fmt(gravity))
        box.addView(label("Physics layers", 12f, C.DIM).apply { setPadding(0, dp(10), 0, 0) })
        box.addView(label(com.sengine.engine.core.PhysicsLayers.names.joinToString(", "), 11.5f, C.TEXT))
        AlertDialog.Builder(this)
            .setTitle("Project settings")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                project.startScene = scenes[startIndex]
                project.orientation = orientation
                project.saveMeta()
                val g = (gravityField.getChildAt(0) as android.widget.EditText).text.toString().toFloatOrNull()
                if (g != null) { engine.scene.gravityY = g; engine.scene.gravityX = 0f }
                project.saveScene(engine.scene)
                toast("Project settings saved")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun context(value: Float): View = hbox().apply { addView(numberField(value, 0.5f) { /* read on save */ }) }

    private fun fileMenu() {
        val items = arrayOf("Save scene", "Save scene as…", "Export project .zip", "Capture screenshot", "Import asset", "Asset library", "Open in player")
        AlertDialog.Builder(this).setItems(items) { _, which ->
            when (which) {
                0 -> saveScene()
                1 -> inputDialog("Save as", engine.scene.name) { raw ->
                    val n = raw.trim()
                    if (n.isNotBlank()) {
                        engine.scene.name = n
                        project.saveScene(engine.scene)
                        dirty = false
                        toast("Saved scene $n")
                    }
                }
                2 -> exportProjectZip()
                3 -> captureScreenshot()
                4 -> importAssetDialog()
                5 -> startActivity(Intent(this, AssetStoreActivity::class.java).putExtra("project", project.name))
                6 -> startActivity(Intent(this, PlayerActivity::class.java).putExtra("project", project.name))
            }
        }.show()
    }

    private fun exportProjectZip() {
        try {
            val out = File(cacheDir, "${project.name}.zip")
            out.outputStream().use { com.sengine.project.ProjectManager.exportZip(project, it) }
            val uri = ShareProvider.uriFor(this, out)
            if (uri == null) return toast("Export failed")
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/zip")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share project"))
        } catch (e: Exception) {
            toast("Export failed: ${e.message}")
        }
    }

    private fun captureScreenshot() {
        viewport.screenshot { bmp ->
            try {
                val name = project.uniqueAssetName("${engine.scene.name}_shot.png")
                project.assetFile(name).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                toast("Saved assets/$name")
                onStructureChanged()
            } catch (e: Exception) {
                toast("Screenshot failed: ${e.message}")
            }
        }
    }

    private fun saveScene() {
        try {
            project.scenesDir.mkdirs()
            project.saveScene(engine.scene)
            dirty = false
            toast("Saved scene ${engine.scene.name}")
        } catch (e: Exception) {
            toast("Save failed: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- undo / redo
    private fun undo() {
        val restored = history.undo(state.selectedId) ?: return toast("Nothing to undo")
        applySnapshot(restored.first, restored.second)
    }

    private fun redo() {
        val restored = history.redo(state.selectedId) ?: return toast("Nothing to redo")
        applySnapshot(restored.first, restored.second)
    }

    private fun applySnapshot(scene: Scene, selected: Long) {
        engine.replaceScene(scene)
        state.select(selected)
        onStructureChanged()
        inspector.rebuild()
        toast("Scene state restored")
    }

    // ---------------------------------------------------------------- profiler / console
    private val profilerTick = android.os.Handler(android.os.Looper.getMainLooper())
    private val profilerRunnable = object : Runnable {
        override fun run() {
            if (::engine.isInitialized) {
                val stats = engine.renderer?.renderer?.stats
                profiler.text = buildString {
                    append("fps ${engine.fps.toInt()}")
                    append("  ·  frame ${engine.renderMs.toInt()}ms (script ${engine.scriptMs.toInt()} physics ${engine.physicsMs.toInt()} anim ${engine.animationMs.toInt()} fx ${engine.particlesMs.toInt()})")
                    append("  ·  objects ${engine.scene.objects.count { !it.destroyed }}")
                    append("  ·  bodies ${engine.rigidBodyCount}")
                    append("  ·  particles ${engine.activeParticles}")
                    if (stats != null) {
                        append("  ·  draws ${stats.drawCalls} quads ${stats.quadCount} batches ${stats.batchCount} culled ${stats.culled}")
                        append("  ·  textures ${engine.renderer?.textures?.textureCount ?: 0} (${(engine.renderer?.textures?.uploadedBytes ?: 0L) / 1024} KB)")
                    }
                    append("  ·  heap ${(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576}MB")
                }
                sceneLabel.text = "· ${engine.scene.name}" + if (dirty) " •" else ""
            }
            profilerTick.postDelayed(this, 500)
        }
    }

    private fun appendConsole(level: Int, message: String) {
        val prefix = when (level) {
            2 -> "✘ "
            1 -> "▲ "
            else -> "• "
        }
        console.append("$prefix$message\n")
        val lines = console.text.split('\n')
        if (lines.size > 60) console.text = lines.takeLast(50).joinToString("\n")
    }

    // ---------------------------------------------------------------- lifecycle
    override fun onResume() {
        super.onResume()
        profilerTick.post(profilerRunnable)
    }

    override fun onPause() {
        super.onPause()
        profilerTick.removeCallbacks(profilerRunnable)
        if (dirty) saveScene()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::engine.isInitialized) synchronized(engine.lock) { engine.release() }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (engine.input.handleKeyEvent(event ?: KeyEvent(KeyEvent.ACTION_DOWN, keyCode), true)) {
            when (keyCode) {
                KeyEvent.KEYCODE_Z -> { undo(); return true }
                KeyEvent.KEYCODE_Y -> { redo(); return true }
                KeyEvent.KEYCODE_S -> { saveScene(); return true }
                KeyEvent.KEYCODE_SPACE -> { togglePlay(); return true }
                KeyEvent.KEYCODE_DEL -> {
                    state.selectedId.takeIf { it >= 0 }?.let { engine.scene.findById(it) }?.let {
                        engine.scene.remove(it)
                        state.clearSelection()
                        onStructureChanged()
                        onEdited(true)
                    }
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        engine.input.handleKeyEvent(event ?: KeyEvent(KeyEvent.ACTION_UP, keyCode), false)
        return super.onKeyUp(keyCode, event)
    }

    // ---------------------------------------------------------------- ViewportController.Callbacks
    override fun onSelectionChanged(go: GameObject?) {
        hierarchy.setSelection(state.selectedIds.toSet())
        inspector.rebuild()
    }

    override fun onSceneEdited(commit: Boolean) {
        if (commit) history.record(state.selectedId)
        dirty = true
        hierarchy.notifyDataSetChanged()
    }

    override fun onContextMenu(go: GameObject?, worldX: Float, worldY: Float) {
        if (go != null) {
            select(go, false)
            objectMenu(go)
        } else {
            AlertDialog.Builder(this)
                .setItems(arrayOf("Add object here", "Paste", "Reset view")) { _, which ->
                    when (which) {
                        0 -> addObjectMenu()
                        2 -> viewport.resetZoom()
                    }
                }.show()
        }
    }

    override fun onStatus(text: String) {
        hudId.text = text
    }

    // ---------------------------------------------------------------- InspectorPanel.Host
    override fun onEdited(commit: Boolean) {
        dirty = true
        if (commit) history.record(state.selectedId)
        hierarchy.notifyDataSetChanged()
    }

    override fun onStructureChanged() {
        hierarchy.notifyDataSetChanged()
        dirty = true
    }

    override fun openScript(name: String) {
        startActivity(Intent(this, ScriptEditorActivity::class.java)
            .putExtra("project", project.name).putExtra("asset", name))
    }

    override fun openTilemapEditor(go: GameObject) {
        // the tile palette lives in the inspector + viewport tools (real painting, not a stub)
        setTool(if (go.getAny<TilemapRenderer>()?.data != null) Tool.TILE_PAINT else Tool.SELECT)
        toast("Tile tools active - pick a tile in the inspector and paint in the viewport")
        inspector.rebuild()
    }

    override fun openParticleEditor(go: GameObject) {
        val emitter = go.getAny<ParticleEmitter>() ?: return
        AlertDialog.Builder(this)
            .setTitle("Particle preset")
            .setItems(com.sengine.engine.fx.ParticlePresets.names.toTypedArray()) { _, which ->
                emitter.applyPreset(com.sengine.engine.fx.ParticlePresets.names[which])
                emitter.burst()
                toast("Preset: ${emitter.preset}")
                inspector.rebuild()
            }.show()
    }

    override fun openAnimationEditor(clip: String) {
        startActivity(Intent(this, AnimationEditorActivity::class.java)
            .putExtra("project", project.name).putExtra("clip", clip))
    }

    override fun openPrefabEditor(go: GameObject) {
        savePrefab(go)
    }

    override fun pickAsset(kind: com.sengine.engine.core.AssetKind, current: String, onPick: (String) -> Unit) {
        val list = project.listAssets(kind).let { assets ->
            if (kind == com.sengine.engine.core.AssetKind.TEXTURE) assets.filter {
                val e = it.substringAfterLast('.', "").lowercase()
                e in listOf("png", "jpg", "jpeg", "webp", "svg", "atlas")
            } else assets
        }
        if (list.isEmpty()) {
            toast("No ${kind.name.lowercase()} assets yet - import one with the Assets panel")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Pick ${kind.name.lowercase()}")
            .setItems(list.toTypedArray()) { _, which -> onPick(list[which]); inspector.rebuild() }
            .setNeutralButton("Clear") { _, _ -> onPick(""); inspector.rebuild() }
            .show()
    }

    companion object {
        private const val REQ_IMPORT = 61
    }
}
