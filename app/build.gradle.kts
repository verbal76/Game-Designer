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
        // CI run number gives a monotonically increasing build code; local builds use 1.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "2.0.0"
        buildConfigField("String", "RUNTIME_FINGERPRINT", "\"$runtimeFingerprint\"")
        buildConfigField("int", "SHELL_API_LEVEL", libs.versions.shellApi.get())
        buildConfigField("String", "OTA_REPO", "\"verbal76/Game-Designer\"")
    }

    // Release signing comes from the environment (CI), never from source. Without it, a local release build is debug-signed.
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

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (ksPath != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
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
