package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.model.AssetRecord
import com.hotattic.gamedesigner.core.model.AssetResolution
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Traits

enum class AssetKind { SPRITES, MODELS, TEXTURES, TILESETS, UI_KIT, ICONS, VFX, MUSIC, SFX, FONT }

data class AssetNeed(val id: String, val label: String, val kind: AssetKind, val detail: String)

/**
 * A source with a published blanket license. Blanket licenses are verifiable at the source level, but the generated
 * instructions still require per-file provenance logging, because individual files can be mislabeled.
 */
data class AssetSource(
    val id: String,
    val name: String,
    val url: String,
    val license: String,
    val blanketCc0: Boolean,
    val kinds: Set<AssetKind>,
    val licenseNote: String,
)

object AssetSources {
    val all = listOf(
        AssetSource("kenney", "Kenney", "https://kenney.nl/assets", "CC0 1.0", true,
            setOf(AssetKind.SPRITES, AssetKind.MODELS, AssetKind.TILESETS, AssetKind.UI_KIT, AssetKind.ICONS, AssetKind.SFX, AssetKind.MUSIC, AssetKind.FONT, AssetKind.VFX),
            "Kenney publishes its asset packs under CC0; confirm the license text on each pack page and keep a copy of it."),
        AssetSource("quaternius", "Quaternius", "https://quaternius.com", "CC0 1.0", true,
            setOf(AssetKind.MODELS),
            "Free packs are published as CC0; confirm on the pack page. Some premium packs differ - use only the free CC0 ones."),
        AssetSource("polyhaven", "Poly Haven", "https://polyhaven.com", "CC0 1.0", true,
            setOf(AssetKind.TEXTURES, AssetKind.MODELS),
            "All assets are CC0."),
        AssetSource("ambientcg", "ambientCG", "https://ambientcg.com", "CC0 1.0", true,
            setOf(AssetKind.TEXTURES),
            "All assets are CC0."),
        AssetSource("opengameart", "OpenGameArt.org (CC0-filtered)", "https://opengameart.org", "per-asset (filter: CC0)", false,
            setOf(AssetKind.SPRITES, AssetKind.TILESETS, AssetKind.MUSIC, AssetKind.SFX, AssetKind.UI_KIT, AssetKind.MODELS),
            "License varies per asset. Only use items whose own page states CC0; record the page URL and author."),
        AssetSource("itch_cc0", "itch.io (CC0-tagged assets)", "https://itch.io/game-assets/assets-cc0", "per-asset (filter: CC0)", false,
            setOf(AssetKind.SPRITES, AssetKind.TILESETS, AssetKind.MODELS, AssetKind.MUSIC, AssetKind.SFX, AssetKind.UI_KIT),
            "License varies per creator. Only use items whose page explicitly states CC0 or public domain; 'free download' is not a license. Record the page URL and creator."),
        AssetSource("freesound_cc0", "Freesound (CC0-filtered)", "https://freesound.org", "per-asset (filter: CC0)", false,
            setOf(AssetKind.SFX, AssetKind.MUSIC),
            "License varies per sound; filter to CC0 and record each sound's page URL. Downloads require a free account - prefer other sources when possible."),
    )

    fun forKind(kind: AssetKind, includeNonBlanket: Boolean = true) =
        all.filter { kind in it.kinds && (includeNonBlanket || it.blanketCc0) }
}

object AssetPlan {

    fun needs(t: Traits): List<AssetNeed> {
        if (!t.genresKnown) return emptyList()
        // 3D models are needed for true 3D and for 2.5D with a voxel/3D look (the world is 3D even though play is on a plane).
        val is3D = t.dimension == "3D" || t.value(Keys.ART_DIRECTION) == "voxel"
        val out = mutableListOf<AssetNeed>()
        val visualKind = if (is3D) AssetKind.MODELS else AssetKind.SPRITES
        out += AssetNeed("characters", if (is3D) "Character and creature models" else "Character and creature sprites", visualKind,
            if (t.has(com.hotattic.gamedesigner.core.schema.Tag.COMBAT)) "Player characters/heroes and all enemies and bosses in the content scope, with the animation states the gameplay needs (idle/move/attack/hit/death as relevant)."
            else "The player character(s) and any non-combat creatures or props the design names, with the animation states the gameplay needs (idle, move, jump, land, catch, climb as relevant).")
        out += AssetNeed("environment", if (is3D) "Environment models and props" else "Environment tiles, backgrounds and props", if (is3D) AssetKind.MODELS else AssetKind.TILESETS,
            "Everything needed to render the world structure chosen for this game.")
        if (is3D) out += AssetNeed("materials", "Materials and textures", AssetKind.TEXTURES, "Surface materials consistent with the chosen art direction.")
        out += AssetNeed("ui_kit", "UI kit (panels, buttons, bars, icons)", AssetKind.UI_KIT, "All menus, HUD elements, settings, and icon sets referenced by the spec.")
        out += AssetNeed("vfx", "Visual effects", AssetKind.VFX,
            if (t.has(com.hotattic.gamedesigner.core.schema.Tag.COMBAT)) "Hit effects, pickups, death, ambient particles." else "Feedback effects for the game's key actions (landing, catching, bouncing, failing and recovering as relevant) and ambient particles.")
        out += AssetNeed("font", "Fonts", AssetKind.FONT, "At least one readable UI font and one display font; must satisfy the license policy.")
        when (t.value(Keys.AUDIO)) {
            "silent" -> Unit
            "sfx_only" -> out += AssetNeed("sfx", "Sound effects", AssetKind.SFX, "UI, gameplay actions, impacts, pickups, alerts.")
            else -> {
                out += AssetNeed("sfx", "Sound effects", AssetKind.SFX, "UI, gameplay actions, impacts, pickups, alerts.")
                out += AssetNeed("music", "Music tracks", AssetKind.MUSIC, "Menu track plus gameplay track(s) matching the mood; loopable.")
            }
        }
        return out
    }

    /** Default resolution for a need under the owner's asset policy. Never returns an unresolved result. */
    fun defaultRecord(need: AssetNeed, t: Traits, now: Long): AssetRecord {
        val policy = t.value(Keys.ASSET_POLICY) ?: "cc0_default"
        val art = t.value(Keys.ART_DIRECTION)
        if (policy.startsWith("supplied")) {
            val gaps = when (policy) {
                "supplied_cc0" -> "Gaps the supplied packs do not cover: use a coherent CC0/public-domain set (verify each license), and only then an original procedural replacement: ${proceduralFallback(need)}"
                "supplied_original" -> "Gaps the supplied packs do not cover: create original assets (${proceduralFallback(need)})"
                else -> "Nothing external: anything the supplied packs lack is created from them or by project code (${proceduralFallback(need)})"
            }
            val sources = if (policy == "supplied_cc0") AssetSources.forKind(need.kind).filter { it.blanketCc0 }.joinToString("; ") { "${it.name} (${it.url})" } else ""
            return AssetRecord(
                needId = need.id, resolution = AssetResolution.USER_SUPPLIED, description = need.detail,
                source = "Owner-supplied asset packs delivered with the master prompt" + if (sources.isNotEmpty()) "; gap sources: $sources" else "",
                license = "Owner-supplied (the owner confirms the right to use them); gap assets per the policy",
                notes = "INSPECT THE SUPPLIED PACKS FIRST: list their files, formats, scale, rigs/animations and any license or readme, and use their real contents for this need. Do not replace them with external or generated assets. $gaps",
                verifiedAt = null,
            )
        }
        val proceduralArt = art in setOf("minimal_geometric", "vector_flat")
        val sources = AssetSources.forKind(need.kind, includeNonBlanket = policy != "original_only")
            .filter { policy != "original_only" }
        val preferExternal = policy != "original_only" && !(proceduralArt && need.kind in setOf(AssetKind.SPRITES, AssetKind.TILESETS, AssetKind.MODELS, AssetKind.VFX, AssetKind.UI_KIT, AssetKind.ICONS, AssetKind.TEXTURES)) && sources.isNotEmpty()
        return if (preferExternal) {
            val blanket = sources.filter { it.blanketCc0 }
            val ordered = (blanket + sources.filter { !it.blanketCc0 })
            AssetRecord(
                needId = need.id,
                resolution = AssetResolution.EXTERNAL_CC0,
                description = need.detail,
                source = ordered.joinToString("; ") { "${it.name} (${it.url})" },
                license = if (policy == "cc0_or_cc_by") "CC0 1.0 (CC-BY allowed with attribution)" else "CC0 1.0 / public domain",
                notes = "Choose ONE visually coherent set (consistency over variety). If no coherent clean-license set covers this need, build the original procedural fallback: ${proceduralFallback(need)}",
                verifiedAt = null,
            )
        } else {
            AssetRecord(
                needId = need.id,
                resolution = AssetResolution.PROCEDURAL,
                description = need.detail,
                source = "Original, generated by project code",
                license = "Original work (project license)",
                notes = proceduralFallback(need),
                verifiedAt = now,
            )
        }
    }

    fun proceduralFallback(need: AssetNeed): String = when (need.kind) {
        AssetKind.SPRITES -> "Draw sprites from code: palette-constrained shape/pixel generators producing spritesheets at build time, with deterministic seeds and per-state animation frames."
        AssetKind.MODELS -> "Generate meshes from primitives and parametric/lathe/extrude builders with vertex colors or simple materials; deterministic seeds."
        AssetKind.TEXTURES -> "Generate tileable textures with noise/pattern shaders baked at build time."
        AssetKind.TILESETS -> "Generate tiles procedurally from a fixed palette with edge-matching rules; bake into an atlas."
        AssetKind.UI_KIT -> "Build the UI from engine-native drawing primitives (rounded rects, gradients, icons drawn as vectors) with a shared theme."
        AssetKind.ICONS -> "Draw icons as vector paths in code with a single stroke style."
        AssetKind.VFX -> "Use engine particle systems and shaders with a shared small palette."
        AssetKind.MUSIC -> "Synthesize loopable music from a small procedural sequencer (scales, chord progressions, simple instruments) rendered at build time."
        AssetKind.SFX -> "Synthesize effects with a small sfxr-style synthesizer (waveform, envelope, filter) rendered at build time with seeded parameters per effect."
        AssetKind.FONT -> "Use a CC0 font from a blanket-CC0 source, or generate a bitmap font from code."
    }

    /** Records for all needs not yet resolved, using defaults. */
    fun resolveMissing(project: Project, now: Long): List<AssetRecord> {
        val t = Traits(project)
        val have = project.assets.map { it.needId }.toSet()
        return needs(t).filter { it.id !in have }.map { defaultRecord(it, t, now) }
    }

    fun unresolved(project: Project): List<AssetNeed> {
        val have = project.assets.map { it.needId }.toSet()
        return needs(Traits(project)).filter { it.id !in have }
    }
}
