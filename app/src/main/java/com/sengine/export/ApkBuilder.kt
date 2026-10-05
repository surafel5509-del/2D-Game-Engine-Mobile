package com.sengine.export

import org.json.JSONObject
import java.io.File
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class GameBuildConfig(
    val appName: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Int,
)

/**
 * Builds a standalone, installable game APK: the S Engine runtime (this app's own APK) is
 * repackaged with the project embedded under assets/game/, the manifest is patched with the
 * game's package name / label / version, and the result is zip-aligned and v2-signed.
 */
object ApkBuilder {
    const val SOURCE_PACKAGE = "com.sengine.app"
    const val GAME_ASSETS = "assets/game/"
    private val PACKAGE_RE = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
    private val NO_COMPRESS = setOf("png", "jpg", "jpeg", "webp", "ogg", "mp3", "m4a", "arsc")

    fun validPackage(p: String) = PACKAGE_RE.matches(p) && p != SOURCE_PACKAGE

    fun defaultPackage(projectName: String): String {
        val clean = projectName.lowercase().replace(Regex("[^a-z0-9]"), "")
        val seg = if (clean.isEmpty() || !clean[0].isLetter()) "game$clean" else clean
        return "com.sengine.game.$seg"
    }

    private fun isSignatureFile(name: String): Boolean {
        if (!name.startsWith("META-INF/")) return false
        val n = name.substringAfterLast('/').uppercase()
        return n == "MANIFEST.MF" || n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC")
    }

    fun build(
        sourceApk: File,
        projectDir: File,
        cfg: GameBuildConfig,
        key: PrivateKey,
        certs: List<X509Certificate>,
        out: File,
        workDir: File,
        progress: (String, Float) -> Unit = { _, _ -> },
    ): File {
        require(validPackage(cfg.packageName)) { "Invalid package name: ${cfg.packageName}" }
        require(cfg.appName.isNotBlank()) { "App name is empty" }
        workDir.mkdirs()
        val entriesFile = File(workDir, "entries.tmp")
        lateinit var writer: ZipWriter
        progress("Reading S Engine runtime…", 0.02f)
        ZipFile(sourceApk).use { zip ->
            val entries = zip.entries().toList().filter { !it.isDirectory }
            entriesFile.outputStream().buffered(1 shl 16).use { os ->
                writer = ZipWriter(os)
                entries.forEachIndexed { i, e ->
                    val name = e.name
                    if (isSignatureFile(name) || name.startsWith(GAME_ASSETS)) return@forEachIndexed
                    var data = zip.getInputStream(e).use { it.readBytes() }
                    if (name == "AndroidManifest.xml") {
                        progress("Patching manifest…", 0.05f)
                        data = AxmlPatcher.patch(data, SOURCE_PACKAGE, cfg.packageName, cfg.appName, cfg.versionName, cfg.versionCode).bytes
                    }
                    val stored = e.method == ZipEntry.STORED || name == "resources.arsc"
                    writer.add(name, data, compress = !stored, align = if (name.endsWith(".so")) 4096 else 4)
                    if (i % 20 == 0) progress("Packing runtime ($i/${entries.size})…", 0.05f + 0.6f * i / entries.size)
                }
                progress("Embedding game data…", 0.7f)
                val base = projectDir.canonicalFile
                val files = base.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.sortedBy { it.path }.toList()
                for (f in files) {
                    val rel = f.canonicalFile.relativeTo(base).invariantSeparatorsPath
                    val ext = f.extension.lowercase()
                    writer.add(GAME_ASSETS + "project/" + rel, f.readBytes(), compress = ext !in NO_COMPRESS)
                }
                val info = JSONObject()
                    .put("name", cfg.appName).put("package", cfg.packageName)
                    .put("versionName", cfg.versionName).put("versionCode", cfg.versionCode)
                    .put("project", projectDir.name).put("builtAt", System.currentTimeMillis())
                writer.add(GAME_ASSETS + "build.json", info.toString(2).toByteArray(), compress = true)
            }
        }
        progress("Signing (APK Signature Scheme v2)…", 0.85f)
        out.parentFile?.mkdirs()
        ApkSignerV2.sign(entriesFile, writer.centralDirectory(), writer.count, key, certs, out)
        entriesFile.delete()
        progress("Done", 1f)
        return out
    }
}
