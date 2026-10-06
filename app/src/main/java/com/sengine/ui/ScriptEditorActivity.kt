package com.sengine.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.sengine.engine.script.ScriptSystem
import com.sengine.project.Project
import com.sengine.project.ProjectManager
import com.sengine.engine.script.ScriptReference

/**
 * JavaScript behaviour editor with syntax validation (compiled with the same Rhino engine the game
 * runs), a live API reference, error line reporting, snippets and instant save.
 *
 * The engine is strictly 2D: the API reference lists only 2D features.
 */
class ScriptEditorActivity : Activity() {

    private lateinit var project: Project
    private lateinit var editor: EditText
    private lateinit var status: TextView
    private lateinit var reference: LinearLayout
    private var assetName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        assetName = intent.getStringExtra("asset") ?: "NewScript.js"
        project = intent.getStringExtra("projectDir")?.let { Project(java.io.File(it)) }
            ?: ProjectManager.open(this, intent.getStringExtra("project") ?: "")

        val root = vbox().apply { setBackgroundColor(C.BG) }

        val header = hbox().apply {
            setBackgroundColor(C.HEADER)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        header.addView(button("‹ Back") { finish() })
        header.addView(label(assetName, 14f, C.TEXT, bold = true).apply {
            setPadding(dp(10), 0, dp(10), 0)
        })
        header.addView(spacer())
        header.addView(button("Snippets") { snippets() })
        header.addView(button("API reference") { toggleReference() }.apply { layoutParams = lp(WRAP, WRAP).margins(dp(6), 0, 0, 0) })
        header.addView(button("Validate") { validate(showToast = true) }.apply { layoutParams = lp(WRAP, WRAP).margins(dp(6), 0, 0, 0) })
        header.addView(button("Save", C.ACCENT, 0xFFFFFFFF.toInt()) { save() }.apply { layoutParams = lp(WRAP, WRAP).margins(dp(6), 0, 0, 0) })
        root.addView(header, lp(MATCH, WRAP))

        val body = hbox().apply { setPadding(dp(8), dp(8), dp(8), dp(8)) }
        val codeColumn = vbox().apply { layoutParams = lp(0, MATCH, 1f) }

        editor = EditText(this).apply {
            setText(project.readAsset(assetName) ?: com.sengine.project.ScriptTemplates.basic())
            setTextColor(0xFFD7E1F0.toInt())
            setBackgroundColor(C.FIELD)
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setHorizontallyScrolling(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    dirty = true
                    validate(showToast = false)
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        codeColumn.addView(ScrollView(this).apply {
            addView(editor)
        }, lp(MATCH, 0, 1f))
        status = label("", 11.5f, C.DIM).apply { setPadding(dp(6), dp(6), dp(6), 0) }
        codeColumn.addView(status, lp(MATCH, WRAP))
        body.addView(codeColumn)

        reference = vbox().apply {
            background = round(C.PANEL, dp(6).toFloat())
            setPadding(dp(10), dp(10), dp(10), dp(10))
            visibility = View.GONE
        }
        body.addView(ScrollView(this).apply {
            addView(reference)
        }, lp(dp(300), MATCH).margins(dp(8), 0, 0, 0))
        root.addView(body, lp(MATCH, 0, 1f))
        setContentView(root)

        buildReference()
        validate(showToast = false)
    }

    private var dirty = false

    private fun save() {
        project.writeAsset(assetName, editor.text.toString())
        dirty = false
        toast("Saved $assetName")
        validate(showToast = true)
    }

    /** Compiles the script with Rhino - exactly the runtime parser - and reports real errors. */
    private fun validate(showToast: Boolean): Boolean {
        val src = editor.text.toString()
        val result = com.sengine.engine.script.ScriptValidator.validate(src, assetName)
        if (result.ok) {
            status.text = "✔ no syntax errors · ${src.lines().size} lines · ${src.length} chars"
            status.setTextColor(C.GREEN)
            return true
        }
        status.text = "✘ line ${result.line}: ${result.message}"
        status.setTextColor(C.RED)
        if (showToast) toast("Line ${result.line}: ${result.message}", long = true)
        return false
    }

    private fun toggleReference() {
        reference.visibility = if (reference.visibility == View.GONE) View.VISIBLE else View.GONE
    }

    private fun buildReference() {
        reference.addView(label("S Engine 2D scripting API", 14f, C.TEXT, bold = true))
        reference.addView(label("Scripts are plain JavaScript. Attach one to an object with the Script component.", 11f, C.DIM).apply {
            setPadding(0, dp(4), 0, dp(10))
        })
        for ((group, lines) in ScriptReference.GROUPS) {
            reference.addView(label(group, 12.5f, C.ACCENT, bold = true).apply { setPadding(0, dp(10), 0, dp(4)) })
            for (line in lines) {
                reference.addView(label(line, 11.5f, C.TEXT).apply { setPadding(0, dp(2), 0, 0) })
            }
        }
        reference.addView(label("Lifecycle: start() · update(dt) · onDestroy() · onTap() · onCollision(other) · onTrigger(other)", 11.5f, C.GREEN).apply {
            setPadding(0, dp(12), 0, 0)
        })
    }

    private fun snippets() {
        val names = com.sengine.project.ScriptTemplates.names
        AlertDialog.Builder(this)
            .setTitle("Insert snippet")
            .setItems(names.toTypedArray()) { _, which ->
                                val code = com.sengine.project.ScriptTemplates.get(names[which])
                val start = editor.selectionStart.coerceAtLeast(0)
                editor.text.insert(start, code)
                toast("Inserted ${names[which]}")
            }
            .show()
    }

    override fun onBackPressed() {
        if (dirty) {
            AlertDialog.Builder(this)
                .setTitle("Save changes?")
                .setPositiveButton("Save") { _, _ -> save(); finish() }
                .setNegativeButton("Discard") { _, _ -> finish() }
                .setNeutralButton("Cancel", null)
                .show()
        } else {
            super.onBackPressed()
        }
    }

    /** Compiles a snippet for the validation status badge. */
    @Suppress("unused")
    private fun preludeCheck(): Boolean = ScriptSystem.PRELUDE.isNotBlank()
}
