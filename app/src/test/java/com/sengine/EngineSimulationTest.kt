package com.sengine

import com.sengine.engine.Engine
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.core.SceneSerializer
import com.sengine.engine.core.ScriptComponent
import com.sengine.engine.core.TextRenderer
import com.sengine.engine.tilemap.TilemapRenderer
import com.sengine.engine.vehicle.Vehicle2D
import com.sengine.project.Project
import com.sengine.project.Templates
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.abs

/**
 * Headless simulation of the bundled 2D templates: the real engine loop, 2D physics and Rhino
 * scripts run without a rendering surface. Every assertion checks observable gameplay state.
 */
class EngineSimulationTest {

    /**
     * True when a real JavaScript runtime is on the classpath. The offline sandbox compiles against
     * a Rhino API stub whose classes cannot execute scripts; CI resolves org.mozilla:rhino and runs
     * every script-driven test for real.
     */
    private val scripting: Boolean by lazy {
        try {
            Class.forName("org.mozilla.javascript.optimizer.OptRuntime")
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun assumeScripting() =
        Assume.assumeTrue("no JavaScript runtime on the classpath (script-driven check skipped)", scripting)

    private fun newProject(template: String): Project {
        val dir = Files.createTempDirectory("sengine").toFile()
        val p = Project(File(dir, "Test"))
        p.saveMeta()
        Templates.all.first { it.name == template }.build(p)
        p.saveMeta()
        return p
    }

    private class Run(val engine: Engine, val errors: MutableList<String>)

    private fun start(p: Project): Run {
        val e = Engine(p, p.loadScene(p.startScene))
        e.gameView.widthPx = 1600; e.gameView.heightPx = 900
        e.input.setScreenSize(1600f, 900f)
        val errors = ArrayList<String>()
        e.listeners.add(object : Engine.Listener {
            override fun onLog(level: Int, message: String) {
                if (level >= 2) errors.add(message)
                println("SIM log[$level]: $message")
            }
        })
        e.play()
        return Run(e, errors)
    }

    private fun Run.frames(n: Int, each: (Int) -> Unit = {}) {
        repeat(n) { i -> each(i); synchronized(engine.lock) { engine.tick(1f / 60f) } }
    }

    // ------------------------------------------------------------------ templates

    @Test
    fun serializationRoundTrip() {
        for (name in Templates.all.map { it.name }) {
            val p = newProject(name)
            val s = p.loadScene("Main")
            val json = SceneSerializer.toJson(s).toString()
            val s2 = SceneSerializer.fromJson(JSONObject(json))
            assertEquals("object count for $name", s.objects.size, s2.objects.size)
            assertEquals("round trip for $name", json, SceneSerializer.toJson(s2).toString())
            println("SIM template '$name' objects=${s.objects.size}")
        }
    }

    /** Runs a template for [frames] frames with the given input script driving the frames. */
    private fun runTemplate(name: String, frames: Int, input: (Int, com.sengine.engine.Engine) -> Unit = { _, _ -> }) {
        val r = start(newProject(name))
        r.frames(frames) { i -> input(i, r.engine) }
        val bad = r.engine.scene.objects.filter { !it.x.isFinite() || !it.y.isFinite() || !it.rotation.isFinite() }
        println("SIM '$name' ran $frames frames objects=${r.engine.scene.objects.size} errors=${r.errors.size}${
            if (bad.isEmpty()) "" else " nonFinite=${bad.map { it.name }}"}")
        assertTrue("script errors in '$name': ${r.errors}", r.errors.isEmpty())
        assertTrue("transforms must stay finite in '$name': ${bad.map { it.name }}", bad.isEmpty())
    }

    @Test
    fun platformerPlayerMovesJumpsAndCollects() {
        assumeScripting()
        runTemplate("Platformer Demo", 240) { i, engine ->
            engine.input.setJoystick(if (i % 120 < 60) 1f else -1f, 0f, true)
            if (i % 40 == 0) engine.input.pressButtonA() else engine.input.releaseButtonA()
        }
    }

    @Test
    fun shooterSpawnsEnemiesAndBullets() {
        assumeScripting()
        runTemplate("Space Shooter", 240) { _, engine ->
            engine.input.setJoystick(0.6f, 0f, true)
            engine.input.pressButtonA()
        }
    }

    @Test
    fun physicsSandboxSpawnsOnInjectedTap() {
        assumeScripting()
        runTemplate("Physics Sandbox", 240) { i, engine ->
            engine.input.setJoystick(0.4f, 0f, true)
            if (i % 60 == 30) engine.input.injectTap(800f, 300f)
        }
    }

    @Test
    fun tilemapTemplateBuildsCollisionAndRuns() {
        val r = start(newProject("Tilemap Level"))
        r.frames(120)
        val tilemap = r.engine.scene.objects.first { it.getAny<TilemapRenderer>() != null }
        val renderer = tilemap.getAny<TilemapRenderer>()!!
        assertNotNull("tilemap data should be assigned", renderer.data)
        val holder = r.engine.scene.objects.firstOrNull { it.name == renderer.collisionHolderName }
        assertNotNull("tilemap collision bodies should be generated", holder)
        println("SIM tilemap layers=${renderer.data!!.layers.size} chunks=${renderer.data!!.layers.sumOf { it.chunkCount() }} stats=${renderer.stats()}")
        val player = r.engine.scene.find("Player")!!
        val rb = player.getAny<Rigidbody2D>()!!
        assertTrue("hero should stand on the tilemap", rb.grounded)
        val x0 = player.x
        if (scripting) {
            r.frames(60) { r.engine.input.setJoystick(1f, 0f, true) }
            r.engine.input.setJoystick(0f, 0f, false)
            println("SIM tilemap hero x=${player.x} y=${player.y}")
            assertTrue("hero should walk along the level", player.x > x0 + 1f)
        }
        assertTrue("script errors: ${r.errors}", r.errors.isEmpty())
    }

    @Test
    fun vehicleTemplateDrives() {
        val r = start(newProject("Hill Climb Vehicle"))
        val car = r.engine.scene.find("Vehicle")!!
        val vehicle = car.getAny<Vehicle2D>()!!
        r.frames(90)
        assertEquals("both wheels should be built", 2, vehicle.wheelObjects.size)
        // Drive the way a player does: the template's Driver.js copies the joystick axis into the
        // throttle every frame, so the throttle is also set directly for runs without a JS runtime
        // (where the script never executes and cannot read the input).
        r.engine.input.setJoystick(1f, 0f, true)
        r.engine.input.pressButtonA()
        var commandedSpin = 0f
        var driveForce = 0f
        var carriedLoad = 0f
        r.frames(600) {
            r.engine.input.setJoystick(1f, 0f, true)
            vehicle.throttle = 1f
            // The tyres are driven to the commanded spin rate (arcade model): throttle > 0 must ask
            // for a clockwise spin (negative), which is what drives the car to the right.
            commandedSpin = minOf(commandedSpin, vehicle.telemetry.commandedWheelSpin)
            driveForce = maxOf(driveForce, vehicle.telemetry.driveForce)
            carriedLoad = maxOf(carriedLoad, vehicle.telemetry.suspensionLoad)
        }
        r.engine.input.releaseButtonA()
        r.frames(30)
        println("SIM vehicle drive=${"%.0f".format(driveForce)}N spin=${"%.0f".format(commandedSpin)}deg/s " +
            "grounded=${vehicle.telemetry.groundedWheels} x=${"%.2f".format(car.x)} " +
            "load=${"%.0f".format(carriedLoad)} dist=${"%.2f".format(vehicle.telemetry.distanceTravelled)}")
        assertTrue("throttle should command the driven wheels, was $commandedSpin deg/s", commandedSpin < -1000f)
        assertTrue("throttle should command a traction force, was $driveForce N", driveForce > 10f)
        assertTrue("the suspension must carry the chassis load", carriedLoad > 1f)
        // The buggy has to climb the template's rolling terrain, not just creep: a pinned vehicle (or
        // one that only slips its tyres) stalls within a few centimetres.
        assertTrue("throttle should drive the vehicle up the hill, distance was ${vehicle.telemetry.distanceTravelled}",
            vehicle.telemetry.distanceTravelled > 5f)
        assertTrue("the vehicle must stay numerically sane", car.x.isFinite() && car.y.isFinite() && abs(car.x) < 600f)
        assertTrue("script errors: ${r.errors}", r.errors.isEmpty())
    }

    @Test
    fun scriptErrorsAreReportedNotThrown() {
        assumeScripting()
        val p = newProject("Empty 2D")
        p.writeAsset("Bad.js", "function update(dt) { undefinedThing.foo(); }")
        val s = p.loadScene("Main")
        s.find("Square")!!.add(ScriptComponent().also { it.script = "Bad.js" })
        p.saveScene(s)
        val r = start(p)
        r.frames(5)
        println("SIM errors=${r.errors}")
        assertEquals(1, r.errors.size)
    }
}
