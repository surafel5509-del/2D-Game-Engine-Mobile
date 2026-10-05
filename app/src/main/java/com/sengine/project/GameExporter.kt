package com.sengine.project

import android.content.Context
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Produces a player-only Android Studio project containing this project's game data. */
data class GameExportConfig(val displayName: String, val applicationId: String)

object GameExporter {
    private const val TEMPLATE = "export-template.zip"
    private const val MAX_PROJECT_CONTENT_BYTES = 240L * 1024L * 1024L

    fun validate(config: GameExportConfig) {
        require(config.displayName.trim().isNotEmpty()) { "Game title is required" }
        require(config.displayName.length <= 48) { "Game title must be 48 characters or fewer" }
        require(config.applicationId.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*){1,}"))) {
            "Use a valid package id such as com.example.mygame"
        }
    }

    /**
     * Writes a portable Android Studio/CI project as a zip. The generated project
     * contains the current player runtime source, but not the editor UI.
     */
    fun exportAndroidProject(context: Context, project: Project, config: GameExportConfig, output: OutputStream) {
        validate(config)
        val rawProjectBytes = project.dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        require(rawProjectBytes <= MAX_PROJECT_CONTENT_BYTES) { "Project contents exceed the 240 MB standalone game-export limit" }
        val stagedProject = File.createTempFile("sengine-game-project-", ".zip", context.cacheDir)
        try {
            stagedProject.outputStream().use { ProjectManager.exportZip(project, it) }
            ZipOutputStream(output.buffered()).use { zip ->
                writeText(zip, "settings.gradle.kts", settingsGradle)
                writeText(zip, "build.gradle.kts", rootGradle)
                writeText(zip, "gradle.properties", gradleProperties)
                writeText(zip, "app/build.gradle.kts", appGradle(config.applicationId))
                writeText(zip, "app/src/main/AndroidManifest.xml", manifest(project.orientation))
                writeText(zip, "app/src/main/res/values/strings.xml", strings(config.displayName))
                writeText(zip, "app/src/main/res/values/colors.xml", colors)
                writeText(zip, "app/src/main/res/values/themes.xml", themes)
                writeText(zip, "app/src/main/res/drawable/game_icon.xml", gameIcon)
                writeText(zip, "README.md", readme(config, project))
                writeText(zip, ".github/workflows/build-game.yml", workflow)
                writeText(zip, ".gitignore", gitignore)
                writeText(zip, "game-export.json", exportMetadata(config, project))

                zip.putNextEntry(ZipEntry("app/src/main/assets/sengine_project.zip"))
                stagedProject.inputStream().buffered().use { it.copyTo(zip) }
                zip.closeEntry()

                context.assets.open(TEMPLATE).use { template ->
                    ZipInputStream(template).use { source ->
                        while (true) {
                            val entry = source.nextEntry ?: break
                            if (entry.isDirectory) continue
                            require(!entry.name.startsWith("/") && ".." !in entry.name.split('/')) { "Invalid runtime template path" }
                            zip.putNextEntry(ZipEntry(entry.name))
                            source.copyTo(zip)
                            zip.closeEntry()
                            source.closeEntry()
                        }
                    }
                }
            }
        } finally {
            stagedProject.delete()
        }
    }

    private fun writeText(zip: ZipOutputStream, path: String, content: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun json(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r")

    private fun strings(name: String) = """<resources><string name="app_name">${xml(name)}</string></resources>
"""

    private fun appGradle(applicationId: String) = """plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStoreFile = providers.gradleProperty("SENGINE_KEYSTORE_PATH").orNull ?: providers.environmentVariable("SENGINE_KEYSTORE_PATH").orNull
val releaseStorePassword = providers.gradleProperty("SENGINE_KEYSTORE_PASSWORD").orNull ?: providers.environmentVariable("SENGINE_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.gradleProperty("SENGINE_KEY_ALIAS").orNull ?: providers.environmentVariable("SENGINE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.gradleProperty("SENGINE_KEY_PASSWORD").orNull ?: providers.environmentVariable("SENGINE_KEY_PASSWORD").orNull
val releaseSigningEnabled = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }

android {
    namespace = "$applicationId"
    compileSdk = 34
    defaultConfig {
        applicationId = "$applicationId"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }
    signingConfigs {
        create("release") {
            if (releaseSigningEnabled) {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningEnabled) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("org.mozilla:rhino:1.7.13")
}
"""

    private fun manifest(orientation: Int): String {
        val requested = if (orientation == 1) "sensorPortrait" else "sensorLandscape"
        return """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-feature android:glEsVersion="0x00020000" android:required="true" />
    <application android:allowBackup="false" android:label="@string/app_name" android:icon="@drawable/game_icon"
        android:supportsRtl="true" android:theme="@style/Theme.Game">
        <activity android:name="com.sengine.ui.PlayerActivity" android:exported="true"
            android:screenOrientation="$requested"
            android:configChanges="orientation|screenSize|screenLayout|keyboardHidden|keyboard">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
"""
    }

    private fun exportMetadata(config: GameExportConfig, project: Project) = """{
  "format": 1,
  "engine": "S Engine 2D",
  "gameName": "${json(config.displayName)}",
  "applicationId": "${json(config.applicationId)}",
  "project": "${json(project.name)}",
  "orientation": "${if (project.orientation == 1) "portrait" else "landscape"}",
  "build": "Android Gradle project; debug APK from CI or local Android SDK"
}
"""

    private fun readme(config: GameExportConfig, project: Project) = """# ${config.displayName}

Standalone Android game project exported from **S Engine 2D**.

- Package id: `${config.applicationId}`
- Source project: `${project.name}`
- Runtime: the 2D-only S Engine player, with only the game's scenes/assets bundled (the editor is not included).
- JavaScript behaviors run with Mozilla Rhino; rendering uses OpenGL ES 2.0.

## Build locally

Requirements: JDK 17, Android SDK platform/build-tools 34, Android SDK environment variables, and Gradle 8.7.

```bash
gradle assembleDebug
```

Install `app/build/outputs/apk/debug/app-debug.apk` on a device or emulator. Android Studio can open this folder as a Gradle project.

## Build with GitHub Actions

Create a GitHub repository from these files and push the project. The included workflow builds an installable debug APK and publishes it as the `game-debug-apk` artifact. Download the APK from the workflow run, then use **S Engine → Install Game APK** (or Android's package installer) on your device.

For a privately signed release, configure repository secrets `SENGINE_KEYSTORE_BASE64`, `SENGINE_KEYSTORE_PASSWORD`, `SENGINE_KEY_ALIAS`, and `SENGINE_KEY_PASSWORD`, then push a `v*` tag. The release artifact is not signed with Android's public debug key.

## Project contents

The project's scenes, scripts, and assets are embedded in `app/src/main/assets/sengine_project.zip` and refreshed on first launch/update. Engine player sources are under `app/src/main/java/com/sengine/`. Change the game icon in `app/src/main/res/drawable/game_icon.xml` and update the package id in `app/build.gradle.kts` before publishing.
"""

    private val settingsGradle = """pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "S Engine Game"
include(":app")
"""

    private val rootGradle = """plugins {
    id("com.android.application") version "8.3.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.23" apply false
}
"""

    private val gradleProperties = """org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
"""

    private val colors = """<resources>
    <color name="game_bg">#FF101522</color>
    <color name="game_accent">#FF4C8DFF</color>
</resources>
"""

    private val themes = """<resources>
    <style name="Theme.Game" parent="Theme.AppCompat.NoActionBar">
        <item name="colorAccent">@color/game_accent</item>
        <item name="android:windowBackground">@color/game_bg</item>
        <item name="android:statusBarColor">#FF000000</item>
        <item name="android:navigationBarColor">#FF000000</item>
    </style>
</resources>
"""

    private val gameIcon = """<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="48dp" android:height="48dp" android:viewportWidth="48" android:viewportHeight="48">
    <path android:fillColor="#FF151C2C" android:pathData="M0,0h48v48h-48z" />
    <path android:fillColor="#FF4C8DFF" android:pathData="M8,13h32v22h-32z" />
    <path android:fillColor="#FFFFFFFF" android:pathData="M14,20h4v4h-4zM10,24h4v4h-4zM14,28h4v4h-4zM18,24h4v4h-4zM29,20h5v5h-5zM35,27h5v5h-5z" />
</vector>
"""

    private val gitignore = """.gradle/
local.properties
.idea/
.DS_Store
build/
app/build/
*.iml
*.apk
*.aab
*.jks
*.keystore
"""

    private val workflow = """name: Build Android game APK
on:
  push:
    branches: ['**']
    tags: ['v*']
  pull_request:
  workflow_dispatch:
permissions:
  contents: read
jobs:
  debug:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '8.7'
      - name: Build player APK
        run: gradle assembleDebug --stacktrace
      - uses: actions/upload-artifact@v4
        with:
          name: game-debug-apk
          path: app/build/outputs/apk/debug/app-debug.apk
          if-no-files-found: error
          retention-days: 30
  release:
    if: startsWith(github.ref, 'refs/tags/v')
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '8.7'
      - name: Validate release signing secrets
        env:
          KEYSTORE_BASE64: ${'$'}{{ secrets.SENGINE_KEYSTORE_BASE64 }}
          KEYSTORE_PASSWORD: ${'$'}{{ secrets.SENGINE_KEYSTORE_PASSWORD }}
          KEY_ALIAS: ${'$'}{{ secrets.SENGINE_KEY_ALIAS }}
          KEY_PASSWORD: ${'$'}{{ secrets.SENGINE_KEY_PASSWORD }}
        run: test -n "${'$'}KEYSTORE_BASE64" && test -n "${'$'}KEYSTORE_PASSWORD" && test -n "${'$'}KEY_ALIAS" && test -n "${'$'}KEY_PASSWORD"
      - name: Restore private keystore
        env:
          KEYSTORE_BASE64: ${'$'}{{ secrets.SENGINE_KEYSTORE_BASE64 }}
        run: echo "${'$'}KEYSTORE_BASE64" | base64 --decode > "${'$'}RUNNER_TEMP/game-release.jks"
      - name: Build signed release APK
        env:
          SENGINE_KEYSTORE_PATH: ${'$'}{{ runner.temp }}/game-release.jks
          SENGINE_KEYSTORE_PASSWORD: ${'$'}{{ secrets.SENGINE_KEYSTORE_PASSWORD }}
          SENGINE_KEY_ALIAS: ${'$'}{{ secrets.SENGINE_KEY_ALIAS }}
          SENGINE_KEY_PASSWORD: ${'$'}{{ secrets.SENGINE_KEY_PASSWORD }}
        run: gradle assembleRelease --stacktrace
      - uses: actions/upload-artifact@v4
        with:
          name: game-release-apk
          path: app/build/outputs/apk/release/app-release.apk
          if-no-files-found: error
          retention-days: 60
"""
