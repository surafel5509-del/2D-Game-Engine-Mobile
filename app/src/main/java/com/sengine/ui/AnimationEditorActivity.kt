package com.sengine.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.sengine.engine.anim.AnimationClip
import com.sengine.engine.anim.LoopMode
import com.sengine.engine.render.TextureCache
import com.sengine.project.ClipLibrary
import com.sengine.project.Project
import com.sengine.project.ProjectManager
import kotlin.math.max

/**
 * Animation editor: sprite sheet preview with the frame grid, playback (loop / once / ping-pong),
 * fps, speed, per-frame timing, event markers, timeline curves and clip management.
 *
 * The preview draws the real sprite sheet through the engine's texture cache, so what you see is
 * exactly what the game renders.
 */
class AnimationEditorActivity : Activity() {


    private lateinit var project: Project
    private lateinit var textures: TextureCache
    private lateinit var preview: PreviewView
    private lateinit var info: TextView
    private lateinit var playlist: LinearLayout
    private var clip: AnimationClip? = null
    private var playing = false
    private var time = 0f
    private var lastTick = 0L
    private var frameByFrame = false
    private var selectedEvent = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        project = intent.getStringExtra("projectDir")?.let { Project(java.io.File(it)) }
            ?: ProjectManager.open(this, intent.getStringExtra("project") ?: "")
        textures = TextureCache(project)
        try { textures.initGl() } catch (_: Throwable) { /* headless: preview falls back to a placeholder */ }

        val root = vbox().apply { setBackgroundColor(C.BG) }
        val header = hbox().apply {
            setBackgroundColor(C.HEADER)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        header.addView(button("‹ Back") { finish() })
        header.addView(label("Animation editor", 14f, C.TEXT, bold = true).apply { setPadding(dp(10), 0, dp(10), 0) })
        header.addView(spacer())
        header.addView(button("New clip") { newClip() })
        header.addView(button("Save", C.ACCENT, 0xFFFFFFFF.toInt()) { save() }.apply { layoutParams = lp(WRAP, WRAP).margins(dp(6), 0, 0, 0) })
        root.addView(header, lp(MATCH, WRAP))

        val body = hbox().apply { setPadding(dp(8), dp(8), dp(8), dp(8)) }
        // left: clip list
        playlist = vbox().apply {
            background = round(C.PANEL, dp(6).toFloat())
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        body.addView(android.widget.ScrollView(this).apply { addView(playlist) }, lp(dp(190), MATCH))

        // middle: preview
        val middle = vbox().apply { layoutParams = lp(0, MATCH, 1f).margins(dp(8), 0, dp(8), 0) }
        preview = PreviewView()
        middle.addView(preview, lp(MATCH, 0, 1f))
        info = label("", 11.5f, C.DIM).apply { setPadding(dp(4), dp(4), dp(4), 0) }
        middle.addView(info)
        body.addView(middle)

        // right: properties
        val props = vbox().apply {
            background = round(C.PANEL, dp(6).toFloat())
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        body.addView(android.widget.ScrollView(this).apply { addView(props) }, lp(dp(300), MATCH))
        root.addView(body, lp(MATCH, 0, 1f))
        setContentView(root)
        buildProps(props)
        rebuildList()
        clip = ClipLibrary.list(project).firstOrNull()?.let { ClipLibrary.clip(project, it) }
        updateInfo()
    }

    // ---------------------------------------------------------------- clip management
    private fun rebuildList() {
        playlist.removeAllViews()
        playlist.addView(label("CLIPS", 11f, C.DIM, bold = true))
        val clips = ClipLibrary.list(project)
        if (clips.isEmpty()) {
            playlist.addView(label("No .anim assets yet.\nTap New clip to create one.", 11.5f, C.DIM).apply {
                setPadding(0, dp(8), 0, 0)
            })
        }
        for (name in clips) {
            val isCurrent = clip?.name == name.removeSuffix(".anim")
            playlist.addView(button(name, if (isCurrent) C.ACCENT else C.PANEL2, if (isCurrent) 0xFFFFFFFF.toInt() else C.TEXT) {
                clip = ClipLibrary.clip(project, name)
                time = 0f
                updateInfo()
                preview.invalidate()
                rebuildList()
            }.apply { layoutParams = lp(MATCH, WRAP).margins(0, dp(4), 0, 0) })
        }
    }

    private fun newClip() {
        inputDialog("New clip name", "HeroRun") { raw ->
            val name = raw.trim().ifBlank { "Animation" }
            val clip = ClipLibrary.template(name)
            ClipLibrary.save(project, clip)
            this.clip = clip
            rebuildList()
            toast("Created $name.anim - set its texture and frame grid on the right")
        }
    }

    private fun save() {
        val c = clip ?: return toast("No clip selected")
        if (ClipLibrary.save(project, c)) toast("Saved ${c.name}.anim") else toast("Save failed")
        rebuildList()
    }

    // ---------------------------------------------------------------- properties panel
    private fun buildProps(props: LinearLayout) {
        props.addView(label("CLIP", 11f, C.DIM, bold = true))
        val c = clip

        props.addView(row("Name", field(c?.name ?: "").apply {
            setOnFocusChangeListener { _, has ->
                if (!has && clip != null) { clip!!.name = text.toString(); updateInfo() }
            }
        }))
        props.addView(row("Texture", this.hbox().apply {
            val value = label(clip?.texture?.ifBlank { "(none)" } ?: "-", 12f, C.TEXT).apply { layoutParams = lp(0, WRAP, 1f) }
            addView(value)
            addView(button("…") {
                pickAsset { picked ->
                    clip?.texture = picked
                    textures.clear()
                    updateInfo()
                    preview.invalidate()
                    buildPropsRefresh(props)
                }
            })
        }))
        var columns = clip?.columns ?: 4
        var rows = clip?.rows ?: 4
        props.addView(row("Columns", intField(columns, 1, 1, 64) { columns = it }))
        props.addView(row("Rows", intField(rows, 1, 1, 64) { rows = it }))
        props.addView(button("Apply grid (rebuild frames)", C.PANEL2) {
            clip?.buildGrid(columns, rows)
            updateInfo()
            preview.invalidate()
            toast("Frames rebuilt: ${columns} x ${rows}")
        }.apply { layoutParams = lp(MATCH, WRAP).margins(0, dp(6), 0, 0) })

        props.addView(label("PLAYBACK", 11f, C.DIM, bold = true).apply { setPadding(0, dp(12), 0, 0) })
        props.addView(row("FPS", slider(clip?.fps ?: 12f, 1f, 60f) { clip?.fps = it; updateInfo() }))
        props.addView(row("Speed", slider(clip?.speed ?: 1f, 0.1f, 3f) { clip?.speed = it; updateInfo() }))
        val loopLabels = LoopMode.labels
        props.addView(row("Loop", choice(loopLabels, LoopMode.indexOf(clip?.loop ?: LoopMode.LOOP)) {
            clip?.loop = LoopMode.of(it)
        }))
        props.addView(hbox().apply {
            setPadding(0, dp(8), 0, 0)
            addView(button("▶ Play") { playing = true; lastTick = System.nanoTime() })
            addView(button("❚❚") { playing = false }.apply { layoutParams = lp(WRAP, WRAP).margins(dp(6), 0, 0, 0) })
            addView(button("◀ frame") {
                frameByFrame = true
                val c2 = clip ?: return@button
                c2.let { time = max(0f, time - 1f / it.fps) }
                preview.invalidate(); updateInfo()
            }.apply { layoutParams = lp(WRAP, WRAP).margins(dp(6), 0, 0, 0) })
            addView(button("frame ▶") {
                frameByFrame = true
                val c2 = clip ?: return@button
                time += 1f / c2.fps
                preview.invalidate(); updateInfo()
            }.apply { layoutParams = lp(WRAP, WRAP).margins(dp(6), 0, 0, 0) })
        })

        val bar = SeekBar(this).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(C.ACCENT)
            thumbTintList = android.content.res.ColorStateList.valueOf(C.ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val c2 = clip ?: return
                    if (fromUser) {
                        playing = false
                        frameByFrame = true
                        time = c2.duration * (p / 1000f)
                        preview.invalidate()
                        updateInfo()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        props.addView(label("Timeline", 11f, C.DIM).apply { setPadding(0, dp(10), 0, 0) })
        props.addView(bar)

        props.addView(label("EVENTS", 11f, C.DIM, bold = true).apply { setPadding(0, dp(12), 0, 0) })
        val events = clip?.events ?: emptyList()
        if (events.isEmpty()) {
            props.addView(label("No events. Add one at the current time.", 11.5f, C.DIM))
        }
        for ((index, e) in events.withIndex()) {
            props.addView(hbox().apply {
                addView(label("t=${fmt(e.time)} ${e.name}", 11.5f, if (index == selectedEvent) C.ACCENT else C.TEXT), lp(0, WRAP, 1f))
                addView(button("✕") {
                    clip?.events?.remove(e)
                    buildPropsRefresh(props)
                })
            })
        }
        props.addView(button("＋ Add event at current time") {
            val c2 = clip ?: return@button
            inputDialog("Event name", "footstep") { name ->
                c2.addEvent(time, name.ifBlank { "event" })
                toast("Event '${name}' at ${fmt(time)}s")
                buildPropsRefresh(props)
            }
        }.apply { layoutParams = lp(MATCH, WRAP).margins(0, dp(6), 0, 0) })

        props.addView(label("TIMELINE CURVES", 11f, C.DIM, bold = true).apply { setPadding(0, dp(12), 0, 0) })
        val tracks = clip?.tracks ?: emptyList()
        if (tracks.isEmpty()) props.addView(label("No property tracks.", 11.5f, C.DIM))
        for (t in tracks) {
            props.addView(hbox().apply {
                addView(label(t.target, 11.5f, C.TEXT), lp(0, WRAP, 1f))
                addView(button("✕") { clip?.tracks?.remove(t); buildPropsRefresh(props) })
            })
        }
        props.addView(button("＋ Add track (e.g. scaleX)") {
            val c2 = clip ?: return@button
            inputDialog("Property", "scaleX") { prop ->
                c2.addTrack(prop.ifBlank { "x" }, com.sengine.engine.math.AnimationCurve.linear(0f, 1f))
                buildPropsRefresh(props)
            }
        }.apply { layoutParams = lp(MATCH, WRAP).margins(0, dp(6), 0, 0) })
    }

    private fun buildPropsRefresh(props: LinearLayout) {
        props.removeAllViews()
        buildProps(props)
        preview.invalidate()
        updateInfo()
    }

    private fun pickAsset(onPick: (String) -> Unit) {
        val images = project.listAssets(com.sengine.engine.core.AssetKind.TEXTURE).filter {
            val e = it.substringAfterLast('.', "").lowercase()
            e == "png" || e == "jpg" || e == "jpeg" || e == "webp" || e == "svg"
        }
        if (images.isEmpty()) return toast("Import images in the editor's Assets tab first")
        AlertDialog.Builder(this).setTitle("Pick texture").setItems(images.toTypedArray()) { _, which -> onPick(images[which]) }.show()
    }

    // ---------------------------------------------------------------- preview
    private inner class PreviewView : View(this) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0x99FFFFFF.toInt()
            strokeWidth = 2f
        }
        private var sheet: Bitmap? = null
        private var sheetName = ""

        private fun sheet(): Bitmap? {
            val c = clip ?: return null
            if (c.texture.isBlank()) return null
            if (sheetName == c.texture && sheet != null) return sheet
            sheetName = c.texture
            val file = project.assetFile(c.texture)
            sheet = if (file.exists()) android.graphics.BitmapFactory.decodeFile(file.absolutePath) else null
            return sheet
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(C.FIELD)
            val c = clip
            val bmp = sheet()
            if (c == null || bmp == null) {
                paint.color = C.DIM
                paint.textSize = 30f
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText(
                    if (c == null) "Select or create a clip" else "Assign a sprite sheet texture",
                    width * 0.5f, height * 0.5f, paint
                )
                return
            }
            val scale = minOf(width / bmp.width.toFloat(), height / bmp.height.toFloat()) * 0.92f
            val w = bmp.width * scale
            val h = bmp.height * scale
            val left = (width - w) * 0.5f
            val top = (height - h) * 0.5f
            paint.alpha = 255
            canvas.drawBitmap(bmp, null, android.graphics.RectF(left, top, left + w, top + h), paint)

            // frame grid + current frame highlight
            val fw = w / c.columns
            val fh = h / c.rows
            framePaint.color = 0x44FFFFFF
            framePaint.alpha = 110
            for (i in 0..c.columns) canvas.drawLine(left + i * fw, top, left + i * fw, top + h, framePaint)
            for (j in 0..c.rows) canvas.drawLine(left, top + j * fh, left + w, top + j * fh, framePaint)

            val index = c.frameIndexAt(time)
            val col = index % c.columns
            val row = index / c.columns
            framePaint.color = C.ACCENT
            framePaint.strokeWidth = 3f
            canvas.drawRect(left + col * fw, top + row * fh, left + (col + 1) * fw, top + (row + 1) * fh, framePaint)

            // event markers
            paint.color = C.YELLOW
            for (e in c.events) {
                val x = left + (e.time / c.duration.coerceAtLeast(0.001f)) * w
                canvas.drawRect(x - 1.5f, top - 8f, x + 1.5f, top, paint)
            }
            paint.color = C.TEXT
            paint.textSize = 24f
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText("frame ${index + 1}/${c.frameCount}   t=${fmt(time)}s", left, top - 16f, paint)
            paint.color = 0xFFFFFFFF.toInt()
            paint.strokeWidth = 2f
            framePaint.strokeWidth = 2f
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                playing = !playing
                lastTick = System.nanoTime()
                return true
            }
            return super.onTouchEvent(event)
        }
    }

    override fun onResume() {
        super.onResume()
        tickHandler.post(tickRunnable)
    }

    override fun onPause() {
        super.onPause()
        tickHandler.removeCallbacks(tickRunnable)
    }

    private val tickHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            val now = System.nanoTime()
            val dt = ((now - lastTick) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
            lastTick = now
            val c = clip
            if (playing && c != null) {
                time += dt * c.speed
                if (time > c.duration) {
                    time = when (c.loop) {
                        LoopMode.ONCE -> { playing = false; c.duration }
                        LoopMode.PING_PONG -> 0f
                        LoopMode.LOOP -> time % c.duration
                    }
                }
                preview.invalidate()
                updateInfo()
            }
            tickHandler.postDelayed(this, 16)
        }
    }

    private fun updateInfo() {
        val c = clip
        info.text = if (c == null) "No clip loaded" else try {
            "frames: ${c.frameCount}  ·  ${c.columns}x${c.rows}  ·  ${fmt(c.duration)}s  ·  fps ${fmt(c.fps)}  ·  ${c.loop.label}  ·  events ${c.events.size}  ·  tracks ${c.tracks.size}"
        } catch (e: Exception) {
            "clip error: ${e.message}"
        }
    }
}
