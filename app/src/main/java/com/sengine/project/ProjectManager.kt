package com.sengine.project

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.security.MessageDigest

object ProjectManager {
    fun root(ctx: Context) = File(ctx.filesDir, "projects").also { it.mkdirs() }

    fun list(ctx: Context): List<Project> =
        (root(ctx).listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .map { Project(it) }
            .sortedByDescending { it.dir.lastModified() }

    fun open(ctx: Context, name: String): Project {
        val safe = sanitize(name).ifBlank { throw IllegalArgumentException("Invalid project name") }
        val base = root(ctx).canonicalFile
        val dir = File(base, safe).canonicalFile
        require(dir.path.startsWith(base.path + File.separator)) { "Invalid project path" }
        return Project(dir)
    }

    fun sanitize(name: String) = name.trim().replace(Regex("[^A-Za-z0-9 _\\-]"), "").take(40)

    fun exists(ctx: Context, name: String) = File(root(ctx), sanitize(name)).exists()

    fun create(ctx: Context, name: String, template: Templates.Template): Project {
        val safe = sanitize(name).ifBlank { throw IllegalArgumentException("Invalid project name") }
        val p = Project(File(root(ctx), safe))
        p.saveMeta()
        template.build(p)
        p.saveMeta()
        return p
    }

    fun delete(ctx: Context, p: Project): Boolean {
        if (!isInProjectsRoot(ctx, p)) return false
        return p.dir.canonicalFile.deleteRecursively()
    }

    fun rename(ctx: Context, p: Project, newName: String): Project? {
        if (!isInProjectsRoot(ctx, p)) return null
        val safe = sanitize(newName).ifBlank { return null }
        val target = File(root(ctx), safe)
        if (target.exists()) return null
        return if (p.dir.renameTo(target)) Project(target) else null
    }

    fun duplicate(ctx: Context, p: Project): Project {
        require(isInProjectsRoot(ctx, p)) { "Project is outside the projects directory" }
        var n = "${p.name} Copy"
        var i = 2
        while (exists(ctx, n)) n = "${p.name} Copy ${i++}"
        val target = File(root(ctx), sanitize(n))
        p.dir.copyRecursively(target)
        return Project(target)
    }

    /** Installs/refreshes the immutable game project bundled into a standalone APK. */
    fun installBundled(ctx: Context, assetPath: String = "sengine_project.zip"): Project {
        val digest = MessageDigest.getInstance("SHA-256")
        ctx.assets.open(assetPath).buffered().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val prefs = ctx.getSharedPreferences("sengine-bundled-game", Context.MODE_PRIVATE)
        val prefix = assetPath.replace(Regex("[^A-Za-z0-9_]"), "_")
        val storedHash = prefs.getString("$prefix.hash", null)
        val storedName = prefs.getString("$prefix.project", null)
        if (storedHash == hash && !storedName.isNullOrBlank()) {
            try {
                val cached = open(ctx, storedName)
                if (cached.sceneExists(cached.startScene)) return cached
            } catch (_: Exception) { }
        }
        if (!storedName.isNullOrBlank()) {
            try { delete(ctx, open(ctx, storedName)) } catch (_: Exception) { }
        }
        val installed = ctx.assets.open(assetPath).use { importZip(ctx, it, "Game") }
        prefs.edit().putString("$prefix.hash", hash).putString("$prefix.project", installed.name).apply()
        return installed
    }

    fun exportZip(p: Project, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            p.dir.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(p.dir).path.replace('\\', '/')
                zip.putNextEntry(ZipEntry("${p.name}/$rel"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Imports a project zip exported by S Engine with zip-slip and zip-bomb limits. */
    fun importZip(ctx: Context, input: InputStream, fallbackName: String): Project {
        val tmp = File(ctx.cacheDir, "import_${System.currentTimeMillis()}_${System.nanoTime()}")
        tmp.mkdirs()
        try {
            ZipInputStream(input).use { zip ->
                val prefix = tmp.canonicalPath + File.separator
                val buffer = ByteArray(16 * 1024)
                var entries = 0
                var totalBytes = 0L
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++entries > MAX_IMPORT_ENTRIES) throw IllegalArgumentException("Project archive has too many files")
                    val f = File(tmp, entry.name).canonicalFile
                    if (!f.path.startsWith(prefix)) throw IllegalArgumentException("Unsafe path in project archive")
                    if (entry.isDirectory) f.mkdirs() else {
                        f.parentFile?.mkdirs()
                        f.outputStream().buffered().use { out ->
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                totalBytes += count
                                if (totalBytes > MAX_IMPORT_BYTES) throw IllegalArgumentException("Project archive is too large")
                                out.write(buffer, 0, count)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            // Project root is the folder containing project.json.
            val metaDir = tmp.walkTopDown().firstOrNull { it.isFile && it.name == "project.json" }?.parentFile
                ?: throw IllegalArgumentException("Not an S Engine project (project.json missing)")
            var name = sanitize(if (metaDir == tmp) fallbackName else metaDir.name).ifBlank { "Imported" }
            val base = name
            var i = 2
            while (exists(ctx, name)) name = "$base ${i++}"
            val target = File(root(ctx), name)
            metaDir.copyRecursively(target)
            return Project(target)
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun isInProjectsRoot(ctx: Context, project: Project): Boolean = try {
        project.dir.canonicalFile.parentFile == root(ctx).canonicalFile && project.dir.name.isNotBlank()
    } catch (_: Exception) { false }

    private const val MAX_IMPORT_ENTRIES = 20_000
    private const val MAX_IMPORT_BYTES = 256L * 1024L * 1024L
}
