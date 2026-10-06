package com.sengine.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.sengine.engine.core.Camera2D
import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.GameObject
import com.sengine.engine.lighting.Light2D
import com.sengine.engine.fx.ParticleEmitter
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.core.ScriptComponent
import com.sengine.engine.core.SpriteRenderer
import com.sengine.engine.core.TextRenderer
import com.sengine.engine.tilemap.TilemapRenderer
import com.sengine.engine.ui.UiCanvas

/**
 * Scene-tree list for the editor hierarchy panel: indentation, per-object glyph, selection,
 * visibility toggle and multi-selection. Rows are plain views (no support library).
 */
class HierarchyAdapter(
    private val context: Context,
    private val rows: () -> List<Row>,
    private val onSelect: (GameObject, Boolean) -> Unit,
    private val onToggleVisible: (GameObject) -> Unit,
    private val onLongPress: (GameObject) -> Unit
) : BaseAdapter() {

    class Row(val go: GameObject, val depth: Int)

    private var selection: Set<Long> = emptySet()

    fun setSelection(ids: Set<Long>) {
        selection = ids
        notifyDataSetChanged()
    }

    override fun getCount() = rows().size
    override fun getItem(position: Int): Any = rows()[position].go
    override fun getItemId(position: Int): Long = rows()[position].go.id
    override fun hasStableIds() = true

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val list = rows()
        val row = list.getOrNull(position) ?: return TextView(context)
        val go = row.go
        val box = (convertView as? android.widget.LinearLayout ?: context.hbox().apply {
            setPadding(context.dp(6), 0, context.dp(6), 0)
            minimumHeight = context.dp(34)
        })
        box.removeAllViews()
        box.setBackgroundColor(if (go.id in selection) C.SEL else 0x00000000)

        val indent = View(context).apply { layoutParams = lp(context.dp(12 * row.depth), 1) }
        box.addView(indent)

        val glyph = glyphFor(go)
        box.addView(context.label(glyph, 14f, if (go.isActiveInHierarchy()) C.ACCENT else C.DIM).apply {
            layoutParams = lp(context.dp(24), WRAP)
            gravity = Gravity.CENTER
        })

        val nameView = context.label(go.name, 13f, if (go.isActiveInHierarchy()) C.TEXT else C.DIM).apply {
            layoutParams = lp(0, WRAP, 1f)
            setOnClickListener {
                onSelect(go, false)
            }
            setOnLongClickListener {
                onSelect(go, false)
                onLongPress(go)
                true
            }
        }
        box.addView(nameView)

        val script = go.getAny<ScriptComponent>()
        val tags = buildString {
            if (go.tag.isNotBlank() && go.tag != "Untagged") append(go.tag)
            if (script != null) {
                if (isNotEmpty()) append(' ')
                append(if (script.script.isBlank()) "{script}" else "{${script.script}}")
            }
            if (go.groups.isNotEmpty()) {
                if (isNotEmpty()) append(' ')
                append(go.groups.joinToString(" ", "[", "]"))
            }
        }
        if (tags.isNotBlank()) {
            box.addView(context.label(tags, 10.5f, C.DIM).apply {
                setPadding(context.dp(4), 0, context.dp(6), 0)
                typeface = Typeface.DEFAULT
            })
        }

        val eye = context.label(if (go.active) "◉" else "○", 14f, if (go.active) C.GREEN else C.DIM).apply {
            layoutParams = lp(context.dp(30), WRAP)
            gravity = Gravity.CENTER
            contentDescription = if (go.active) "Hide object" else "Show object"
            isClickable = true
            setOnClickListener {
                onToggleVisible(go)
                notifyDataSetChanged()
            }
        }
        box.addView(eye)
        return box
    }

    private fun glyphFor(go: GameObject): String = when {
        go.getAny<Camera2D>() != null -> "▣"
        go.getAny<TilemapRenderer>() != null -> "▦"
        go.getAny<SpriteRenderer>() != null -> "▧"
        go.getAny<TextRenderer>() != null -> "T"
        go.getAny<ParticleEmitter>() != null -> "✳"
        go.getAny<Light2D>() != null -> "☀"
        go.getAny<UiCanvas>() != null -> "▤"
        go.getAny<Rigidbody2D>() != null || go.getAny<Collider2D>() != null -> "⬢"
        else -> "◦"
    }
}
