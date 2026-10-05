package com.sengine.ui

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sengine.engine.core.AssetKind
import com.sengine.project.BuiltinAsset
import com.sengine.project.BuiltinAssetLibrary
import com.sengine.project.Project
import com.sengine.project.ProjectManager
import java.io.File

/** Browses and copies the offline 100-item S Engine starter library into a project. */
class AssetLibraryActivity : AppCompatActivity() {
    private lateinit var project: Project
    private lateinit var entries: List<BuiltinAsset>
    private lateinit var rows: RecyclerView
    private lateinit var categoryBar: LinearLayout
    private lateinit var search: EditText
    private lateinit var summary: TextView
    private lateinit var adapter: LibraryAdapter
    private var selectedCategory = "All"
    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val projectName = intent.getStringExtra("project") ?: run { finish(); return }
        project = ProjectManager.open(this, projectName)
        entries = try { BuiltinAssetLibrary.list(this) } catch (e: Exception) {
            Toast.makeText(this, "Asset library could not be loaded: ${e.message}", Toast.LENGTH_LONG).show()
            finish(); return
        }

        val root = vbox().apply { setBackgroundColor(C.BG) }
        val header = hbox().apply { setPadding(dp(14), dp(10), dp(12), dp(10)); setBackgroundColor(C.HEADER) }
        val titleBox = vbox()
        titleBox.addView(label("STARTER ASSET LIBRARY", 16f, C.TEXT, true))
        titleBox.addView(label("${entries.size} offline assets · original CC0-1.0 content", 11f, C.DIM))
        header.addView(titleBox, lp(0, WRAP, 1f))
        header.addView(button("Done", C.ACCENT, 0xFFFFFFFF.toInt()) { finish() })
        root.addView(header, lp(MATCH, WRAP))

        search = field("").apply { hint = "Search characters, props, sounds, scripts…"; setPadding(dp(12), dp(7), dp(12), dp(7)) }
        root.addView(search, lp(MATCH, WRAP).margins(dp(12), dp(10), dp(12), dp(6)))

        categoryBar = hbox().apply { setPadding(dp(8), dp(3), dp(8), dp(4)) }
        val categoryScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(categoryBar)
        }
        root.addView(categoryScroll, lp(MATCH, WRAP))
        summary = label("", 11f, C.DIM).apply { setPadding(dp(14), dp(4), dp(14), dp(4)) }
        root.addView(summary, lp(MATCH, WRAP))

        rows = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@AssetLibraryActivity)
            setPadding(dp(8), dp(2), dp(8), dp(8))
            clipToPadding = false
        }
        adapter = LibraryAdapter()
        rows.adapter = adapter
        root.addView(rows, lp(MATCH, 0, 1f))
        setContentView(root)
        buildCategories()
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = applyFilter()
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        applyFilter()
    }

    private fun buildCategories() {
        categoryBar.removeAllViews()
        val categories = listOf("All") + entries.map { it.category }.distinct()
        categories.forEach { category ->
            categoryBar.addView(button(category, if (category == selectedCategory) C.ACCENT else C.PANEL2,
                if (category == selectedCategory) 0xFFFFFFFF.toInt() else C.TEXT) {
                selectedCategory = category
                buildCategories()
                applyFilter()
            }.apply { textSize = 11f }, lp(WRAP, WRAP).margins(dp(3), 0, dp(3), 0))
        }
    }

    private fun applyFilter() {
        if (!::adapter.isInitialized) return
        val query = search.text.toString().trim().lowercase()
        val filtered = entries.filter { item ->
            (selectedCategory == "All" || item.category == selectedCategory) &&
                (query.isEmpty() || item.name.lowercase().contains(query) || item.description.lowercase().contains(query) || item.filename.lowercase().contains(query))
        }
        adapter.submit(filtered)
        summary.text = "${filtered.size} shown  ·  ${entries.size} total  ·  Tap Add to copy an asset into '${project.name}'"
    }

    private fun install(item: BuiltinAsset) {
        try {
            val installed = BuiltinAssetLibrary.install(this, project, item)
            setResult(RESULT_OK)
            Toast.makeText(this, "Added $installed to ${project.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't add asset: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showSource(item: BuiltinAsset) {
        try {
            val text = assets.open("asset-library/${item.path}").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val view = label(text, 12f, C.TEXT).apply {
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(dp(14), dp(10), dp(14), dp(10))
            }
            MaterialAlertDialogBuilder(this).setTitle(item.name).setView(ScrollView(this).apply { addView(view) })
                .setPositiveButton("Add to Project") { _, _ -> install(item) }
                .setNegativeButton("Close", null).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't read source: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun preview(item: BuiltinAsset) {
        try {
            mediaPlayer?.release()
            mediaPlayer = null
            val file = File(cacheDir, "asset-preview-${item.id}.wav")
            assets.open("asset-library/${item.path}").use { input -> file.outputStream().use { input.copyTo(it) } }
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { player -> player.release(); if (mediaPlayer === player) mediaPlayer = null }
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't preview audio: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroy()
    }

    private inner class LibraryAdapter : RecyclerView.Adapter<LibraryAdapter.Holder>() {
        private val visible = ArrayList<BuiltinAsset>()

        fun submit(items: List<BuiltinAsset>) {
            visible.clear(); visible.addAll(items); notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val card = hbox().apply {
                background = round(C.PANEL, dp(8).toFloat())
                setPadding(dp(10), dp(8), dp(8), dp(8))
                gravity = Gravity.CENTER_VERTICAL
            }
            return Holder(card)
        }

        override fun getItemCount() = visible.size

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(visible[position])

        inner class Holder(private val card: LinearLayout) : RecyclerView.ViewHolder(card) {
            private val preview = FrameLayout(this@AssetLibraryActivity)
            private val info = vbox()
            private val actions = vbox()
            private val name = label("", 13f, C.TEXT, true)
            private val detail = label("", 10f, C.DIM).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }

            init {
                card.addView(preview, lp(dp(62), dp(62)).margins(0, 0, dp(10), 0))
                info.addView(name)
                info.addView(detail)
                card.addView(info, lp(0, WRAP, 1f))
                card.addView(actions, lp(WRAP, WRAP).margins(dp(6), 0, 0, 0))
            }

            private fun addCenteredPreview(view: View) {
                preview.addView(view, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
            }

            fun bind(item: BuiltinAsset) {
                name.text = item.name
                detail.text = "${item.category} · ${item.description}"
                preview.removeAllViews()
                when (item.kind) {
                    AssetKind.TEXTURE -> try {
                        val image = ImageView(this@AssetLibraryActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true }
                        assets.open("asset-library/${item.path}").use { image.setImageBitmap(BitmapFactory.decodeStream(it)) }
                        preview.addView(image, FrameLayout.LayoutParams(MATCH, MATCH))
                    } catch (_: Exception) { addCenteredPreview(label("IMG", 11f, C.DIM)) }
                    AssetKind.SOUND -> addCenteredPreview(label("♪", 28f, C.GREEN, true))
                    AssetKind.SCRIPT -> addCenteredPreview(label("JS", 16f, C.YELLOW, true))
                    AssetKind.SHADER -> addCenteredPreview(label("GL", 16f, 0xFFB69CFF.toInt(), true))
                    null -> addCenteredPreview(label("•", 20f, C.DIM))
                }
                actions.removeAllViews()
                when (item.kind) {
                    AssetKind.SOUND -> actions.addView(button("Preview", C.PANEL2) { this@AssetLibraryActivity.preview(item) }.apply { textSize = 10f })
                    AssetKind.SCRIPT, AssetKind.SHADER -> actions.addView(button("Source", C.PANEL2) { showSource(item) }.apply { textSize = 10f })
                    else -> Unit
                }
                actions.addView(button("＋ Add", C.ACCENT, 0xFFFFFFFF.toInt()) { install(item) }.apply { textSize = 11f })
            }
        }
    }
}
