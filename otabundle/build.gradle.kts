plugins {
    id("com.android.application")
}

// Packaging-only module: assembling it makes AGP desugar+dex the application layer with the correct classpath.
// CI extracts classes*.dex from the resulting APK into bundle.zip. It is never installed or distributed.
android {
    namespace = "com.hotattic.gamedesigner.otabundle"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.hotatticgames.gamedesigner.otabundle"
        minSdk = 28
        targetSdk = 36
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":applayer"))
}

// Keep the Kotlin stdlib out of the bundle: the shell provides it, and one copy avoids duplicate-class surprises.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin")
    exclude(group = "org.jetbrains")
}
