plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStoreFile = providers.gradleProperty("SENGINE_KEYSTORE_PATH").orNull
    ?: providers.environmentVariable("SENGINE_KEYSTORE_PATH").orNull
val releaseStorePassword = providers.gradleProperty("SENGINE_KEYSTORE_PASSWORD").orNull
    ?: providers.environmentVariable("SENGINE_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.gradleProperty("SENGINE_KEY_ALIAS").orNull
    ?: providers.environmentVariable("SENGINE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.gradleProperty("SENGINE_KEY_PASSWORD").orNull
    ?: providers.environmentVariable("SENGINE_KEY_PASSWORD").orNull
val releaseSigningEnabled = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
    .all { !it.isNullOrBlank() }

android {
    namespace = "com.sengine"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sengine.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "2.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
            // Never sign a publishing build with Android's publicly known debug key.
            // Configure the four SENGINE_* values to produce a signed release APK.
            if (releaseSigningEnabled) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // Rhino 1.7.14 hits Android's javax.lang.model APIs; 1.7.13 is the supported runtime.
    implementation("org.mozilla:rhino:1.7.13")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("failed", "passed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}
