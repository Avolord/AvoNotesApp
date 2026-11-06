import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/*
 * Release signing. Looked up, in order:
 *  1. keystore.properties in the project root (local builds; git-ignored), with
 *     storeFile, storePassword, keyAlias, keyPassword
 *  2. environment variables (CI): AVONOTES_KEYSTORE (path), AVONOTES_KEYSTORE_PASSWORD,
 *     AVONOTES_KEY_ALIAS, AVONOTES_KEY_PASSWORD
 * Without either, release builds are produced unsigned.
 */
val signing: Map<String, String>? = run {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) {
        val p = Properties().apply { f.inputStream().use { load(it) } }
        mapOf(
            "storeFile" to p.getProperty("storeFile"),
            "storePassword" to p.getProperty("storePassword"),
            "keyAlias" to p.getProperty("keyAlias"),
            "keyPassword" to p.getProperty("keyPassword"),
        )
    } else System.getenv("AVONOTES_KEYSTORE")?.takeIf { it.isNotBlank() }?.let {
        mapOf(
            "storeFile" to it,
            "storePassword" to System.getenv("AVONOTES_KEYSTORE_PASSWORD").orEmpty(),
            "keyAlias" to System.getenv("AVONOTES_KEY_ALIAS").orEmpty(),
            "keyPassword" to System.getenv("AVONOTES_KEY_PASSWORD").orEmpty(),
        )
    }
}

android {
    namespace = "com.avonotes"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.avonotes"
        // API 30 (Android 11): RenderNode (29) plus the WindowInsets / insets-controller APIs.
        minSdk = 30
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0-alpha"
    }

    signingConfigs {
        if (signing != null) {
            create("release") {
                storeFile = file(signing.getValue("storeFile"))
                storePassword = signing.getValue("storePassword")
                keyAlias = signing.getValue("keyAlias")
                keyPassword = signing.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Lets a development build sit next to the released app on the same device.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    lint {
        // CI fails on lint errors; warnings are reported but don't fail the build.
        abortOnError = true
        warningsAsErrors = false
    }

    // Reproducible-ish APKs (F-Droid friendly): no dependency metadata blob in the APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Deliberately no dependencies beyond the Kotlin stdlib: everything uses the
// platform SDK (View, RenderNode, PdfRenderer, Storage Access Framework).
