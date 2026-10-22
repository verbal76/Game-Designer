plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.hotattic.gamedesigner.shellapi"
    compileSdk = 37
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
    defaultConfig { buildConfigField("int", "SHELL_API_LEVEL", libs.versions.shellApi.get()) }
}

// The shell API is part of the APK (stable). Libraries it touches are provided by :app, so they are compileOnly here.
dependencies {
    compileOnly(platform(libs.androidx.compose.bom))
    compileOnly(libs.androidx.compose.runtime)
    compileOnly(libs.kotlinx.coroutines.core)
}
