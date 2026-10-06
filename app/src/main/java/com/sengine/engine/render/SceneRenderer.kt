package com.sengine.engine.render

import android.opengl.GLES20
import com.sengine.engine.core.Camera2D
import com.sengine.engine.core.CameraAttach
import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Parallax
import com.sengine.engine.core.PhysicsLayers
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.core.Scene
import com.sengine.engine.core.SpriteRenderer
import com.sengine.engine.core.TextRenderer
import com.sengine.engine.fx.ParticleEmitter
import com.sengine.engine.fx.Trail2D
import com.sengine.engine.lighting.AmbientLight2D
import com.sengine.engine.lighting.Light2D
import com.sengine.engine.lighting.LightOccluder2D
import com.sengine.engine.lighting.LightSystem
import com.sengine.engine.math.Colors
import com.sengine.engine.math.Affine
import com.sengine.engine.math.M
import com.sengine.engine.math.Rect2
import com.sengine.engine.tilemap.TileLayer
import com.sengine.engine.tilemap.TilemapRenderer
import com.sengine.engine.ui.UiCanvas
import com.sengine.engine.ui.UiCommand
import com.sengine.engine.ui.UiDrawList
import com.sengine.engine.ui.UiTextAlign
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws a whole 2D scene: tiles, sprites, text, particles, trails, lights, post effects and the
 * UI layer, with camera follow/zoom/shake, parallax, culling, sorting and pixel-perfect scaling.
 *
 * Rendering order (all strictly 2D):
 *  1. tiles / sprites / particles / trails sorted by (sorting layer, order, Y),
 *  2. the light map is rendered off-screen and multiplied over the scene,
 *  3. post effects (bloom, vignette, chromatic, CRT, shockwave...) composite to the screen,
 *  4. UI canvases and HUD sprites in screen space,
 *  5. editor overlays (grid, colliders, gizmos) when the editor asks for them.
 */
class SceneRenderer {

    lateinit var renderer: Renderer2D
    lateinit var materials: MaterialLibrary
    lateinit var textures: TextureCache
    lateinit var lighting: Lighting2D
    lateinit var post: PostProcessor
    lateinit var lightSystem: LightSystem

    /** When set, the renderer draws editor overlays and follows the editor's view. */
    var editor: EditorState? = null

    /** Resolution scale: 1 = native, 0.5 = half resolution (mobile battery/performance knob). */
    var resolutionScale = 1f
    var culling = true
    /** Sorts sprites inside a layer by their Y position (side-view and top-down games). */
    var sortByY = true
    /** Enables the 2D light map pass. */
    var lightingEnabled = true
    /** Enables post processing (bloom etc). */
    var postEnabled = true

    var time = 0f
        private set
    var frame = 0L
        private set
    var drawnObjects = 0
        private set
    /** World-space rectangle of the last frame (culling + light gathering). */
    val lastView = Rect2(0f, 0f, 0f, 0f)

    var activeCamera: GameObject? = null
        private set

    /** Sorting layer name -> index cache, rebuilt when the scene layers change. */
    private val layerOrder = HashMap<String, Int>()

    private class DrawItem {
        var go: GameObject? = null
        var sprite: SpriteRenderer? = null
        var text: TextRenderer? = null
        var emitter: ParticleEmitter? = null
        var trail: Trail2D? = null
        var tilemap: TilemapRenderer? = null
        var layerIndex = 0
        var order = 0
        var sortY = 0f
        var kind = KIND_SPRITE
        var world = Affine()

        companion object {
            const val KIND_SPRITE = 0
            const val KIND_TEXT = 1
            const val KIND_PARTICLE = 2
            const val KIND_TRAIL = 3
            const val KIND_TILEMAP = 4
        }
    }

    private val items = ArrayList<DrawItem>(512)
    private val pool = ArrayList<DrawItem>(512)
    private var poolIndex = 0

    private val viewStorage = View2D()

    /** The UI system that produced the draw lists (set by the engine/editor). */
    var uiSystem: com.sengine.engine.ui.UiSystem? = null

    /** HUD units per pixel for screen-space sprites and text. */
    var hudPixelsPerUnit = 100f

    companion object {
        /**
         * Builds a renderer wired to an engine: GL state, texture cache, materials, fonts,
         * 2D lighting and the post-processing chain. Call from the GL thread (surface created).
         */
        fun forEngine(engine: com.sengine.engine.Engine, editor: EditorState? = null): SceneRenderer {
            val r = SceneRenderer()
            val shaders = ShaderLibrary()
            val textures = TextureCache(engine.project)
            val materials = MaterialLibrary(engine.project)
            val fonts = FontCache(engine.project, textures)
            r.materials = materials
            r.textures = textures
            r.renderer = Renderer2D()
            r.renderer.shaders = shaders
            r.renderer.materials = materials
            r.renderer.textures = textures
            r.renderer.fonts = fonts
            r.lighting = Lighting2D()
            r.post = PostProcessor()
            r.lightSystem = engine.lighting
            r.uiSystem = engine.ui
            r.editor = editor
            engine.renderer = r
            return r
        }
    }

    /** GL initialisation; must run on the GL thread with a current context. */
    fun initGl() {
        renderer.shaders.init()
        textures.initGl()
        materials.clear()
        lighting.ensureTarget(renderer.screenWidth.toInt().coerceAtLeast(16), renderer.screenHeight.toInt().coerceAtLeast(16))
        post.ensureTargets(renderer.screenWidth.toInt().coerceAtLeast(16), renderer.screenHeight.toInt().coerceAtLeast(16))
    }

    fun resize(width: Int, height: Int) {
        renderer.resize(width, height)
        post.ensureTargets(width.coerceAtLeast(16), height.coerceAtLeast(16))
        lighting.ensureTarget((width * 0.5f).toInt().coerceAtLeast(16), (height * 0.5f).toInt().coerceAtLeast(16))
    }

    fun releaseGl() {
        post.release()
        lighting.release()
        textures.release()
        renderer.release()
    }


    // ------------------------------------------------------------------ public API

    fun render(scene: Scene, dt: Float, background: Int, drawUi: Boolean = true) {
        time += dt
        frame++
        val camGo = findCamera(scene)
        activeCamera = camGo
        val camera = camGo?.getAny<Camera2D>()
        val bg = camera?.background ?: background

        val view = editor?.view ?: viewStorage
        view.widthPx = renderer.screenWidth.toInt().coerceAtLeast(1)
        view.heightPx = renderer.screenHeight.toInt().coerceAtLeast(1)
        if (editor == null) updateView(scene, view, camGo, camera, dt)

        renderer.pixelsPerUnit = view.pixelsPerUnit.coerceAtLeast(0.0001f)
        lastView.set(view.cx - view.halfW, view.cy - view.halfH, view.halfW * 2f, view.halfH * 2f)

        gather(scene, view)

        // ---- render targets
        val fbW = renderer.screenWidth.toInt().coerceAtLeast(1)
        val fbH = renderer.screenHeight.toInt().coerceAtLeast(1)
        val scaledW = (fbW * resolutionScale).toInt().coerceAtLeast(16)
        val scaledH = (fbH * resolutionScale).toInt().coerceAtLeast(16)
        post.enabled = postEnabled && (camera == null || camera.postFx != 0) || post.shockwaveAmount > 0.001f
        post.ensureTargets(scaledW, scaledH)
        lighting.enabled = lightingEnabled
        lighting.ensureTarget(fbW, fbH)
        post.time = time
        post.configure(PostProcessor.Effect.of(camera?.postFx ?: 0), camera?.postIntensity ?: 1f)

        post.beginWorld(renderer, bg)

        // ---- world pass
        renderer.setOrtho(renderer.projection, view.cx - view.halfW, view.cx + view.halfW, view.cy - view.halfH, view.cy + view.halfH)
        renderer.setBlend(Renderer2D.BLEND_PREMULTIPLIED)
        post.overrideProjection(renderer, view)
        drawWorld(scene, view, dt)

        // ---- lighting pass
        renderer.flush()
        val ambient = sceneAmbient(scene)
        lighting.enabled = lightingEnabled && ambient.showLights
        if (lighting.enabled) {
            lightSystem.ambient = ambient
            lightSystem.gather(scene, lastView, time)
            lighting.render(renderer, lightSystem, lastView, bg, ambient.color, ambient.intensity)
            // multiply the light map over the scene target
            renderer.flush()
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, post.sceneFbo)
            GLES20.glViewport(0, 0, post.sceneWidth.coerceAtLeast(1), post.sceneHeight.coerceAtLeast(1))
            renderer.setScreenProjection(renderer.screenWidth, renderer.screenHeight)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_DST_COLOR, GLES20.GL_ZERO)
            renderer.blit(lighting.target?.texture ?: 0, renderer.shaders.blit)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            renderer.setBlend(Renderer2D.BLEND_PREMULTIPLIED)
            renderer.setOrtho(renderer.projection, view.cx - view.halfW, view.cx + view.halfW, view.cy - view.halfH, view.cy + view.halfH)
        }

        // ---- world-space editor overlays
        editor?.let { e ->
            if (e.showGrid) drawGrid(view, e)
            if (e.showColliders) drawColliders(scene)
            if (e.showTileGrid) drawTileGrid(scene, view)
            if (e.showLightGizmos) drawLightGizmos(scene)
            if (e.showGizmos) drawSelectionGizmos(scene, e, view)
            drawEditorBrush(e)
        }

        // ---- post process + present
        post.present(renderer, post.lastEffect, post.lastIntensity)

        // ---- screen-space pass
        renderer.setScreenProjection(renderer.screenWidth, renderer.screenHeight)
        renderer.setBlend(Renderer2D.BLEND_PREMULTIPLIED)
        drawScreenSpace(scene, view)
        if (drawUi && editor == null) drawUi(scene, uiSystem)
        renderer.endFrame()
    }

    // ------------------------------------------------------------------ camera

    private fun findCamera(scene: Scene): GameObject? =
        scene.objects.firstOrNull { !it.destroyed && it.isActiveInHierarchy() && it.getAny<Camera2D>() != null }

    private fun updateView(scene: Scene, view: View2D, camGo: GameObject?, camera: Camera2D?, dt: Float) {
        if (camera == null || camGo == null) {
            view.size = 5f
            view.rotation = 0f
            return
        }
        view.size = camera.size
        val target = scene.findOrNull(camera.follow)
        var tx: Float
        var ty: Float
        if (target != null) {
            tx = target.worldX() + camera.offsetX
            ty = target.worldY() + camera.offsetY
            val rb = target.get<Rigidbody2D>()
            if (rb != null) {
                tx += rb.vx * camera.lookAheadX * 0.35f
                ty += rb.vy * camera.lookAheadY * 0.2f
            }
        } else {
            tx = camGo.worldX() + camera.offsetX
            ty = camGo.worldY() + camera.offsetY
        }
        // dead zone
        if (camera.deadZoneX > 0f && abs(tx - view.cx) < camera.deadZoneX) tx = view.cx
        if (camera.deadZoneY > 0f && abs(ty - view.cy) < camera.deadZoneY) ty = view.cy
        // smoothing (frame-rate independent exponential follow)
        val k = if (camera.smoothing <= 0f) 1f else (1f - Math.exp((-camera.smoothing * dt).toDouble())).toFloat().coerceIn(0f, 1f)
        view.cx += (tx - view.cx) * k
        view.cy += (ty - view.cy) * k
        // limits
        if (camera.limitWidth > 0f && camera.limitHeight > 0f) {
            val minX = camera.limitX + view.halfW
            val maxX = camera.limitX + camera.limitWidth - view.halfW
            val minY = camera.limitY + view.halfH
            val maxY = camera.limitY + camera.limitHeight - view.halfH
            view.cx = if (minX > maxX) camera.limitX + camera.limitWidth * 0.5f else M.clamp(view.cx, minX, maxX)
            view.cy = if (minY > maxY) camera.limitY + camera.limitHeight * 0.5f else M.clamp(view.cy, minY, maxY)
        }
        // screen shake (decaying noise, deterministic so it is reproducible in tests)
        if (camera.shake > 0.001f) {
            val s = camera.shake
            val t = time * 37f
            view.cx += sin(t * 3.1f) * s * 0.35f
            view.cy += cos(t * 2.7f) * s * 0.28f
            view.rotation = camera.rotation + sin(t * 4.3f) * s * 0.6f
            camera.shake = maxOf(0f, camera.shake - camera.shakeDecay * dt * camera.shake)
        } else {
            camera.shake = 0f
            view.rotation = camera.rotation
        }
        view.pixelPerfect = camera.pixelPerfect
        camera.viewWidth = view.halfW * 2f
        camera.viewHeight = view.halfH * 2f
        camera.targetX = tx
        camera.targetY = ty
        if (camera.pixelPerfect) view.applyPixelSnap()
    }

    // ------------------------------------------------------------------ gathering & sorting

    private fun layerIndexOf(scene: Scene, name: String): Int {
        val cached = layerOrder[name]
        if (cached != null) return cached
        val i = scene.layerIndex(name)
        layerOrder[name] = i
        return i
    }

    private fun obtain(kind: Int): DrawItem {
        while (poolIndex < pool.size) {
            val item = pool[poolIndex]
            if (item.go == null) return item
            poolIndex++
        }
        val item = DrawItem()
        item.kind = kind
        pool.add(item)
        return item
    }

    private fun gather(scene: Scene, view: View2D) {
        items.clear()
        poolIndex = 0
        for (item in pool) item.go = null
        val cull = view.cullBounds(3f)
        for (go in scene.objects) {
            if (go.destroyed || !go.isActiveInHierarchy()) continue
            val world = go.computeWorld()
            val sprite = go.getAny<SpriteRenderer>()
            val text = go.getAny<TextRenderer>()
            val emitter = go.getAny<ParticleEmitter>()
            val trail = go.getAny<Trail2D>()
            val tilemap = go.getAny<TilemapRenderer>()
            if (sprite == null && text == null && emitter == null && trail == null && tilemap == null) continue
            if (tilemap != null) {
                val item = obtain(DrawItem.KIND_TILEMAP)
                item.go = go; item.tilemap = tilemap
                item.layerIndex = layerIndexOf(scene, tilemap.sortingLayer)
                item.order = tilemap.order
                item.sortY = world.ty
                item.world.set(world)
                items.add(item)
            }
            if (sprite != null && sprite.visible && !sprite.screenSpace) {
                if (!culling || isVisible(sprite, world, cull)) {
                    val item = obtain(DrawItem.KIND_SPRITE)
                    item.go = go; item.sprite = sprite
                    item.layerIndex = layerIndexOf(scene, go.sortingLayer)
                    item.order = go.order
                    item.sortY = world.ty
                    item.world.set(world)
                    items.add(item)
                } else renderer.stats.culled++
            }
            if (text != null && text.visible && !text.screenSpace) {
                val item = obtain(DrawItem.KIND_TEXT)
                item.go = go; item.text = text
                item.layerIndex = layerIndexOf(scene, go.sortingLayer)
                item.order = go.order
                item.sortY = world.ty
                item.world.set(world)
                items.add(item)
            }
            if (emitter != null && emitter.visible) {
                val item = obtain(DrawItem.KIND_PARTICLE)
                item.go = go; item.emitter = emitter
                item.layerIndex = layerIndexOf(scene, go.sortingLayer)
                item.order = go.order + emitter.sortOrder
                item.sortY = world.ty
                item.world.set(world)
                items.add(item)
            }
            if (trail != null) {
                val item = obtain(DrawItem.KIND_TRAIL)
                item.go = go; item.trail = trail
                item.layerIndex = layerIndexOf(scene, go.sortingLayer)
                item.order = go.order + trail.order
                item.sortY = world.ty
                item.world.set(world)
                items.add(item)
            }
        }
        val ySort = sortByY
        items.sortWith { a, b ->
            var c = a.layerIndex - b.layerIndex
            if (c != 0) return@sortWith c
            c = a.order - b.order
            if (c != 0) return@sortWith c
            if (ySort) {
                c = if (a.sortY < b.sortY) 1 else if (a.sortY > b.sortY) -1 else 0
                if (c != 0) return@sortWith c
            }
            ((a.go?.id ?: 0L) - (b.go?.id ?: 0L)).toInt()
        }
        drawnObjects = items.size
    }

    private fun isVisible(sprite: SpriteRenderer, world: Affine, cull: Rect2): Boolean {
        val hw = sprite.width * world.scaleX * 0.5f + 0.5f
        val hh = sprite.height * world.scaleY * 0.5f + 0.5f
        return world.tx + hw >= cull.left && world.tx - hw <= cull.right &&
            world.ty + hh >= cull.top && world.ty - hh <= cull.bottom
    }

    // ------------------------------------------------------------------ world drawing

    private fun drawWorld(scene: Scene, view: View2D, dt: Float) {
        for (item in items) {
            val go = item.go ?: continue
            when (item.kind) {
                DrawItem.KIND_TILEMAP -> item.tilemap?.let { drawTilemap(it, go, view) }
                DrawItem.KIND_SPRITE -> item.sprite?.let { drawSprite(it, item.world, go, view) }
                DrawItem.KIND_TEXT -> item.text?.let { drawText(it, item.world, view) }
                DrawItem.KIND_PARTICLE -> item.emitter?.let { drawEmitter(it, go, view) }
                DrawItem.KIND_TRAIL -> item.trail?.let { drawTrail(it, item.world) }
            }
        }
    }

    private fun parallaxOffset(go: GameObject, world: Affine, view: View2D, parallax: Parallax): FloatArray {
        val px = world.tx * parallax.factorX + view.cx * (1f - parallax.factorX)
        val py = world.ty * parallax.factorY + view.cy * (1f - parallax.factorY)
        var ox = px + parallax.scroll * parallax.autoScrollX
        var oy = py + parallax.scroll * parallax.autoScrollY
        if (parallax.repeatX && parallax.repeatWidth > 0.01f) {
            val base = view.cx - view.halfW
            val rel = (ox - base) % parallax.repeatWidth
            ox = base + (if (rel < 0) rel + parallax.repeatWidth else rel)
        }
        if (parallax.repeatY && parallax.repeatHeight > 0.01f) {
            val base = view.cy - view.halfH
            val rel = (oy - base) % parallax.repeatHeight
            oy = base + (if (rel < 0) rel + parallax.repeatHeight else rel)
        }
        return floatArrayOf(ox - world.tx, oy - world.ty)
    }

    private fun drawSprite(sprite: SpriteRenderer, world: Affine, go: GameObject, view: View2D) {
        val parallax = go.getAny<Parallax>()
        val w = Affine()
        w.set(world)
        if (parallax != null) {
            val off = parallaxOffset(go, world, view, parallax)
            w.tx += off[0]
            w.ty += off[1]
            val repeats = if (parallax.repeatX) maxOf(1, (view.halfW * 2f / parallax.repeatWidth).toInt() + 1) else 1
            for (i in 0 until repeats) {
                drawSpriteOnce(sprite, w, go)
                w.tx += parallax.repeatWidth
            }
        } else {
            drawSpriteOnce(sprite, w, go)
        }
    }

    private fun drawSpriteOnce(sprite: SpriteRenderer, world: Affine, go: GameObject) {
        var region: TexRegion? = null
        var u0 = 0f; var v0 = 1f; var u1 = 1f; var v1 = 0f
        if (sprite.shape == SpriteRenderer.SHAPE_TEXTURE || sprite.shape == SpriteRenderer.SHAPE_NINE_SLICE) {
            region = textures.region(sprite.texture)
            if (region == null && sprite.texture.isNotBlank()) region = missingRegion(sprite.texture)
            if (region != null && !sprite.useFrameUv && (sprite.sheetColumns > 1 || sprite.sheetRows > 1)) {
                val cols = sprite.sheetColumns.coerceAtLeast(1)
                val rows = sprite.sheetRows.coerceAtLeast(1)
                u0 = sprite.frameX.toFloat() / cols
                u1 = (sprite.frameX + 1f) / cols
                v0 = 1f - sprite.frameY.toFloat() / rows
                v1 = 1f - (sprite.frameY + 1f) / rows
            }
        }
        val base = if (sprite.material.isNotBlank()) materials.material(sprite.material) else null
        val material = if (base != null && sprite.shaderParam != 1f) {
            // Per-object runtime override (hit flash, dissolve progress...). The shared material is
            // never mutated - the instance caches the override for this object only.
            val inst = materials.instance(sprite.material, "go" + go.id)
            for (key in materials.RUNTIME_PARAMS) {
                val v = base.floats[key] ?: continue
                inst.floats[key] = v * sprite.shaderParam
            }
            inst
        } else base
        val program = materials.bind(renderer.shaders, material, time, 1f / maxOf(1, region?.tex?.w ?: 1), 1f / maxOf(1, region?.tex?.h ?: 1))
        val blend = if (sprite.additive) Renderer2D.BLEND_ADDITIVE else Renderer2D.BLEND_PREMULTIPLIED
        if (sprite.shape == SpriteRenderer.SHAPE_TEXTURE || sprite.shape == SpriteRenderer.SHAPE_NINE_SLICE) {
            if (region != null && !sprite.useFrameUv && (sprite.sheetColumns > 1 || sprite.sheetRows > 1)) {
                // sheet frames are drawn with explicit UVs, bypassing the region rect
                val w = sprite.width * world.scaleX
                val h = sprite.height * world.scaleY
                renderer.quad(
                    world.tx, world.ty, w, h, world.rotationDeg, sprite.pivotX, sprite.pivotY,
                    u0, v0, u1, v1, sprite.color, region.tex, program, sprite.flipX, sprite.flipY,
                    material?.name, blend
                )
                renderer.stats.sprites++
            } else {
                renderer.sprite(sprite, world, region, material?.name, program, blend = blend)
            }
        } else {
            // procedural 2D shapes: square, circle, triangle, capsule, rounded box
            drawProceduralSprite(sprite, world)
        }
    }

    private val missingWarned = HashSet<String>()

    private fun missingRegion(name: String): TexRegion? {
        val tex = textures.missing()
        if (missingWarned.size < 32 && missingWarned.add(name)) {
            android.util.Log.w(GL.TAG, "Missing texture asset: $name")
        }
        return TexRegion(tex, 0f, 1f, 1f, 0f, 0, 0, tex.w, tex.h)
    }

    private fun drawProceduralSprite(sprite: SpriteRenderer, world: Affine) {
        val w = sprite.width * world.scaleX
        val h = sprite.height * world.scaleY
        when (sprite.shape) {
            SpriteRenderer.SHAPE_CIRCLE -> renderer.circle(world.tx, world.ty, minOf(w, h) * 0.5f, sprite.color)
            SpriteRenderer.SHAPE_CAPSULE -> renderer.shape(
                world.tx - w * 0.5f, world.ty - h * 0.5f, w, h, minOf(w, h) * 0.5f, sprite.color, rotation = world.rotationDeg
            )
            SpriteRenderer.SHAPE_TRIANGLE -> {
                val r = minOf(w, h) * 0.5f
                renderer.triangle(world.tx, world.ty + r, world.tx - r, world.ty - r, world.tx + r, world.ty - r, sprite.color)
            }
            else -> renderer.shape(
                world.tx - w * 0.5f, world.ty - h * 0.5f, w, h, minOf(w, h) * sprite.roundness * 0.5f, sprite.color,
                rotation = world.rotationDeg
            )
        }
    }

    private fun drawText(text: TextRenderer, world: Affine, view: View2D) {
        val size = text.size * world.scaleY
        val lines = text.text.split('\n').size
        val blockHeight = size * text.lineSpacing * lines
        val top = when (text.verticalAlign) {
            1 -> world.ty - blockHeight * 0.5f
            2 -> world.ty - blockHeight
            else -> world.ty
        }
        if (text.shadow) {
            renderer.text(
                text.text, world.tx + text.shadowOffsetX * world.scaleX, top + text.shadowOffsetY * world.scaleY, size,
                text.shadowColor, text.align, text.font, text.bold, text.wrapWidth * world.scaleX,
                text.letterSpacing, text.lineSpacing, 0, 0f
            )
        }
        renderer.text(
            text.text, world.tx, top, size, text.color, text.align, text.font, text.bold,
            text.wrapWidth * world.scaleX, text.letterSpacing, text.lineSpacing,
            if (text.outline) text.outlineColor else 0, text.outlineWidth
        )
    }

    private fun drawEmitter(emitter: ParticleEmitter, go: GameObject, view: View2D) {
        val system = emitter.system ?: return
        val region = if (emitter.texture.isNotBlank()) textures.region(emitter.texture) else null
        val world = go.computeWorld()
        // simulation space 0 = world (particles already carry world positions), 1 = local to the object
        if (emitter.simulationSpace == 1) {
            renderer.pushOffset(world.tx, world.ty)
            renderer.particles(system, region, emitter.additive, emitter.softness)
            renderer.popOffset()
        } else {
            renderer.particles(system, region, emitter.additive, emitter.softness)
        }
    }

    private fun drawTrail(trail: Trail2D, world: Affine) {
        renderer.trail(trail, 0f, 0f)
    }

    // ------------------------------------------------------------------ tilemaps

    private fun drawTilemap(tilemap: TilemapRenderer, go: GameObject, view: View2D) {
        val data = tilemap.data ?: return
        if (!tilemap.visible) return
        val origin = go.computeWorld()
        val tileSize = data.worldSizeOfTile()
        val viewRect = lastView.grow(tileSize * 2f)
        for ((layerIndex, layer) in data.layers.withIndex()) {
            if (!layer.visible) continue
            val tileset = data.tilesetOf(layer) ?: continue
            val tex = textures.image(tileset.texture)
            if (tex == null) {
                // keep rendering with the missing-texture checker so the level is still visible
                drawTilesetFallback(data, layer, origin, tileSize)
                continue
            }
            val count = layer.chunkCount()
            if (count == 0) continue
            val layerOpacity = layer.opacity.coerceIn(0f, 1f)
            val tint = Colors.scale(layer.tint, 1f, layerOpacity)
            val region = TexRegion(tex, 0f, 1f, 1f, 0f, 0, 0, tex.w, tex.h)
            val material = if (tilemap.material.isNotBlank()) materials.material(tilemap.material) else null
            val program = materials.bind(renderer.shaders, material, time, 1f / tex.w, 1f / tex.h)
            val blend = if (tilemap.additive) Renderer2D.BLEND_ADDITIVE else Renderer2D.BLEND_PREMULTIPLIED
            val parX = if (layer.parallaxX != 0f) (view.cx - origin.tx) * (1f - layer.parallaxX) else 0f
            val parY = if (layer.parallaxY != 0f) (view.cy - origin.ty) * (1f - layer.parallaxY) else 0f
            layer.forEachIn(
                Rect2(
                    (viewRect.left - origin.tx - parX) / tileSize - 1f,
                    (viewRect.top - origin.ty - parY) / tileSize - 1f,
                    viewRect.w / tileSize + 2f, viewRect.h / tileSize + 2f
                )
            ) { tx, ty, id ->
                val uid = activeTileIndex(tileset, id)
                val uv = tileset.uvFor(uid, tex.w, tex.h)
                val x = origin.tx + parX + tx * tileSize + tileSize * 0.5f
                val y = origin.ty + parY + ty * tileSize + tileSize * 0.5f
                renderer.quad(x, y, tileSize, tileSize, 0f, 0.5f, 0.5f, uv[0], uv[1], uv[2], uv[3], tint, region.tex, program, false, false, material?.name, blend)
                renderer.stats.tileQuads++
            }
        }
    }

    /** Picks the frame of an animated tile for the current time. */
    private fun activeTileIndex(tileset: com.sengine.engine.tilemap.Tileset, index: Int): Int {
        val frames = tileset.animated[index] ?: return index
        if (frames.isEmpty()) return index
        val fps = if (tileset.animationFps <= 0f) 4f else tileset.animationFps
        val f = (time * fps).toInt() % frames.size
        return frames[if (f < 0) f + frames.size else f]
    }

    private fun drawTilesetFallback(data: com.sengine.engine.tilemap.TilemapData, layer: TileLayer, origin: Affine, tileSize: Float) {
        val tex = textures.missing()
        val region = TexRegion(tex, 0f, 1f, 1f, 0f, 0, 0, tex.w, tex.h)
        val tint = Colors.scale(0xFFFF77FF.toInt(), 1f, layer.opacity)
        layer.forEachIn(Rect2((lastView.left - origin.tx) / tileSize - 1f, (lastView.top - origin.ty) / tileSize - 1f, lastView.w / tileSize + 2f, lastView.h / tileSize + 2f)) { tx, ty, _ ->
            renderer.quad(
                origin.tx + tx * tileSize + tileSize * 0.5f, origin.ty + ty * tileSize + tileSize * 0.5f,
                tileSize, tileSize, 0f, 0.5f, 0.5f, 0f, 1f, 1f, 0f, tint, region.tex, null, false, false, null
            )
            renderer.stats.tileQuads++
        }
    }

    // ------------------------------------------------------------------ screen space & UI

    private fun drawScreenSpace(scene: Scene, view: View2D) {
        val cx = renderer.screenWidth * 0.5f
        val cy = renderer.screenHeight * 0.5f
        for (go in scene.objects) {
            if (go.destroyed || !go.isActiveInHierarchy()) continue
            val sprite = go.getAny<SpriteRenderer>() ?: continue
            if (!sprite.visible || !sprite.screenSpace) continue
            val world = go.computeWorld()
            val region = textures.region(sprite.texture)
            val attach = go.getAny<CameraAttach>()
            val x = cx + world.tx + (attach?.offsetX ?: 0f)
            val y = cy - world.ty + (attach?.offsetY ?: 0f)
            val w = sprite.width * world.scaleX * hudPixelsPerUnit
            val h = sprite.height * world.scaleY * hudPixelsPerUnit
            renderer.texturedRect(
                x - w * 0.5f, y - h * 0.5f, w, h, region, sprite.color,
                if (sprite.additive) Renderer2D.BLEND_ADDITIVE else Renderer2D.BLEND_PREMULTIPLIED
            )
        }
        for (go in scene.objects) {
            if (go.destroyed || !go.isActiveInHierarchy()) continue
            val text = go.getAny<TextRenderer>() ?: continue
            if (!text.visible || !text.screenSpace) continue
            val world = go.computeWorld()
            renderer.text(
                text.text, cx + world.tx, cy - world.ty, text.size * hudPixelsPerUnit, text.color, text.align, text.font,
                text.bold, text.wrapWidth * hudPixelsPerUnit, text.letterSpacing, text.lineSpacing,
                if (text.outline) text.outlineColor else 0, text.outlineWidth * hudPixelsPerUnit
            )
        }
    }

    /** Draws every [UiCanvas] of the scene using the draw lists produced by `UiSystem`. */
    fun drawUi(scene: Scene, system: com.sengine.engine.ui.UiSystem? = null) {
        val lists = system?.drawLists
        for (go in scene.objects) {
            if (go.destroyed || !go.isActiveInHierarchy()) continue
            val canvas = go.getAny<UiCanvas>() ?: continue
            val list = lists?.get(go) ?: continue
            drawUiList(canvas, list)
        }
    }

    private fun drawUiList(canvas: UiCanvas, list: UiDrawList) {
        val scale = if (canvas.scale <= 0f) 1f else canvas.scale
        val offX = (renderer.screenWidth - canvas.referenceWidth * scale) * 0.5f
        val offY = (renderer.screenHeight - canvas.referenceHeight * scale) * 0.5f
        if (canvas.backdrop) {
            renderer.shape(0f, 0f, renderer.screenWidth, renderer.screenHeight, 0f, canvas.backdropColor)
        }
        var scissorStack = 0
        for (cmd in list.commands) {
            when (cmd) {
                is UiCommand.Rect -> {
                    val r = cmd
                    renderer.shape(
                        r.x * scale + offX, r.y * scale + offY, r.w * scale, r.h * scale,
                        r.radius * scale, r.color, r.borderWidth * scale, r.borderColor
                    )
                }
                is UiCommand.Text -> {
                    val t = cmd
                    val size = t.size * scale
                    // recompute the text position from its alignment inside the command rect
                    val align = when (t.align) {
                        UiTextAlign.LEFT -> 0
                        UiTextAlign.RIGHT -> 2
                        else -> 1
                    }
                    val textWidth = renderer.measureText(t.text, t.font, t.bold) * (size / 48f)
                    val x = when (t.align) {
                        UiTextAlign.LEFT -> t.x * scale + offX
                        UiTextAlign.RIGHT -> (t.x + t.w) * scale + offX - textWidth
                        else -> (t.x + t.w * 0.5f) * scale + offX - textWidth * 0.5f
                    }
                    val lines = t.text.split('\n').size
                    val block = size * 1.2f * lines
                    val y = when (t.verticalAlign) {
                        0 -> t.y * scale + offY
                        2 -> (t.y + t.h) * scale + offY - block
                        else -> (t.y + t.h * 0.5f) * scale + offY - block * 0.5f
                    }
                    renderer.text(
                        t.text, x, y, size, t.color, align, t.font, t.bold,
                        if (t.wrap) t.w * scale else 0f, 0f, 1.15f
                    )
                }
                is UiCommand.Sprite -> {
                    val s = cmd
                    val region = textures.region(s.texture)
                    if (region != null) {
                        renderer.texturedRect(s.x * scale + offX, s.y * scale + offY, s.w * scale, s.h * scale, region, s.tint)
                    } else if (s.texture.isBlank()) {
                        renderer.shape(s.x * scale + offX, s.y * scale + offY, s.w * scale, s.h * scale, 0f, s.tint)
                    }
                }
                is UiCommand.Polygon -> {
                    val pts = FloatArray(cmd.points.size)
                    for (i in cmd.points.indices step 2) {
                        pts[i] = cmd.points[i] * scale + offX
                        pts[i + 1] = cmd.points[i + 1] * scale + offY
                    }
                    renderer.convexPolygon(pts, cmd.color)
                }
                is UiCommand.Clip -> {
                    renderer.flush()
                    GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                    val x = (cmd.x * scale + offX).toInt().coerceAtLeast(0)
                    val y = (renderer.screenHeight - (cmd.y + cmd.h) * scale - offY).toInt().coerceAtLeast(0)
                    val w = (cmd.w * scale).toInt().coerceAtLeast(0)
                    val h = (cmd.h * scale).toInt().coerceAtLeast(0)
                    GLES20.glScissor(x, y, w, h)
                    scissorStack++
                }
                UiCommand.ClipEnd -> {
                    if (scissorStack > 0) {
                        scissorStack--
                        renderer.flush()
                        if (scissorStack == 0) GLES20.glDisable(GLES20.GL_SCISSOR_TEST) else GLES20.glScissor(0, 0, renderer.screenWidth.toInt(), renderer.screenHeight.toInt())
                    }
                }
            }
        }
        if (scissorStack > 0) {
            renderer.flush()
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        }
    }

    // ------------------------------------------------------------------ editor overlays

    private fun drawGrid(view: View2D, state: EditorState) {
        val step = if (state.gridSize > 0.01f) state.gridSize else 1f
        val doubled = step * 5f
        val left = view.cx - view.halfW
        val right = view.cx + view.halfW
        val bottom = view.cy - view.halfH
        val top = view.cy + view.halfH
        val maxLines = 400
        val x0 = (left / step).toInt() - 1
        val x1 = (right / step).toInt() + 1
        val y0 = (bottom / step).toInt() - 1
        val y1 = (top / step).toInt() + 1
        val xCount = (x1 - x0).coerceAtMost(maxLines)
        val yCount = (y1 - y0).coerceAtMost(maxLines)
        val thin = 1f / view.pixelsPerUnit
        val thick = 2f / view.pixelsPerUnit
        for (i in 0..xCount) {
            val x = (x0 + i) * step
            val major = abs(x % doubled) < 0.0001f
            renderer.line(x, bottom, x, top, if (major) thick else thin, if (major) 0x2AFFFFFF else 0x18FFFFFF)
        }
        for (i in 0..yCount) {
            val y = (y0 + i) * step
            val major = abs(y % doubled) < 0.0001f
            renderer.line(left, y, right, y, if (major) thick else thin, if (major) 0x2AFFFFFF else 0x18FFFFFF)
        }
        // axis lines
        renderer.line(0f, bottom, 0f, top, thick, 0x44FF4444.toInt())
        renderer.line(left, 0f, right, 0f, thick, 0x4444FF44)
    }

    private fun drawColliders(scene: Scene) {
        val width = 1.6f / maxOf(0.0001f, renderer.pixelsPerUnit) * 1.5f
        for (go in scene.objects) {
            if (go.destroyed || !go.isActiveInHierarchy()) continue
            val col = go.getAny<Collider2D>() ?: continue
            val w = go.computeWorld()
            val cx = w.mapX(col.offsetX, col.offsetY)
            val cy = w.mapY(col.offsetX, col.offsetY)
            val color = when {
                col.isTrigger -> 0xAAFFD54F.toInt()
                col.oneWay -> 0xAAAB47BC.toInt()
                col.layer == PhysicsLayers.indexOf("Terrain") -> 0xAA4CAF50.toInt()
                go.get<Rigidbody2D>()?.bodyType == 2 -> 0xAA64B5F6.toInt()
                else -> 0xAA29B6F6.toInt()
            }
            when (col.shape) {
                Collider2D.SHAPE_CIRCLE -> renderer.circle(cx, cy, col.radius * w.scaleX, 0x33FFFFFF, width * 2f, color)
                Collider2D.SHAPE_CAPSULE -> renderer.shape(
                    cx - col.width * w.scaleX * 0.5f, cy - col.height * w.scaleY * 0.5f,
                    col.width * w.scaleX, col.height * w.scaleY, minOf(col.width * w.scaleX, col.height * w.scaleY) * 0.5f,
                    0x22FFFFFF, width, color
                )
                Collider2D.SHAPE_POLYGON -> {
                    val pts = col.points.trim().split(Regex("[\\s;]+")).flatMap { p ->
                        val xy = p.split(',')
                        listOf((xy.getOrNull(0)?.toFloatOrNull() ?: 0f) * w.scaleX + cx, (xy.getOrNull(1)?.toFloatOrNull() ?: 0f) * w.scaleY + cy)
                    }.toFloatArray()
                    renderer.convexPolygon(pts, 0x22FFFFFF, color, width)
                }
                else -> renderer.shape(
                    cx - col.width * w.scaleX * 0.5f, cy - col.height * w.scaleY * 0.5f,
                    col.width * w.scaleX, col.height * w.scaleY, 0f, 0x22FFFFFF, width, color
                )
            }
        }
    }

    private fun drawTileGrid(scene: Scene, view: View2D) {
        val width = 1f / maxOf(0.0001f, renderer.pixelsPerUnit)
        for (go in scene.objects) {
            val tm = go.getAny<TilemapRenderer>() ?: continue
            val data = tm.data ?: continue
            if (!go.isActiveInHierarchy()) continue
            val origin = go.computeWorld()
            val size = data.worldSizeOfTile()
            val left = ((view.cx - view.halfW - origin.tx) / size).toInt() - 1
            val right = ((view.cx + view.halfW - origin.tx) / size).toInt() + 1
            val bottom = ((view.cy - view.halfH - origin.ty) / size).toInt() - 1
            val top = ((view.cy + view.halfH - origin.ty) / size).toInt() + 1
            if (right - left > 500 || top - bottom > 500) continue
            for (tx in left..right) {
                val x = origin.tx + tx * size
                renderer.line(x, bottom * size + origin.ty, x, top * size + origin.ty, width, 0x22FFFFFF)
            }
            for (ty in bottom..top) {
                val y = origin.ty + ty * size
                renderer.line(left * size + origin.tx, y, right * size + origin.tx, y, width, 0x22FFFFFF)
            }
        }
    }

    private fun drawLightGizmos(scene: Scene) {
        for (go in scene.objects) {
            val light = go.getAny<Light2D>() ?: continue
            if (!light.visible) continue
            val w = go.computeWorld()
            renderer.circle(w.tx, w.ty, light.radius, 0x18FFCC00, 1f / renderer.pixelsPerUnit * 2f, 0x88FFCC00.toInt())
            val dir = Math.toRadians(light.direction.toDouble())
            renderer.line(w.tx, w.ty, w.tx + cos(dir).toFloat() * light.radius * 0.5f, w.ty + sin(dir).toFloat() * light.radius * 0.5f, 2f / renderer.pixelsPerUnit * 2f, 0xCCFFCC00.toInt())
            go.getAny<LightOccluder2D>()?.let { }
        }
        for (go in scene.objects) {
            val occ = go.getAny<LightOccluder2D>() ?: continue
            val pts = occ.worldPolygon()
            if (pts.isNotEmpty()) renderer.polyline(pts, 1.5f / renderer.pixelsPerUnit * 2f, 0x88FF7043.toInt(), true)
        }
    }

    /** Selection outline + move/rotate/scale handles for the current editor selection. */
    private fun drawSelectionGizmos(scene: Scene, state: EditorState, view: View2D) {
        val selected = state.selectedIds
        if (selected.isEmpty()) return
        val handle = 10f / view.pixelsPerUnit
        for (id in selected) {
            val go = scene.findById(id) ?: continue
            val w = go.computeWorld()
            // bounds from the sprite/collider so the outline hugs the object
            val sprite = go.getAny<SpriteRenderer>()
            val col = go.getAny<Collider2D>()
            val hw = when {
                sprite != null -> sprite.width * w.scaleX * 0.5f
                col != null -> col.width * w.scaleX * 0.5f
                else -> 0.5f
            }
            val hh = when {
                sprite != null -> sprite.height * w.scaleY * 0.5f
                col != null -> col.height * w.scaleY * 0.5f
                else -> 0.5f
            }
            val x0 = w.tx - hw; val y0 = w.ty - hh
            val x1 = w.tx + hw; val y1 = w.ty + hh
            val outline = 2f / view.pixelsPerUnit
            renderer.line(x0, y0, x1, y0, outline, 0xFFFFC107.toInt())
            renderer.line(x1, y0, x1, y1, outline, 0xFFFFC107.toInt())
            renderer.line(x1, y1, x0, y1, outline, 0xFFFFC107.toInt())
            renderer.line(x0, y1, x0, y0, outline, 0xFFFFC107.toInt())
            if (state.tool == Tool.MOVE) {
                renderer.line(w.tx, w.ty, w.tx + state.gizmoLength(), w.ty, handle * 0.25f, 0xFFEF5350.toInt())
                renderer.line(w.tx, w.ty, w.tx, w.ty + state.gizmoLength(), handle * 0.25f, 0xFF66BB6A.toInt())
                renderer.circle(w.tx + state.gizmoLength(), w.ty, handle * 0.4f, 0xFFEF5350.toInt())
                renderer.circle(w.tx, w.ty + state.gizmoLength(), handle * 0.4f, 0xFF66BB6A.toInt())
            } else if (state.tool == Tool.ROTATE) {
                val r = maxOf(hw, hh) + handle * 2f
                val steps = 48
                var prevX = w.tx + r; var prevY = w.ty
                for (i in 1..steps) {
                    val a = M.TAU * i / steps
                    val x = w.tx + cos(a) * r
                    val y = w.ty + sin(a) * r
                    renderer.line(prevX, prevY, x, y, handle * 0.2f, 0x66FFC107)
                    prevX = x; prevY = y
                }
            } else if (state.tool == Tool.SCALE) {
                renderer.shape(x1 - handle, y1 - handle, handle * 2f, handle * 2f, handle * 0.4f, 0xFFFFC107.toInt())
                renderer.shape(x0 - handle, y1 - handle, handle * 2f, handle * 2f, handle * 0.4f, 0xFFFFC107.toInt())
                renderer.shape(x1 - handle, y0 - handle, handle * 2f, handle * 2f, handle * 0.4f, 0xFFFFC107.toInt())
                renderer.shape(x0 - handle, y0 - handle, handle * 2f, handle * 2f, handle * 0.4f, 0xFFFFC107.toInt())
            }
        }
        if (state.marqueeActive) {
            val x = minOf(state.marqueeX0, state.marqueeX1)
            val y = minOf(state.marqueeY0, state.marqueeY1)
            val w = abs(state.marqueeX1 - state.marqueeX0)
            val h = abs(state.marqueeY1 - state.marqueeY0)
            renderer.shape(x, y, w, h, 0f, 0x2242A5F5, 1.5f / view.pixelsPerUnit, 0xAA42A5F5.toInt())
        }
    }

    /** Tile brush preview so the tilemap editor always shows what will be painted. */
    private fun drawEditorBrush(state: EditorState) {
        val tool = state.tool
        if (tool != Tool.TILE_PAINT && tool != Tool.TILE_ERASE && tool != Tool.TILE_RECT) return
        val size = state.brushSize.coerceAtLeast(1)
        val cell = state.gridSize.coerceAtLeast(0.01f)
        val x = (state.dragWorldX / cell).toInt() * cell
        val y = (state.dragWorldY / cell).toInt() * cell
        for (i in 0 until size) for (j in 0 until size) {
            renderer.shape(x + i * cell, y + j * cell, cell, cell, 0f, 0x334FC3F7, 1.5f / renderer.pixelsPerUnit, 0xCC4FC3F7.toInt())
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun sceneAmbient(scene: Scene): com.sengine.engine.lighting.AmbientLight {
        val holder = scene.objects.firstOrNull { !it.destroyed && it.getAny<AmbientLight2D>() != null }
        val comp = holder?.getAny<AmbientLight2D>()
        return comp?.ambient ?: fallbackAmbient
    }

    private val fallbackAmbient = com.sengine.engine.lighting.AmbientLight().also {
        it.color = 0xFF3A4152.toInt()
    }
}
