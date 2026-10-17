plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.compose)
}

// Build-time identity of the application layer. The v3 APK ships layer sequence 5 (application layer v3.0) as the known-good fallback; OTA bundles
// are built with -PlayerVersion=<n> -PlayerLabel=<name> and must have a higher version.
val layerVersion = providers.gradleProperty("layerVersion").orElse("16").get()
val sourceSha = providers.gradleProperty("sourceSha").orElse("local").get()
val layerLabel = providers.gradleProperty("layerLabel").orElse("v5-bundled").get()

android {
    namespace = "com.hotattic.gamedesigner.applayer"
    compileSdk = 37
    defaultConfig {
        minSdk = 28
        buildConfigField("int", "LAYER_VERSION", layerVersion)
        buildConfigField("String", "LAYER_LABEL", "\"$layerLabel\"")
        buildConfigField("String", "SOURCE_SHA", "\"$sourceSha\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions { unitTests.isReturnDefaultValues = true; unitTests.isIncludeAndroidResources = true }
}

// Third-party libraries are provided by the shell (:app) at runtime, so they are compileOnly here: the OTA bundle then
// contains ONLY our own classes (:applayer + :core) and cannot drift from the runtime the shell was built with.
dependencies {
    implementation(project(":core"))
    compileOnly(project(":shellapi"))
    compileOnly(platform(libs.androidx.compose.bom))
    compileOnly(libs.androidx.compose.ui)
    compileOnly(libs.androidx.compose.ui.tooling.preview)
    compileOnly(libs.androidx.compose.material3)
    compileOnly(libs.androidx.compose.material.icons)
    compileOnly(libs.androidx.activity.compose)
    compileOnly(libs.androidx.core.ktx)
    compileOnly(libs.androidx.lifecycle.viewmodel.compose)
    compileOnly(libs.androidx.lifecycle.runtime.compose)
    compileOnly(libs.androidx.navigation.compose)
    compileOnly(libs.kotlinx.coroutines.android)
    compileOnly(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui)
    testImplementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    // Real-component rendering and behaviour tests (JVM, Robolectric). Test-only: none of this reaches the app or the OTA bundle.
    testImplementation(libs.androidx.activity.compose)
    testImplementation(libs.androidx.compose.material.icons)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.androidx.core.ktx)
}
