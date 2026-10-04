package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.model.ClaudePlan
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.schema.EngineCatalog
import com.hotattic.gamedesigner.core.schema.EngineRecommender
import com.hotattic.gamedesigner.core.schema.Fields
import com.hotattic.gamedesigner.core.schema.GenreKnowledge
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Platforms
import com.hotattic.gamedesigner.core.schema.Tag
import com.hotattic.gamedesigner.core.schema.Traits

enum class Severity { BLOCKER, WARNING, NOTE }

/** A concrete change that resolves a conflict: decision key -> new value. */
data class Alternative(val label: String, val changes: Map<String, String>)

data class Conflict(
    val id: String,
    val severity: Severity,
    val title: String,
    val message: String,
    val recommendation: String,
    val affectedKeys: List<String>,
    val alternatives: List<Alternative> = emptyList(),
    /** Blockers about verified impossibilities cannot be overridden - the underlying decision must change. */
    val overridable: Boolean = true,
)

/**
 * Deterministic design-conflict detection. Each rule explains the problem, recommends an alternative, and (unless it
 * is a verified impossibility) can be knowingly overridden by the owner, after which it is recorded, not repeated.
 */
object ConflictEngine {

    private val rules: List<(Traits) -> Conflict?> = listOf(
        // --- Verified impossibilities -------------------------------------------------------------------------
        { t ->
            val e = t.engine
            if (e == null) null else {
                val missing = t.platforms.filter { !EngineCatalog.supportsWrapped(e, it) }
                if (missing.isEmpty()) null else {
                    val alt = EngineRecommender.best(t.platforms, t.dimension, t.complexity, t.beginner, t.tags)
                    Conflict("engine_platform_unsupported", Severity.BLOCKER, "${e.name} can't target ${missing.joinToString { Platforms.labels[it] ?: it }}",
                        "${e.name} has no supported export for ${missing.joinToString { Platforms.labels[it] ?: it }}.",
                        alt?.let { "Switch to ${it.engine.name}, which covers your platforms." } ?: "Drop the unsupported platform or pick another engine.",
                        listOf(Keys.ENGINE, Keys.PLATFORMS),
                        listOfNotNull(alt?.let { Alternative("Use ${it.engine.name}", mapOf(Keys.ENGINE to it.engine.id)) }),
                        overridable = false)
                }
            }
        },
        { t ->
            val e = t.engine
            if (e != null && t.dimension == "3D" && !e.supports3D) {
                val alt = EngineRecommender.best(t.platforms, "3D", t.complexity, t.beginner, t.tags)
                Conflict("engine_no_3d", Severity.BLOCKER, "${e.name} is 2D-only", "You chose 3D but ${e.name} does not support 3D rendering.",
                    alt?.let { "Switch to ${it.engine.name}." } ?: "Choose 2D or another engine.", listOf(Keys.ENGINE, Keys.DIMENSION),
                    listOfNotNull(alt?.let { Alternative("Use ${it.engine.name}", mapOf(Keys.ENGINE to it.engine.id)) }, Alternative("Make the game 2D", mapOf(Keys.DIMENSION to "2D"))),
                    overridable = false)
            } else null
        },
        { t ->
            val id = t.value(Keys.PACKAGE_ID)
            if (id != null && !Fields.PACKAGE_ID_REGEX.matches(id.trim()))
                Conflict("package_id_invalid", Severity.BLOCKER, "Package ID is not valid", "\"$id\" is not a valid application ID (lowercase letters/digits/underscores in dot-separated parts, starting with a letter).",
                    "Use something like com.hotatticgames.mygame.", listOf(Keys.PACKAGE_ID), overridable = false)
            else null
        },
        // --- Platform / pipeline -----------------------------------------------------------------------------
        { t ->
            if (Platforms.IOS in t.platforms && t.value(Keys.CI_BUILD) == "claude_environment")
                Conflict("ios_build_env", Severity.WARNING, "iOS builds can't happen in Claude's Linux workspace",
                    "iPhone/iPad builds need macOS and Xcode. Claude's cloud workspace is Linux.",
                    "Use GitHub Actions with a macOS runner for iOS, or build on a Mac.", listOf(Keys.CI_BUILD, Keys.PLATFORMS),
                    listOf(Alternative("Use GitHub Actions", mapOf(Keys.CI_BUILD to "github_actions"))))
            else null
        },
        { t ->
            if (Platforms.IOS in t.platforms && t.storePlan == "personal_sideload")
                Conflict("ios_sideload", Severity.NOTE, "iPhone install needs an Apple Developer account",
                    "Installing your own build on an iPhone requires an Apple Developer account (paid) for signing, or very short-lived free provisioning.",
                    "Keep Android as the first target and add iOS when you're ready for the account.", listOf(Keys.PLATFORMS, Keys.STORE_PLAN))
            else null
        },
        { t ->
            if (Platforms.ANDROID in t.platforms && t.value(Keys.CI_BUILD) == "claude_environment")
                Conflict("android_sdk_env", Severity.WARNING, "Android SDK may not be installable in Claude's workspace",
                    "Building an Android APK needs the Android SDK from Google's servers, which some Claude environments block or lack.",
                    "Use GitHub Actions to produce the APK; Claude still writes and tests the code and reads the CI result.", listOf(Keys.CI_BUILD),
                    listOf(Alternative("Use GitHub Actions", mapOf(Keys.CI_BUILD to "github_actions"))))
            else null
        },
        { t ->
            val e = t.engine
            if (e != null && e.autonomyFit <= 2)
                Conflict("engine_autonomy", Severity.WARNING, "${e.name} is hard for Claude to drive autonomously",
                    e.caveats, "A code-first engine lets Claude build, test and package without a GUI editor.", listOf(Keys.ENGINE),
                    EngineRecommender.rank(t.platforms, t.dimension, t.complexity, t.beginner, t.tags).filter { it.engine.autonomyFit >= 4 }.take(2)
                        .map { Alternative("Use ${it.engine.name}", mapOf(Keys.ENGINE to it.engine.id)) })
            else null
        },
        { t ->
            val e = t.engine
            if (e != null && t.dimension == "3D" && Platforms.ANDROID in t.platforms && e.id in setOf("unreal"))
                Conflict("unreal_phone", Severity.WARNING, "Unreal is very heavy for phones",
                    "Large install/build size, long build times and high minimum hardware make Unreal a poor phone-first choice.",
                    "Godot or Unity handle phone 3D far more comfortably.", listOf(Keys.ENGINE),
                    listOf(Alternative("Use Godot", mapOf(Keys.ENGINE to "godot"))))
            else null
        },
        // --- Design coherence --------------------------------------------------------------------------------
        { t ->
            val p = t.value(Keys.PERSPECTIVE)
            if (t.dimension == "2D" && p in setOf("first_person", "third_person", "top_down_3d", "isometric_3d", "fixed_camera"))
                Conflict("perspective_vs_2d", Severity.WARNING, "That camera needs 3D", "\"$p\" is a 3D camera but the game is set to 2D.",
                    "Pick a 2D perspective or switch the game to 3D.", listOf(Keys.PERSPECTIVE, Keys.DIMENSION),
                    listOf(Alternative("Top-down 2D", mapOf(Keys.PERSPECTIVE to "top_down")), Alternative("Make it 3D", mapOf(Keys.DIMENSION to "3D"))))
            else null
        },
        { t ->
            val a = t.value(Keys.ART_DIRECTION)
            if (t.dimension == "3D" && a in setOf("pixel_art", "hand_drawn", "vector_flat"))
                Conflict("art_vs_3d", Severity.WARNING, "That art style is 2D", "\"$a\" is a 2D style; in a 3D game it needs special shaders or billboard techniques.",
                    "Use Low poly or Stylized 3D, or make the game 2D/2.5D.", listOf(Keys.ART_DIRECTION, Keys.DIMENSION),
                    listOf(Alternative("Low poly", mapOf(Keys.ART_DIRECTION to "low_poly")), Alternative("Make it 2.5D", mapOf(Keys.DIMENSION to "2.5D"))))
            else null
        },
        { t ->
            val o = t.value(Keys.ORIENTATION)
            if (o == "portrait" && t.isMobile && t.genres.any { it.id in setOf("platformer", "metroidvania", "shooter", "racing", "fighting") })
                Conflict("portrait_vs_action", Severity.WARNING, "Portrait cramps this kind of game", "Platformers, shooters, racers and fighters need horizontal space for visibility and controls.",
                    "Use landscape.", listOf(Keys.ORIENTATION), listOf(Alternative("Landscape", mapOf(Keys.ORIENTATION to "landscape"))))
            else null
        },
        { t ->
            if (t.isMobile && t.usesTouch && t.genres.any { it.id in setOf("factory_automation", "strategy_rts", "city_builder") } && t.value(Keys.ORIENTATION) == "portrait")
                Conflict("dense_ui_portrait", Severity.WARNING, "Dense management UI in portrait is hard", "Builders and RTS games need a lot of on-screen information.",
                    "Use landscape or both.", listOf(Keys.ORIENTATION), listOf(Alternative("Landscape", mapOf(Keys.ORIENTATION to "landscape"))))
            else null
        },
        { t ->
            if (t.mobileOnly && t.inputs == setOf("touch") && t.genres.any { it.id == "fighting" })
                Conflict("touch_only_fighter", Severity.NOTE, "Touch-only fighters are hard to make precise", "Fighting games rely on precise multi-button input.",
                    "Add optional gamepad support and generous input buffering.", listOf(Keys.INPUT_METHODS),
                    listOf(Alternative("Touch + gamepad", mapOf(Keys.INPUT_METHODS to "touch|gamepad"))))
            else null
        },
        { t ->
            if (t.dimension == "3D" && t.isMobile && (t.complexity >= 8 || t.value(Keys.WORLD_STRUCTURE) == "open_map"))
                Conflict("heavy_3d_mobile", Severity.WARNING, "Large 3D worlds are demanding on phones",
                    "Open-world or very complex 3D on mid-range phones needs streaming, LOD and strict budgets, and multiplies asset and testing cost.",
                    "Consider 2.5D or a hub/stage structure with smaller levels.", listOf(Keys.DIMENSION, Keys.WORLD_STRUCTURE),
                    listOf(Alternative("2.5D", mapOf(Keys.DIMENSION to "2.5D")), Alternative("Hub and missions", mapOf(Keys.WORLD_STRUCTURE to "hub_missions"))))
            else null
        },
        { t ->
            if (Platforms.WEB in t.platforms && t.dimension == "3D" && t.complexity >= 7)
                Conflict("heavy_web_3d", Severity.WARNING, "Heavy 3D in a browser is risky", "Download size, WebGL limits and memory make complex 3D on the web fragile.",
                    "Keep the web build to a lighter edition or drop web.", listOf(Keys.PLATFORMS))
            else null
        },
        { t ->
            val d = t.value(Keys.DIFFICULTY_FAILURE)
            val s = t.value(Keys.SAVE_SYSTEM)
            if (d == "permadeath_meta" && s in setOf("checkpoint_only", "manual_slots"))
                Conflict("permadeath_save", Severity.WARNING, "Permadeath conflicts with that save style", "Permadeath runs with checkpoints or manual save slots invite save-scumming and make meta rewards ambiguous.",
                    "Save meta progress plus current-run state.", listOf(Keys.DIFFICULTY_FAILURE, Keys.SAVE_SYSTEM),
                    listOf(Alternative("Save meta and run state", mapOf(Keys.SAVE_SYSTEM to "run_meta_save"))))
            else null
        },
        { t ->
            val p = t.value(Keys.PROGRESSION)
            val s = t.value(Keys.SAVE_SYSTEM)
            if (p in setOf("meta_unlocks", "both", "xp_levels", "tech_tree") && s == "checkpoint_only")
                Conflict("progression_save", Severity.WARNING, "Permanent progression needs real saves", "\"$p\" must persist between sessions, which checkpoint-only saving doesn't guarantee.",
                    "Use autosave with Continue.", listOf(Keys.PROGRESSION, Keys.SAVE_SYSTEM), listOf(Alternative("Autosave with Continue", mapOf(Keys.SAVE_SYSTEM to "autosave_continue"))))
            else null
        },
        // --- Network / cost / privacy ------------------------------------------------------------------------
        { t ->
            if (t.network != null && t.network != "fully_offline")
                Conflict("network_cost", Severity.WARNING, "Online features add servers, cost and privacy duties",
                    "Online features usually need a backend with ongoing cost, security work and a privacy policy. Online multiplayer design isn't part of this version of Game Designer.",
                    "Make the game fully offline for the first build; add online extras later.", listOf(Keys.NETWORK_POLICY),
                    listOf(Alternative("Fully offline", mapOf(Keys.NETWORK_POLICY to "fully_offline"))))
            else null
        },
        { t ->
            val m = t.value(Keys.MONETIZATION)
            val p = t.value(Keys.PRIVACY)
            if (m == "ads" && p == "no_data")
                Conflict("ads_privacy", Severity.WARNING, "Ad networks collect data", "Ad SDKs collect device identifiers, so 'no data collected' would be inaccurate on a store listing.",
                    "Declare data collection or drop ads.", listOf(Keys.MONETIZATION, Keys.PRIVACY),
                    listOf(Alternative("Free, no ads", mapOf(Keys.MONETIZATION to "free_no_ads")), Alternative("Declare analytics", mapOf(Keys.PRIVACY to "anonymous_analytics"))))
            else null
        },
        { t ->
            val m = t.value(Keys.MONETIZATION)
            if (m in setOf("ads", "iap"))
                Conflict("paid_services", Severity.NOTE, "That monetization depends on external services", "Ads and in-app purchases depend on third-party services and store billing, which introduce fees, review requirements and server-side validation.",
                    "Fine if intentional; otherwise stay free with no ads for now.", listOf(Keys.MONETIZATION))
            else null
        },
        // --- Resources / licensing ---------------------------------------------------------------------------
        { t ->
            val rec = ScopeEngine.recommend(t.project)
            if (rec.resources.level == ResourceLevel.EXTREME)
                Conflict("scope_extreme", Severity.WARNING, "This build would be extremely large",
                    rec.resources.explanation + if (t.project.prefs.claudePlan == ClaudePlan.PRO) " That is a lot for a Pro plan shared with other projects." else "",
                    "Go one scope tier smaller, or accept the phased plan.", listOf(Keys.SCOPE_CHOICE),
                    listOf(Alternative("Go smaller", mapOf(Keys.SCOPE_CHOICE to "smaller"))))
            else null
        },
        { t ->
            if (t.value(Keys.ASSET_POLICY) == "cc0_or_cc_by")
                Conflict("ccby_attribution", Severity.NOTE, "CC-BY assets require visible credit", "Every CC-BY asset used needs attribution in the game's credits screen and listing.",
                    "Stay CC0-only to avoid attribution obligations.", listOf(Keys.ASSET_POLICY), listOf(Alternative("CC0 only", mapOf(Keys.ASSET_POLICY to "cc0_default"))))
            else null
        },
        { t ->
            val c = t.value(Keys.CONCEPT)?.lowercase().orEmpty()
            if (listOf("multiplayer", "co-op online", "online co-op", "pvp", "online pvp", "mmo").any { GenreKnowledge.containsWord(c, it) })
                Conflict("multiplayer_deferred", Severity.NOTE, "Online multiplayer is deferred", "Game Designer's first version doesn't design online multiplayer. The game will be specified as single-player (or local), keeping the architecture ready for it later.",
                    "Proceed single-player now.", listOf(Keys.CONCEPT))
            else null
        },
        { t ->
            val q = t.genres.firstOrNull { it.id == "factory_automation" || it.id == "city_builder" }
            if (q != null && t.beginner && t.engine?.id in setOf("bevy"))
                Conflict("beginner_bevy", Severity.WARNING, "Bevy is very hard for beginners to inspect or tweak", "Rust and a pre-1.0 engine make small hands-on changes difficult.",
                    "Godot or libGDX are friendlier.", listOf(Keys.ENGINE), listOf(Alternative("Use Godot", mapOf(Keys.ENGINE to "godot"))))
            else null
        },
        { t ->
            if (t.genres.size > 2)
                Conflict("too_many_genres", Severity.NOTE, "Three or more genres is hard to make coherent", "Each genre brings its own required systems; blending many dilutes all of them.",
                    "Keep one primary genre and one flavor genre.", listOf(Keys.GENRE))
            else null
        },
    )

    fun all(project: Project): List<Conflict> {
        val t = Traits(project)
        if (!t.genresKnown && t.project.decisions.isEmpty()) return emptyList()
        return rules.mapNotNull { runCatching { it(t) }.getOrNull() }
    }

    fun open(project: Project): List<Conflict> {
        val acked = project.conflictAcks.map { it.conflictId }.toSet()
        return all(project).filter { it.severity != Severity.NOTE && (it.id !in acked || !it.overridable) }
    }

    /** Notes never block, but are shown once and listed in the spec. */
    fun notes(project: Project): List<Conflict> = all(project).filter { it.severity == Severity.NOTE }

    fun acknowledged(project: Project): List<Conflict> {
        val acked = project.conflictAcks.map { it.conflictId }.toSet()
        return all(project).filter { it.id in acked && it.overridable }
    }

    /** Blockers that must be fixed before generation. */
    fun blockers(project: Project): List<Conflict> = all(project).filter { it.severity == Severity.BLOCKER }
}
