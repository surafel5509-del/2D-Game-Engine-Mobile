package com.sengine.ui

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.ScrollView
import com.sengine.engine.Engine
import com.sengine.engine.core.Component
import com.sengine.engine.core.ComponentRegistry
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Prop
import com.sengine.engine.core.Scene
import com.sengine.engine.math.Colors
import com.sengine.engine.render.Tool

/**
 * Inspector for the selected object: transform, tags/groups/layer, every component's properties
 * (floats, ints, bools, strings, colors, choices, vectors, asset references, flags and runtime
 * info) plus add/remove component, duplicate, prefab and runtime telemetry.
 *
 * All edits go straight to the live component objects, so they are visible instantly on the GL
 * thread; callers get a [Host.onEdited] callback to push undo entries and mark the project dirty.
 */
class InspectorPanel(
    private val context: Context,
    private val engine: Engine,
    private val host: Host
) {

    interface Host {
        fun onEdited(commit: Boolean)
        fun onStructureChanged()
        fun openScript(name: String)
        fun openTilemapEditor(go: GameObject)
        fun openParticleEditor(go: GameObject)
        fun openAnimationEditor(clip: String)
        fun openPrefabEditor(go: GameObject)
        fun pickAsset(kind: com.sengine.engine.core.AssetKind, current: String, onPick: (String) -> Unit)
    }

    private val root = context.vbox()

    fun view(): View = ScrollView(context).apply {
        addView(root)
        setBackgroundColor(C.PANEL)
    }

    fun rebuild() {
        root.removeAllViews()
        val scene = engine.scene
        val state = engine.editor
        val selected = state?.selectedId?.let { scene.findById(it) }
        if (selected == null || selected.destroyed) {
            root.addView(context.label("No selection", 13f, C.DIM).apply {
                setPadding(context.dp(12), context.dp(16), context.dp(12), context.dp(8))
            })
            root.addView(context.label("Select an object in the hierarchy or tap one in the viewport.", 11.5f, C.DIM).apply {
                setPadding(context.dp(12), 0, context.dp(12), context.dp(12))
            })
            if (state != null) {
                root.addView(sceneSection(scene))
            }
            return
        }
        root.addView(objectHeader(selected, scene))
        root.addView(context.divider())
        root.addView(transformSection(selected))
        root.addView(context.divider())
        for (c in selected.components.toList()) {
            root.addView(componentSection(selected, c))
        }
        root.addView(context.divider())
        root.addView(context.hbox().apply {
            setPadding(context.dp(8), 0, context.dp(8), context.dp(12))
            addView(context.button("＋ Add component", C.ACCENT, 0xFFFFFFFF.toInt()) { addComponentDialog(selected) }, lp(MATCH, WRAP))
        })
    }

    // ---------------------------------------------------------------- header
    private fun objectHeader(go: GameObject, scene: Scene): View = context.vbox().apply {
        setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(4))
        addView(context.hbox().apply {
            val nameField = context.field(go.name).apply {
                layoutParams = lp(0, WRAP, 1f)
                setOnFocusChangeListener { _, has ->
                    if (!has) {
                        val n = text.toString().trim()
                        if (n.isNotEmpty()) { go.name = n; host.onStructureChanged() }
                    }
                }
            }
            addView(nameField)
            addView(context.button("⧉") { duplicate(go) }.apply { layoutParams = lp(context.dp(38), WRAP).margins(context.dp(6), 0, 0, 0) })
            addView(context.button("✕", C.RED, 0xFFFFFFFF.toInt()) {
                context.confirmDialog("Delete ${go.name}?", "The object and its children are removed from the scene.") {
                    scene.remove(go)
                    engine.editor?.clearSelection()
                    host.onStructureChanged()
                    host.onEdited(true)
                }
            }.apply { layoutParams = lp(context.dp(38), WRAP).margins(context.dp(6), 0, 0, 0) })
        })
        addView(context.hbox().apply {
            setPadding(0, context.dp(6), 0, 0)
            addView(context.label("Tag", 12f, C.DIM), lp(context.dp(48), WRAP))
            addView(context.field(go.tag).apply {
                layoutParams = lp(0, WRAP, 1f)
                setOnFocusChangeListener { _, has ->
                    if (!has) { go.tag = text.toString(); host.onEdited(true) }
                }
            })
        })
        addView(context.hbox().apply {
            setPadding(0, context.dp(6), 0, 0)
            val layers = scene.sortingLayers()
            addView(context.label("Layer", 12f, C.DIM), lp(context.dp(48), WRAP))
            addView(context.choice(layers, layers.indexOf(go.sortingLayer).coerceAtLeast(0)) {
                go.sortingLayer = layers[it]
                host.onEdited(true)
            }, lp(0, WRAP, 1f))
        })
        addView(context.hbox().apply {
            setPadding(0, context.dp(6), 0, 0)
            addView(context.label("Order", 12f, C.DIM), lp(context.dp(48), WRAP))
            addView(context.intField(go.order) { go.order = it; host.onEdited(true) }, lp(0, WRAP, 1f))
            addView(context.label(" Y-Sort", 12f, C.DIM), lp(WRAP, WRAP))
            addView(context.checkField(go.sortByY) { go.sortByY = it; host.onEdited(true) })
        })
        addView(context.hbox().apply {
            setPadding(0, context.dp(6), 0, 0)
            addView(context.checkField(go.active) {
                go.active = it
                host.onStructureChanged()
                host.onEdited(true)
            })
            addView(context.label("Active", 12f, C.DIM))
            addView(context.spacer())
            addView(context.label("id ${go.id} · ${go.depth()} deep", 10.5f, C.DIM))
        })
        if (go.groups.isNotEmpty()) {
            addView(context.label("groups: ${go.groups.joinToString(", ")}", 11f, C.DIM).apply { setPadding(0, context.dp(4), 0, 0) })
        }
    }

    private fun duplicate(go: GameObject) {
        val copy = engine.scene.duplicate(go)
        engine.editor?.select(copy.id)
        host.onStructureChanged()
        host.onEdited(true)
    }

    // ---------------------------------------------------------------- transform
    private fun transformSection(go: GameObject): View = context.vbox().apply {
        setPadding(context.dp(8), context.dp(6), context.dp(8), context.dp(6))
        addView(context.section("Transform"))
        addView(context.row("Position", context.hbox().apply {
            addView(context.numberField(go.x, 0.1f) { go.x = it; host.onEdited(false) }, lp(0, WRAP, 1f))
            addView(context.numberField(go.y, 0.1f) { go.y = it; host.onEdited(false) }, lp(0, WRAP, 1f).margins(context.dp(6), 0, 0, 0))
        }))
        addView(context.row("Rotation", context.numberField(go.rotation, 5f) { go.rotation = it; host.onEdited(false) }))
        addView(context.row("Scale", context.hbox().apply {
            addView(context.numberField(go.scaleX, 0.1f) { go.scaleX = it; host.onEdited(false) }, lp(0, WRAP, 1f))
            addView(context.numberField(go.scaleY, 0.1f) { go.scaleY = it; host.onEdited(false) }, lp(0, WRAP, 1f).margins(context.dp(6), 0, 0, 0))
        }))
        addView(context.hbox().apply {
            setPadding(0, context.dp(6), 0, 0)
            addView(context.button("Reset rotation") { go.rotation = 0f; host.onEdited(true); rebuild() })
            addView(context.button("Center on parent") {
                go.x = 0f; go.y = 0f
                host.onEdited(true)
            }.apply { layoutParams = lp(WRAP, WRAP).margins(context.dp(6), 0, 0, 0) })
            addView(context.button("Flip X") {
                go.scaleX = -go.scaleX
                host.onEdited(true)
            }.apply { layoutParams = lp(WRAP, WRAP).margins(context.dp(6), 0, 0, 0) })
        })
    }

    // ---------------------------------------------------------------- components
    private fun componentSection(go: GameObject, c: Component): View = context.vbox().apply {
        setPadding(context.dp(8), context.dp(6), context.dp(8), context.dp(6))
        addView(context.section(c.type, "✕", {
            context.confirmDialog("Remove ${c.type}?", "Component settings are lost.") {
                go.remove(c)
                host.onStructureChanged()
                host.onEdited(true)
                rebuild()
            }
        }))
        if (!c.enabled) {
            addView(context.label("(disabled - runtime skipped)", 11f, C.YELLOW))
        }
        for (p in c.props()) {
            addView(propRow(go, c, p))
        }
        // component-specific shortcuts: real tools, not decoration
        when {
            c is com.sengine.engine.core.ScriptComponent -> addView(context.button("Edit script") {
                if (c.script.isNotBlank()) host.openScript(c.script) else context.toast("Assign a script asset first")
            }.apply { layoutParams = lp(MATCH, WRAP).margins(0, context.dp(6), 0, 0) })
            c is com.sengine.engine.tilemap.TilemapRenderer -> addView(context.button("Edit tilemap") { host.openTilemapEditor(go) }
                .apply { layoutParams = lp(MATCH, WRAP).margins(0, context.dp(6), 0, 0) })
            c is com.sengine.engine.fx.ParticleEmitter -> addView(context.button("Open particle editor") { host.openParticleEditor(go) }
                .apply { layoutParams = lp(MATCH, WRAP).margins(0, context.dp(6), 0, 0) })
            c is com.sengine.engine.anim.Animator -> addView(context.button("Open animation editor") {
                host.openAnimationEditor(c.clip)
            }.apply { layoutParams = lp(MATCH, WRAP).margins(0, context.dp(6), 0, 0) })
        }
    }

    private fun propRow(go: GameObject, c: Component, p: Prop): View = when (p) {
        is Prop.F -> context.row(p.name, context.numberField(p.get(), p.step, p.min, p.max) {
            p.set(it); host.onEdited(false)
        }, p.tooltip)
        is Prop.I -> context.row(p.name, context.intField(p.get(), p.step, p.min, p.max) {
            p.set(it); host.onEdited(false)
        }, p.tooltip)
        is Prop.B -> context.row(p.name, context.hbox().apply {
            addView(context.checkField(p.get()) { p.set(it); host.onEdited(true) })
            addView(context.spacer(0.01f))
        }, p.tooltip)
        is Prop.S -> context.row(p.name, context.field(p.get(), multiline = p.multiline).apply {
            setOnFocusChangeListener { _, has -> if (!has) { p.set(text.toString()); host.onEdited(true) } }
        }, p.tooltip)
        is Prop.Color -> context.row(p.name, context.hbox().apply {
            addView(context.colorSwatch(p.get()) { p.set(it); host.onEdited(true) })
            addView(context.label(Colors.toHex(p.get()), 11.5f, C.DIM).apply { setPadding(context.dp(8), 0, 0, 0) })
            addView(context.spacer())
        }, p.tooltip)
        is Prop.Choice -> context.row(p.name, context.choice(p.options, p.get()) { p.set(it); host.onEdited(true); rebuild() }, p.tooltip)
        is Prop.Asset -> context.row(p.name, context.hbox().apply {
            val value = context.label(p.get().ifBlank { "(none)" }, 12f, if (p.get().isBlank()) C.DIM else C.TEXT).apply {
                layoutParams = lp(0, WRAP, 1f)
            }
            addView(value)
            addView(context.button("…") { host.pickAsset(p.kind, p.get()) { picked -> p.set(picked); host.onEdited(true); rebuild() } })
        }, p.tooltip)
        is Prop.V2 -> context.row(p.name, context.hbox().apply {
            addView(context.numberField(p.getX(), 0.05f) { p.setX(it); host.onEdited(false) }, lp(0, WRAP, 1f))
            addView(context.numberField(p.getY(), 0.05f) { p.setY(it); host.onEdited(false) }, lp(0, WRAP, 1f).margins(context.dp(6), 0, 0, 0))
        }, p.tooltip)
        is Prop.Flags -> context.row(p.name, context.vbox().apply {
            for (i in p.labels.indices) {
                addView(context.hbox().apply {
                    addView(context.checkField((p.get() and (1 shl i)) != 0) { on ->
                        p.set(if (on) p.get() or (1 shl i) else p.get() and (1 shl i).inv())
                        host.onEdited(true)
                    })
                    addView(context.label(p.labels[i], 12f, C.TEXT))
                })
            }
            addView(context.spacer(0.01f))
        }, p.tooltip)
        is Prop.Info -> context.row(p.name, context.label(p.get(), 12f, C.ACCENT), p.tooltip)
    }

    private fun addComponentDialog(go: GameObject) {
        val categories = ComponentRegistry.categories.keys.toList()
        AlertDialog.Builder(context)
            .setTitle("Add component")
            .setItems(categories.toTypedArray()) { _, which ->
                val types = ComponentRegistry.categories[categories[which]] ?: emptyList()
                if (types.isEmpty()) return@setItems
                AlertDialog.Builder(context)
                    .setTitle(categories[which])
                    .setItems(types.toTypedArray()) { _, index ->
                        val component = ComponentRegistry.create(types[index])
                        if (component == null) {
                            context.toast("Unknown component: ${types[index]}")
                            return@setItems
                        }
                        val missing = component.requires().filter { go.getByType(it) == null }
                        if (missing.isNotEmpty() && missing.size > 4) {
                            context.toast("Add the required components first: ${missing.joinToString(", ")}")
                            return@setItems
                        }
                        for (need in missing) ComponentRegistry.create(need)?.let { go.add(it) }
                        go.add(component)
                        host.onStructureChanged()
                        host.onEdited(true)
                        rebuild()
                    }
                    .show()
            }
            .show()
    }

    // ---------------------------------------------------------------- scene settings
    private fun sceneSection(scene: Scene): View = context.vbox().apply {
        setPadding(context.dp(8), context.dp(6), context.dp(8), context.dp(6))
        addView(context.section("Scene"))
        addView(context.row("Name", context.field(scene.name).apply {
            setOnFocusChangeListener { _, has -> if (!has) { scene.name = text.toString(); host.onEdited(true) } }
        }))
        addView(context.row("Gravity", context.hbox().apply {
            addView(context.numberField(scene.gravityX, 0.5f) { scene.gravityX = it; host.onEdited(true) }, lp(0, WRAP, 1f))
            addView(context.numberField(scene.gravityY, 0.5f) { scene.gravityY = it; host.onEdited(true) }, lp(0, WRAP, 1f).margins(context.dp(6), 0, 0, 0))
        }))
        addView(context.row("Ambient", context.hbox().apply {
            addView(context.colorSwatch(scene.ambient) { scene.ambient = it; host.onEdited(true) })
            addView(context.spacer())
        }))
        addView(context.row("Objects", context.label(scene.objects.count { !it.destroyed }.toString(), 12f, C.ACCENT)))
        addView(context.row("Sorting layers", context.label(scene.sortingLayers().joinToString(", "), 11f, C.DIM)))
        val tool = engine.editor?.tool
        addView(context.row("Active tool", context.label(tool?.label ?: "-", 12f, C.ACCENT)))
        if (engine.mode == Engine.Mode.PLAY || engine.mode == Engine.Mode.PAUSED) {
            addView(context.divider())
            addView(context.section("Runtime"))
            addView(context.row("FPS", context.label(engine.fps.toInt().toString(), 12f, C.ACCENT)))
            addView(context.row("Rigid bodies", context.label(engine.rigidBodyCount.toString(), 12f, C.ACCENT)))
            addView(context.row("Particles", context.label(engine.activeParticles.toString(), 12f, C.ACCENT)))
            addView(context.row("Tasks", context.label(engine.activeTasks.toString(), 12f, C.ACCENT)))
            addView(context.row("Time", context.label(fmt(engine.time.toFloat()) + "s", 12f, C.ACCENT)))
        }
    }
}

/** Small helper so panels can list sorting layers without touching Scene internals. */
fun Scene.sortingLayers(): List<String> {
    val out = ArrayList<String>()
    for (layer in sortingLayers) if (layer.isNotBlank() && layer !in out) out.add(layer)
    for (go in objects) if (!go.sortingLayer.isBlank() && go.sortingLayer !in out) out.add(go.sortingLayer)
    return out
}

/** Renders the tool palette used by the viewport toolbar. */
fun toolIcon(tool: Tool): String = when (tool) {
    Tool.SELECT -> "⬚"
    Tool.MOVE -> "✥"
    Tool.ROTATE -> "⟳"
    Tool.SCALE -> "⤢"
    Tool.HAND -> "✋"
    Tool.RECT -> "▭"
    Tool.CIRCLE -> "◯"
    Tool.TILE_PAINT -> "▦"
    Tool.TILE_ERASE -> "⌫"
    Tool.TILE_FILL -> "🪣"
    Tool.TILE_RECT -> "▩"
    Tool.POLYGON -> "⬠"
    Tool.SPAWN -> "★"
    Tool.ZOOM -> "🔍"
}
