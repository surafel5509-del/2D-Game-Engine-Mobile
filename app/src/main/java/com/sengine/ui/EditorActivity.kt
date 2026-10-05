package com.sengine.ui

import android.annotation.SuppressLint
import android.app.ProgressDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sengine.BuildConfig
import com.sengine.engine.Engine
import com.sengine.engine.core.AssetKind
import com.sengine.engine.core.AudioSource
import com.sengine.engine.core.Camera2D
import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Joint2D
import com.sengine.engine.core.ParticleEmitter
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.core.Scene
import com.sengine.engine.core.ScriptComponent
import com.sengine.engine.core.SpriteRenderer
import com.sengine.engine.core.SpriteAnimator
import com.sengine.engine.core.TextRenderer
import com.sengine.engine.render.EditorState
import com.sengine.engine.render.SceneRenderer
import com.sengine.engine.render.Tool
import com.sengine.project.GameExportConfig
import com.sengine.project.GameExporter
import com.sengine.project.Project
import com.sengine.project.ProjectManager
import com.sengine.project.Templates

class EditorActivity : AppCompatActivity(), EditorHost {

    override lateinit var engine: Engine
    override lateinit var history: History
    override lateinit var project: Project
    val state = EditorState()

    private lateinit var glView: GLSurfaceView
    private lateinit var sceneRenderer: SceneRenderer
    private lateinit var controller: ViewportController
    private lateinit var inspector: InspectorPanel
    private lateinit var hierarchy: HierarchyAdapter
    private lateinit var controls: GameControlsView
    private lateinit var toolbar: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var statsText: TextView
    private lateinit var consoleText: TextView
    private lateinit var consoleScroll: ScrollView
    private lateinit var assetsRow: LinearLayout
    private lateinit var assetsScroll: HorizontalScrollView
    private lateinit var hierarchyPanel: View
    private lateinit var inspectorPanel: View
    private lateinit var bottomPanel: LinearLayout
    private lateinit var bottomContent: FrameLayout
    private lateinit var tabConsole: TextView
    private lateinit var tabAssets: TextView
    private lateinit var assetButtons: LinearLayout
    private lateinit var playBtn: TextView
    private lateinit var pauseBtn: TextView
    private lateinit var stepBtn: TextView
    private val toolButtons = HashMap<Tool, TextView>()

    private val handler = Handler(Looper.getMainLooper())
    private var lastObjectCount = -1
    private var importKind = AssetKind.TEXTURE

    private val ticker = object : Runnable {
        override fun run() {
            inspector.refreshValues()
            val count = engine.scene.objects.size
            if (engine.mode != Engine.Mode.EDIT && count != lastObjectCount) refreshHierarchy()
            val mode = when (engine.mode) { Engine.Mode.EDIT -> "EDIT"; Engine.Mode.PLAY -> "▶ PLAYING"; Engine.Mode.PAUSED -> "⏸ PAUSED" }
            val physicsStats = engine.physics.statistics
            statsText.text = "$mode  •  ${engine.scene.name}  •  ${engine.fps.toInt()} FPS  •  $count objects" +
                (if (controller.snap) "  •  snap" else "") +
                "\n${sceneRenderer.renderTimeMs.toInt()} ms  •  ${sceneRenderer.drawCalls} draws / ${sceneRenderer.spritesSubmitted} quads / ${sceneRenderer.textureBinds} binds  •  " +
                "${physicsStats.bodies} bodies / ${physicsStats.candidatePairs} candidates / ${physicsStats.contacts} contacts / ${physicsStats.joints} joints"
            handler.postDelayed(this, 200)
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importAsset(uri)
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) try {
            contentResolver.openOutputStream(uri)?.use { ProjectManager.exportZip(project, it) }
            toast("Project exported")
        } catch (e: Exception) { toast("Export failed: ${e.message}") }
    }

    private val assetLibraryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) refreshAssets()
    }

    private val gameExportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) exportAndroidProject(uri)
    }

    private val installApkLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) stageAndInstallApk(uri)
    }
    private var pendingInstallFile: java.io.File? = null
    private var waitingForInstallPermission = false
    private var pendingGameExport: GameExportConfig? = null

    private val engineListener = object : Engine.Listener {
        override fun onLog(level: Int, message: String) { handler.post { appendConsole(level, message) } }
        override fun onModeChanged(mode: Engine.Mode) { handler.post { updateModeUi() } }
        override fun onSceneReplaced() {
            handler.post {
                refreshHierarchy()
                if (engine.mode == Engine.Mode.EDIT) inspector.rebuild()
            }
        }
    }

    // ================================================================== lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        project = ProjectManager.open(this, intent.getStringExtra("project") ?: run { finish(); return })
        val sceneName = if (project.sceneExists(project.startScene)) project.startScene
        else project.listScenes().firstOrNull() ?: "Main"
        val scene = if (project.sceneExists(sceneName)) project.loadScene(sceneName) else Scene(sceneName).also {
            it.create("Main Camera").add(Camera2D())
        }
        engine = Engine(project, scene)
        history = History(engine)
        engine.listeners.add(engineListener)
        buildUi()
        synchronized(engine.lock) {
            scene.updateTransforms()
            val cam = engine.mainCamera()
            if (cam != null) {
                state.view.cx = cam.world.tx; state.view.cy = cam.world.ty
                state.view.size = (cam.getAny<Camera2D>()!!.size * 1.3f)
            } else state.view.size = 6f
        }
        refreshHierarchy()
        inspector.rebuild()
        refreshAssets()
        updateModeUi()
        appendConsole(0, "S Engine 2D 2.1 — project '${project.name}', scene '${scene.name}'")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (engine.mode != Engine.Mode.EDIT) { engine.stop(); return }
                saveScene(silent = true)
                finish()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        handler.post(ticker)
        refreshAssets()
        if (pendingInstallFile != null) {
            if (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()) continueInstallApk()
            else if (waitingForInstallPermission) {
                waitingForInstallPermission = false
                toast("Allow installs from S Engine in Android Settings, then try Install Game APK again.")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
        handler.removeCallbacks(ticker)
        if (engine.mode == Engine.Mode.EDIT && history.dirty) saveScene(silent = true)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::engine.isInitialized) {
            engine.listeners.remove(engineListener)
            engine.release()
        }
    }

    // ================================================================== UI construction
    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        val root = vbox().apply { setBackgroundColor(C.BG) }

        // ---- toolbar
        toolbar = hbox().apply { setPadding(dp(4), dp(3), dp(4), dp(3)) }
        val tb = toolbar
        fun sep() = tb.addView(View(this).apply { setBackgroundColor(0xFF45474D.toInt()) }, lp(dp(1), dp(22)).margins(dp(5), 0, dp(5), 0))
        fun tbtn(t: String, onClick: (View) -> Unit): TextView {
            val b = button(t, C.PANEL2, C.TEXT, onClick).apply { textSize = 15f; setPadding(dp(10), dp(5), dp(10), dp(5)) }
            tb.addView(b, lp(WRAP, WRAP).margins(dp(2), 0, dp(2), 0))
            return b
        }
        tbtn("←") { onBackPressedDispatcher.onBackPressed() }.tooltipText = "Back to projects"
        titleText = label("", 13f, C.TEXT, true).apply { setPadding(dp(6), 0, dp(6), 0); maxWidth = dp(160); isSingleLine = true }
        tb.addView(titleText)
        sep()
        tbtn("☰") { toggle(hierarchyPanel) }
        for ((tool, glyph) in listOf(Tool.HAND to "✋", Tool.MOVE to "✥", Tool.ROTATE to "⟳", Tool.SCALE to "⤢")) {
            val button = tbtn(glyph) { setTool(tool) }
            button.tooltipText = when (tool) {
                Tool.HAND -> "Pan viewport"
                Tool.MOVE -> "Move tool (W)"
                Tool.ROTATE -> "Rotate tool (E)"
                Tool.SCALE -> "Scale tool (R)"
            }
            toolButtons[tool] = button
        }
        sep()
        playBtn = tbtn("▶") { if (engine.mode == Engine.Mode.EDIT) startPlay() else engine.stop() }.apply { tooltipText = "Run / Stop (Space)" }
        pauseBtn = tbtn("⏸") { if (engine.mode == Engine.Mode.PAUSED) engine.play() else engine.pause() }.apply { tooltipText = "Pause / Resume" }
        stepBtn = tbtn("⏭") { engine.stepFrame() }.apply { tooltipText = "Step one frame" }
        sep()
        tbtn("↶") { undo() }.tooltipText = "Undo (Ctrl+Z)"
        tbtn("↷") { redo() }.tooltipText = "Redo (Ctrl+Y)"
        sep()
        tbtn("＋") { addObjectMenu(it) }.tooltipText = "Add object"
        tbtn("💾") { saveScene() }.tooltipText = "Save scene (Ctrl+S)"
        tbtn("⋮") { mainMenu(it) }
        tbtn("▤") { toggle(inspectorPanel) }
        val tbScroll = HorizontalScrollView(this).apply { addView(tb); isHorizontalScrollBarEnabled = false; setBackgroundColor(C.HEADER) }
        root.addView(tbScroll, lp(MATCH, WRAP))

        // ---- middle
        val middle = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        // hierarchy
        val hp = vbox().apply { setBackgroundColor(C.PANEL) }
        val hh = hbox().apply { setBackgroundColor(C.HEADER); setPadding(dp(8), dp(2), dp(2), dp(2)) }
        hh.addView(label("HIERARCHY", 11f, C.DIM, true), lp(0, WRAP, 1f))
        hh.addView(button("＋", C.HEADER) { addObjectMenu(it) }.apply { textSize = 13f })
        hp.addView(hh, lp(MATCH, WRAP))
        hierarchy = HierarchyAdapter(this,
            onClick = { select(it.id) },
            onLongClick = { go, v -> objectMenu(go, v) },
            onToggleActive = { go -> history.record(state.selectedId); synchronized(engine.lock) { go.active = !go.active }; refreshHierarchy(); inspector.refreshValues() })
        val rv = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@EditorActivity); adapter = hierarchy }
        hp.addView(rv, lp(MATCH, 0, 1f))
        hierarchyPanel = hp
        middle.addView(hp, lp(dp(180), MATCH))

        // viewport
        val vp = FrameLayout(this)
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            sceneRenderer = SceneRenderer(engine, state)
            setRenderer(sceneRenderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        controller = ViewportController(this, engine, state)
        glView.setOnTouchListener(controller)
        vp.addView(glView)
        controls = GameControlsView(this) { engine.input }
        vp.addView(controls)
        statsText = label("", 11f, 0xCCFFFFFF.toInt()).apply {
            setPadding(dp(8), dp(3), dp(8), dp(3)); maxLines = 2
            background = round(0xCC16181C.toInt(), dp(4).toFloat())
        }
        vp.addView(statsText, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(dp(6), dp(6), 0, 0) })
        val frameBtn = button("⌖", 0x88000000.toInt()) { controller.frame(synchronized(engine.lock) { engine.scene.findById(state.selectedId) }) }
        vp.addView(frameBtn, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(6), 0) })
        middle.addView(vp, lp(0, MATCH, 1f))

        // inspector
        val ip = vbox().apply { setBackgroundColor(C.PANEL) }
        val ih = hbox().apply { setBackgroundColor(C.HEADER); setPadding(dp(8), dp(6), dp(8), dp(6)) }
        ih.addView(label("INSPECTOR", 11f, C.DIM, true))
        ip.addView(ih, lp(MATCH, WRAP))
        val inspContent = vbox()
        ip.addView(ScrollView(this).apply { addView(inspContent); isFillViewport = true }, lp(MATCH, 0, 1f))
        inspector = InspectorPanel(this, this, inspContent)
        inspectorPanel = ip
        middle.addView(ip, lp(dp(270), MATCH))

        root.addView(middle, lp(MATCH, 0, 1f))

        // ---- bottom panel
        bottomPanel = vbox().apply { setBackgroundColor(C.PANEL) }
        val tabs = hbox().apply { setBackgroundColor(C.HEADER); setPadding(dp(4), dp(2), dp(4), dp(2)) }
        tabConsole = button("Console", C.HEADER) { showTab(0) }.apply { textSize = 12f }
        tabAssets = button("Assets", C.HEADER) { showTab(1) }.apply { textSize = 12f }
        tabs.addView(tabConsole); tabs.addView(tabAssets)
        tabs.addView(View(this), lp(0, 1, 1f))
        assetButtons = hbox()
        assetButtons.addView(button("+ Script") { newScriptDialog { refreshAssets(); openScript(it) } }.apply { textSize = 12f }, lp(WRAP, WRAP).margins(dp(2), 0, dp(2), 0))
        assetButtons.addView(button("Import Image") { importKind = AssetKind.TEXTURE; importLauncher.launch(arrayOf("image/*")) }.apply { textSize = 12f }, lp(WRAP, WRAP).margins(dp(2), 0, dp(2), 0))
        assetButtons.addView(button("Import Sound") { importKind = AssetKind.SOUND; importLauncher.launch(arrayOf("audio/*")) }.apply { textSize = 12f }, lp(WRAP, WRAP).margins(dp(2), 0, dp(2), 0))
        assetButtons.addView(button("Library · 100") {
            assetLibraryLauncher.launch(Intent(this, AssetLibraryActivity::class.java).putExtra("project", project.name))
        }.apply { textSize = 12f }, lp(WRAP, WRAP).margins(dp(2), 0, dp(2), 0))
        tabs.addView(assetButtons)
        tabs.addView(button("Clear", C.HEADER) { consoleText.text = "" }.apply { textSize = 12f })
        tabs.addView(button("▾", C.HEADER) { toggle(bottomContent) }.apply { textSize = 12f })
        bottomPanel.addView(tabs, lp(MATCH, WRAP))

        bottomContent = FrameLayout(this)
        consoleText = label("", 11f, 0xFFCFD2D6.toInt()).apply { typeface = Typeface.MONOSPACE; setPadding(dp(8), dp(4), dp(8), dp(4)); setTextIsSelectable(true) }
        consoleScroll = ScrollView(this).apply { addView(consoleText) }
        bottomContent.addView(consoleScroll)
        assetsRow = hbox().apply { setPadding(dp(6), dp(6), dp(6), dp(6)) }
        assetsScroll = HorizontalScrollView(this).apply { addView(assetsRow) }
        bottomContent.addView(assetsScroll)
        bottomPanel.addView(bottomContent, lp(MATCH, dp(112)))
        root.addView(bottomPanel, lp(MATCH, WRAP))

        setContentView(root)
        setTool(Tool.MOVE)
        showTab(0)
        updateTitle()
    }

    private fun toggle(v: View) { v.visibility = if (v.visibility == View.VISIBLE) View.GONE else View.VISIBLE }

    private fun showTab(i: Int) {
        consoleScroll.visibility = if (i == 0) View.VISIBLE else View.GONE
        assetsScroll.visibility = if (i == 1) View.VISIBLE else View.GONE
        assetButtons.visibility = if (i == 1) View.VISIBLE else View.GONE
        tabConsole.setTextColor(if (i == 0) C.ACCENT else C.DIM)
        tabAssets.setTextColor(if (i == 1) C.ACCENT else C.DIM)
        bottomContent.visibility = View.VISIBLE
    }

    private fun updateTitle() {
        titleText.text = "${project.name} / ${engine.scene.name}"
    }

    private fun setTool(t: Tool) {
        state.tool = t
        for ((tool, b) in toolButtons) b.setButtonColor(if (tool == t) C.ACCENT else C.PANEL2)
    }

    private fun updateModeUi() {
        val m = engine.mode
        playBtn.text = if (m == Engine.Mode.EDIT) "▶" else "■"
        playBtn.setButtonColor(if (m == Engine.Mode.EDIT) C.PANEL2 else C.GREEN)
        pauseBtn.setButtonColor(if (m == Engine.Mode.PAUSED) C.YELLOW else C.PANEL2)
        stepBtn.alpha = if (m == Engine.Mode.PAUSED) 1f else 0.4f
        toolbar.setBackgroundColor(if (m == Engine.Mode.EDIT) C.HEADER else 0xFF1D2E45.toInt())
        controls.visibility = if (m == Engine.Mode.EDIT || !project.useTouchControls) View.GONE else View.VISIBLE
        controls.reset()
        if (m == Engine.Mode.EDIT) { refreshHierarchy(); inspector.rebuild() }
        updateTitle()
    }

    fun hideKeyboard(v: View) {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    }

    // ================================================================== EditorHost
    override fun selectedId() = state.selectedId

    override fun select(id: Long) {
        currentFocus?.let { it.clearFocus(); hideKeyboard(it) }
        state.selectedId = id
        hierarchy.selectedId = id
        hierarchy.notifyDataSetChanged()
        inspector.rebuild()
    }

    override fun onStructureChanged() = refreshHierarchy()

    fun onObjectEdited() = inspector.refreshValues()

    fun refreshHierarchy() {
        val (items, all) = synchronized(engine.lock) { engine.scene.hierarchy() to engine.scene.objects.toList() }
        lastObjectCount = all.size
        hierarchy.selectedId = state.selectedId
        hierarchy.submit(items, all)
    }

    override fun openScript(name: String) {
        startActivity(Intent(this, ScriptEditorActivity::class.java).putExtra("project", project.name).putExtra("asset", name))
    }

    override fun newScriptDialog(onCreated: (String) -> Unit) {
        val f = field("NewBehaviour")
        MaterialAlertDialogBuilder(this)
            .setTitle("New Script")
            .setView(LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(f, lp(MATCH, WRAP)) })
            .setPositiveButton("Create") { _, _ ->
                var n = f.text.toString().trim().replace(Regex("[^A-Za-z0-9_\\-]"), "").ifBlank { "NewBehaviour" }
                if (!n.endsWith(".js")) n += ".js"
                n = project.uniqueAssetName(n)
                project.writeAsset(n, Templates.NEW_SCRIPT)
                refreshAssets()
                onCreated(n)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ================================================================== play / undo / save
    private fun startPlay() {
        currentFocus?.let { it.clearFocus(); hideKeyboard(it) }
        consoleText.text = ""
        engine.play()
    }

    private fun undo() {
        if (engine.mode != Engine.Mode.EDIT) return
        val r = history.undo(state.selectedId) ?: run { toast("Nothing to undo"); return }
        applySnapshot(r.first, r.second)
    }

    private fun redo() {
        if (engine.mode != Engine.Mode.EDIT) return
        val r = history.redo(state.selectedId) ?: run { toast("Nothing to redo"); return }
        applySnapshot(r.first, r.second)
    }

    private fun applySnapshot(s: Scene, sel: Long) {
        synchronized(engine.lock) { s.name = engine.scene.name; engine.replaceScene(s) }
        state.selectedId = sel
        refreshHierarchy(); inspector.rebuild()
    }

    private fun saveScene(silent: Boolean = false) {
        if (engine.mode != Engine.Mode.EDIT) { if (!silent) toast("Stop play mode before saving"); return }
        try {
            synchronized(engine.lock) { project.saveScene(engine.scene) }
            project.saveMeta()
            history.dirty = false
            if (!silent) toast("Saved ${engine.scene.name}") else appendConsole(0, "Auto-saved ${engine.scene.name}")
        } catch (e: Exception) { toast("Save failed: ${e.message}") }
    }

    // ================================================================== object creation
    private fun addObjectMenu(anchor: View) {
        if (engine.mode != Engine.Mode.EDIT) { toast("Stop play mode to add objects"); return }
        val pm = PopupMenu(this, anchor)
        val items = listOf("Empty", "Square", "Circle", "Triangle", "Sprite Sheet", "Text", "Camera", "Particle System",
            "Physics Box", "Physics Ball", "Physics Joint", "Static Platform", "Trigger Zone", "Empty Child")
        items.forEach { pm.menu.add(it) }
        pm.setOnMenuItemClickListener { createObject(it.title.toString()); true }
        pm.show()
    }

    private fun createObject(kind: String) {
        history.record(state.selectedId)
        val go = synchronized(engine.lock) {
            val scene = engine.scene
            val parent = if (kind == "Empty Child") scene.findById(state.selectedId) else null
            val g = scene.create(if (kind == "Empty Child") "GameObject" else kind, parent)
            if (parent == null) { g.x = snap(state.view.cx); g.y = snap(state.view.cy) }
            when (kind) {
                "Empty", "Empty Child" -> g.name = scene.uniqueName("GameObject")
                "Square" -> g.add(SpriteRenderer())
                "Circle" -> g.add(SpriteRenderer().also { it.shape = 1 })
                "Triangle" -> g.add(SpriteRenderer().also { it.shape = 2 })
                "Sprite Sheet" -> { g.add(SpriteRenderer()); g.add(SpriteAnimator()) }
                "Text" -> g.add(TextRenderer())
                "Camera" -> g.add(Camera2D())
                "Particle System" -> g.add(ParticleEmitter())
                "Physics Box" -> { g.add(SpriteRenderer().also { it.color = 0xFFFFB74D.toInt() }); g.add(Collider2D()); g.add(Rigidbody2D()) }
                "Physics Ball" -> {
                    g.add(SpriteRenderer().also { it.shape = 1; it.color = 0xFF4FC3F7.toInt() })
                    g.add(Collider2D().also { it.shape = 1 }); g.add(Rigidbody2D().also { it.bounciness = 0.6f })
                }
                "Physics Joint" -> {
                    g.add(SpriteRenderer().also { it.shape = 1; it.color = 0xFFB69CFF.toInt() })
                    g.add(Collider2D().also { it.shape = 1; it.radius = 0.12f })
                    g.add(Rigidbody2D().also { it.mass = 0.5f })
                    g.add(Joint2D().also { it.jointType = 1; it.connectedAnchorX = g.x; it.connectedAnchorY = g.y })
                }
                "Static Platform" -> { g.scaleX = 4f; g.scaleY = 0.5f; g.add(SpriteRenderer().also { it.color = 0xFF8D6E63.toInt() }); g.add(Collider2D()) }
                "Trigger Zone" -> { g.scaleX = 2f; g.scaleY = 2f; g.add(Collider2D().also { it.isTrigger = true }) }
            }
            g
        }
        refreshHierarchy()
        select(go.id)
    }

    private fun snap(v: Float) = Math.round(v * 2f) / 2f

    private fun objectMenu(go: GameObject, anchor: View) {
        if (engine.mode != Engine.Mode.EDIT) { select(go.id); return }
        val pm = PopupMenu(this, anchor)
        listOf("Rename", "Duplicate", "Delete", "Create Child", "Move Up", "Move Down", "Unparent", "Frame in View").forEach { pm.menu.add(it) }
        pm.setOnMenuItemClickListener { item ->
            when (item.title) {
                "Rename" -> renameDialog(go)
                "Frame in View" -> controller.frame(go)
                else -> {
                    history.record(state.selectedId)
                    var newSel = state.selectedId
                    synchronized(engine.lock) {
                        val scene = engine.scene
                        when (item.title) {
                            "Duplicate" -> newSel = scene.duplicate(go).also { it.x += 0.5f; it.y -= 0.5f }.id
                            "Delete" -> { scene.remove(go); if (newSel == go.id) newSel = -1 }
                            "Create Child" -> newSel = scene.create("GameObject", go).id
                            "Move Up" -> scene.moveInOrder(go, -1)
                            "Move Down" -> scene.moveInOrder(go, 1)
                            "Unparent" -> InspectorPanel.reparentKeepWorld(go, null)
                        }
                        Unit
                    }
                    refreshHierarchy()
                    select(newSel)
                }
            }
            true
        }
        pm.show()
    }

    private fun renameDialog(go: GameObject) {
        val f = field(go.name)
        MaterialAlertDialogBuilder(this)
            .setTitle("Rename")
            .setView(LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(f, lp(MATCH, WRAP)) })
            .setPositiveButton("OK") { _, _ ->
                history.record(state.selectedId)
                synchronized(engine.lock) { go.name = f.text.toString().ifBlank { go.name } }
                refreshHierarchy(); inspector.rebuild()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ================================================================== menus & scenes
    private fun mainMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        val entries = listOf(
            "Save Scene", "Scenes…", "Run Game (fullscreen)", "Export Project (.zip)",
            "Project Settings…", "Export Android Game Project…", "Install Game APK…",
            (if (state.showGrid) "Hide" else "Show") + " Grid",
            (if (state.showColliders) "Hide" else "Show") + " Colliders",
            "Snap: " + if (controller.snap) "ON" else "OFF",
            "Toggle Bottom Panel", "Script API Reference", "About S Engine"
        )
        entries.forEach { pm.menu.add(it) }
        pm.setOnMenuItemClickListener { item ->
            val t = item.title.toString()
            when {
                t == "Save Scene" -> saveScene()
                t == "Scenes…" -> scenesDialog()
                t.startsWith("Run Game") -> { saveScene(silent = true); startActivity(Intent(this, PlayerActivity::class.java).putExtra("project", project.name)) }
                t.startsWith("Project Settings") -> projectSettingsDialog()
                t.startsWith("Export Android Game") -> { saveScene(silent = true); exportGameDialog() }
                t.startsWith("Install Game APK") -> installApkLauncher.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
                t == "Export Project (.zip)" || t.startsWith("Export Project") -> { saveScene(silent = true); exportLauncher.launch("${project.name}.zip") }
                t.endsWith("Grid") -> state.showGrid = !state.showGrid
                t.endsWith("Colliders") -> state.showColliders = !state.showColliders
                t.startsWith("Snap") -> controller.snap = !controller.snap
                t == "Toggle Bottom Panel" -> toggle(bottomPanel)
                t == "Script API Reference" -> showText("Script API", ScriptEditorActivity.API_DOC)
                t.startsWith("About") -> showText("About S Engine",
                    "S Engine 2.1\n\nAn Android-first 2D game engine and editor.\n\n" +
                        "• Scene editor with hierarchy, inspector, gizmos, undo/redo\n• OpenGL ES 2.0 renderer: shapes, sprites, text, particles\n" +
                        "• Physics: rigidbodies, box/circle colliders, triggers\n• JavaScript behaviours (Mozilla Rhino)\n" +
                        "• Multiple scenes, audio, touch controls, fullscreen player\n• 100 offline CC0 assets; standalone Android game export and APK installer\n• Project import/export as .zip")
            }
            true
        }
        pm.show()
    }

    private fun projectSettingsDialog() {
        val box = vbox().apply { setPadding(dp(20), dp(8), dp(20), 0) }
        box.addView(label("Target device orientation", 12f, C.DIM))
        val orientations = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.VERTICAL }
        val landscape = android.widget.RadioButton(this).apply { id = 1; text = "Landscape"; setTextColor(C.TEXT) }
        val portrait = android.widget.RadioButton(this).apply { id = 2; text = "Portrait"; setTextColor(C.TEXT) }
        orientations.addView(landscape); orientations.addView(portrait)
        orientations.check(if (project.orientation == 1) portrait.id else landscape.id)
        box.addView(orientations)
        val touchControls = android.widget.CheckBox(this).apply {
            text = "Show virtual joystick and A/B buttons"
            setTextColor(C.TEXT)
            isChecked = project.useTouchControls
        }
        box.addView(touchControls)
        MaterialAlertDialogBuilder(this)
            .setTitle("Project Settings")
            .setMessage("These settings apply to fullscreen preview and standalone Android game exports.")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                project.orientation = if (orientations.checkedRadioButtonId == portrait.id) 1 else 0
                project.useTouchControls = touchControls.isChecked
                project.saveMeta()
                toast("Project settings saved")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportGameDialog() {
        if (engine.mode != Engine.Mode.EDIT) { toast("Stop play mode before exporting"); return }
        val rawSlug = project.name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "game" }
        val slug = if (rawSlug.firstOrNull()?.isLetter() == true) rawSlug else "game_$rawSlug"
        val box = vbox().apply { setPadding(dp(20), dp(8), dp(20), 0) }
        val titleField = field(project.name)
        val packageField = field("com.example.$slug")
        box.addView(label("Game title", 12f, C.DIM))
        box.addView(titleField, lp(MATCH, WRAP).margins(0, dp(4), 0, dp(10)))
        box.addView(label("Android package id", 12f, C.DIM))
        box.addView(packageField, lp(MATCH, WRAP).margins(0, dp(4), 0, dp(6)))
        box.addView(label("Creates a player-only Android Studio project ZIP with a GitHub APK build workflow. Build the APK on a computer or CI; this editor does not run Gradle on-device.", 11f, C.DIM))
        MaterialAlertDialogBuilder(this)
            .setTitle("Export Android Game")
            .setView(box)
            .setPositiveButton("Choose ZIP location") { _, _ ->
                val config = GameExportConfig(titleField.text.toString().trim(), packageField.text.toString().trim())
                try {
                    GameExporter.validate(config)
                    pendingGameExport = config
                    gameExportLauncher.launch("${slug}-android-project.zip")
                } catch (e: Exception) { toast(e.message ?: "Invalid export settings") }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportAndroidProject(uri: Uri) {
        val config = pendingGameExport ?: return
        pendingGameExport = null
        val progress = ProgressDialog(this).apply {
            setMessage("Packaging the player runtime and game assets…")
            setCancelable(false)
            show()
        }
        Thread {
            try {
                val output = contentResolver.openOutputStream(uri) ?: throw IllegalStateException("Can't open the selected ZIP location")
                output.use { GameExporter.exportAndroidProject(applicationContext, project, config, it) }
                handler.post {
                    if (!isFinishing) toast("Android game project exported. Open its README to build the APK.")
                    progress.dismiss()
                }
            } catch (e: Exception) {
                handler.post {
                    if (!isFinishing) toast("Game export failed: ${e.message}")
                    progress.dismiss()
                }
            }
        }.start()
    }

    private fun stageAndInstallApk(uri: Uri) {
        val folder = java.io.File(cacheDir, "apks").apply { mkdirs() }
        val staged = java.io.File(folder, "game-install.apk")
        val progress = ProgressDialog(this).apply {
            setMessage("Copying APK into the secure installer cache…")
            setCancelable(false)
            show()
        }
        Thread {
            try {
                val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("Can't open APK")
                input.use { source ->
                    staged.outputStream().buffered().use { target ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= 512L * 1024L * 1024L) { "APK exceeds the 512 MB install limit" }
                            target.write(buffer, 0, count)
                        }
                    }
                }
                handler.post { progress.dismiss(); pendingInstallFile = staged; continueInstallApk() }
            } catch (e: Exception) {
                staged.delete()
                handler.post { progress.dismiss(); toast("APK staging failed: ${e.message}") }
            }
        }.start()
    }

    private fun continueInstallApk() {
        val staged = pendingInstallFile ?: return
        if (!staged.isFile || staged.length() == 0L) { pendingInstallFile = null; toast("APK file is missing or empty"); return }
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            waitingForInstallPermission = true
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (e: Exception) { toast("Allow installs from S Engine in Android Settings: ${e.message}") }
            return
        }
        pendingInstallFile = null
        waitingForInstallPermission = false
        try {
            val contentUri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", staged)
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).setData(contentUri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        } catch (e: Exception) {
            toast("Could not open Android's installer: ${e.message}")
        }
    }

    private fun showText(title: String, text: String) {
        val tv = label(text, 12f).apply { typeface = Typeface.MONOSPACE; setPadding(dp(18), dp(10), dp(18), dp(10)); setTextIsSelectable(true) }
        MaterialAlertDialogBuilder(this).setTitle(title).setView(ScrollView(this).apply { addView(tv) }).setPositiveButton("Close", null).show()
    }

    private fun scenesDialog() {
        if (engine.mode != Engine.Mode.EDIT) { toast("Stop play mode first"); return }
        saveScene(silent = true)
        val scenes = project.listScenes()
        val labels = scenes.map { s ->
            s + (if (s == engine.scene.name) "   (open)" else "") + (if (s == project.startScene) "   ★ start" else "")
        } + "+ New Scene"
        MaterialAlertDialogBuilder(this)
            .setTitle("Scenes")
            .setItems(labels.toTypedArray()) { _, i ->
                if (i == scenes.size) newSceneDialog() else sceneActions(scenes[i])
            }
            .show()
    }

    private fun sceneActions(name: String) {
        val actions = arrayOf("Open", "Set as Start Scene", "Duplicate", "Delete")
        MaterialAlertDialogBuilder(this)
            .setTitle(name)
            .setItems(actions) { _, i ->
                when (i) {
                    0 -> openScene(name)
                    1 -> { project.startScene = name; project.saveMeta(); toast("$name is now the start scene") }
                    2 -> {
                        var n = "$name Copy"; var k = 2
                        while (project.sceneExists(n)) n = "$name Copy ${k++}"
                        project.sceneFile(name).copyTo(project.sceneFile(n))
                        toast("Created $n")
                    }
                    3 -> {
                        if (name == engine.scene.name) toast("Can't delete the open scene")
                        else { project.deleteScene(name); toast("Deleted $name") }
                    }
                }
            }
            .show()
    }

    private fun newSceneDialog() {
        val f = field("Level2")
        MaterialAlertDialogBuilder(this)
            .setTitle("New Scene")
            .setView(LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(f, lp(MATCH, WRAP)) })
            .setPositiveButton("Create") { _, _ ->
                val n = ProjectManager.sanitize(f.text.toString())
                if (n.isBlank() || project.sceneExists(n)) { toast("Invalid or existing name"); return@setPositiveButton }
                val s = Scene(n)
                s.create("Main Camera").add(Camera2D())
                project.saveScene(s)
                openScene(n)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openScene(name: String) {
        val s = project.loadScene(name)
        synchronized(engine.lock) { engine.replaceScene(s) }
        history.clear(); history.dirty = false
        state.selectedId = -1
        refreshHierarchy(); inspector.rebuild(); updateTitle()
        appendConsole(0, "Opened scene $name")
    }

    // ================================================================== assets
    fun refreshAssets() {
        if (!::assetsRow.isInitialized) return
        assetsRow.removeAllViews()
        val assets = project.listAssets()
        if (assets.isEmpty()) assetsRow.addView(label("No assets yet. Create a script or import images / sounds.", 12f, C.DIM).apply { setPadding(dp(8), dp(20), 0, 0) })
        for (name in assets) assetsRow.addView(assetCard(name), lp(dp(88), MATCH).margins(dp(3), 0, dp(3), 0))
    }

    private fun assetCard(name: String): View {
        val kind = AssetKind.of(name)
        val card = vbox().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            background = round(C.PANEL2, dp(6).toFloat())
            setPadding(dp(4), dp(6), dp(4), dp(4))
        }
        if (kind == AssetKind.TEXTURE) {
            val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            try {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                iv.setImageBitmap(BitmapFactory.decodeFile(project.assetFile(name).absolutePath, opts))
            } catch (_: Throwable) {}
            card.addView(iv, lp(dp(56), dp(48)))
        } else {
            val (glyph, color) = when (kind) {
                AssetKind.SCRIPT -> "JS" to C.YELLOW
                AssetKind.SOUND -> "♪" to C.GREEN
                AssetKind.SHADER -> "GL" to 0xFFB69CFF.toInt()
                else -> "?" to C.DIM
            }
            card.addView(label(glyph, 20f, color, true).apply { gravity = Gravity.CENTER }, lp(dp(56), dp(48)))
        }
        card.addView(label(name, 10f, C.TEXT).apply { gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        card.setOnClickListener { assetMenu(name, kind, it) }
        return card
    }

    private fun assetMenu(name: String, kind: AssetKind?, anchor: View) {
        val pm = PopupMenu(this, anchor)
        val sel = synchronized(engine.lock) { engine.scene.findById(state.selectedId) }
        when (kind) {
            AssetKind.SCRIPT -> { pm.menu.add("Edit"); if (sel != null) pm.menu.add("Attach to ${sel.name}") }
            AssetKind.SHADER -> pm.menu.add("Edit Source")
            AssetKind.TEXTURE -> { pm.menu.add("Create Sprite"); if (sel != null) pm.menu.add("Assign to ${sel.name}") }
            AssetKind.SOUND -> { pm.menu.add("Preview"); if (sel != null) pm.menu.add("Add AudioSource to ${sel.name}") }
            null -> {}
        }
        pm.menu.add("Delete")
        pm.setOnMenuItemClickListener { item ->
            val t = item.title.toString()
            when {
                t == "Edit" || t == "Edit Source" -> openScript(name)
                t == "Preview" -> try {
                    android.media.MediaPlayer().apply {
                        setDataSource(project.assetFile(name).absolutePath); setOnCompletionListener { it.release() }; prepare(); start()
                    }
                } catch (e: Exception) { toast("Can't play: ${e.message}") }
                t == "Create Sprite" -> {
                    history.record(state.selectedId)
                    val go = synchronized(engine.lock) {
                        val g = engine.scene.create(name.substringBeforeLast('.'))
                        g.x = snap(state.view.cx); g.y = snap(state.view.cy)
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(project.assetFile(name).absolutePath, opts)
                        if (opts.outWidth > 0) g.scaleY = opts.outHeight.toFloat() / opts.outWidth
                        g.add(SpriteRenderer().also { it.texture = name })
                        g
                    }
                    refreshHierarchy(); select(go.id)
                }
                sel != null && t.startsWith("Attach") -> {
                    history.record(state.selectedId)
                    synchronized(engine.lock) { sel.add(ScriptComponent().also { it.script = name }) }
                    inspector.rebuild()
                }
                sel != null && t.startsWith("Assign") -> {
                    history.record(state.selectedId)
                    synchronized(engine.lock) {
                        (sel.getAny<SpriteRenderer>() ?: sel.add(SpriteRenderer())).texture = name
                    }
                    inspector.rebuild()
                }
                sel != null && t.startsWith("Add AudioSource") -> {
                    history.record(state.selectedId)
                    synchronized(engine.lock) { sel.add(AudioSource().also { it.clip = name }) }
                    inspector.rebuild()
                }
                t == "Delete" -> MaterialAlertDialogBuilder(this)
                    .setTitle("Delete $name?")
                    .setPositiveButton("Delete") { _, _ -> project.assetFile(name).delete(); refreshAssets() }
                    .setNegativeButton("Cancel", null).show()
            }
            true
        }
        pm.show()
    }

    private fun importAsset(uri: Uri) {
        try {
            var display = "asset"
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) display = it.getString(0)
            }
            var n = display.replace(Regex("[^A-Za-z0-9_.\\-]"), "_")
            if (AssetKind.of(n) == null) {
                val mime = contentResolver.getType(uri) ?: ""
                n += when {
                    mime.contains("png") -> ".png"
                    mime.contains("jpeg") || mime.contains("jpg") -> ".jpg"
                    mime.contains("webp") -> ".webp"
                    mime.contains("ogg") -> ".ogg"
                    mime.contains("mpeg") || mime.contains("mp3") -> ".mp3"
                    mime.contains("wav") -> ".wav"
                    importKind == AssetKind.SOUND -> ".ogg"
                    else -> ".png"
                }
            }
            n = project.uniqueAssetName(n)
            project.assetsDir.mkdirs()
            contentResolver.openInputStream(uri)!!.use { input -> project.writeAsset(n, input) }
            refreshAssets()
            showTab(1)
            toast("Imported $n")
        } catch (e: Exception) {
            toast("Import failed: ${e.message}")
        }
    }

    // ================================================================== console & input
    private fun appendConsole(level: Int, msg: String) {
        if (!::consoleText.isInitialized) return
        val color = when (level) { 2 -> "#FF7B72"; 1 -> "#E3B341"; else -> "#CFD2D6" }
        val line = android.text.Html.fromHtml("<font color='$color'>${android.text.TextUtils.htmlEncode(msg)}</font><br>", android.text.Html.FROM_HTML_MODE_LEGACY)
        consoleText.append(line)
        if (consoleText.text.length > 20000) consoleText.text = consoleText.text.subSequence(consoleText.text.length - 15000, consoleText.text.length)
        consoleScroll.post { consoleScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun duplicateSelection(): Boolean {
        if (engine.mode != Engine.Mode.EDIT) return false
        val original = synchronized(engine.lock) { engine.scene.findById(state.selectedId) } ?: return false
        history.record(state.selectedId)
        val copy = synchronized(engine.lock) { engine.scene.duplicate(original).also { it.x += 0.5f; it.y -= 0.5f } }
        refreshHierarchy(); select(copy.id)
        return true
    }

    private fun deleteSelection(): Boolean {
        if (engine.mode != Engine.Mode.EDIT) return false
        val selected = synchronized(engine.lock) { engine.scene.findById(state.selectedId) } ?: return false
        history.record(state.selectedId)
        synchronized(engine.lock) { engine.scene.remove(selected) }
        refreshHierarchy(); select(-1L)
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val repeat = event?.repeatCount ?: 0
        if (keyCode == KeyEvent.KEYCODE_SPACE && currentFocus !is android.widget.EditText && repeat == 0) {
            if (engine.mode == Engine.Mode.EDIT) startPlay() else engine.stop()
            return true
        }
        if (engine.mode != Engine.Mode.EDIT && keyCode != KeyEvent.KEYCODE_BACK) {
            engine.input.keys.add(keyCode); return true
        }
        if (event?.isCtrlPressed == true && repeat == 0) {
            when (keyCode) {
                KeyEvent.KEYCODE_S -> { saveScene(); return true }
                KeyEvent.KEYCODE_Z -> { if (event.isShiftPressed) redo() else undo(); return true }
                KeyEvent.KEYCODE_Y -> { redo(); return true }
                KeyEvent.KEYCODE_D -> { duplicateSelection(); return true }
            }
        }
        if (currentFocus !is android.widget.EditText && repeat == 0) {
            when (keyCode) {
                KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> if (deleteSelection()) return true
                KeyEvent.KEYCODE_W -> { setTool(Tool.MOVE); return true }
                KeyEvent.KEYCODE_E -> { setTool(Tool.ROTATE); return true }
                KeyEvent.KEYCODE_R -> { setTool(Tool.SCALE); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        engine.input.keys.remove(keyCode)
        return super.onKeyUp(keyCode, event)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
