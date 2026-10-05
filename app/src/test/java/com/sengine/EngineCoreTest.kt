package com.sengine

import com.sengine.engine.core.Camera2D
import com.sengine.engine.core.Joint2D
import com.sengine.engine.core.Scene
import com.sengine.engine.core.SceneSerializer
import com.sengine.engine.core.SpriteAnimator
import com.sengine.engine.core.SpriteRenderer
import com.sengine.project.GameExportConfig
import com.sengine.project.GameExporter
import com.sengine.project.Project
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EngineCoreTest {
    @Test
    fun spriteSheetAnimatorAdvancesLoopsAndMapsAtlasUv() {
        val sprite = SpriteRenderer().also {
            it.uvX = 0.2f; it.uvY = 0.1f; it.uvWidth = 0.6f; it.uvHeight = 0.8f
        }
        val animator = SpriteAnimator().also {
            it.columns = 2; it.rows = 2; it.firstFrame = 0; it.frameCount = 3
            it.framesPerSecond = 2f; it.looping = true
        }
        animator.resetRuntime()
        animator.advance(0.5f)
        assertEquals(1, animator.frame)
        animator.advance(1f)
        assertEquals(0, animator.frame)
        val uv = animator.atlasUv(sprite, FloatArray(4))
        assertEquals(0.2f, uv[0], 0.0001f)
        assertEquals(0.1f, uv[1], 0.0001f)
        assertEquals(0.3f, uv[2], 0.0001f)
        assertEquals(0.4f, uv[3], 0.0001f)
    }

    @Test
    fun sceneRoundTripPersistsPhysicsCameraAndAtlasSettings() {
        val scene = Scene("RoundTrip")
        scene.create("Camera").add(Camera2D().also {
            it.follow = "Player"; it.pixelPerfect = true; it.limitEnabled = true
            it.limitLeft = -20f; it.limitRight = 20f
        })
        scene.create("Sprite").also { go ->
            go.add(SpriteRenderer().also { it.texture = "atlas.png"; it.uvX = 0.25f; it.uvWidth = 0.25f })
            go.add(SpriteAnimator().also { it.columns = 4; it.rows = 2; it.frameCount = 6; it.framesPerSecond = 12f })
        }
        scene.create("Joint").add(Joint2D().also {
            it.jointType = 2; it.connectedBody = "Sprite"; it.length = 3.5f; it.frequency = 3f
        })
        val json = SceneSerializer.toJson(scene).toString()
        val restored = SceneSerializer.fromJson(JSONObject(json))
        assertEquals(json, SceneSerializer.toJson(restored).toString())
        assertTrue(restored.find("Camera")!!.getAny<Camera2D>()!!.pixelPerfect)
        assertEquals(4, restored.find("Sprite")!!.getAny<SpriteAnimator>()!!.columns)
        val joint = restored.find("Joint")!!.getAny<Joint2D>()!!
        assertEquals("Sprite", joint.connectedBody)
        assertEquals(3.5f, joint.length, 0.001f)
        assertEquals(3f, joint.frequency, 0.001f)
    }

    @Test
    fun malformedParentCycleIsDiscardedDuringLoad() {
        val json = JSONObject(
            """{"name":"Cycle","objects":[{"id":1,"name":"A","parent":2},{"id":2,"name":"B","parent":1}]}"""
        )
        val scene = SceneSerializer.fromJson(json)
        assertTrue(scene.hierarchy().size <= 2)
        assertTrue(scene.objects.none { it.parent === it })
    }

    @Test
    fun projectWritesAtomicallyAndRejectsPathTraversal() {
        val root = Files.createTempDirectory("sengine-project-test").toFile()
        val project = Project(File(root, "Test")).also { it.saveMeta() }
        project.writeAsset("logic.js", "function update(dt) {}")
        assertEquals("function update(dt) {}", project.readAsset("logic.js"))
        var rejected = false
        try { project.writeAsset("../escaped.txt", "no") } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue("asset path traversal must be rejected", rejected)
        project.saveMeta()
        assertTrue(project.dir.resolve("project.json").isFile)
    }

    @Test
    fun exportedProjectSettingsPersistTouchControlsAndOrientation() {
        val dir = Files.createTempDirectory("sengine-project-meta").toFile()
        val project = Project(File(dir, "Portrait Game")).also {
            it.orientation = 1
            it.useTouchControls = false
            it.saveMeta()
        }
        val restored = Project(project.dir)
        assertEquals(1, restored.orientation)
        assertEquals(false, restored.useTouchControls)
    }

    @Test
    fun androidGameExportValidatesDisplayNameAndPackageId() {
        GameExporter.validate(GameExportConfig("Great Game", "com.studio.greatgame"))
        assertTrue(runCatching { GameExporter.validate(GameExportConfig("", "com.example.game")) }.isFailure)
        assertTrue(runCatching { GameExporter.validate(GameExportConfig("Great Game", "not-a-package")) }.isFailure)
    }
}
