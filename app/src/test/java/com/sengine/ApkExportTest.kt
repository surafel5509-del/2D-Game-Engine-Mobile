package com.sengine

import com.sengine.export.ApkBuilder
import com.sengine.export.AxmlPatcher
import com.sengine.export.GameBuildConfig
import com.sengine.project.Project
import com.sengine.project.Templates
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipFile

/**
 * Exports a real game APK from the built S Engine debug APK (CI only: needs
 * SENGINE_SOURCE_APK, SENGINE_KEYSTORE and SENGINE_EXPORT_OUT). CI then checks the
 * output with `apksigner verify` and `aapt2 dump badging`.
 */
class ApkExportTest {
    @Test
    fun exportStandaloneGameApk() {
        val src = System.getenv("SENGINE_SOURCE_APK")
        val ks = System.getenv("SENGINE_KEYSTORE")
        val out = System.getenv("SENGINE_EXPORT_OUT")
        assumeTrue("export env not set", src != null && ks != null && out != null)

        val store = KeyStore.getInstance("PKCS12")
        File(ks!!).inputStream().use { store.load(it, "sengine".toCharArray()) }
        val alias = store.aliases().toList().first()
        val key = store.getKey(alias, "sengine".toCharArray()) as PrivateKey
        val certs = store.getCertificateChain(alias).map { it as X509Certificate }

        val dir = Files.createTempDirectory("export").toFile()
        val p = Project(File(dir, "Space Run"))
        p.saveMeta()
        Templates.all.first { it.name == "Space Shooter" }.build(p)
        p.saveMeta()
        File(p.dir, ".build_settings.json").writeText("{}") // dotfiles must not be exported

        val cfg = GameBuildConfig("Space Run", "com.sengine.game.spacerun", "1.2.3", 7)
        val steps = ArrayList<String>()
        val t0 = System.currentTimeMillis()
        ApkBuilder.build(File(src!!), p.dir, cfg, key, certs, File(out!!), File(dir, "work")) { s, _ -> steps += s }
        println("SIM export ${File(out).length()} bytes in ${System.currentTimeMillis() - t0} ms, steps=${steps.distinct().size}")

        ZipFile(out).use { z ->
            val names = z.entries().toList().map { it.name }
            assertTrue(names.contains("classes.dex"))
            assertTrue(names.contains("resources.arsc"))
            assertTrue(names.contains("assets/game/project/project.json"))
            assertTrue(names.any { it.startsWith("assets/game/project/scenes/") })
            assertTrue("dotfiles excluded", names.none { it.endsWith(".build_settings.json") })
            assertTrue("old signature removed", names.none { it.endsWith(".RSA") || it.endsWith(".SF") })
            val info = JSONObject(z.getInputStream(z.getEntry("assets/game/build.json")).readBytes().decodeToString())
            assertEquals("com.sengine.game.spacerun", info.getString("package"))
            assertEquals("Space Run", info.getString("project"))
            val manifest = z.getInputStream(z.getEntry("AndroidManifest.xml")).readBytes()
            // Patching again is idempotent for already-renamed packages and exposes the string pool
            val strings = AxmlPatcher.patch(manifest, "com.sengine.game.spacerun", "com.sengine.game.spacerun", null, null, null).strings
            println("SIM export manifest strings: ${strings.filter { it.contains("sengine") || it == "Space Run" || it == "1.2.3" }}")
            assertTrue(strings.contains("com.sengine.game.spacerun"))
            assertTrue(strings.contains("Space Run"))
            assertTrue(strings.contains("1.2.3"))
            // The engine's content provider is declared as ${applicationId}.share, so the export has
            // to rename that authority too - otherwise two installed games would fight over it.
            assertTrue("share provider authority renamed",
                strings.contains("com.sengine.game.spacerun.share"))
            assertTrue("no engine package left in the manifest",
                strings.none { it == "com.sengine.app" || it.startsWith("com.sengine.app.") })
            // The game has to be launchable and has to find the activity that boots the embedded
            // project (ProjectsActivity redirects into PlayerActivity when assets/game/build.json is
            // present, so both declarations must survive the repackaging).
            assertTrue("launcher entry point missing", strings.contains("android.intent.action.MAIN"))
            assertTrue("launcher entry point missing", strings.contains("android.intent.category.LAUNCHER"))
            assertTrue("player activity missing", strings.contains("com.sengine.ui.PlayerActivity"))
            assertTrue("start scene not embedded",
                names.contains("assets/game/project/scenes/Main.scene.json"))
        }
    }
}
