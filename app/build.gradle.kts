plugins {
    id("com.android.application")
    alias(libs.plugins.kotlin.compose)
}

// Runtime generation identity. OTA bundles must be built with exactly this fingerprint to be accepted.
val runtimeFingerprint = "k${libs.versions.kotlin.get()}-c${libs.versions.composeBom.get()}-a${libs.versions.agp.get()}-s${libs.versions.shellApi.get()}"

android {
    namespace = "com.hotattic.gamedesigner"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hotatticgames.gamedesigner"
        minSdk = 28
        targetSdk = 36
        // CI sets GD_VERSION_CODE = minutes since the Unix epoch: monotonic across every workflow (run numbers are per-workflow
        // and are not comparable). Local builds use 1.
        versionCode = System.getenv("GD_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = "5.0.0"
        buildConfigField("String", "RUNTIME_FINGERPRINT", "\"$runtimeFingerprint\"")
        buildConfigField("int", "SHELL_API_LEVEL", libs.versions.shellApi.get())
        buildConfigField("String", "OTA_REPO", "\"verbal76/Game-Designer\"")
        buildConfigField("String", "LITERTLM_VERSION", "\"${libs.versions.litertlm.get()}\"")
    }

    // Release signing comes from the environment (CI), never from source. There is deliberately NO fallback to the debug key: a release build
    // without the permanent key must fail, because a debug-signed APK of the production application id can never update the installed app.
    val ksPath = System.getenv("GD_KEYSTORE")
    signingConfigs {
        if (ksPath != null) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("GD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("GD_KEY_ALIAS")
                keyPassword = System.getenv("GD_KEY_PASSWORD")
            }
        }
    }
    gradle.taskGraph.whenReady {
        val releasePackaging = allTasks.any { it.project == project && Regex("(assemble|bundle|package)Release").matches(it.name) }
        if (releasePackaging) {
            val missing = listOf("GD_KEYSTORE", "GD_KEYSTORE_PASSWORD", "GD_KEY_ALIAS", "GD_KEY_PASSWORD").filter { System.getenv(it).isNullOrBlank() }
            if (missing.isNotEmpty() || !file(ksPath!!).exists())
                throw GradleException("Refusing to sign a release build without the permanent signing key (missing: ${missing.ifEmpty { listOf("the keystore file") }.joinToString()}). Release builds are made only by the release workflows; a debug-signed production APK could never update the installed app.")
        }
    }

    buildTypes {
        // The debug build is the SIDE-BY-SIDE TEST build: its own application id, so it installs next to the production app and has its own private
        // storage (projects, secrets, OTA state). It can never touch, update or replace the installed production app. Release is unchanged.
        debug {
            applicationIdSuffix = ".test"
            versionNameSuffix = "-test"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")   // null (unsigned) when the key is absent; the guard above stops release packaging then
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint { checkReleaseBuilds = false }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":shellapi"))
    implementation(project(":applayer"))
    implementation(project(":otakit"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.litertlm.android)

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}
