package com.sengine.ui

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Bundle
import android.view.Gravity
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sengine.project.AssetLibrary
import com.sengine.project.Project
import com.sengine.project.ProjectManager
import java.io.File
import java.util.concurrent.Executors

/** Built-in asset store: procedurally generated textures, sprite sheets, sounds, shaders, scripts, blueprints and 3D models. */
class AssetStoreActivity : AppCompatActivity() {
    private lateinit var project: Project
    private lateinit var grid: GridLayout
    private lateinit var chips: LinearLayout
    private lateinit var countLabel: TextView
    private var category = "All"
    private var player: MediaPlayer? = null
    private val previews = HashMap<String, Bitmap>()
    private val pool = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        project = ProjectManager.open(this, intent.getStringExtra("project")!!)
        category = intent.getStringExtra("category") ?: "All"

        val root = vbox().apply { setBackgroundColor(C.BG) }
        val bar = hbox().apply { setBackgroundColor(C.HEADER); setPadding(dp(6), dp(4), dp(6), dp(4)) }
        bar.addView(button("←") { finish() })
        bar.addView(label("🛒 Asset Store  •  ${project.name}", 15f, C.TEXT, true).apply { setPadding(dp(10), 0, dp(10), 0) }, lp(0, WRAP, 1f))
        countLabel = label("", 12f, C.DIM).apply { setPadding(0, 0, dp(10), 0) }
        bar.addView(countLabel)
        bar.addView(button("Add all in tab", C.ACCENT, 0xFFFFFFFF.toInt()) { addAll() })
        root.addView(bar, lp(MATCH, WRAP))

        chips = hbox().apply { setPadding(dp(6), dp(4), dp(6), dp(4)) }
        root.addView(HorizontalScrollView(this).apply { addView(chips); setBackgroundColor(C.PANEL); isHorizontalScrollBarEnabled = false }, lp(MATCH, WRAP))

        grid = GridLayout(this).apply { setPadding(dp(6), dp(6), dp(6), dp(6)) }
        root.addView(ScrollView(this).apply { addView(grid) }, lp(MATCH, 0, 1f))
        setContentView(root)
        grid.post { rebuild() }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release(); player = null
        pool.shutdownNow()
    }

    private fun items() = AssetLibrary.items.filter { category == "All" || it.category == category }

    private fun rebuild() {
        chips.removeAllViews()
        for (c in AssetLibrary.categories) {
            val n = if (c == "All") AssetLibrary.items.size else AssetLibrary.items.count { it.category == c }
            chips.addView(button("$c ($n)", if (c == category) C.ACCENT else C.PANEL2, if (c == category) 0xFFFFFFFF.toInt() else C.TEXT) {
                category = c; rebuild()
            }.apply { textSize = 12f }, lp(WRAP, WRAP).margins(dp(2), 0, dp(2), 0))
        }
        val list = items()
        countLabel.text = "${list.count { it.installed(project) }}/${list.size} in project"
        grid.removeAllViews()
        val cardW = dp(170)
        val cols = (grid.width / (cardW + dp(8))).coerceAtLeast(2)
        grid.columnCount = cols
        val w = (grid.width - dp(12)) / cols - dp(8)
        for (item in list) grid.addView(card(item), GridLayout.LayoutParams().apply { width = w; setMargins(dp(4), dp(4), dp(4), dp(4)) })
    }

    private fun card(item: AssetLibrary.Item): LinearLayout {
        val c = vbox().apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply { setColor(C.PANEL); cornerRadius = dp(8).toFloat() }
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply { setColor(0xFF2A2D32.toInt()); cornerRadius = dp(6).toFloat() }
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val glyph = label(item.glyph.ifBlank { "?" }, 26f, C.ACCENT, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(0xFF2A2D32.toInt()); cornerRadius = dp(6).toFloat() }
        }
        val gen = item.preview
        if (gen != null) {
            c.addView(img, lp(MATCH, dp(96)))
            val cached = previews[item.title]
            if (cached != null) setPixelated(img, cached)
            else pool.execute {
                val b = try { gen() } catch (_: Throwable) { null } ?: return@execute
                runOnUiThread { previews[item.title] = b; setPixelated(img, b) }
            }
        } else c.addView(glyph, lp(MATCH, dp(96)))
        c.addView(label(item.title, 13f, C.TEXT, true).apply { isSingleLine = true }, lp(MATCH, WRAP).margins(0, dp(6), 0, 0))
        c.addView(label(item.category, 10f, C.ACCENT))
        c.addView(label(item.description, 11f, C.DIM).apply { maxLines = 2; minLines = 2 })
        c.addView(label(item.files.joinToString(", "), 10f, C.DIM).apply { isSingleLine = true })
        val row = hbox()
        if (item.sound != null) row.addView(button("▶") { preview(item) }.apply { textSize = 12f }, lp(WRAP, WRAP).margins(0, dp(6), dp(4), 0))
        val installed = item.installed(project)
        row.addView(button(if (installed) "✓ Re-add" else "+ Add", if (installed) C.PANEL2 else C.GREEN, if (installed) C.TEXT else 0xFFFFFFFF.toInt()) {
            install(listOf(item))
        }.apply { textSize = 12f }, lp(0, WRAP, 1f).margins(0, dp(6), 0, 0))
        c.addView(row, lp(MATCH, WRAP))
        return c
    }

    private fun setPixelated(img: ImageView, b: Bitmap) {
        // Nearest-neighbour upscaling keeps pixel art crisp.
        val d = BitmapDrawable(resources, b).apply { paint.isFilterBitmap = b.width > 64 }
        img.setImageDrawable(d)
    }

    private fun preview(item: AssetLibrary.Item) {
        val bytes = item.sound?.invoke() ?: return
        val f = File(cacheDir, "store_preview.wav")
        f.writeBytes(bytes)
        player?.release()
        player = MediaPlayer().apply {
            setDataSource(f.absolutePath)
            setOnPreparedListener { it.start() }
            prepareAsync()
        }
    }

    private fun addAll() = install(items())

    private fun install(list: List<AssetLibrary.Item>) {
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Adding assets…").setMessage("Generating ${list.sumOf { it.files.size }} files").setCancelable(false).show()
        pool.execute {
            var files = 0
            val errors = ArrayList<String>()
            for (i in list) try { i.install(project); files += i.files.size } catch (e: Throwable) { errors += "${i.title}: ${e.message}" }
            runOnUiThread {
                dialog.dismiss()
                Toast.makeText(this, if (errors.isEmpty()) "Added $files files to ${project.name}" else "Added $files files, ${errors.size} failed", Toast.LENGTH_SHORT).show()
                rebuild()
            }
        }
    }
}
