package com.sengine.project

import com.sengine.engine.core.AssetKind
import com.sengine.engine.core.Scene
import com.sengine.engine.core.SceneSerializer
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A game project stored in app-private storage. */
class Project(val dir: File) {
    val name: String get() = dir.name
    val assetsDir = File(dir, "assets")
    val scenesDir = File(dir, "scenes")
    private val metaFile = File(dir, "project.json")

    var startScene = "Main"
    var created = System.currentTimeMillis()
    var orientation = 0 // 0 landscape, 1 portrait
    var useTouchControls = true

    init {
        if (metaFile.exists()) {
            try {
                val o = JSONObject(metaFile.readText())
                startScene = o.optString("startScene", "Main")
                created = o.optLong("created", created)
                orientation = o.optInt("orientation", 0).coerceIn(0, 1)
                useTouchControls = o.optBoolean("touchControls", true)
            } catch (_: Exception) {
                // A damaged project file should not prevent opening the editor.
            }
        }
    }

    fun saveMeta() {
        dir.mkdirs(); assetsDir.mkdirs(); scenesDir.mkdirs()
        val o = JSONObject()
        o.put("name", name)
        o.put("engine", "S Engine 2.1")
        o.put("startScene", startScene)
        o.put("created", created)
        o.put("orientation", orientation.coerceIn(0, 1))
        o.put("touchControls", useTouchControls)
        atomicWrite(metaFile, o.toString(2))
    }

    fun sceneFile(n: String): File = safeChild(scenesDir, "$n.scene.json")

    fun sceneExists(n: String) = try { sceneFile(n).isFile } catch (_: IllegalArgumentException) { false }

    fun listScenes(): List<String> =
        (scenesDir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".scene.json") }
            .map { it.name.removeSuffix(".scene.json") }
            .sorted()

    fun loadScene(n: String): Scene {
        val f = sceneFile(n)
        if (!f.exists()) return Scene(n)
        return SceneSerializer.fromJson(JSONObject(f.readText())).also { it.name = n }
    }

    fun saveScene(scene: Scene) {
        scenesDir.mkdirs()
        atomicWrite(sceneFile(scene.name), SceneSerializer.toJson(scene).toString(1))
    }

    fun deleteScene(n: String) = sceneFile(n).delete()

    fun assetFile(n: String): File = safeChild(assetsDir, n)

    fun listAssets(kind: AssetKind? = null): List<String> =
        (assetsDir.listFiles() ?: emptyArray())
            .filter { it.isFile && (kind == null || AssetKind.of(it.name) == kind) }
            .map { it.name }
            .sortedWith(compareBy({ AssetKind.of(it)?.ordinal ?: 9 }, { it.lowercase() }))

    fun readAsset(n: String): String? = try { assetFile(n).takeIf { it.isFile }?.readText() } catch (_: IllegalArgumentException) { null }

    fun writeAsset(n: String, text: String) {
        assetsDir.mkdirs()
        atomicWrite(assetFile(n), text)
    }

    /** Stream-imports a binary asset using a temporary sibling file, so interruption cannot leave a truncated asset. */
    fun writeAsset(n: String, source: InputStream) {
        assetsDir.mkdirs()
        val destination = assetFile(n)
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, ".${destination.name}.${System.nanoTime()}.tmp")
        try {
            temporary.outputStream().buffered().use { source.copyTo(it) }
            moveIntoPlace(temporary, destination)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    fun uniqueAssetName(base: String): String {
        val safeBase = File(base).name
        if (!assetFile(safeBase).exists()) return safeBase
        val stem = safeBase.substringBeforeLast('.')
        val ext = safeBase.substringAfterLast('.', "")
        var i = 1
        while (assetFile("${stem}_$i.$ext").exists()) i++
        return "${stem}_$i.$ext"
    }

    private fun safeChild(parent: File, child: String): File {
        require(child.isNotBlank() && child != "." && child != "..") { "Invalid project path" }
        val canonicalRoot = parent.canonicalFile
        val result = File(canonicalRoot, child).canonicalFile
        require(result.path.startsWith(canonicalRoot.path + File.separator)) { "Project path escapes its directory" }
        return result
    }

    private fun atomicWrite(destination: File, content: String) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, ".${destination.name}.${System.nanoTime()}.tmp")
        try {
            temporary.outputStream().bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(content)
                writer.flush()
            }
            moveIntoPlace(temporary, destination)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun moveIntoPlace(temporary: File, destination: File) {
        try {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
