pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "GameDesigner"
include(":core")
include(":otakit")
// The Android app module needs the Android SDK. It is included unless -PcoreOnly is passed
// (used in environments without the SDK, e.g. the cloud authoring sandbox).
if (!providers.gradleProperty("coreOnly").isPresent) {
    include(":shellapi")
    include(":applayer")
    include(":otabundle")
    include(":app")
}
