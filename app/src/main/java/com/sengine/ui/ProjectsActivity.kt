package com.sengine.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.sengine.export.GameRuntime
import com.sengine.project.Project
import com.sengine.project.ProjectManager
import com.sengine.project.Templates

/**
 * Project hub: create from a template, open the editor, play, duplicate, rename, delete, export a
 * project zip and import one. Includes a live thumbnail drawn from the project's first scene.
 */
class ProjectsActivity : Activity() {

    private lateinit var list: ListView
    private var projects: List<Project> = emptyList()
    private val adapter = object : BaseAdapter() {
        override fun getCount() = projects.size
        override fun getItem(position: Int): Any = projects[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
            row(projects[position], convertView)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // An exported game carries its project inside the APK (assets/game): boot straight into the
        // game instead of the project manager.
        if (GameRuntime.isStandalone(this)) {
            startActivity(Intent(this, PlayerActivity::class.java)
                .putExtra("embedded", true)
                .putExtra("standalone", true))
            finish()
            return
        }
        val root = vbox().apply { setBackgroundColor(C.BG) }

        val header = hbox().apply {
            setBackgroundColor(C.HEADER)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        header.addView(label("S ENGINE", 18f, C.TEXT, bold = true))
        header.addView(label("  2D game engine", 12f, C.DIM))
        header.addView(spacer())
        header.addView(button("＋ New project", C.ACCENT, 0xFFFFFFFF.toInt()) { newProjectDialog() })
        header.addView(button("⇩ Import .zip") { importZip() }.apply {
            layoutParams = lp(WRAP, WRAP).margins(dp(8), 0, 0, 0)
        })
        root.addView(header, lp(MATCH, WRAP))

        list = ListView(this).apply {
            divider = null
            dividerHeight = 0
            adapter = this@ProjectsActivity.adapter
            setBackgroundColor(C.BG)
        }
        root.addView(list, lp(MATCH, 0, 1f))

        val footer = hbox().apply {
            setBackgroundColor(C.HEADER)
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        footer.addView(label("Tap a project to open it · long-press for more actions", 11f, C.DIM))
        root.addView(footer, lp(MATCH, WRAP))
        setContentView(root)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        projects = ProjectManager.list(this)
        adapter.notifyDataSetChanged()
        if (projects.isEmpty()) {
            toast("No projects yet - tap New project to start from a template")
        }
    }

    private fun row(p: Project, convertView: View?): View {
        val box = (convertView as? LinearLayout) ?: hbox().apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minimumHeight = dp(76)
        }
        box.removeAllViews()
        box.setBackgroundColor(C.BG)

        val thumb = (box.getChildAt(0) as? ImageView) ?: ImageView(this).apply {
            layoutParams = lp(dp(84), dp(56))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(C.PANEL)
        }
        (thumb.background as? android.graphics.drawable.GradientDrawable)?.cornerRadius = dp(6).toFloat()
        thumb.setImageBitmap(thumbnail(p))
        if (thumb.parent == null) box.addView(thumb)

        val info = vbox().apply {
            layoutParams = lp(0, WRAP, 1f).margins(dp(12), 0, dp(8), 0)
        }
        val sceneCount = p.listScenes().size
        val assetCount = (p.assetsDir.listFiles()?.size ?: 0)
        info.addView(label(p.name, 15f, C.TEXT, bold = true))
        info.addView(label("$sceneCount scenes · $assetCount assets · orientation ${if (p.orientation == 1) "portrait" else "landscape"}", 11.5f, C.DIM))
        info.addView(label("start: ${p.startScene}", 11f, C.DIM))
        box.addView(info)

        box.addView(button("▶ Play", C.GREEN, 0xFFFFFFFF.toInt()) { play(p) })
        box.addView(button("Open") { open(p) }.apply { layoutParams = lp(WRAP, WRAP).margins(dp(6), 0, 0, 0) })
        box.addView(button("⋯", C.PANEL2) { menu(p) }.apply { layoutParams = lp(dp(40), WRAP).margins(dp(6), 0, 0, 0) })

        box.setOnClickListener { open(p) }
        box.setOnLongClickListener { menu(p); true }
        return box
    }

    /** Cheap thumbnail: draws the project's first scene layout (objects as coloured blocks). */
    private fun thumbnail(p: Project): Bitmap {
        val bmp = Bitmap.createBitmap(dp(84).coerceAtLeast(48), dp(56).coerceAtLeast(32), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xFF1B2027.toInt()
        canvas.drawRect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), paint)
        runCatching {
            val scene = p.loadScene(p.startScene)
            val bounds = scene.contentBounds()
            val w = bounds.w.coerceAtLeast(1f)
            val h = bounds.h.coerceAtLeast(1f)
            val scale = minOf(bmp.width / w, bmp.height / h) * 0.8f
            for (go in scene.objects.take(60)) {
                val sprite = go.components.firstOrNull { it is com.sengine.engine.core.SpriteRenderer }
                    as? com.sengine.engine.core.SpriteRenderer ?: continue
                val x = (go.x - bounds.centerX) * scale + bmp.width * 0.5f
                val y = bmp.height * 0.5f - (go.y - bounds.centerY) * scale
                paint.color = sprite.color
                val sw = (sprite.width * scale).coerceAtLeast(2f)
                val sh = (sprite.height * scale).coerceAtLeast(2f)
                if (sprite.shape == com.sengine.engine.core.SpriteRenderer.SHAPE_CIRCLE) {
                    canvas.drawCircle(x, y, sw * 0.5f, paint)
                } else {
                    canvas.drawRect(x - sw * 0.5f, y - sh * 0.5f, x + sw * 0.5f, y + sh * 0.5f, paint)
                }
            }
        }
        return bmp
    }

    private fun open(p: Project) {
        startActivity(Intent(this, EditorActivity::class.java).putExtra("project", p.name))
    }

    private fun play(p: Project) {
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("project", p.name))
    }

    private fun menu(p: Project) {
        val actions = arrayOf("Open in editor", "Play", "Rename", "Duplicate", "Export .zip", "Delete")
        AlertDialog.Builder(this)
            .setTitle(p.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> open(p)
                    1 -> play(p)
                    2 -> inputDialog("Rename project", p.name) { name ->
                        val clean = ProjectManager.sanitize(name)
                        if (clean.isBlank()) return@inputDialog toast("Invalid name")
                        val renamed = ProjectManager.rename(this, p, clean)
                        if (renamed == null) toast("A project with that name already exists") else refresh()
                    }
                    3 -> { ProjectManager.duplicate(this, p); refresh(); toast("Duplicated") }
                    4 -> exportZip(p)
                    5 -> confirmDialog("Delete ${p.name}?", "This permanently removes the project and its assets.") {
                        ProjectManager.delete(p)
                        refresh()
                        toast("Deleted")
                    }
                }
            }
            .show()
    }

    private fun newProjectDialog() {
        val names = Templates.all.map { it.name }
        val box = vbox().apply { setPadding(dp(14), dp(6), dp(14), 0) }
        box.addView(label("Project name", 12f, C.DIM))
        val nameField = field("My Game")
        box.addView(nameField)
        box.addView(label("Template", 12f, C.DIM).apply { setPadding(0, dp(10), 0, 0) })
        var templateIndex = 0
        box.addView(choice(names, 0) { templateIndex = it })
        box.addView(label(Templates.all.firstOrNull()?.description ?: "", 11f, C.DIM).apply {
            setPadding(0, dp(8), 0, 0)
        })
        val templateInfo = box.getChildAt(box.childCount - 1) as TextView
        (box.getChildAt(3) as android.widget.Spinner).onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    templateInfo.text = Templates.all[position].description
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        AlertDialog.Builder(this)
            .setTitle("New project")
            .setView(box)
            .setPositiveButton("Create") { _, _ ->
                val raw = nameField.text.toString()
                val name = ProjectManager.sanitize(raw)
                if (name.isBlank()) { toast("Enter a project name"); return@setPositiveButton }
                if (ProjectManager.exists(this, name)) { toast("Project already exists"); return@setPositiveButton }
                val project = ProjectManager.create(this, name, Templates.all[templateIndex])
                refresh()
                open(project)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importZip() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_IMPORT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            val project = contentResolver.openInputStream(uri)?.use { input ->
                ProjectManager.importZip(this, input, "Imported Game")
            }
            if (project == null) toast("Import failed") else { refresh(); toast("Imported ${project.name}") }
        } catch (e: Exception) {
            toast("Import failed: ${e.message}")
        }
    }

    private fun exportZip(p: Project) {
        try {
            val out = java.io.File(cacheDir, "${p.name}.zip")
            out.outputStream().use { ProjectManager.exportZip(p, it) }
            val uri = ShareProvider.uriFor(this, out)
            if (uri == null) { toast("Export failed"); return }
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "Export project"))
        } catch (e: Exception) {
            toast("Export failed: ${e.message}")
        }
    }

    companion object {
        private const val REQ_IMPORT = 41
    }
}
