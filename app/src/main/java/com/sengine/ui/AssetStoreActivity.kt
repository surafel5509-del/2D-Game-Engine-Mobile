package com.sengine.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.sengine.project.AssetLibrary
import com.sengine.project.Project
import com.sengine.project.ProjectManager

/**
 * Content browser: sound packs, sprite packs, animated sprite sheets, shaders, scripts, blueprint
 * packs and full 2D game templates. Everything installs real files into the current project.
 */
class AssetStoreActivity : Activity() {

    private lateinit var project: Project
    private lateinit var list: ListView
    private var items: List<AssetLibrary.Item> = emptyList()
    private var filter = "All"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        project = intent.getStringExtra("projectDir")?.let { Project(java.io.File(it)) }
            ?: ProjectManager.open(this, intent.getStringExtra("project") ?: "")
        val root = vbox().apply { setBackgroundColor(C.BG) }
        val header = hbox().apply {
            setBackgroundColor(C.HEADER)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        header.addView(button("‹ Back") { finish() })
        header.addView(label("Asset library", 14f, C.TEXT, bold = true).apply { setPadding(dp(10), 0, dp(10), 0) })
        header.addView(spacer())
        header.addView(button("Filter: All") { filterDialog() })
        root.addView(header, lp(MATCH, WRAP))

        list = ListView(this).apply {
            divider = null
            dividerHeight = 0
            adapter = adapter
            setBackgroundColor(C.BG)
        }
        root.addView(list, lp(MATCH, 0, 1f))
        setContentView(root)
        reload("All")
    }

    private fun filterDialog() {
        AlertDialog.Builder(this)
            .setTitle("Filter")
            .setItems(AssetLibrary.categories.toTypedArray()) { _, which -> reload(AssetLibrary.categories[which]) }
            .show()
    }

    private fun reload(category: String) {
        filter = category
        items = AssetLibrary.items.filter { category == "All" || it.category == category }
        adapter.notifyDataSetChanged()
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = items[position]
            val box = (convertView as? LinearLayout) ?: hbox().apply {
                setPadding(dp(12), dp(10), dp(12), dp(10))
                minimumHeight = dp(72)
                setBackgroundColor(C.BG)
            }
            box.removeAllViews()

            val thumb = ImageView(this@AssetStoreActivity).apply {
                layoutParams = lp(dp(60), dp(60))
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(C.PANEL)
                setImageBitmap(item.preview?.invoke() ?: glyphBitmap(item))
            }
            box.addView(thumb)

            val info = vbox().apply { layoutParams = lp(0, WRAP, 1f).margins(dp(12), 0, dp(8), 0) }
            info.addView(label(item.title, 14f, C.TEXT, bold = true))
            info.addView(label(item.category + " · " + item.files.size + " files", 11f, C.DIM))
            info.addView(label(item.description, 11f, C.DIM))
            box.addView(info)
            box.addView(button("Install") { install(item) })
            box.setOnClickListener { install(item) }
            return box
        }
    }

    private fun glyphBitmap(item: AssetLibrary.Item): Bitmap {
        val size = dp(60).coerceAtLeast(32)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = C.PANEL2
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = size * 0.55f
            isFakeBoldText = true
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        paint.color = C.ACCENT
        val glyph = item.glyph.ifBlank { item.title.firstOrNull()?.toString() ?: "?" }
        val fm = paint.fontMetrics
        canvas.drawText(glyph, size * 0.5f, size * 0.5f - (fm.ascent + fm.descent) * 0.5f, paint)
        return bmp
    }

    private fun install(item: AssetLibrary.Item) {
        try {
            item.install(project)
            toast("${item.title} installed into ${project.name}")
        } catch (e: Exception) {
            toast("Install failed: ${e.message}", long = true)
        }
    }
}
