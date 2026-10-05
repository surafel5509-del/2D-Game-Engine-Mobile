package com.sengine.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.sengine.export.ApkBuilder
import com.sengine.export.GameBuildConfig
import com.sengine.export.SigningKeys
import com.sengine.project.Project
import com.sengine.project.ProjectManager
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/** Game build settings and APK export. */
class BuildActivity : AppCompatActivity() {

    private lateinit var project: Project
    private lateinit var nameField: EditText
    private lateinit var pkgField: EditText
    private lateinit var verNameField: EditText
    private lateinit var verCodeField: EditText
    private lateinit var keystoreBtn: TextView
    private lateinit var passField: EditText
    private lateinit var aliasField: EditText
    private lateinit var keyGroup: RadioGroup
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var logText: TextView
    private lateinit var buildBtn: TextView
    private lateinit var resultRow: LinearLayout
    private lateinit var autoVersion: CheckBox
    private var keystoreFile: File? = null
    private var lastApk: File? = null
    private var building = false
    private val handler = Handler(Looper.getMainLooper())

    private val settingsFile get() = File(project.dir, ".build_settings.json")

    private val pickKeystore = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            val f = File(filesDir, "keystores/user_keystore" + (if (uri.toString().lowercase().endsWith(".bks")) ".bks" else ".p12"))
            f.parentFile?.mkdirs()
            contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
            keystoreFile = f
            keystoreBtn.text = "Keystore: ${f.name} ✓"
            keyGroup.check(2)
        } catch (e: Exception) { toast("Can't read keystore: ${e.message}") }
    }

    private val saveApk = registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")) { uri ->
        val apk = lastApk
        if (uri != null && apk != null) try {
            contentResolver.openOutputStream(uri)!!.use { o -> apk.inputStream().use { it.copyTo(o) } }
            toast("APK saved")
        } catch (e: Exception) { toast("Save failed: ${e.message}") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        project = ProjectManager.open(this, intent.getStringExtra("project")!!)
        val saved = try { JSONObject(settingsFile.readText()) } catch (_: Exception) { JSONObject() }

        val root = vbox().apply { setBackgroundColor(C.BG) }
        val bar = hbox().apply { setBackgroundColor(C.HEADER); setPadding(dp(6), dp(4), dp(10), dp(4)) }
        bar.addView(button("←") { finish() })
        bar.addView(label("Build Game APK — ${project.name}", 16f, C.TEXT, true).apply { setPadding(dp(10), 0, 0, 0) }, lp(0, WRAP, 1f))
        root.addView(bar, lp(MATCH, WRAP))

        val body = vbox().apply { setPadding(dp(18), dp(12), dp(18), dp(24)) }
        fun section(t: String) = body.addView(label(t, 12f, C.ACCENT, true), lp(MATCH, WRAP).margins(0, dp(14), 0, dp(6)))
        fun row(title: String, v: android.view.View) {
            val r = hbox()
            r.addView(label(title, 13f, C.DIM), lp(dp(150), WRAP))
            r.addView(v, lp(0, WRAP, 1f))
            body.addView(r, lp(MATCH, WRAP).margins(0, dp(3), 0, dp(3)))
        }

        body.addView(label("Exports a standalone Android game. The APK contains the S Engine runtime and your project " +
            "(scenes, scripts, blueprints, textures, sounds, shaders, models) and opens straight into your start scene.", 12f, C.DIM))

        section("APP")
        nameField = field(saved.optString("name", project.name)); row("App name", nameField)
        pkgField = field(saved.optString("package", ApkBuilder.defaultPackage(project.name))); row("Package name", pkgField)
        verNameField = field(saved.optString("versionName", "1.0")); row("Version name", verNameField)
        verCodeField = field(saved.optInt("versionCode", 1).toString(), numeric = true); row("Version code", verCodeField)
        autoVersion = CheckBox(this).apply { text = "Auto-increment version code after each build"; setTextColor(C.TEXT); isChecked = saved.optBoolean("autoVersion", true) }
        body.addView(autoVersion)
        row("Orientation", label(if (project.orientation == 1) "Portrait" else "Landscape", 13f))
        row("Start scene", label(project.startScene, 13f))
        row("Min Android", label("8.0 (API 26)", 13f))

        section("SIGNING")
        keyGroup = RadioGroup(this)
        keyGroup.addView(RadioButton(this).apply { id = 1; text = "Device key (automatic, stored in Android KeyStore)"; setTextColor(C.TEXT) })
        keyGroup.addView(RadioButton(this).apply { id = 2; text = "My keystore file (.p12 / .pfx / .bks)"; setTextColor(C.TEXT) })
        keyGroup.check(1)
        body.addView(keyGroup)
        keystoreBtn = button("Choose keystore…") { pickKeystore.launch(arrayOf("*/*")) }.apply { textSize = 12f }
        body.addView(keystoreBtn, lp(WRAP, WRAP).margins(dp(30), dp(4), 0, dp(4)))
        passField = field("").apply { hint = "Keystore password"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        row("Password", passField)
        aliasField = field(saved.optString("alias", "")).apply { hint = "(first key)" }
        row("Key alias", aliasField)
        body.addView(label("Keep the same key for every release of a game — Android only installs updates signed with the same key. " +
            "The device key never leaves this phone; use your own keystore to build updates from other devices.", 11f, C.DIM),
            lp(MATCH, WRAP).margins(0, dp(4), 0, 0))

        section("BUILD")
        buildBtn = button("🔨  Build APK", C.ACCENT, 0xFFFFFFFF.toInt()) { startBuild() }.apply { textSize = 16f; gravity = Gravity.CENTER }
        body.addView(buildBtn, lp(MATCH, dp(52)))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progress = 0 }
        body.addView(progress, lp(MATCH, WRAP).margins(0, dp(10), 0, 0))
        status = label("Ready", 13f, C.TEXT)
        body.addView(status)
        resultRow = hbox().apply { visibility = android.view.View.GONE }
        resultRow.addView(button("📲 Install", C.GREEN, 0xFFFFFFFF.toInt()) { install() }, lp(0, WRAP, 1f).margins(0, 0, dp(4), 0))
        resultRow.addView(button("↗ Share") { share() }, lp(0, WRAP, 1f).margins(dp(4), 0, dp(4), 0))
        resultRow.addView(button("💾 Save…") { lastApk?.let { saveApk.launch(it.name) } }, lp(0, WRAP, 1f).margins(dp(4), 0, 0, 0))
        body.addView(resultRow, lp(MATCH, WRAP).margins(0, dp(10), 0, 0))
        logText = label("", 11f, C.DIM).apply { typeface = android.graphics.Typeface.MONOSPACE }
        body.addView(logText, lp(MATCH, WRAP).margins(0, dp(10), 0, 0))

        root.addView(ScrollView(this).apply { addView(body) }, lp(MATCH, 0, 1f))
        setContentView(root)

        if (intent.getBooleanExtra("autobuild", false)) startBuild()
    }

    private fun log(s: String) = handler.post { logText.append(s + "\n") }

    private fun saveSettings() {
        val o = JSONObject()
            .put("name", nameField.text.toString()).put("package", pkgField.text.toString())
            .put("versionName", verNameField.text.toString()).put("versionCode", verCodeField.text.toString().toIntOrNull() ?: 1)
            .put("alias", aliasField.text.toString()).put("autoVersion", autoVersion.isChecked)
        settingsFile.writeText(o.toString(2))
    }

    private fun startBuild() {
        if (building) return
        val cfg = GameBuildConfig(
            nameField.text.toString().trim(), pkgField.text.toString().trim(),
            verNameField.text.toString().trim().ifBlank { "1.0" }, verCodeField.text.toString().toIntOrNull() ?: 1)
        if (cfg.appName.isBlank()) { toast("Enter an app name"); return }
        if (!ApkBuilder.validPackage(cfg.packageName)) { toast("Invalid package name (e.g. com.mystudio.mygame)"); return }
        val useFile = keyGroup.checkedRadioButtonId == 2
        val ks = keystoreFile
        if (useFile && ks == null) { toast("Choose a keystore file first"); return }
        saveSettings()
        building = true
        buildBtn.alpha = 0.5f
        resultRow.visibility = android.view.View.GONE
        logText.text = ""
        val pass = passField.text.toString().toCharArray()
        val alias = aliasField.text.toString().trim()
        val out = customOutput() ?: File(File(filesDir, "builds"), "${cfg.appName.replace(Regex("[^A-Za-z0-9_-]"), "_")}-${cfg.versionName}.apk")
        thread(name = "apk-build") {
            val t0 = System.currentTimeMillis()
            try {
                log("S Engine build • ${cfg.packageName} v${cfg.versionName} (${cfg.versionCode})")
                val id = if (useFile) SigningKeys.fromFile(ks!!, pass, alias) else SigningKeys.deviceKey()
                log("Signing with: ${id.description}")
                val src = File(applicationInfo.sourceDir)
                log("Runtime: ${src.name} (${src.length() / 1024} KB)")
                ApkBuilder.build(src, project.dir, cfg, id.key, id.certs, out, File(cacheDir, "build")) { msg, f ->
                    handler.post { status.text = msg; progress.progress = (f * 1000).toInt() }
                }
                val secs = (System.currentTimeMillis() - t0) / 1000f
                log(String.format("✔ Built %s (%.1f MB) in %.1fs", out.name, out.length() / 1048576f, secs))
                handler.post {
                    lastApk = out
                    status.text = "✔ Build succeeded: ${out.name}"
                    resultRow.visibility = android.view.View.VISIBLE
                    if (autoVersion.isChecked) { verCodeField.setText((cfg.versionCode + 1).toString()); saveSettings() }
                }
            } catch (e: Throwable) {
                log("✖ Build failed: ${e.javaClass.simpleName}: ${e.message}")
                handler.post { status.text = "✖ Build failed"; progress.progress = 0 }
            } finally {
                handler.post { building = false; buildBtn.alpha = 1f }
            }
        }
    }

    /** Debug automation: write the APK to the app's external files dir. */
    private fun customOutput(): File? = intent.getStringExtra("output")?.let { File(getExternalFilesDir(null), it) }

    private fun uriFor(f: File): Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)

    private fun install() {
        val apk = lastApk ?: return
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            toast("Allow S Engine to install apps, then tap Install again")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(apk), "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { toast("Can't open installer: ${e.message}") }
    }

    private fun share() {
        val apk = lastApk ?: return
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/vnd.android.package-archive")
                .putExtra(Intent.EXTRA_STREAM, uriFor(apk)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share game APK"))
        } catch (e: Exception) { toast("Share failed: ${e.message}") }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
