package com.sengine.export

import android.content.Context
import com.sengine.project.Project
import org.json.JSONObject
import java.io.File

/** When S Engine runs as an exported game, the project is embedded in assets/game/. */
object GameRuntime {
    private var cached: Project? = null
    private var checked = false

    fun buildInfo(ctx: Context): JSONObject? = try {
        ctx.assets.open("game/build.json").use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
    } catch (_: Exception) { null }

    fun isStandalone(ctx: Context): Boolean = buildInfo(ctx) != null

    /** Extracts the embedded project (once per build) and returns it, or null when running as the editor. */
    fun standaloneProject(ctx: Context): Project? {
        if (checked) return cached
        checked = true
        val info = buildInfo(ctx) ?: return null
        val name = info.optString("project", "Game").ifBlank { "Game" }
        val dir = File(File(ctx.filesDir, "game"), name)
        val stampFile = File(dir, ".build")
        val stamp = info.optLong("builtAt").toString()
        if (!dir.exists() || !stampFile.exists() || stampFile.readText() != stamp) {
            dir.deleteRecursively(); dir.mkdirs()
            copyAssetDir(ctx, "game/project", dir)
            stampFile.writeText(stamp)
        }
        cached = Project(dir)
        return cached
    }

    private fun copyAssetDir(ctx: Context, path: String, dest: File) {
        val list = ctx.assets.list(path) ?: return
        for (n in list) {
            val child = "$path/$n"
            val sub = ctx.assets.list(child)
            if (sub != null && sub.isNotEmpty()) { File(dest, n).mkdirs(); copyAssetDir(ctx, child, File(dest, n)) }
            else ctx.assets.open(child).use { input -> File(dest, n).outputStream().use { input.copyTo(it) } }
        }
    }
}
