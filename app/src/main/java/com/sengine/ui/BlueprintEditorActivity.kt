package com.sengine.ui

import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sengine.engine.blueprint.Blueprint
import com.sengine.engine.blueprint.BlueprintCompiler
import com.sengine.engine.blueprint.BlueprintNodes
import com.sengine.engine.blueprint.BpNode
import com.sengine.project.Project
import com.sengine.project.ProjectManager

/** Visual scripting editor: blueprints compile to JavaScript behaviours at play time. */
class BlueprintEditorActivity : AppCompatActivity() {
    private lateinit var project: Project
    private lateinit var asset: String
    private lateinit var bp: Blueprint
    private lateinit var view: BlueprintView
    private lateinit var palette: View
    private lateinit var title: TextView
    private var saved = ""
    private var dirty = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        project = ProjectManager.open(this, intent.getStringExtra("project")!!)
        asset = intent.getStringExtra("asset")!!
        saved = project.readAsset(asset) ?: ""
        bp = try { Blueprint.parse(saved) } catch (e: Exception) { Blueprint.defaultGraph() }

        val root = vbox().apply { setBackgroundColor(C.BG) }
        val bar = hbox().apply { setBackgroundColor(C.HEADER); setPadding(dp(6), dp(4), dp(6), dp(4)) }
        bar.addView(button("←") { onBackPressedDispatcher.onBackPressed() })
        bar.addView(button("☰ Nodes") { palette.visibility = if (palette.visibility == View.VISIBLE) View.GONE else View.VISIBLE }, lp(WRAP, WRAP).margins(dp(4), 0, 0, 0))
        title = label("Blueprint: $asset", 15f, C.TEXT, true).apply { setPadding(dp(10), 0, dp(10), 0); isSingleLine = true }
        bar.addView(title, lp(0, WRAP, 1f))
        bar.addView(button("⌖") { frameAll() }, lp(WRAP, WRAP).margins(dp(3), 0, dp(3), 0))
        bar.addView(button("{ } JS") { showCode() }, lp(WRAP, WRAP).margins(dp(3), 0, dp(3), 0))
        bar.addView(button("?") { help() }, lp(WRAP, WRAP).margins(dp(3), 0, dp(3), 0))
        bar.addView(button("Save", C.ACCENT, 0xFFFFFFFF.toInt()) { save() }, lp(WRAP, WRAP).margins(dp(3), 0, 0, 0))
        root.addView(bar, lp(MATCH, WRAP))

        val middle = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val pal = vbox().apply { setPadding(dp(6), dp(6), dp(6), dp(6)); setBackgroundColor(C.PANEL) }
        for (cat in BlueprintNodes.categories) {
            pal.addView(label(cat.uppercase(), 11f, C.DIM, true), lp(MATCH, WRAP).margins(dp(4), dp(8), 0, dp(3)))
            for (def in BlueprintNodes.all.filter { it.category == cat }) {
                pal.addView(button(def.title) { addNode(def.type) }.apply { textSize = 12f; setPadding(dp(8), dp(6), dp(8), dp(6)) },
                    lp(MATCH, WRAP).margins(0, dp(2), 0, dp(2)))
            }
        }
        palette = ScrollView(this).apply { addView(pal) }
        middle.addView(palette, lp(dp(190), MATCH))
        view = BlueprintView(this, bp)
        view.onChanged = { markDirty() }
        view.onEditParam = { n, p -> editParam(n, p) }
        view.onNodeMenu = { n -> nodeMenu(n) }
        val frame = FrameLayout(this)
        frame.addView(view)
        middle.addView(frame, lp(0, MATCH, 1f))
        root.addView(middle, lp(MATCH, 0, 1f))
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!dirty) { finish(); return }
                MaterialAlertDialogBuilder(this@BlueprintEditorActivity)
                    .setTitle("Unsaved changes").setMessage("Save $asset?")
                    .setPositiveButton("Save") { _, _ -> save(); finish() }
                    .setNegativeButton("Discard") { _, _ -> finish() }
                    .setNeutralButton("Cancel", null).show()
            }
        })
    }

    private fun markDirty() { dirty = true; title.text = "Blueprint: $asset •" }

    private fun addNode(type: String) {
        val (cx, cy) = view.centerWorld()
        val d = resources.displayMetrics.density
        val n = bp.add(type, Math.round(cx / d / 10f) * 10f + (bp.nodes.size % 5) * 10f, Math.round(cy / d / 10f) * 10f + (bp.nodes.size % 5) * 10f)
        // auto-connect from the selected node's first free output
        view.selected?.let { s ->
            val free = s.def?.outs?.firstOrNull { pin -> bp.links.none { it.from == s.id && it.pin == pin } }
            if (free != null && n.def?.hasIn == true) {
                bp.connect(s.id, free, n.id)
                n.x = s.x + 250f; n.y = s.y
            }
        }
        view.selected = n
        markDirty(); view.invalidate()
    }

    private fun editParam(n: BpNode, name: String) {
        val pd = n.def?.params?.firstOrNull { it.name == name } ?: return
        val f = field(n.param(name), multiline = !pd.str).apply { typeface = Typeface.MONOSPACE }
        val box = vbox().apply { setPadding(dp(20), dp(6), dp(20), 0) }
        box.addView(label(if (pd.str) "Text value" else "JavaScript expression — e.g. 5, self.x + 1, vars.score, input.axisX, other.name", 11f, C.DIM))
        box.addView(f, lp(MATCH, WRAP))
        MaterialAlertDialogBuilder(this)
            .setTitle("${n.def?.title} • $name")
            .setView(box)
            .setPositiveButton("OK") { _, _ -> n.params[name] = f.text.toString(); markDirty(); view.invalidate() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun nodeMenu(n: BpNode) {
        val items = arrayOf("Duplicate", "Disconnect all", "Delete")
        MaterialAlertDialogBuilder(this)
            .setTitle(n.def?.title ?: n.type)
            .setItems(items) { _, i ->
                when (i) {
                    0 -> { val c = bp.add(n.type, n.x + 30f, n.y + 30f); c.params.putAll(n.params); view.selected = c }
                    1 -> bp.links.removeAll { it.from == n.id || it.to == n.id }
                    2 -> { bp.remove(n); view.selected = null }
                }
                markDirty(); view.invalidate()
            }.show()
    }

    private fun frameAll() {
        if (bp.nodes.isEmpty()) return
        val d = resources.displayMetrics.density
        val minX = bp.nodes.minOf { it.x } * d; val minY = bp.nodes.minOf { it.y } * d
        val maxX = bp.nodes.maxOf { it.x } * d + 210 * d; val maxY = bp.nodes.maxOf { it.y + 160 } * d
        val s = minOf(view.width / (maxX - minX + 40 * d), view.height / (maxY - minY + 40 * d)).coerceIn(0.35f, 1.5f)
        view.scale = s
        view.offX = -minX * s + 20 * d; view.offY = -minY * s + 20 * d
        view.invalidate()
    }

    private fun showCode() {
        val code = try { BlueprintCompiler.compile(bp) } catch (e: Exception) { "// error: ${e.message}" }
        val tv = label(code, 12f, 0xFFD4D4D4.toInt()).apply { typeface = Typeface.MONOSPACE; setPadding(dp(16), dp(10), dp(16), dp(10)); setTextIsSelectable(true) }
        MaterialAlertDialogBuilder(this).setTitle("Generated JavaScript").setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Export as .js") { _, _ ->
                val n = project.uniqueAssetName(asset.removeSuffix(".bp") + "_generated.js")
                project.writeAsset(n, code); Toast.makeText(this, "Saved $n", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun help() {
        val text = """
Blueprints are visual scripts. Attach a .bp asset to a Script component just like a .js file.

• Tap a node in the palette to add it (it auto-connects to the selected node).
• Drag from an output ● to another node to connect execution flow.
• Tap a connected ● to disconnect it.
• Tap a parameter to edit it. Parameters are JavaScript expressions:
    self.x, input.axisX, vars.score, other.tag == "Enemy", random(1,5)
• Long-press a node to duplicate or delete it.
• Pinch to zoom, drag the background to pan.
• "{ } JS" shows the generated code.

Variables: use Set Variable / Add To Variable, read them as vars.name.
Events: On Collision / On Trigger provide 'other'; On Message provides 'arg'.
""".trimIndent()
        MaterialAlertDialogBuilder(this).setTitle("Blueprint help").setMessage(text).setPositiveButton("OK", null).show()
    }

    private fun save() {
        val json = bp.toJson().toString(2)
        project.writeAsset(asset, json)
        saved = json; dirty = false
        title.text = "Blueprint: $asset"
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
    }
}
