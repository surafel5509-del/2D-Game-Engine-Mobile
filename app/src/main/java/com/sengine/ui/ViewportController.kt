package com.sengine.ui

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.sengine.engine.Engine
import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.SpriteRenderer
import com.sengine.engine.math.Rect2
import com.sengine.engine.render.EditorState
import com.sengine.engine.render.SceneRenderer
import com.sengine.engine.render.Tool
import com.sengine.engine.tilemap.TileLayer
import com.sengine.engine.tilemap.TilemapRenderer
import android.opengl.GLES20
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The 2D editor viewport: a GLSurfaceView that renders the scene (through [SceneRenderer]) and
 * turns touch input into real editing operations - select, marquee, move/rotate/scale gizmos,
 * pan, zoom, grid snapping, rect/circle creation and tilemap painting.
 *
 * One touch = tool action, two fingers = pan + pinch zoom (exactly like mobile 2D editors).
 */
class ViewportController(
    context: Context,
    private val engine: Engine,
    private val state: EditorState,
    private val callbacks: Callbacks
) : GLSurfaceView(context) {

    interface Callbacks {
        fun onSelectionChanged(go: GameObject?)
        fun onSceneEdited(commit: Boolean)
        fun onContextMenu(go: GameObject?, worldX: Float, worldY: Float)
        fun onStatus(text: String)
    }

    var renderer2d: SceneRenderer? = null
        private set
    private var dragging = false
    private var draggingGizmo = -1 // 0 = body, 1 = X, 2 = Y, 3 = rotate, 4 = scale
    private var draggingObject: GameObject? = null
    private var startWorldX = 0f
    private var startWorldY = 0f
    private var grabOffsetX = 0f
    private var grabOffsetY = 0f
    private var startRotation = 0f
    private var startScaleX = 1f
    private var startScaleY = 1f
    private var startAngleToPointer = 0f
    private var startDistance = 1f
    private var panning = false
    private var panX = 0f
    private var panY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var tapStart = 0L
    private var multiTouch = false

    /** Grid size the tile brush snaps to (world units). */
    var brushTile = 0
    var brushRadius = 0

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = 1f / detector.scaleFactor
            state.view.size = (state.view.size * factor).coerceIn(0.25f, 80f)
            return true
        }
    })

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(object : GLSurfaceView.Renderer {
            override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
                val r = renderer2d ?: SceneRenderer.forEngine(engine, state).also { renderer2d = it }
                r.editor = state
                r.uiSystem = engine.ui
                r.initGl()
            }

            override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, width: Int, height: Int) {
                GLES20.glViewport(0, 0, width, height)
                state.view.widthPx = width
                state.view.heightPx = height
                renderer2d?.resize(width, height)
            }

            override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
                val r = renderer2d ?: return
                val dt = engine.frameDelta()
                synchronized(engine.lock) {
                    engine.tick(dt)
                    val view = state.view
                    view.cx = (state.view.cx + panX)
                    view.cy = (state.view.cy + panY)
                    engine.updateGameView()
                    r.render(engine.scene, dt, engine.backgroundColor(), drawUi = true)
                    engine.drawCalls = r.renderer.stats.drawCalls
                    engine.renderMs = r.renderer.frameMilliseconds
                }
                panX = 0f
                panY = 0f
            }
        })
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    /**
     * Captures the viewport into a bitmap on the GL thread (editor "screenshot" command and
     * asset previews).
     */
    fun screenshot(onReady: (android.graphics.Bitmap) -> Unit) {
        queueEvent {
            val w = width.coerceAtLeast(1)
            val h = height.coerceAtLeast(1)
            val buffer = java.nio.ByteBuffer.allocateDirect(w * h * 4).order(java.nio.ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
            val pixels = IntArray(w * h)
            buffer.asIntBuffer().get(pixels)
            for (y in 0 until h / 2) {
                val top = y * w
                val bottom = (h - 1 - y) * w
                for (x in 0 until w) {
                    val t = pixels[top + x]
                    pixels[top + x] = pixels[bottom + x]
                    pixels[bottom + x] = t
                }
            }
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.setPixels(pixels, 0, w, 0, 0, w, h)
            post { onReady(bmp) }
        }
    }

    // ---------------------------------------------------------------- touch
    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        val vx = e.x
        val vy = e.y
        val wx = screenToWorldX(vx)
        val wy = screenToWorldY(vy)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                moved = false
                multiTouch = false
                tapStart = System.currentTimeMillis()
                lastX = vx; lastY = vy
                startWorldX = wx; startWorldY = wy
                val hit = hitTest(wx, wy)
                draggingGizmo = gizmoAt(vx, vy, hit)
                if (state.tool == Tool.HAND) {
                    panning = true
                } else if (hit != null) {
                    if (!state.isSelected(hit.id) && state.tool != Tool.TILE_PAINT) {
                        val keepMulti = false
                        callbacks.onSelectionChanged(hit)
                        state.select(hit.id)
                        callbacks.onSceneEdited(false)
                    }
                    draggingObject = hit
                    grabOffsetX = wx - hit.x
                    grabOffsetY = wy - hit.y
                    startRotation = hit.rotation
                    startScaleX = hit.scaleX
                    startScaleY = hit.scaleY
                    startAngleToPointer = Math.toDegrees(
                        kotlin.math.atan2((wy - hit.y).toDouble(), (wx - hit.x).toDouble())
                    ).toFloat()
                    startDistance = kotlin.math.hypot((wx - hit.x).toDouble(), (wy - hit.y).toDouble()).toFloat()
                    dragging = draggingGizmo == 0 || state.tool == Tool.MOVE || state.tool == Tool.ROTATE || state.tool == Tool.SCALE
                } else {
                    when (state.tool) {
                        Tool.RECT, Tool.CIRCLE, Tool.POLYGON, Tool.SPAWN -> createAt(wx, wy)
                        Tool.TILE_PAINT, Tool.TILE_ERASE, Tool.TILE_FILL, Tool.TILE_RECT -> paintAt(wx, wy)
                        else -> {
                            state.marqueeActive = true
                            state.marqueeX0 = wx; state.marqueeY0 = wy
                            state.marqueeX1 = wx; state.marqueeY1 = wy
                        }
                    }
                }
                val np = e.pointerCount
                if (np >= 2) {
                    panning = true
                    multiTouch = true
                    state.marqueeActive = false
                }
                requestRender()
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                panning = true
                multiTouch = true
                state.marqueeActive = false
                panX += 0f
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = vx - lastX
                val dy = vy - lastY
                lastX = vx; lastY = vy
                if (abs(dx) + abs(dy) > dp(6)) moved = true
                when {
                    e.pointerCount >= 2 || panning && state.tool == Tool.HAND -> {
                        // two-finger pan in world units, respecting the view scale
                        val ppu = state.view.pixelsPerUnit
                        panX -= dx / ppu
                        panY += dy / ppu
                    }
                    draggingGizmo == 3 -> rotateTo(wx, wy)
                    draggingGizmo == 4 -> scaleTo(wx, wy)
                    dragging && draggingObject != null -> moveSelected(wx, wy)
                    state.marqueeActive -> {
                        state.marqueeX1 = wx; state.marqueeY1 = wy
                    }
                    state.tool == Tool.TILE_PAINT || state.tool == Tool.TILE_ERASE -> paintAt(wx, wy)
                    else -> Unit
                }
                callbacks.onStatus("x=${fmt(wx)}  y=${fmt(wy)}  zoom=${fmt(1f / state.view.size)}")
                requestRender()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (state.marqueeActive) {
                    selectInMarquee()
                    state.marqueeActive = false
                }
                if (dragging || draggingGizmo >= 0) callbacks.onSceneEdited(true)
                val quickTap = !moved && System.currentTimeMillis() - tapStart < 260
                if (quickTap && !multiTouch && state.tool == Tool.SELECT && hitTest(wx, wy) == null) {
                    state.clearSelection()
                    callbacks.onSelectionChanged(null)
                }
                if (quickTap && e.pointerCount == 1 && engine.mode == Engine.Mode.EDIT) {
                    val hit = hitTest(wx, wy)
                    if (hit != null && state.tool == Tool.SELECT) callbacks.onSelectionChanged(hit)
                }
                dragging = false
                draggingGizmo = -1
                draggingObject = null
                panning = false
                multiTouch = false
                requestRender()
            }
        }
        return true
    }

    fun longPress(x: Float, y: Float) {
        val wx = screenToWorldX(x)
        val wy = screenToWorldY(y)
        callbacks.onContextMenu(hitTest(wx, wy), wx, wy)
    }

    // ---------------------------------------------------------------- coordinate helpers
    fun screenToWorldX(px: Float): Float = state.view.screenToWorldX(px)

    fun screenToWorldY(py: Float): Float = state.view.screenToWorldY(py)

    /** Object under the touch (box test against sprite/collider bounds, topmost first). */
    fun hitTest(wx: Float, wy: Float): GameObject? {
        val objects = engine.scene.renderOrder()
        for (i in objects.indices.reversed()) {
            val go = objects[i]
            if (go.destroyed || !go.active) continue
            val w = go.computeWorld()
            val sprite = go.components.firstOrNull { it is SpriteRenderer } as? SpriteRenderer
            val col = go.components.firstOrNull { it is Collider2D } as? Collider2D
            val hw: Float
            val hh: Float
            if (sprite != null) {
                hw = abs(sprite.width * go.scaleX) * 0.5f
                hh = abs(sprite.height * go.scaleY) * 0.5f
            } else if (col != null) {
                val he = col.worldHalfExtents()
                hw = he[0]; hh = he[1]
            } else continue
            val local = worldToLocal(w.tx, w.ty, w.rotationDeg, wx, wy)
            if (abs(local[0]) <= hw && abs(local[1]) <= hh) return go
        }
        return null
    }

    private fun worldToLocal(cx: Float, cy: Float, rotDeg: Float, x: Float, y: Float): FloatArray {
        val rad = Math.toRadians((-rotDeg).toDouble())
        val c = kotlin.math.cos(rad).toFloat()
        val s = kotlin.math.sin(rad).toFloat()
        val dx = x - cx
        val dy = y - cy
        return floatArrayOf(dx * c - dy * s, dx * s + dy * c)
    }

    private fun gizmoAt(vx: Float, vy: Float, hit: GameObject?): Int {
        if (state.tool == Tool.SELECT || state.tool == Tool.HAND) return 0
        val go = hit ?: return -1
        if (!state.isSelected(go.id)) return 0
        val g = go.computeWorld()
        val glyph = state.gizmoLength()
        val hx = state.view.worldToScreenX(g.tx + glyph)
        val hy = state.view.worldToScreenY(g.ty)
        val vxAxis = state.view.worldToScreenX(g.tx)
        val vyAxis = state.view.worldToScreenY(g.ty + glyph)
        if (hypotF(vx - hx, vy - hy) < dp(24)) return 1
        if (hypotF(vx - vxAxis, vy - vyAxis) < dp(24)) return 2
        val scaleX = state.view.worldToScreenX(g.tx + glyph * 0.7f)
        val scaleY = state.view.worldToScreenY(g.ty - glyph * 0.7f)
        if (hypotF(vx - scaleX, vy - scaleY) < dp(24)) return 4
        return 0
    }

    private fun hypotF(dx: Float, dy: Float) = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    // ---------------------------------------------------------------- editing operations
    private fun moveSelected(wx: Float, wy: Float) {
        val go = draggingObject ?: return
        var nx = wx - grabOffsetX
        var ny = wy - grabOffsetY
        if (state.snapToGrid) {
            val snapped = state.snap(nx, ny)
            nx = snapped[0]; ny = snapped[1]
        }
        if (state.activeAxis == 1) ny = go.y
        if (state.activeAxis == 2) nx = go.x
        if (go.parent == null) {
            go.x = nx; go.y = ny
        } else {
            go.setWorldPosition(nx, ny)
        }
        callbacks.onSceneEdited(false)
    }

    private fun rotateTo(wx: Float, wy: Float) {
        val go = draggingObject ?: return
        val angle = Math.toDegrees(kotlin.math.atan2((wy - go.y).toDouble(), (wx - go.x).toDouble())).toFloat()
        val delta = angle - startAngleToPointer
        go.rotation = state.snapAngle(startRotation + delta)
        callbacks.onSceneEdited(false)
    }

    private fun scaleTo(wx: Float, wy: Float) {
        val go = draggingObject ?: return
        val d = kotlin.math.hypot((wx - go.x).toDouble(), (wy - go.y).toDouble()).toFloat()
        val factor = if (startDistance > 0.001f) (d / startDistance).coerceIn(0.02f, 50f) else 1f
        go.scaleX = startScaleX * factor
        go.scaleY = startScaleY * factor
        callbacks.onSceneEdited(false)
    }

    private fun selectInMarquee() {
        val r = Rect2(
            minOf(state.marqueeX0, state.marqueeX1),
            minOf(state.marqueeY0, state.marqueeY1),
            abs(state.marqueeX1 - state.marqueeX0),
            abs(state.marqueeY1 - state.marqueeY0)
        )
        val ids = ArrayList<Long>()
        for (go in engine.scene.objects) {
            if (go.destroyed || !go.active) continue
            val sprite = go.components.firstOrNull { it is SpriteRenderer } as? SpriteRenderer
            val hw = (sprite?.width ?: 1f) * abs(go.scaleX) * 0.5f
            val hh = (sprite?.height ?: 1f) * abs(go.scaleY) * 0.5f
            val w = go.computeWorld()
            if (w.tx + hw >= r.x && w.tx - hw <= r.right && w.ty + hh >= r.y && w.ty - hh <= r.bottom) ids.add(go.id)
        }
        state.selectMany(ids.toLongArray())
        callbacks.onSelectionChanged(ids.firstOrNull()?.let { engine.scene.findById(it) })
    }

    private fun createAt(wx: Float, wy: Float) {
        val p = if (state.snapToGrid) state.snap(wx, wy) else floatArrayOf(wx, wy)
        val name = when (state.tool) {
            Tool.RECT -> "Box"; Tool.CIRCLE -> "Circle"; Tool.POLYGON -> "Polygon"; Tool.SPAWN -> "Spawn"; else -> "Object"
        }
        val go = engine.scene.create(engine.scene.uniqueName(name), state.selectedId.let { engine.scene.findById(it) })
        go.x = p[0]; go.y = p[1]
        val sprite = SpriteRenderer()
        when (state.tool) {
            Tool.RECT -> { sprite.shape = SpriteRenderer.SHAPE_SQUARE; sprite.width = 1f; sprite.height = 1f }
            Tool.CIRCLE -> { sprite.shape = SpriteRenderer.SHAPE_CIRCLE; sprite.width = 1f; sprite.height = 1f }
            Tool.POLYGON -> { sprite.shape = SpriteRenderer.SHAPE_TRIANGLE; sprite.width = 1f; sprite.height = 1f }
            else -> { sprite.shape = SpriteRenderer.SHAPE_SQUARE; sprite.color = 0x5533FF88; sprite.width = 0.6f; sprite.height = 0.6f }
        }
        go.add(sprite)
        state.select(go.id)
        callbacks.onSelectionChanged(go)
        callbacks.onSceneEdited(true)
        requestRender()
    }

    private fun paintAt(wx: Float, wy: Float) {
        val tilemapGo = engine.scene.objects.firstOrNull { it.getAny<TilemapRenderer>() != null } ?: return
        val tm = tilemapGo.getAny<TilemapRenderer>() ?: return
        val data = tm.data ?: return
        val layer: TileLayer = data.layers.getOrNull(state.brushLayer) ?: return
        val w = tilemapGo.computeWorld()
        val local = worldToLocal(w.tx, w.ty, w.rotationDeg, wx, wy)
        val tileW = data.worldSizeOfTile().coerceAtLeast(0.001f)
        val tx = kotlin.math.floor(local[0] / tileW).toInt()
        val ty = kotlin.math.floor(local[1] / tileW).toInt()
        val radius = if (state.brushSize > 1) state.brushSize / 2 else 0
        val value = if (state.tool == Tool.TILE_ERASE) 0 else state.brushTile
        for (ox in -radius..radius) for (oy in -radius..radius) {
            layer.set(tx + ox, ty + oy, value)
        }
        tm.runAutoTileIfEnabled()
        callbacks.onSceneEdited(false)
        requestRender()
    }

    /** Pans the view so the given object is centred (used by "Focus" / double-tap F). */
    fun focus(go: GameObject?) {
        val target = go ?: return
        val w = target.computeWorld()
        state.view.cx = w.tx
        state.view.cy = w.ty
        requestRender()
    }

    fun zoomBy(factor: Float) {
        state.view.size = (state.view.size / factor).coerceIn(0.25f, 80f)
        requestRender()
    }

    fun resetZoom() {
        state.view.size = 5f
        requestRender()
    }
}
