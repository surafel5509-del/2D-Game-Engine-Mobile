package com.sengine

import com.sengine.engine.anim.AnimationClip
import com.sengine.engine.anim.AnimationEvent
import com.sengine.engine.anim.AnimationSystem
import com.sengine.engine.anim.Animator
import com.sengine.engine.anim.ClipProvider
import com.sengine.engine.anim.LoopMode
import com.sengine.engine.blueprint.Blueprint
import com.sengine.engine.blueprint.BlueprintCompiler
import com.sengine.engine.blueprint.BlueprintNodes
import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.core.Scene
import com.sengine.engine.core.SceneSerializer
import com.sengine.engine.core.SpriteRenderer
import com.sengine.engine.core.TextRenderer
import com.sengine.engine.fx.ParticleSystem2D
import com.sengine.engine.math.Rect2
import com.sengine.engine.physics.PhysicsWorld2D
import com.sengine.engine.script.ScriptReference
import com.sengine.engine.tilemap.AutoTile
import com.sengine.engine.tilemap.TilemapData
import com.sengine.engine.tilemap.Tileset
import com.sengine.engine.ui.UiButton
import com.sengine.engine.ui.UiCanvas
import com.sengine.engine.ui.UiSystem
import com.sengine.engine.ui.UiTheme
import com.sengine.engine.ui.UiAnchor
import com.sengine.project.AssetLibrary
import com.sengine.project.Project
import com.sengine.project.ProjectManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.javascript.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * Feature tests for the 2D engine systems: animation, particles, tilemaps, physics, UI, blueprints,
 * the asset store and project export. Everything runs headless (no OpenGL, no Android UI).
 */
class EngineV2Test {

    private fun tempProject(name: String = "Feature"): Project {
        val dir = Files.createTempDirectory("sengine2").toFile()
        val p = Project(File(dir, name))
        p.saveMeta()
        return p
    }

    private fun compiles(js: String, name: String) {
        val cx = Context.enter()
        try {
            cx.optimizationLevel = -1
            cx.languageVersion = Context.VERSION_ES6
            cx.compileString(js, name, 1, null)
        } finally {
            Context.exit()
        }
    }

    // ------------------------------------------------------------------ animation

    @Test
    fun animationClipGridLoopAndEvents() {
        val clip = AnimationClip("Run")
        clip.texture = "HeroRun.png"
        clip.fps = 10f
        clip.loop = LoopMode.LOOP
        clip.buildGrid(4, 1)
        clip.events.add(AnimationEvent(0.2f, "footstep", "left"))

        assertEquals(4, clip.frameCount)
        assertEquals(0.4f, clip.duration, 0.001f)
        val f0 = clip.frameAt(0)!!
        assertEquals(0f, f0.u0, 0.0001f)
        assertEquals(0.25f, f0.u1, 0.0001f)
        assertEquals(0, clip.frameIndexAt(0.05f))
        assertEquals(1, clip.frameIndexAt(0.15f))
        assertEquals(0, clip.frameIndexAt(0.45f)) // wraps for looping clips

        val json = clip.toJson()
        val parsed = AnimationClip.fromJson(JSONObject(json.toString()))
        assertEquals(clip.frameCount, parsed.frameCount)
        assertEquals(LoopMode.LOOP, parsed.loop)
        assertEquals("footstep", parsed.events.first().name)
        println("SIM anim clip frames=${clip.frameCount} duration=${clip.duration}")
    }

    @Test
    fun animationSystemAdvancesFrames() {
        val clip = AnimationClip("Run")
        clip.buildGrid(4, 1)
        clip.fps = 10f
        val provider = object : ClipProvider {
            override fun clip(name: String) = if (name == "Run.anim") clip else null
        }
        val scene = Scene("Main")
        val go = scene.create("Hero")
        go.add(SpriteRenderer().also { it.shape = SpriteRenderer.SHAPE_TEXTURE; it.texture = "HeroRun.png" })
        val animator = Animator().also { it.clip = "Run.anim"; it.playOnStart = true }
        go.add(animator)
        val system = AnimationSystem(provider)
        // 10 fps clip, 4 frames: frame 1 starts at 0.1s (6 frames at 60 fps)
        repeat(7) { system.update(scene, 1f / 60f, playing = true) }
        println("SIM anim system playing=${animator.playing} frame=${animator.frameIndex} time=${animator.time}")
        assertTrue("animator should be playing", animator.playing)
        assertEquals("clip should be resolved", "Run", animator.currentClip!!.name)
        assertEquals("frame index should follow the clip time", 1, animator.frameIndex)
        assertEquals("the animator reports the clip frame under the clip time",
            clip.frameIndexAt(animator.time), animator.frameIndex)
        // the clip loops: after 30 more frames the time wraps back into the clip
        repeat(30) { system.update(scene, 1f / 60f, playing = true) }
        assertTrue("looping clip keeps its time inside the clip", animator.time < 0.4f)
        assertTrue("animation system counted the animator", system.activeAnimators == 1)
    }

    // ------------------------------------------------------------------ particles

    @Test
    fun particlesBurstSimulateAndRecycle() {
        val sys = ParticleSystem2D(64)
        sys.rate = 0f
        sys.lifetime = 0.4f
        sys.lifetimeVariation = 0f
        sys.speed = 2f
        sys.burst(40)
        sys.update(1f / 60f)
        println("SIM particles alive=${sys.count}")
        assertTrue("burst should spawn particles", sys.count > 0)
        var frames = 0
        while (frames < 120 && sys.count > 0) { sys.update(1f / 60f); frames++ }
        println("SIM particles after ${frames} frames alive=${sys.count}")
        assertEquals("particles should expire and be recycled", 0, sys.count)
        assertTrue("capacity stays bounded", sys.capacity() == 64)
    }

    // ------------------------------------------------------------------ tilemaps

    @Test
    fun tilemapChunksAutoTileAndCollisionData() {
        val map = TilemapData("Level")
        map.tileWidth = 16; map.tileHeight = 16; map.pixelsPerUnit = 16f
        assertEquals(1f, map.worldSizeOfTile(), 0.0001f)
        map.tilesets.add(Tileset("Ground", "Grass.png").also { it.solid.add(1); it.solid.add(2) })
        val layer = map.layer("Ground")
        for (x in 0 until 40) layer.set(x, 0, 1)
        layer.set(40, 0, 0) // empty tiles are stored as 0
        assertEquals(1, layer.get(5, 0))
        assertEquals(0, layer.get(5, 1))
        val expectedChunks = 1 + (39 / layer.chunkSize)
        assertEquals("chunks are created on demand", expectedChunks, layer.chunkCount())
        val mask = AutoTile.mask(map, layer, 5, 0, 0)
        assertTrue("a tile surrounded left/right should produce a mask", mask > 0)
        val tileset = map.tilesets.firstOrNull()
        println("SIM tilemap tiles=${layer.chunkCount()} mask=$mask tilesets=$tileset")
        val json = map.toJson().toString()
        val parsed = TilemapData.fromJson(JSONObject(json))
        assertEquals(map.layers.size, parsed.layers.size)
        assertEquals(1, parsed.layers.first().get(5, 0))
        assertEquals(0, parsed.layers.first().get(5, 1))
    }

    // ------------------------------------------------------------------ physics

    @Test
    fun physicsBodyTypesRaycastAndOverlap() {
        val scene = Scene("Main")
        val world = PhysicsWorld2D()
        val floor = scene.create("Floor").also { it.y = -3f }
        floor.add(Collider2D().also { it.width = 30f; it.height = 1f })
        val ball = scene.create("Ball").also { it.y = 2f }
        ball.add(Collider2D().also { it.shape = Collider2D.SHAPE_CIRCLE; it.radius = 0.5f; it.restitution = 0.2f })
        ball.add(Rigidbody2D().also { it.bodyType = 0; it.mass = 1f })
        val mover = scene.create("Mover").also { it.x = -5f }
        mover.add(Collider2D())
        mover.add(Rigidbody2D().also { it.bodyType = 1; it.startVx = 2f })

        repeat(240) { world.step(scene, 1f / 60f) }
        val rb = ball.getAny<Rigidbody2D>()!!
        println("SIM physics ball y=${ball.y} grounded=${rb.grounded} moverX=${mover.x}")
        assertTrue("dynamic body should rest on the static floor", rb.grounded)
        assertTrue("ball rests above the floor", ball.y > -3.5f)
        assertTrue("kinematic body should keep moving", mover.x > -5f)

        val hit = world.rayCast(6f, 4f, 6f, -6f)
        assertEquals("raycast should hit the floor", "Floor", hit?.go?.name)
        assertTrue("overlap query should find bodies", world.overlapCircle(0f, -3f, 4f).isNotEmpty())
        assertTrue("raycast all returns hits", world.rayCastAll(6f, 4f, 6f, -6f).isNotEmpty())
    }

    // ------------------------------------------------------------------ UI

    @Test
    fun uiLayoutAnchorsAndButtonClick() {
        val scene = Scene("Main")
        val canvasGo = scene.create("Canvas")
        val canvas = UiCanvas().also { it.referenceWidth = 1280f; it.referenceHeight = 720f }
        canvasGo.add(canvas)
        val buttonGo = scene.create("PlayButton", canvasGo)
        val button = UiButton().also {
            it.text = "Play"
            it.anchor = UiAnchor.MIDDLE_CENTER
            it.width = 240f; it.height = 72f
        }
        buttonGo.add(button)

        val ui = UiSystem()
        ui.input.screenWidth = 1280f; ui.input.screenHeight = 720f
        ui.update(scene, 1f / 60f)
        val rect = button.rect
        println("SIM ui button rect=(${rect.x},${rect.y},${rect.w},${rect.h})")
        assertTrue("button should be laid out at the centre of the canvas", rect.x > 400f && rect.x < 800f)
        assertEquals("button width comes from the widget", 240f, rect.w, 0.01f)

        val cx = rect.x + rect.w / 2f
        val cy = rect.y + rect.h / 2f
        ui.input.pointerX = cx; ui.input.pointerY = cy
        ui.input.pointerDown = true; ui.input.pointerJustDown = true
        ui.update(scene, 1f / 60f)
        assertTrue("button should be pressed under the pointer", button.pressed)
        ui.input.pointerJustDown = false
        ui.input.pointerJustUp = true; ui.input.pointerDown = false
        ui.update(scene, 1f / 60f)
        println("SIM ui clickCount=${button.clickCount} theme=${UiTheme().name}")
        assertEquals("release over the button should click it", 1, button.clickCount)
    }

    // ------------------------------------------------------------------ blueprints

    @Test
    fun blueprintGraphCompilesToJsAndRoundTrips() {
        val bp = Blueprint()
        var prev: Int? = null
        for ((i, def) in BlueprintNodes.all.withIndex()) {
            val n = bp.add(def.type, i * 50f, 0f)
            if (!def.hasIn) { prev = n.id; continue }
            prev?.let { bp.connect(it, "out", n.id) }
            if (def.outs.contains("out")) prev = n.id
        }
        val js = BlueprintCompiler.compile(bp)
        println("SIM blueprint nodes=${BlueprintNodes.all.size} js=${js.length}")
        compiles(js, "all.bp")
        val bp2 = Blueprint.parse(bp.toJson().toString())
        assertEquals(bp.nodes.size, bp2.nodes.size)
        assertEquals(bp.links.size, bp2.links.size)
        assertEquals(js, BlueprintCompiler.compile(bp2))
        compiles(BlueprintCompiler.compile(Blueprint.defaultGraph()), "default.bp")
    }

    // ------------------------------------------------------------------ content

    @Test
    fun assetStoreIsStrictlyTwoD() {
        val categories = AssetLibrary.categories
        assertFalse("no 3D category may exist", categories.any { it.contains("3D", true) })
        val forbidden = listOf(".obj", ".gltf", ".glb", ".fbx", ".dae", ".mtl")
        for (item in AssetLibrary.items) {
            for (file in item.files) {
                assertFalse("3D asset found: $file", forbidden.any { file.endsWith(it, true) })
            }
            assertFalse("3D item found: ${item.title}", item.title.contains("3D", true))
        }
        for ((name, _, code) in AssetLibrary.Scripts.all) {
            assertFalse("3D script found: $name", code.contains("3D") || code.contains("MeshRenderer") || code.contains("Rigidbody3D"))
            compiles(code, name)
        }
        for ((name, _, code) in AssetLibrary.Shaders.all) {
            assertFalse("shader mentions 3D meshes: $name", code.contains("MeshRenderer") || code.contains("3D"))
        }
        val titles = AssetLibrary.items.map { it.title }
        assertTrue("wheel sprite for vehicles", titles.contains("Rolling Wheel"))
        assertTrue("hill climb pack", titles.contains("Hill Climb Pack"))
        val dir = Files.createTempDirectory("store").toFile()
        val p = Project(File(dir, "Store")); p.saveMeta()
        for (item in AssetLibrary.items.filter { it.category == "Blueprints" || it.category == "Sounds" || it.category == "Scripts" || it.category == "Shaders" }) {
            item.install(p)
            assertTrue("${item.title} installed", item.installed(p))
        }
        val wav = p.assetFile("coin.wav").readBytes()
        assertEquals("RIFF", String(wav, 0, 4))
        println("SIM store items=${AssetLibrary.items.size} categories=${AssetLibrary.categories.size}")
    }

    @Test
    fun scriptReferenceIsTwoDOnlyAndMatchesRuntime() {
        val text = ScriptReference.GROUPS.joinToString("\n") { it.second.joinToString("\n") }
        for (token in listOf("3D", "Mesh", "GLTF", "rotY", "rotZ", "self.z ", "vz")) {
            assertFalse("script reference must not mention $token", text.contains(token))
        }
        for (token in listOf("self.vx", "self.vy", "self.rotation", "physics.raycast", "particles.burst", "camera.shake", "input.axisX", "self.play")) {
            assertTrue("reference should document $token", text.contains(token))
        }
        val templates = com.sengine.project.ScriptTemplates
        assertTrue("script templates exist", templates.names.size >= 10)
        for (name in templates.names) compiles(templates.get(name), "template.js")
    }

    @Test
    fun projectExportZipContainsSceneAndAssets() {
        val p = tempProject("Exported")
        val scene = Scene("Main")
        scene.create("Hero").also { it.add(TextRenderer().also { t -> t.text = "hi" }) }
        p.saveScene(scene)
        p.startScene = "Main"
        p.writeAsset("notes.txt", "hello")
        val out = ByteArrayOutputStream()
        ProjectManager.exportZip(p, out)
        val bytes = out.toByteArray()
        assertTrue("zip should not be empty", bytes.size > 100)
        val entries = ArrayList<String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var e = zip.nextEntry
            while (e != null) { entries.add(e.name); e = zip.nextEntry }
        }
        println("SIM export entries=${entries.size} $entries")
        assertTrue("scene is exported", entries.any { it.contains("scenes/Main") })
        assertTrue("assets are exported", entries.any { it.contains("notes.txt") })
    }

    /** Guards the documented 2D contract: the serializer never writes 3D transform fields. */
    @Test
    fun serializerWritesNoThreeDFields() {
        val scene = Scene("Main")
        scene.create("Obj").also { it.add(SpriteRenderer()) }
        val json = SceneSerializer.toJson(scene).toString()
        assertFalse("no Z position is serialised", json.contains("\"z\""))
        assertFalse("no scaleZ is serialised", json.contains("scaleZ"))
        assertFalse("no rotation X/Y is serialised", json.contains("rotX") || json.contains("rotY"))
        assertNotNull(GameObject(1L, "test"))
        Rect2().also { it.set(0f, 0f, 1f, 1f) }
    }
}
