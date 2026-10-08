// AGP and the Kotlin Gradle plugins must be loaded by the same (root) classloader, otherwise the Kotlin plugin
// cannot see AGP's classes (NoClassDefFoundError: BaseVariant). AGP is therefore put on the root buildscript
// classpath and applied by id (without a version) in :app. It is skipped with -PcoreOnly, which is used in
// environments that cannot download the Android SDK/AGP (the cloud authoring sandbox).
buildscript {
    if (!providers.gradleProperty("coreOnly").isPresent) {
        repositories {
            google()
            mavenCentral()
        }
        dependencies {
            classpath("com.android.tools.build:gradle:9.4.1") // keep in sync with gradle/libs.versions.toml [versions] agp
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
