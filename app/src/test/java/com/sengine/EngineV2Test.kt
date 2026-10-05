package com.sengine

import com.sengine.engine.Engine
import com.sengine.engine.blueprint.Blueprint
import com.sengine.engine.blueprint.BlueprintCompiler
import com.sengine.engine.blueprint.BlueprintNodes
import com.sengine.engine.core.Animator
import com.sengine.engine.core.Rigidbody3D
import com.sengine.engine.core.SceneSerializer
import com.sengine.engine.core.TextRenderer
import com.sengine.project.AssetLibrary
import com.sengine.project.Project
import com.sengine.project.Templates
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.javascript.Context
import java.io.File
import java.nio.file.Files

/** Headless tests for the Ultimate Edition systems: 3D physics, animation, blueprints and asset store content. */
class EngineV2Test {

    private fun newProject(name: String): Project {
        val dir = Files.createTempDirectory("sengine2").toFile()
        val p = Project(File(dir, "Test"))
        p.saveMeta()
        Templates.all.first { it.name == name }.build(p)
        p.saveMeta()
        return p
    }

    private class Run(val engine: Engine, val errors: MutableList<String>)

    private fun start(p: Project): Run {
        val e = Engine(p, p.loadScene(p.startScene))
        e.gameView.widthPx = 1600; e.gameView.heightPx = 900
        val errors = ArrayList<String>()
        e.listeners.add(object : Engine.Listener {
            override fun onLog(level: Int, message: String) {
                if (level >= 2) errors.add(message)
                println("SIM v2 log[$level]: $message")
            }
        })
        e.play()
        return Run(e, errors)
    }

    private fun Run.frames(n: Int, each: (Int) -> Unit = {}) {
        repeat(n) { i -> each(i); synchronized(engine.lock) { engine.tick(1f / 60f) } }
    }

    private fun compiles(js: String, name: String) {
        val cx = Context.enter()
        try {
            cx.optimizationLevel = -1
            cx.languageVersion = Context.VERSION_ES6
            cx.compileString(js, name, 1, null)
        } finally { Context.exit() }
    }

    @Test
    fun demo3dPhysicsAndPickups() {
        val p = newProject("3D Demo")
        val r = start(p)
        val player = r.engine.scene.find("Player")!!
        r.frames(90)
        val rb = player.getAny<Rigidbody3D>()!!
        println("SIM 3d settled y=${player.y} grounded=${rb.grounded}")
        assertTrue("3D player should land on the ground", rb.grounded)
        assertTrue("3D player should rest above ground", player.y > 0f && player.y < 2f)
        val x0 = player.x
        r.frames(60) { r.engine.input.joyX = 1f }
        r.engine.input.joyX = 0f
        println("SIM 3d moved x0=$x0 x=${player.x}")
        assertTrue("3D player should move on X", player.x > x0 + 2f)
        // jump
        var maxY = player.y
        r.frames(40) { i -> r.engine.input.rawA = i < 3; maxY = maxOf(maxY, player.y) }
        r.engine.input.rawA = false
        println("SIM 3d jump maxY=$maxY")
        assertTrue("3D player should jump", maxY > 1.5f)
        // crates should fall and rest (stack stays above ground)
        val crates = r.engine.scene.objects.filter { it.name == "Crate" }
        assertTrue("crates resting", crates.all { it.y > 0.2f && it.y < 3f })
        // pickup
        val coin = r.engine.scene.objects.first { it.tag == "Coin" }
        val before = r.engine.scene.objects.count { it.tag == "Coin" }
        r.frames(60)
        synchronized(r.engine.lock) { player.x = coin.x; player.z = coin.z; player.y = coin.y }
        r.frames(3)
        val after = r.engine.scene.objects.count { it.tag == "Coin" }
        val label = r.engine.scene.find("ScoreText")!!.getAny<TextRenderer>()!!.text
        println("SIM 3d coins $before -> $after label='$label'")
        assertEquals(before - 1, after)
        assertEquals("Coins left: $after", label)
        // raycast straight down from above the ground hits the ground
        val hit = r.engine.physics3D.raycast(r.engine.scene, 15f, 10f, 15f, 0f, -1f, 0f, 50f)
        println("SIM 3d raycast hit=${hit?.name}")
        assertEquals("Ground", hit?.name)
        assertTrue("script errors: ${r.errors}", r.errors.isEmpty())
    }

    @Test
    fun animatedPlatformerPlaysClips() {
        val p = newProject("Animated Platformer")
        val r = start(p)
        val hero = r.engine.scene.find("Player")!!
        r.frames(60)
        r.frames(30) { r.engine.input.joyX = 1f }
        val anim = hero.getAny<Animator>()!!
        println("SIM anim current='${anim.current}' playing=${anim.playing} frame=${anim.frame} x=${hero.x}")
        assertEquals("HeroRun.anim", anim.current)
        assertTrue("run animation should play", anim.playing)
        r.engine.input.joyX = 0f
        r.frames(10)
        assertTrue("animation should stop when idle", !anim.playing)
        val coin = r.engine.scene.objects.first { it.tag == "Coin" }
        val ca = coin.getAny<Animator>()!!
        println("SIM coin anim frame=${ca.frame} playing=${ca.playing}")
        assertTrue("coin spin should autoplay", ca.playing)
        assertTrue("script errors: ${r.errors}", r.errors.isEmpty())
    }

    @Test
    fun blueprintDemoRunsGraphs() {
        val p = newProject("Blueprint Demo")
        val r = start(p)
        val player = r.engine.scene.find("Player")!!
        r.frames(60)
        val x0 = player.x
        r.frames(40) { r.engine.input.joyX = -1f }
        r.engine.input.joyX = 0f
        println("SIM bp player x0=$x0 x=${player.x}")
        assertTrue("blueprint Platformer node should move the player", player.x < x0 - 1.5f)
        val spinner = r.engine.scene.find("Spinner")!!
        println("SIM bp spinner rotation=${spinner.rotation}")
        assertTrue("RotatorBP should spin", spinner.rotation != 0f)
        val gems = r.engine.scene.objects.count { it.name == "Gem" }
        val gem = r.engine.scene.objects.first { it.name == "Gem" }
        synchronized(r.engine.lock) { player.x = gem.x; player.y = gem.y }
        r.frames(3)
        val after = r.engine.scene.objects.count { it.name == "Gem" }
        println("SIM bp gems $gems -> $after")
        assertEquals(gems - 1, after)
        assertTrue("script errors: ${r.errors}", r.errors.isEmpty())
    }

    @Test
    fun v2TemplatesSerializeRoundTrip() {
        for (name in listOf("3D Demo", "Animated Platformer", "Blueprint Demo")) {
            val p = newProject(name)
            val s = p.loadScene("Main")
            val json = SceneSerializer.toJson(s).toString()
            assertEquals(json, SceneSerializer.toJson(SceneSerializer.fromJson(JSONObject(json))).toString())
            println("SIM template '$name' objects=${s.objects.size}")
        }
    }

    @Test
    fun everyBlueprintNodeCompilesToValidJs() {
        val bp = Blueprint()
        var prev: Int? = null
        // one chain per event with every action node attached
        for ((i, def) in BlueprintNodes.all.withIndex()) {
            val n = bp.add(def.type, i * 50f, 0f)
            if (!def.hasIn) { prev = n.id; continue }
            prev?.let { bp.connect(it, "out", n.id) }
            if (def.outs.contains("out")) prev = n.id
        }
        val js = BlueprintCompiler.compile(bp)
        println("SIM bp all-nodes js length=${js.length}")
        compiles(js, "all.bp")
        // JSON round trip
        val bp2 = Blueprint.parse(bp.toJson().toString())
        assertEquals(bp.nodes.size, bp2.nodes.size)
        assertEquals(bp.links.size, bp2.links.size)
        assertEquals(js, BlueprintCompiler.compile(bp2))
        compiles(BlueprintCompiler.compile(Blueprint.defaultGraph()), "default.bp")
    }

    @Test
    fun storeScriptsAndBlueprintsAreValid() {
        for ((name, _, code) in AssetLibrary.Scripts.all) compiles(code, name)
        val dir = Files.createTempDirectory("store").toFile()
        val p = Project(File(dir, "Store")); p.saveMeta()
        for (item in AssetLibrary.items.filter { it.category == "Blueprints" || it.category == "3D Models" || it.category == "Sounds" }) {
            item.install(p)
            assertTrue("${item.title} installed", item.installed(p))
        }
        for (bpName in p.listAssets().filter { it.endsWith(".bp") }) compiles(BlueprintCompiler.compile(Blueprint.parse(p.readAsset(bpName)!!)), bpName)
        val wav = p.assetFile("coin.wav").readBytes()
        assertEquals("RIFF", String(wav, 0, 4)); assertEquals("WAVE", String(wav, 8, 4))
        val obj = p.readAsset("Tree.obj")!!
        assertTrue(obj.lines().count { it.startsWith("v ") } > 20 && obj.lines().count { it.startsWith("f ") } > 20)
        println("SIM store items=${AssetLibrary.items.size}")
    }
}
