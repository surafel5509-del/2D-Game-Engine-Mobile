package com.sengine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Offline resource check.
 *
 * Android resource linking (AAPT2) fails the whole build when a `@type/name` reference cannot be
 * resolved - for example a `android:textColorSecondary` pointing at a colour that was never
 * declared, or a style whose dotted implicit parent does not exist. That failure only shows up in
 * `assembleDebug`, late in the pipeline, so this test reproduces the reference resolution on the
 * JVM: every reference in `res/` and in the manifest must be declared, and every style's implicit
 * parent chain must exist.
 */
class ResourceLinkTest {

    private fun resRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidates = listOf(File(dir, "app/src/main/res"), File(dir, "src/main/res"))
            candidates.firstOrNull { it.isDirectory }?.let { return it }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate res/ from ${System.getProperty("user.dir")}")
    }

    private fun resourceName(file: File): String = file.name.substringBeforeLast('.')

    private fun filesUnder(dir: File, suffix: String = ".xml"): List<File> =
        dir.walkTopDown().filter { it.isFile && it.name.endsWith(suffix) }.toList()

    private val reference = Regex("@(color|string|style|drawable|mipmap|dimen|bool|integer|array|fraction)/([A-Za-z0-9_.]+)")

    @Test
    fun everyResourceReferenceResolves() {
        val res = resRoot()
        val app = res.parentFile.parentFile  // .../app/src/main

        val declared = mutableMapOf<String, MutableSet<String>>()
        fun declare(type: String, name: String) =
            declared.getOrPut(type) { mutableSetOf() }.add(name)

        for (f in filesUnder(res)) {
            when {
                f.parentFile.name == "values" -> {
                    val text = f.readText()
                    Regex("<(color|string|style|dimen|bool|integer|array|string-array|integer-array|fraction)\\s+name=\"([^\"]+)\"")
                        .findAll(text).forEach { declare(it.groupValues[1], it.groupValues[2]) }
                }
                f.parentFile.name.startsWith("drawable") -> declare("drawable", resourceName(f))
                f.parentFile.name.startsWith("mipmap") -> declare("mipmap", resourceName(f))
                f.parentFile.name.startsWith("layout") -> declare("layout", resourceName(f))
                f.parentFile.name == "xml" -> declare("xml", resourceName(f))
            }
        }

        val missing = mutableListOf<String>()
        val sources = filesUnder(res) + listOfNotNull(File(app, "AndroidManifest.xml").takeIf { it.isFile })
        for (f in sources) {
            for (m in reference.findAll(f.readText())) {
                val (type, name) = m.destructured
                if (name !in declared[type].orEmpty()) missing += "${f.name}: @$type/$name"
            }
        }
        assertTrue("unresolved resource references:\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    /**
     * A dotted style without an explicit `parent` inherits from the style named by the part before
     * the last dot (`SEngine.Panel` -> `SEngine`); AAPT2 fails the build when that style does not
     * exist. Styles with an explicit parent are roots and need nothing above them.
     */
    @Test
    fun everyStyleImplicitParentExists() {
        val res = resRoot()
        val explicitParent = mutableMapOf<String, String>()
        val styleTag = Regex("<style\\s+name=\"([^\"]+)\"(?:\\s+parent=\"([^\"]+)\")?")
        for (f in filesUnder(File(res, "values"))) {
            styleTag.findAll(f.readText()).forEach {
                explicitParent[it.groupValues[1]] = it.groupValues[2]
            }
        }
        val styles = explicitParent.keys
        val dangling = mutableListOf<String>()
        for (style in styles) {
            var current = style
            while (explicitParent[current].isNullOrEmpty() && current.contains('.')) {
                val parent = current.substringBeforeLast('.')
                if (parent !in styles) { dangling += "$current -> $parent"; break }
                current = parent
            }
        }
        assertTrue("styles with a missing implicit parent:\n" + dangling.joinToString("\n"), dangling.isEmpty())
    }
}
