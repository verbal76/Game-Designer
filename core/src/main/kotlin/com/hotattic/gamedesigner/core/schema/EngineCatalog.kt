package com.hotattic.gamedesigner.core.schema

object Platforms {
    const val ANDROID = "android"
    const val IOS = "ios"
    const val WINDOWS = "windows"
    const val MAC = "mac"
    const val LINUX = "linux"
    const val WEB = "web"

    val labels = linkedMapOf(
        ANDROID to "Android",
        IOS to "iPhone / iPad",
        WINDOWS to "Windows PC",
        MAC to "macOS",
        LINUX to "Linux",
        WEB to "Web browser",
    )
    val mobile = setOf(ANDROID, IOS)
}

data class EngineProfile(
    val id: String,
    val name: String,
    val language: String,
    /** Platforms with a first-class export. */
    val platforms: Set<String>,
    /** Platforms reachable only through a wrapper layer (e.g. Capacitor); usable but lower confidence. */
    val wrappedPlatforms: Set<String> = emptySet(),
    val supports2D: Boolean = true,
    val supports3D: Boolean = false,
    /** 0..5: how well the whole build/test/package loop can be driven by an autonomous coding agent without a GUI editor. */
    val autonomyFit: Int,
    /** 0..5: capability ceiling for heavy simulation / large scope. */
    val capability: Int,
    /** 0..5: friendliness to a non-programmer owner inspecting/tweaking the result. */
    val beginnerFit: Int,
    val strengths: String,
    val caveats: String,
    val headlessValidation: String,
    val buildArtifactNotes: String,
    /** Baseline stable line known at authoring time; the generated spec must tell Claude to verify the current version. */
    val baselineVersionNote: String,
    val licenseNote: String,
)

object EngineCatalog {

    val all: List<EngineProfile> = listOf(
        EngineProfile(
            "godot", "Godot Engine 4.x", "GDScript (optionally C#)",
            setOf(Platforms.ANDROID, Platforms.IOS, Platforms.WINDOWS, Platforms.MAC, Platforms.LINUX, Platforms.WEB),
            supports2D = true, supports3D = true, autonomyFit = 4, capability = 3, beginnerFit = 4,
            strengths = "Free MIT-licensed engine with excellent 2D and capable 3D; scenes and scripts are text files an agent can author; light enough for phones.",
            caveats = "3D scale and tooling are below Unity/Unreal; iOS export requires a Mac with Xcode; Android export needs JDK, Android SDK and export templates; web export has threading/size constraints.",
            headlessValidation = "godot --headless with GUT or gdUnit4 for logic tests; headless scripted scene runs for smoke playtests; export presets for CI.",
            buildArtifactNotes = "Android APK/AAB via export preset (debug keystore for installable test builds); Windows/Linux/web via export templates.",
            baselineVersionNote = "Godot 4.x stable line; verify the newest stable release before pinning.",
            licenseNote = "MIT",
        ),
        EngineProfile(
            "unity", "Unity", "C#",
            setOf(Platforms.ANDROID, Platforms.IOS, Platforms.WINDOWS, Platforms.MAC, Platforms.LINUX, Platforms.WEB),
            supports2D = true, supports3D = true, autonomyFit = 2, capability = 5, beginnerFit = 3,
            strengths = "Largest ecosystem and strongest 3D/mobile tooling; scales to very large projects.",
            caveats = "Editor-centric: scene/prefab authoring and builds need a licensed Unity install (CI needs a license activation); hard for an autonomous agent to verify visually; project files are bulky.",
            headlessValidation = "Unity Test Framework in batchmode; requires installed editor and license on the build machine.",
            buildArtifactNotes = "Builds via batchmode or GameCI; Android needs matching SDK/NDK modules.",
            baselineVersionNote = "Use the current Unity LTS line; verify before pinning.",
            licenseNote = "Proprietary; Personal tier terms apply",
        ),
        EngineProfile(
            "unreal", "Unreal Engine 5", "C++ / Blueprints",
            setOf(Platforms.ANDROID, Platforms.IOS, Platforms.WINDOWS, Platforms.MAC, Platforms.LINUX),
            supports2D = false, supports3D = true, autonomyFit = 1, capability = 5, beginnerFit = 2,
            strengths = "Top-end 3D visuals and tooling.",
            caveats = "Very heavy on phones and on build machines (hundreds of GB, long builds); Blueprint assets are binary and not agent-friendly; poor fit for autonomous phone-first work.",
            headlessValidation = "Automation framework via UnrealEditor-Cmd; heavy.",
            buildArtifactNotes = "UAT BuildCookRun; requires large installs.",
            baselineVersionNote = "Current UE 5.x release; verify before pinning.",
            licenseNote = "Proprietary; royalty terms apply above revenue threshold",
        ),
        EngineProfile(
            "libgdx", "libGDX", "Kotlin / Java",
            setOf(Platforms.ANDROID, Platforms.WINDOWS, Platforms.MAC, Platforms.LINUX),
            wrappedPlatforms = setOf(Platforms.IOS, Platforms.WEB),
            supports2D = true, supports3D = true, autonomyFit = 5, capability = 4, beginnerFit = 2,
            strengths = "Code-only framework: everything is source an agent can write, test with JUnit and build with Gradle; excellent Android fit; very good 2D, basic 3D.",
            caveats = "No visual editor; iOS (MobiVM) and web (GWT/TeaVM) backends need extra care; you assemble more engine pieces yourself.",
            headlessValidation = "Headless backend plus JUnit for simulation/rules; deterministic fixed-step logic is easy to test.",
            buildArtifactNotes = "Gradle assembleDebug/assembleRelease for Android; desktop jar/native packaging.",
            baselineVersionNote = "Current libGDX release on Maven Central; verify before pinning.",
            licenseNote = "Apache-2.0",
        ),
        EngineProfile(
            "android_native", "Native Android (Kotlin + Compose/Canvas)", "Kotlin",
            setOf(Platforms.ANDROID),
            supports2D = true, supports3D = false, autonomyFit = 5, capability = 2, beginnerFit = 3,
            strengths = "Smallest, fastest, most testable path for puzzle, card, idle/management and simple 2D games on Android; no engine runtime.",
            caveats = "Android only; no physics or scene tooling out of the box; not suited to 3D or heavy real-time action.",
            headlessValidation = "JVM unit tests for rules; Robolectric/instrumented tests for UI flows.",
            buildArtifactNotes = "Gradle assembleDebug/assembleRelease; APK/AAB.",
            baselineVersionNote = "Current AGP + Kotlin + Compose BOM stable set; verify before pinning.",
            licenseNote = "Apache-2.0",
        ),
        EngineProfile(
            "web_phaser", "Phaser (TypeScript, web)", "TypeScript",
            setOf(Platforms.WEB),
            wrappedPlatforms = setOf(Platforms.ANDROID, Platforms.IOS, Platforms.WINDOWS, Platforms.MAC, Platforms.LINUX),
            supports2D = true, supports3D = false, autonomyFit = 5, capability = 3, beginnerFit = 3,
            strengths = "Fastest iteration; agent can run and visually test in a headless browser; trivial distribution as a web page; can be wrapped for Android/iOS via Capacitor.",
            caveats = "Wrapped mobile builds add WebView performance limits and packaging steps; no native 3D.",
            headlessValidation = "Vitest for logic; Playwright for scripted playthroughs and screenshots.",
            buildArtifactNotes = "Vite build to static site; Capacitor Android Gradle build for APK.",
            baselineVersionNote = "Current Phaser 3.x/4 release; verify before pinning.",
            licenseNote = "MIT",
        ),
        EngineProfile(
            "web_three", "three.js / Babylon.js (TypeScript, web 3D)", "TypeScript",
            setOf(Platforms.WEB),
            wrappedPlatforms = setOf(Platforms.ANDROID, Platforms.IOS, Platforms.WINDOWS, Platforms.MAC, Platforms.LINUX),
            supports2D = true, supports3D = true, autonomyFit = 4, capability = 3, beginnerFit = 2,
            strengths = "3D in the browser with fully code-driven assets; testable with headless browsers and screenshots.",
            caveats = "WebView/mobile GPU limits; asset pipeline and physics are assembled from libraries.",
            headlessValidation = "Playwright with software rendering for smoke tests and screenshot comparison.",
            buildArtifactNotes = "Vite build; Capacitor wrapper for Android/iOS.",
            baselineVersionNote = "Current three.js or Babylon.js release; verify before pinning.",
            licenseNote = "MIT / Apache-2.0",
        ),
        EngineProfile(
            "flutter_flame", "Flutter + Flame", "Dart",
            setOf(Platforms.ANDROID, Platforms.IOS, Platforms.WEB, Platforms.WINDOWS, Platforms.MAC, Platforms.LINUX),
            supports2D = true, supports3D = false, autonomyFit = 4, capability = 2, beginnerFit = 3,
            strengths = "One code base for phones, web and desktop; great UI toolkit for menus-heavy games; text-only project.",
            caveats = "2D only; smaller game-dev ecosystem than Godot/Unity; iOS builds still need macOS.",
            headlessValidation = "flutter test for logic and widget tests; golden tests for visuals.",
            buildArtifactNotes = "flutter build apk/appbundle/web/windows.",
            baselineVersionNote = "Current Flutter stable; verify before pinning.",
            licenseNote = "BSD-3-Clause / MIT",
        ),
        EngineProfile(
            "bevy", "Bevy", "Rust",
            setOf(Platforms.WINDOWS, Platforms.MAC, Platforms.LINUX, Platforms.WEB),
            wrappedPlatforms = setOf(Platforms.ANDROID, Platforms.IOS),
            supports2D = true, supports3D = true, autonomyFit = 4, capability = 4, beginnerFit = 1,
            strengths = "Code-only ECS engine with excellent performance for big simulations; great for deterministic headless testing.",
            caveats = "Pre-1.0 with frequent breaking releases; mobile support is less mature; steep learning curve; long compile times.",
            headlessValidation = "cargo test with headless App runs; deterministic schedules.",
            buildArtifactNotes = "cargo build per target; Android/iOS need extra tooling.",
            baselineVersionNote = "Latest Bevy release; pin exactly and verify.",
            licenseNote = "MIT / Apache-2.0",
        ),
    )

    val byId = all.associateBy { it.id }
    fun get(id: String): EngineProfile? = byId[id]

    fun supports(engine: EngineProfile, platform: String) = platform in engine.platforms
    fun supportsWrapped(engine: EngineProfile, platform: String) = platform in engine.platforms || platform in engine.wrappedPlatforms
}

data class EngineRecommendation(val engine: EngineProfile, val score: Int, val rationale: String, val notes: List<String>)

/** Deterministic engine ranking. Inputs are plain values so it is trivially testable. */
object EngineRecommender {

    fun rank(
        platforms: Set<String>,
        dimension: String?, // "2D", "2.5D", "3D"
        complexity: Int,
        beginner: Boolean,
        tags: Set<Tag> = emptySet(),
    ): List<EngineRecommendation> {
        val wants3D = dimension == "3D"
        return EngineCatalog.all.mapNotNull { e ->
            if (wants3D && !e.supports3D) return@mapNotNull null
            if (!wants3D && !e.supports2D) return@mapNotNull null
            val missingNative = platforms.filter { !EngineCatalog.supports(e, it) }
            val missingAny = platforms.filter { !EngineCatalog.supportsWrapped(e, it) }
            if (missingAny.isNotEmpty()) return@mapNotNull null
            var score = e.autonomyFit * 4 + e.capability * 2
            if (beginner) score += e.beginnerFit * 2
            val notes = mutableListOf<String>()
            if (missingNative.isNotEmpty()) {
                score -= 6 * missingNative.size
                notes += "${missingNative.joinToString { Platforms.labels[it] ?: it }} only via a wrapper layer."
            }
            // Heavy simulation wants capability headroom.
            if (complexity >= 4 && e.capability < 3) score -= 8
            if (wants3D && e.id in setOf("unreal", "unity") && Platforms.ANDROID in platforms) {
                notes += "Heavy for phones and hard for an autonomous agent to verify headlessly."
            }
            if (Tag.BUILDING in tags || Tag.SIMULATION in tags) {
                if (e.id in setOf("bevy", "libgdx", "godot", "unity")) score += 2
            }
            EngineRecommendation(e, score, e.strengths, notes)
        }.sortedByDescending { it.score }
    }

    fun best(platforms: Set<String>, dimension: String?, complexity: Int, beginner: Boolean, tags: Set<Tag> = emptySet()): EngineRecommendation? =
        rank(platforms, dimension, complexity, beginner, tags).firstOrNull()
}
