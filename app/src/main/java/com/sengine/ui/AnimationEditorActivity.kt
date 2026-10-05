package com.sengine.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sengine.engine.anim.AnimationClip
import com.sengine.engine.core.AssetKind
import com.sengine.project.Project
import com.sengine.project.ProjectManager
import org.json.JSONObject

/** Sprite-sheet animation editor: slice a sheet into cells, pick frames, preview and save `.anim` clips. */
class AnimationEditorActivity : AppCompatActivity() {
    private lateinit var project: Project
    private var asset: String? = null
    private var clip = AnimationClip()
    private var bitmap: Bitmap? = null
    private var saved = ""

    private lateinit var title: TextView
    private lateinit var sheet: SheetView
    private lateinit var preview: PreviewView
    private lateinit var texBtn: TextView
    private lateinit var colsField: EditText
    private lateinit var rowsField: EditText
    private lateinit var fpsField: EditText
    private lateinit var framesField: EditText
    private lateinit var loopBox: CheckBox
    private lateinit var strip: LinearLayout
    private lateinit var info: TextView
    private var updating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        project = ProjectManager.open(this, intent.getStringExtra("project")!!)
        asset = intent.getStringExtra("asset")

        val root = vbox().apply { setBackgroundColor(C.BG) }
        val bar = hbox().apply { setBackgroundColor(C.HEADER); setPadding(dp(6), dp(4), dp(6), dp(4)) }
        bar.addView(button("←") { onBackPressedDispatcher.onBackPressed() })
        title = label("", 15f, C.TEXT, true).apply { setPadding(dp(10), 0, dp(10), 0); isSingleLine = true }
        bar.addView(title, lp(0, WRAP, 1f))
        bar.addView(button("Open…") { openDialog() }, lp(WRAP, WRAP).margins(dp(3), 0, dp(3), 0))
        bar.addView(button("New") { newClip() }, lp(WRAP, WRAP).margins(dp(3), 0, dp(3), 0))
        bar.addView(button("Save", C.ACCENT, 0xFFFFFFFF.toInt()) { save() }, lp(WRAP, WRAP).margins(dp(3), 0, 0, 0))
        root.addView(bar, lp(MATCH, WRAP))

        val middle = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // left: settings + preview
        val left = vbox().apply { setPadding(dp(10), dp(8), dp(10), dp(8)); setBackgroundColor(C.PANEL) }
        preview = PreviewView(this)
        left.addView(preview, lp(MATCH, dp(170)))
        val playRow = hbox()
        playRow.addView(button("⏯") { preview.playing = !preview.playing }, lp(0, WRAP, 1f).margins(0, dp(4), dp(2), 0))
        playRow.addView(button("⏮") { preview.step(-1) }, lp(0, WRAP, 1f).margins(dp(2), dp(4), dp(2), 0))
        playRow.addView(button("⏭") { preview.step(1) }, lp(0, WRAP, 1f).margins(dp(2), dp(4), 0, 0))
        left.addView(playRow, lp(MATCH, WRAP))
        info = label("", 11f, C.DIM)
        left.addView(info)

        fun row(t: String, v: View) {
            val r = hbox(); r.addView(label(t, 12f, C.DIM), lp(dp(70), WRAP)); r.addView(v, lp(0, WRAP, 1f))
            left.addView(r, lp(MATCH, WRAP).margins(0, dp(3), 0, dp(3)))
        }
        texBtn = button("(choose texture)") { chooseTexture() }.apply { textSize = 12f }
        row("Sheet", texBtn)
        colsField = field("1", numeric = true); row("Columns", colsField)
        rowsField = field("1", numeric = true); row("Rows", rowsField)
        fpsField = field("10", numeric = true); row("FPS", fpsField)
        framesField = field("0"); row("Frames", framesField)
        loopBox = CheckBox(this).apply { text = "Loop"; setTextColor(C.TEXT) }
        left.addView(loopBox)
        val tools = hbox()
        tools.addView(button("All") { setFrames((0 until clip.columns * clip.rows).toMutableList()) }.apply { textSize = 11f }, lp(0, WRAP, 1f))
        tools.addView(button("Row") { rowFrames() }.apply { textSize = 11f }, lp(0, WRAP, 1f).margins(dp(2), 0, 0, 0))
        tools.addView(button("Rev") { setFrames(clip.frames.reversed().toMutableList()) }.apply { textSize = 11f }, lp(0, WRAP, 1f).margins(dp(2), 0, 0, 0))
        tools.addView(button("⇄") { setFrames((clip.frames + clip.frames.reversed().drop(1).dropLast(1)).toMutableList()) }.apply { textSize = 11f }, lp(0, WRAP, 1f).margins(dp(2), 0, 0, 0))
        tools.addView(button("Clear") { setFrames(mutableListOf()) }.apply { textSize = 11f }, lp(0, WRAP, 1f).margins(dp(2), 0, 0, 0))
        left.addView(tools, lp(MATCH, WRAP).margins(0, dp(4), 0, 0))
        left.addView(label("Tap cells on the sheet to append frames. Tap a frame in the strip to remove it.", 11f, C.DIM), lp(MATCH, WRAP).margins(0, dp(6), 0, 0))
        middle.addView(ScrollView(this).apply { addView(left) }, lp(dp(270), MATCH))

        // right: sheet + frame strip
        val right = vbox()
        sheet = SheetView(this) { cell -> setFrames((clip.frames + cell).toMutableList()) }
        right.addView(sheet, lp(MATCH, 0, 1f))
        strip = hbox().apply { setPadding(dp(4), dp(4), dp(4), dp(4)) }
        right.addView(HorizontalScrollView(this).apply { addView(strip); setBackgroundColor(C.HEADER) }, lp(MATCH, dp(76)))
        middle.addView(right, lp(0, MATCH, 1f))
        root.addView(middle, lp(MATCH, 0, 1f))
        setContentView(root)

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { if (!updating) readFields() }
        }
        listOf(colsField, rowsField, fpsField, framesField).forEach { it.addTextChangedListener(watcher) }
        loopBox.setOnCheckedChangeListener { _, b -> if (!updating) { clip.loop = b; refresh(false) } }

        val a = asset
        if (a != null) load(a) else {
            val first = project.listAssets(AssetKind.ANIMATION).firstOrNull()
            if (first != null) load(first) else newClip(ask = false)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (clip.toJson().toString() == saved || asset == null && clip.texture.isBlank()) { finish(); return }
                MaterialAlertDialogBuilder(this@AnimationEditorActivity)
                    .setTitle("Unsaved changes").setMessage("Save animation?")
                    .setPositiveButton("Save") { _, _ -> if (save()) finish() }
                    .setNegativeButton("Discard") { _, _ -> finish() }
                    .setNeutralButton("Cancel", null).show()
            }
        })
    }

    private fun load(name: String) {
        asset = name
        clip = try { AnimationClip.fromJson(JSONObject(project.readAsset(name) ?: "{}")) } catch (_: Exception) { AnimationClip() }
        saved = clip.toJson().toString()
        loadBitmap()
        writeFields(); refresh(true)
    }

    private fun newClip(ask: Boolean = true) {
        if (!ask) { asset = null; clip = AnimationClip(); saved = clip.toJson().toString(); loadBitmap(); writeFields(); refresh(true); return }
        val f = field("NewAnimation")
        MaterialAlertDialogBuilder(this).setTitle("New animation")
            .setView(LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(f, lp(MATCH, WRAP)) })
            .setPositiveButton("Create") { _, _ ->
                var n = f.text.toString().trim().replace(Regex("[^A-Za-z0-9_\\-]"), "").ifBlank { "NewAnimation" }
                if (!n.endsWith(".anim")) n += ".anim"
                n = project.uniqueAssetName(n)
                clip = AnimationClip(texture = clip.texture, columns = clip.columns, rows = clip.rows)
                project.writeAsset(n, clip.toJson().toString(2))
                load(n)
            }.setNegativeButton("Cancel", null).show()
    }

    private fun openDialog() {
        val items = project.listAssets(AssetKind.ANIMATION)
        if (items.isEmpty()) { toast("No animations yet — tap New"); return }
        MaterialAlertDialogBuilder(this).setTitle("Open animation")
            .setItems(items.toTypedArray()) { _, i -> load(items[i]) }.show()
    }

    private fun chooseTexture() {
        val items = project.listAssets(AssetKind.TEXTURE)
        if (items.isEmpty()) { toast("Import an image or get sprite sheets from the Asset Store"); return }
        MaterialAlertDialogBuilder(this).setTitle("Sprite sheet")
            .setItems(items.toTypedArray()) { _, i ->
                clip.texture = items[i]
                loadBitmap()
                guessGrid()
                writeFields(); refresh(true)
            }.show()
    }

    /** Guess columns/rows for sheets with square cells laid out in one row, e.g. 512x64 → 8x1. */
    private fun guessGrid() {
        val b = bitmap ?: return
        if (b.width > b.height && b.width % b.height == 0) { clip.columns = b.width / b.height; clip.rows = 1 }
        else if (b.height > b.width && b.height % b.width == 0) { clip.columns = 1; clip.rows = b.height / b.width }
        clip.frames = (0 until clip.columns * clip.rows).toMutableList()
    }

    private fun loadBitmap() {
        bitmap = if (clip.texture.isBlank()) null else try { BitmapFactory.decodeFile(project.assetFile(clip.texture).absolutePath) } catch (_: Throwable) { null }
    }

    private fun rowFrames() {
        val last = clip.frames.lastOrNull() ?: 0
        val row = last / clip.columns.coerceAtLeast(1)
        setFrames((row * clip.columns until (row + 1) * clip.columns).toMutableList())
    }

    private fun setFrames(f: MutableList<Int>) {
        clip.frames = f
        updating = true; framesField.setText(AnimationClip.formatFrames(f)); updating = false
        refresh(false)
    }

    private fun readFields() {
        clip.columns = (colsField.text.toString().toIntOrNull() ?: 1).coerceIn(1, 64)
        clip.rows = (rowsField.text.toString().toIntOrNull() ?: 1).coerceIn(1, 64)
        clip.fps = (fpsField.text.toString().toFloatOrNull() ?: 10f).coerceIn(0.1f, 120f)
        clip.frames = AnimationClip.parseFrames(framesField.text.toString()).filter { it < clip.columns * clip.rows }.toMutableList()
        refresh(false)
    }

    private fun writeFields() {
        updating = true
        colsField.setText(clip.columns.toString()); rowsField.setText(clip.rows.toString())
        fpsField.setText(fmt(clip.fps)); framesField.setText(AnimationClip.formatFrames(clip.frames))
        loopBox.isChecked = clip.loop
        updating = false
    }

    private fun refresh(full: Boolean) {
        title.text = "Animation: " + (asset ?: "(unsaved)")
        texBtn.text = clip.texture.ifBlank { "(choose texture)" }
        sheet.set(bitmap, clip)
        preview.set(bitmap, clip)
        info.text = "${clip.frames.size} frames • ${fmt(clip.duration)} s" + (bitmap?.let { " • sheet ${it.width}×${it.height}" } ?: "")
        strip.removeAllViews()
        clip.frames.forEachIndexed { i, cell ->
            val v = FrameThumb(this, bitmap, clip, cell, i)
            v.setOnClickListener { setFrames(clip.frames.toMutableList().also { it.removeAt(i) }) }
            strip.addView(v, lp(dp(64), dp(64)).margins(dp(2), 0, dp(2), 0))
        }
        if (full) preview.restart()
    }

    private fun save(): Boolean {
        var name = asset
        if (name == null) {
            name = project.uniqueAssetName((clip.texture.substringBeforeLast('.').ifBlank { "Animation" }) + ".anim")
            asset = name
        }
        project.writeAsset(name, clip.toJson().toString(2))
        saved = clip.toJson().toString()
        refresh(false)
        toast("Saved $name")
        return true
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ views

    companion object {
        fun cellRect(b: Bitmap, clip: AnimationClip, cell: Int, out: Rect) {
            val c = clip.columns.coerceAtLeast(1); val r = clip.rows.coerceAtLeast(1)
            val cw = b.width / c; val ch = b.height / r
            val col = cell % c; val row = (cell / c).coerceAtMost(r - 1)
            out.set(col * cw, row * ch, (col + 1) * cw, (row + 1) * ch)
        }

        fun drawChecker(c: Canvas, r: RectF, size: Float, p: Paint) {
            var y = r.top; var yi = 0
            while (y < r.bottom) {
                var x = r.left; var xi = yi
                while (x < r.right) {
                    p.color = if (xi % 2 == 0) 0xFF3A3D42.toInt() else 0xFF2E3136.toInt()
                    c.drawRect(x, y, minOf(x + size, r.right), minOf(y + size, r.bottom), p)
                    x += size; xi++
                }
                y += size; yi++
            }
        }
    }

    @SuppressLint("ViewConstructor")
    class SheetView(ctx: Context, val onCell: (Int) -> Unit) : View(ctx) {
        private var bmp: Bitmap? = null
        private var clip = AnimationClip()
        private val p = Paint().apply { isFilterBitmap = false }
        private val line = Paint().apply { color = 0x88FFFFFF.toInt(); strokeWidth = 1f; style = Paint.Style.STROKE }
        private val hi = Paint().apply { color = 0x554C8DFF }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD54F.toInt(); textSize = 12f * resources.displayMetrics.density; isFakeBoldText = true }
        private val dst = RectF()

        fun set(b: Bitmap?, c: AnimationClip) { bmp = b; clip = c; invalidate() }

        override fun onDraw(c: Canvas) {
            c.drawColor(0xFF1B1D21.toInt())
            val b = bmp
            if (b == null) {
                txt.textAlign = Paint.Align.CENTER
                c.drawText("Choose a sprite sheet texture", width / 2f, height / 2f, txt)
                txt.textAlign = Paint.Align.LEFT
                return
            }
            val s = minOf((width - 20f) / b.width, (height - 20f) / b.height)
            val w = b.width * s; val h = b.height * s
            dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
            drawChecker(c, dst, 12f * resources.displayMetrics.density, p)
            c.drawBitmap(b, null, dst, p)
            val cols = clip.columns.coerceAtLeast(1); val rows = clip.rows.coerceAtLeast(1)
            val cw = w / cols; val ch = h / rows
            for (cell in clip.frames.distinct()) {
                val col = cell % cols; val row = cell / cols
                if (row >= rows) continue
                c.drawRect(dst.left + col * cw, dst.top + row * ch, dst.left + (col + 1) * cw, dst.top + (row + 1) * ch, hi)
            }
            for (i in 0..cols) c.drawLine(dst.left + i * cw, dst.top, dst.left + i * cw, dst.bottom, line)
            for (j in 0..rows) c.drawLine(dst.left, dst.top + j * ch, dst.right, dst.top + j * ch, line)
            // frame order numbers
            clip.frames.forEachIndexed { i, cell ->
                val col = cell % cols; val row = cell / cols
                if (row < rows) c.drawText("${i + 1}", dst.left + col * cw + 4, dst.top + row * ch + txt.textSize + 2 + (i / (cols * rows)) * txt.textSize, txt)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP && bmp != null && dst.contains(e.x, e.y)) {
                val col = ((e.x - dst.left) / (dst.width() / clip.columns.coerceAtLeast(1))).toInt().coerceIn(0, clip.columns - 1)
                val row = ((e.y - dst.top) / (dst.height() / clip.rows.coerceAtLeast(1))).toInt().coerceIn(0, clip.rows - 1)
                onCell(row * clip.columns + col)
            }
            return true
        }
    }

    class PreviewView(ctx: Context) : View(ctx) {
        private var bmp: Bitmap? = null
        private var clip = AnimationClip()
        var playing = true
        private var start = SystemClock.uptimeMillis()
        private var manual = 0
        private val p = Paint().apply { isFilterBitmap = false }
        private val src = Rect()
        private val dst = RectF()
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9AA0A6.toInt(); textSize = 11f * resources.displayMetrics.density }

        fun set(b: Bitmap?, c: AnimationClip) { bmp = b; clip = c; invalidate() }
        fun restart() { start = SystemClock.uptimeMillis(); manual = 0 }
        fun step(d: Int) { playing = false; manual = Math.floorMod(currentIndex() + d, clip.frames.size.coerceAtLeast(1)); invalidate() }

        private fun currentIndex(): Int {
            val n = clip.frames.size
            if (n == 0) return 0
            if (!playing) return manual.coerceIn(0, n - 1)
            val idx = (((SystemClock.uptimeMillis() - start) / 1000f) * clip.fps).toInt()
            return if (clip.loop) idx % n else minOf(idx, n - 1)
        }

        override fun onDraw(c: Canvas) {
            dst.set(0f, 0f, width.toFloat(), height.toFloat())
            drawChecker(c, dst, 10f * resources.displayMetrics.density, p)
            val b = bmp
            if (b != null && clip.frames.isNotEmpty()) {
                val i = currentIndex()
                if (playing) manual = i
                cellRect(b, clip, clip.frames[i], src)
                val s = minOf(width * 0.9f / src.width(), height * 0.9f / src.height())
                val w = src.width() * s; val h = src.height() * s
                dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
                c.drawBitmap(b, src, dst, p)
                c.drawText("frame ${i + 1}/${clip.frames.size}  (cell ${clip.frames[i]})", 8f, height - 8f, txt)
            }
            if (playing) postInvalidateOnAnimation()
        }
    }

    @SuppressLint("ViewConstructor")
    class FrameThumb(ctx: Context, private val bmp: Bitmap?, private val clip: AnimationClip, private val cell: Int, private val index: Int) : View(ctx) {
        private val p = Paint().apply { isFilterBitmap = false }
        private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 10f * resources.displayMetrics.density }
        private val src = Rect()
        private val dst = RectF()
        override fun onDraw(c: Canvas) {
            dst.set(0f, 0f, width.toFloat(), height.toFloat())
            drawChecker(c, dst, 8f * resources.displayMetrics.density, p)
            bmp?.let { b ->
                cellRect(b, clip, cell, src)
                val s = minOf(width / src.width().toFloat(), height / src.height().toFloat())
                val w = src.width() * s; val h = src.height() * s
                dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
                c.drawBitmap(b, src, dst, p)
            }
            c.drawText("${index + 1}", 4f, t.textSize + 2f, t)
        }
    }
}
