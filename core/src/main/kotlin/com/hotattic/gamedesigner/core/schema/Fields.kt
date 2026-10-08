package com.hotattic.gamedesigner.core.schema

import com.hotattic.gamedesigner.core.model.Category
import com.hotattic.gamedesigner.core.model.ProjectMode

object Keys {
    const val CONCEPT = "concept"
    const val GENRE = "genre"
    const val DIMENSION = "dimension"
    const val PERSPECTIVE = "perspective"
    const val PLATFORMS = "platforms"
    const val ORIENTATION = "orientation"
    const val CORE_FANTASY = "core_fantasy"
    const val PLAYER_FEELING = "player_feeling"
    const val REFERENCES = "references"
    const val REFERENCE_ASPECTS = "reference_aspects"
    const val CORE_LOOP = "core_loop"
    const val SESSION_STRUCTURE = "session_structure"
    const val WORLD_STRUCTURE = "world_structure"
    const val MOVEMENT_CAMERA = "movement_camera"
    const val COMBAT_MODEL = "combat_model"
    const val ENEMIES_BOSSES = "enemies_bosses"
    const val HAS_COMBAT = "has_combat"
    const val HAS_ECONOMY = "has_economy"
    const val HAS_CRAFTING = "has_crafting"
    const val CHARACTERS = "characters_classes"
    const val PROGRESSION = "progression"
    const val ECONOMY = "economy_systems"
    const val SURVIVAL_CRAFTING = "survival_crafting"
    const val AUTOMATION_SIM = "automation_sim_systems"
    const val STORY = "story_narrative"
    const val WIN_LOSS = "win_loss_conditions"
    const val DIFFICULTY_FAILURE = "difficulty_failure"
    const val SAVE_SYSTEM = "save_system"
    const val TUTORIAL = "tutorial_onboarding"
    const val INPUT_METHODS = "input_methods"
    const val TOUCH_SCHEME = "touch_scheme"
    const val INPUT_REMAP = "input_remapping"
    const val ART_DIRECTION = "art_direction"
    const val COLOR_MOOD = "color_mood"
    const val VFX = "vfx_juice"
    const val AUDIO = "audio_music"
    const val HUD_UI = "hud_ui_style"
    const val MENUS_SETTINGS = "menus_settings"
    const val ACCESSIBILITY = "accessibility"
    const val PERFORMANCE = "performance_target"
    const val MIN_HARDWARE = "min_hardware"
    const val ENGINE = "engine"
    const val TOOLCHAIN_PREFS = "toolchain_prefs"
    const val NETWORK_POLICY = "network_policy"
    const val OTA_UPDATES = "ota_updates"
    const val CI_BUILD = "ci_build"
    const val TESTING = "testing_strategy"
    const val ASSET_POLICY = "asset_policy"
    const val BRAND_ICON = "branding_icon"
    const val BRAND_STUDIO = "branding_studio_splash"
    const val BRAND_GAME_SPLASH = "branding_game_splash"
    const val SCOPE_CHOICE = "scope_choice"
    const val DISPLAY_NAME = "display_name"
    const val PACKAGE_ID = "package_id"
    const val VERSION_STRATEGY = "version_strategy"
    const val STORE_PLAN = "store_plan"
    const val MONETIZATION = "monetization"
    const val PRIVACY = "privacy_data"
    const val SIGNING = "signing_plan"
    const val DONE = "definition_of_done"
    const val EXCLUSIONS = "exclusions_deferred"
    const val CONTINUATION_GOAL = "continuation_goal"
    const val PRESERVE_SYSTEMS = "preserve_systems"
    const val KNOWN_ISSUES = "known_issues"
    const val FIVE_MINUTES = "five_minutes"
    const val FIRST_SLICE = "first_slice"
    const val MUST_NOT_CHANGE = "must_not_change"

    val brandingKeyForSlot = mapOf(
        com.hotattic.gamedesigner.core.model.BrandingSlot.ICON to BRAND_ICON,
        com.hotattic.gamedesigner.core.model.BrandingSlot.STUDIO_SPLASH to BRAND_STUDIO,
        com.hotattic.gamedesigner.core.model.BrandingSlot.GAME_SPLASH to BRAND_GAME_SPLASH,
    )
}

/** Question cardinality, explicit in the schema: drives both the UI control and how answers are interpreted. */
enum class FieldKind {
    TEXT, SINGLE, MULTI, BOOLEAN, NUMBER;
    /** Answers are chosen from [Field.options]. */
    val isSelect: Boolean get() = this == SINGLE || this == MULTI
    val isFreeform: Boolean get() = this == TEXT || this == NUMBER
}

data class Option(val id: String, val label: String, val description: String = "", /** Short phrases an owner naturally uses for this option; matched as whole phrases. */ val aliases: List<String> = emptyList())

/** What "choose for me" would pick, and why. */
data class Suggestion(val value: String, val rationale: String)

/**
 * Which decisions the owner is asked about and which Bob derives himself. The owner decides what could change the game
 * (vision, loop, world, failure, scope, look, invariants); routine engineering is derived with a recorded DEFAULT provenance and
 * can still be reviewed through "refine". Kept apart from the field table so tiers can be tuned without touching schemas.
 */
object Tiers {
    val derive = setOf(
        Keys.PERSPECTIVE, Keys.REFERENCE_ASPECTS, Keys.SESSION_STRUCTURE, Keys.SAVE_SYSTEM, Keys.TUTORIAL, Keys.INPUT_METHODS, Keys.TOUCH_SCHEME,
        Keys.INPUT_REMAP, Keys.VFX, Keys.AUDIO, Keys.HUD_UI, Keys.MENUS_SETTINGS, Keys.ACCESSIBILITY, Keys.PERFORMANCE, Keys.ENGINE,
        Keys.CORE_FANTASY, Keys.ORIENTATION, Keys.NETWORK_POLICY, Keys.CI_BUILD, Keys.TESTING, Keys.SCOPE_CHOICE, Keys.PACKAGE_ID, Keys.VERSION_STRATEGY, Keys.STORE_PLAN,
        Keys.MONETIZATION, Keys.PRIVACY, Keys.SIGNING, Keys.ENEMIES_BOSSES,
    )
    val forceAsk = setOf(Keys.PLAYER_FEELING, Keys.FIRST_SLICE, Keys.MUST_NOT_CHANGE)
    val forceOptional = setOf(Keys.REFERENCES, Keys.FIVE_MINUTES)
    fun required(key: String, declared: Boolean) = when (key) { in forceAsk -> true; in forceOptional -> false; else -> declared }
}

class Field(
    val key: String,
    val category: Category,
    val kind: FieldKind,
    val title: String,
    val prompt: String,
    /** Plain-language explanation for beginners ("why am I being asked this?"). */
    val why: String,
    val priority: Int,
    required: Boolean = true,
    val modes: Set<ProjectMode> = NEW_ONLY,
    val expertOnly: Boolean = false,
    val relevant: (Traits) -> Boolean = { true },
    val options: (Traits) -> List<Option> = { emptyList() },
    val suggest: (Traits) -> Suggestion? = { null },
    /** Returns an error message if invalid. */
    val validate: (Traits, String) -> String? = { _, _ -> null },
    val allowCustom: Boolean = false,
    /**
     * Keys whose change makes a NON-owner decision of this field stale (it is then cleared and re-derived/re-asked).
     * null = the default rule (see [Dependencies]). Owner-authored decisions are never cleared automatically.
     */
    val derivedFrom: Set<String>? = null,
) {
    val required: Boolean = Tiers.required(key, required)
    /** Bob derives this himself (recorded as DEFAULT) instead of asking; the owner can still change it through "refine". */
    val derived: Boolean get() = key in Tiers.derive

    fun isRelevant(t: Traits): Boolean = t.mode in modes && relevant(t)

    companion object {
        val NEW_ONLY = setOf(ProjectMode.NEW_GAME)
        val ALL_MODES = ProjectMode.values().toSet()
        val CONTINUATION = setOf(ProjectMode.EXISTING_GAME, ProjectMode.PLAYTEST_CONTINUE)
    }
}

private fun o(id: String, label: String, desc: String = "", vararg aliases: String) = Option(id, label, desc, aliases.toList())
private fun List<Option>.pick(vararg ids: String) = filter { it.id in ids }

object Fields {

    val genreOptions: List<Option> = GenreKnowledge.all.map { Option(it.id, it.label) } + Option("other", "Something else", "Describe it in your own words.")

    private fun primary(t: Traits): Genre = t.genres.firstOrNull() ?: GenreKnowledge.other

    val all: List<Field> = listOf(
        Field(Keys.CONCEPT, Category.GAMEPLAY, FieldKind.TEXT, "Game idea",
            "What game do you want to make? Describe the idea however you want. You can name games you like, describe the feeling you want, or give me only a rough concept.",
            "Everything else is built from this. A rough idea is fine - I'll fill in the rest with you.",
            10, modes = Field.ALL_MODES,
            validate = { _, v -> if (v.trim().length < 8) "Tell me a little more - a sentence is plenty." else null }),

        Field(Keys.CONTINUATION_GOAL, Category.GAMEPLAY, FieldKind.SINGLE, "What are we doing to this game?",
            "What do you want to do with the existing game?",
            "I'll inspect what is really in the repository first, then plan changes that don't break what already works.",
            12, modes = Field.CONTINUATION,
            options = { listOf(
                o("expand", "Expand it", "Add new content or systems."),
                o("repair", "Repair it", "Fix bugs and broken systems."),
                o("modernize", "Modernize it", "Upgrade engine/toolchain/dependencies."),
                o("rebuild_system", "Rebuild a system", "Replace one subsystem while keeping the rest."),
                o("polish_release", "Polish for release", "Final quality, assets and packaging."),
            ) }),

        Field(Keys.PRESERVE_SYSTEMS, Category.GAMEPLAY, FieldKind.TEXT, "Keep as-is",
            "Is there anything that works today that I must not break or replace?",
            "Protects the parts you're happy with.",
            14, required = false, modes = Field.CONTINUATION),

        Field(Keys.KNOWN_ISSUES, Category.GAMEPLAY, FieldKind.TEXT, "Known problems",
            "What problems have you noticed? Describe what happens, in your own words.",
            "Real symptoms from playing beat any guess about the code.",
            16, required = false, modes = Field.CONTINUATION),

        Field(Keys.GENRE, Category.GAMEPLAY, FieldKind.MULTI, "Genre",
            "What kind of game is this? Pick one or mix several.",
            "Genre decides which questions matter and which systems the game must contain.",
            20, modes = Field.ALL_MODES, options = { genreOptions }, allowCustom = true,
            // "Choose for me" reads the owner's own description; a design that fits no known genre is treated as a custom one built from their words.
            suggest = { t ->
                val said = (listOf(t.project.originalConcept) + t.project.activeFacts().map { it.text }).joinToString(". ")
                GenreKnowledge.detect(said).firstOrNull()?.let { Suggestion(it.id, "That is what your description sounds like.") }
                    ?: Suggestion("other", "Nothing standard fits, so I'll build it as a custom design from your description.")
            }),

        Field(Keys.DIMENSION, Category.GAMEPLAY, FieldKind.SINGLE, "2D or 3D",
            "Should it be 2D, top-down, isometric, 2.5D (3D look, 2D play) or full 3D?",
            "3D costs far more in art, performance and build risk. 2D can still look fantastic.",
            30, modes = Field.ALL_MODES,
            options = { listOf(o("2D", "2D", "Flat 2D, usually side-view."), o("2.5D", "2.5D", "3D visuals on a 2D plane or fixed camera."), o("3D", "3D"), o("top_down", "Top-down (2D)", "2D seen from directly above."), o("isometric", "Isometric (2.5D)", "Angled fixed camera, 2D play on a grid or plane.")) },
            suggest = { t ->
                val g = primary(t)
                if (t.mobileOnly && g.complexity >= 4) Suggestion("2D", "A ${g.label.lowercase()} is already a big system; 2D keeps asset and performance risk low on a phone.")
                else Suggestion("2D", "2D is the lowest-risk way to get a complete, polished, playable game on a phone.")
            }),

        Field(Keys.PERSPECTIVE, Category.GAMEPLAY, FieldKind.SINGLE, "Camera / perspective",
            "What does the player see - side view, top-down, first person...?",
            "Camera choice drives controls, art and level design.",
            35,
            options = { t ->
                if (t.dimension == "3D") listOf(
                    o("third_person", "Third person"), o("first_person", "First person"),
                    o("top_down_3d", "Top-down 3D"), o("isometric_3d", "Isometric 3D"), o("fixed_camera", "Fixed cameras / screens"))
                else listOf(
                    o("side_view", "Side view"), o("vertical_scroll", "Vertical scroller", "Side-on view that scrolls up and down."), o("top_down", "Top-down"), o("isometric_2d", "Isometric"),
                    o("fixed_screen", "Single fixed screen / board"), o("ui_driven", "Menu/card driven (no world camera)"))
            },
            suggest = { t ->
                val g = primary(t).id
                val v = when {
                    t.dimension == "3D" -> when (g) { "shooter" -> "first_person"; "racing" -> "third_person"; "city_builder", "colony_sim", "fantasy_city_builder", "factory_automation", "strategy_rts" -> "isometric_3d"; else -> "third_person" }
                    g in setOf("platformer", "metroidvania", "fighting") -> "side_view"
                    g in setOf("puzzle", "narrative_adventure") -> "fixed_screen"
                    g in setOf("card_deckbuilder", "sim_management", "management_sim") -> "ui_driven"
                    g in setOf("city_builder", "colony_sim", "fantasy_city_builder", "factory_automation", "strategy_rts", "turn_based_strategy") -> "isometric_2d"
                    else -> "top_down"
                }
                Suggestion(v, "Most common and best-tested choice for a ${primary(t).label.lowercase()}.")
            }),

        Field(Keys.PLATFORMS, Category.PLATFORM, FieldKind.MULTI, "Target platforms",
            "Where should the game run? Android phone is the default.",
            "Each extra platform adds testing and packaging work. Pick the ones you will actually use.",
            40, modes = Field.ALL_MODES,
            options = { Platforms.labels.map { (id, label) -> Option(id, label) } },
            suggest = { Suggestion(Platforms.ANDROID, "You test on an Android phone, and one platform done well beats three done halfway.") }),

        Field(Keys.ORIENTATION, Category.PLATFORM, FieldKind.SINGLE, "Phone orientation",
            "Portrait, landscape, or both on a phone?",
            "Portrait suits one-handed puzzle/card/idle play; landscape suits action and builders.",
            45, relevant = { it.isMobile },
            options = { listOf(o("landscape", "Landscape"), o("portrait", "Portrait"), o("both", "Both (rotate)")) },
            suggest = { t ->
                val g = primary(t).id
                if (t.value(Keys.PERSPECTIVE) == "vertical_scroll") Suggestion("portrait", "A vertical scroller reads best in portrait, with the long axis along the scroll direction.")
                else if (g in setOf("puzzle", "card_deckbuilder", "sim_management", "management_sim", "narrative_adventure")) Suggestion("portrait", "One-handed portrait play fits ${primary(t).label.lowercase()} games.")
                else Suggestion("landscape", "Action, platforming and builders need width for controls and visibility.")
            }),

        Field(Keys.CORE_FANTASY, Category.GAMEPLAY, FieldKind.TEXT, "Core fantasy",
            "In one or two sentences: who is the player and what power or experience are they enjoying?",
            "The core fantasy is the north star that settles every later design argument.",
            50, relevant = { it.genresKnown },
            suggest = { t -> t.value(Keys.CONCEPT)?.let { Suggestion(ConceptText.fantasy(it), "Drawn from how you described the idea; refine it any time.") } },
            validate = { _, v -> if (v.trim().length < 12) "A bit more detail please - one full sentence." else null }),

        Field(Keys.PLAYER_FEELING, Category.GAMEPLAY, FieldKind.TEXT, "Intended feeling",
            "What should the player feel while playing? Tense, cozy, powerful, clever or relaxed are only examples - any word or sentence works, even just one word.",
            "Tone guides pacing, audio, color and difficulty.",
            52, required = false, relevant = { it.genresKnown },
            suggest = { t ->
                val said = DimensionLexicon.sentencesFor(t.project, DimId.FEELING).firstOrNull()
                if (said != null) Suggestion(said, "In your own words.")
                else Suggestion(when (primary(t).id) {
                    "survivors_like" -> "Powerful and frantic: fragile at the start, snowballing into overwhelming force."
                    "action_roguelite" -> "Tense and rewarding: every run is a gamble that pays off when a build clicks."
                    "platformer", "metroidvania" -> "Precise and flowing: tight control, with a rush when moves chain cleanly."
                    "puzzle" -> "Calm and clever: quiet focus, with a satisfying click when the answer lands."
                    "city_builder", "colony_sim", "fantasy_city_builder", "management_sim", "factory_automation", "sim_management" -> "Absorbing and orderly: steady, satisfying growth from small tweaks."
                    "shooter", "fighting" -> "Fast and sharp: tense encounters and clean, readable feedback."
                    else -> "Engaging and readable, with steady tension and clear payoffs."
                }, "A fitting default for this kind of game; correct it in your own words any time.")
            }),

        Field(Keys.REFERENCES, Category.GAMEPLAY, FieldKind.TEXT, "Reference games",
            "Which existing games are you drawing from, and which part of each? Say \"none\" if there aren't any.",
            "Named references let me research what makes them work. We take the ideas, never their art, characters or music.",
            55, modes = Field.ALL_MODES, relevant = { it.genresKnown },
            suggest = { Suggestion("none", "No reference games; the design stands on its own.") }),


        Field(Keys.REFERENCE_ASPECTS, Category.GAMEPLAY, FieldKind.TEXT, "What to take from the references",
            "What exactly do you want from those games - the structure, the pacing, the feel of the combat, the progression? One line per game is plenty.",
            "Knowing which part of each reference you love stops me copying the wrong thing.",
            57, relevant = { it.genresKnown && it.value(Keys.REFERENCES).let { v -> v != null && v != "none" } },
            suggest = { t ->
                val names = t.project.references.map { it.name }.ifEmpty { listOf(t.value(Keys.REFERENCES).orEmpty()) }
                Suggestion(names.joinToString(" ") { "From $it: its core loop structure, pacing and the way build choices compound." } + " Take design ideas only, never characters, art, maps, music or writing.", "A safe default; correct me if you meant something else.")
            }),

        Field(Keys.CORE_LOOP, Category.GAMEPLAY, FieldKind.TEXT, "Core gameplay loop",
            "Describe the loop the player repeats minute to minute. I can draft it from the genre if you want.",
            "The loop is what makes the game fun or not. Everything else supports it.",
            60, relevant = { it.genresKnown },
            suggest = { t ->
                val gs = t.genres
                val base = t.loopText(gs.firstOrNull() ?: GenreKnowledge.other)
                val blend = gs.drop(1).joinToString { it.label.lowercase() }
                Suggestion(if (blend.isEmpty()) base else "$base Blended with $blend mechanics.", "Drafted from the genre's proven loop.")
            }),

        Field(Keys.SESSION_STRUCTURE, Category.GAMEPLAY, FieldKind.SINGLE, "Session length",
            "How long is one sitting of play?",
            "Phone play is usually short and interruptible, which affects saves and level size.",
            65, relevant = { it.genresKnown },
            options = { listOf(
                o("bite_sized", "1-5 minutes", "Quick levels or rounds."),
                o("short_runs", "10-20 minutes", "A run or a stage."),
                o("medium_sessions", "30-60 minutes", "A mission or chapter."),
                o("long_sessions", "1+ hours", "Deep sessions, campaigns."),
                o("endless", "Open-ended", "Sandbox or idle, no fixed session."),
            ) },
            suggest = { t ->
                when (primary(t).id) {
                    "survivors_like", "action_roguelite", "card_deckbuilder", "tower_defense" -> Suggestion("short_runs", "Run-based games fit 10-20 minute phone sessions.")
                    "puzzle", "platformer" -> Suggestion("bite_sized", "Short levels respect phone play.")
                    "city_builder", "colony_sim", "fantasy_city_builder", "management_sim", "factory_automation", "survival_crafting", "sim_management" -> Suggestion("endless", "Builders and survival games are open-ended; autosave covers interruptions.")
                    else -> Suggestion("medium_sessions", "A balanced default for story/action games.")
                }
            }),

        Field(Keys.WORLD_STRUCTURE, Category.CONTENT, FieldKind.SINGLE, "World / level structure",
            "How is the world organized?",
            "This determines whether we build tools to generate content or hand-author it.",
            70, relevant = { it.genresKnown && !it.hasGenre("card_deckbuilder") || it.genres.size > 1 },
            options = { listOf(
                o("single_arena", "One arena", "A single contained play space."),
                o("procedural_stages", "Procedurally generated stages"),
                o("authored_levels", "Hand-authored levels"),
                o("hub_missions", "Hub and missions"),
                o("vertical_shaft", "One vertical shaft / tower", "Traverse a single tall space up or down."),
                o("open_map", "Large connected/open map"),
            ) },
            suggest = { t ->
                if (t.value(Keys.PERSPECTIVE) == "vertical_scroll") Suggestion("vertical_shaft", "A vertical scroller is one continuous tall world, not a string of separate levels.")
                else when (primary(t).id) {
                    "survivors_like" -> Suggestion("single_arena", "Survivors-likes thrive on scalable arenas with escalating density.")
                    "action_roguelite", "survival_crafting" -> Suggestion("procedural_stages", "Procedural content gives replay value without hand-building huge amounts of levels.")
                    "metroidvania", "rpg" -> Suggestion("open_map", "Exploration is central.")
                    "city_builder", "colony_sim", "fantasy_city_builder", "management_sim", "factory_automation", "sim_management" -> Suggestion("single_arena", "A single large map or board with scenarios.")
                    else -> Suggestion("authored_levels", "Hand-authored levels give the best-quality first playable.")
                }
            }),

        Field(Keys.MOVEMENT_CAMERA, Category.GAMEPLAY, FieldKind.TEXT, "Movement and camera feel",
            "How should movement and the camera feel - snappy, floaty, heavy, smooth-follow?",
            "Feel is what separates a game that feels good from one that is merely functional.",
            75, relevant = { it.has(Tag.MOVEMENT) },
            suggest = { t ->
                val txt = if (t.has(Tag.FAST_TWITCH)) "Snappy, responsive movement with tight acceleration/deceleration, input buffering and a smooth-follow camera with slight look-ahead."
                else "Smooth, readable movement with a stable follow camera, minimal screen shake and clear feedback."
                Suggestion(txt, "A safe default for ${primary(t).label.lowercase()}.")
            }),

        Field(Keys.COMBAT_MODEL, Category.GAMEPLAY, FieldKind.MULTI, "Combat style",
            "How does combat work? Pick every style that applies, or describe your own in a few words.",
            "Combat style decides controls, enemy AI and balance work.",
            80, relevant = { it.has(Tag.COMBAT) }, allowCustom = true,
            options = { t -> listOf(
                o("auto_attack", "Auto-attack", "Weapons fire on their own; you position."),
                o("aimed_real_time", "Aim and shoot in real time"),
                o("melee_combos", "Melee and combos"),
                o("ability_cooldown", "Abilities on cooldowns"),
                o("per_character", "Different for each character or side", "Each playable character or mode fights in its own way."),
                o("turn_based", "Turn-based"),
                o("tactical_grid", "Grid tactics"),
            ).filter { !(it.id in setOf("turn_based", "tactical_grid") && Tag.TURN_BASED in t.rejectedTags) && !(it.id == "per_character" && !t.twoSides) } },
            suggest = { t ->
                if (t.twoSides) return@Field Suggestion("per_character", "You described two different characters or sides; each should fight in its own way.")
                when (primary(t).id) {
                    "survivors_like" -> Suggestion("auto_attack", "Defining feature of the genre, and ideal for touch controls.")
                    "card_deckbuilder", "turn_based_strategy" -> Suggestion(if (primary(t).id == "card_deckbuilder") "turn_based" else "tactical_grid", "Matches the genre.")
                    "shooter" -> Suggestion("aimed_real_time", "Core of the genre.")
                    "fighting", "platformer", "metroidvania" -> Suggestion("melee_combos", "Fits tight action games.")
                    else -> Suggestion("ability_cooldown", "Flexible and works well on touch.")
                }
            }),

        Field(Keys.ENEMIES_BOSSES, Category.CONTENT, FieldKind.TEXT, "Enemies and bosses",
            "What do enemies and bosses look like and how do they behave?",
            "A distinct, readable roster is a big part of the content volume.",
            82, relevant = { it.has(Tag.COMBAT) },
            suggest = { t -> Suggestion("A roster of distinct enemy archetypes (chaser, ranged, swarm, tank, elite) with telegraphed attacks, plus bosses with phase-based patterns. Names and designs are original and fit the world and tone described in Part A.", "Archetype-based roster scales cleanly with scope.") }),

        Field(Keys.CHARACTERS, Category.GAMEPLAY, FieldKind.SINGLE, "Characters / classes",
            "One hero, several heroes, or classes with loadouts?",
            "More playable characters multiply content and balance work.",
            85, relevant = { it.has(Tag.CHARACTERS) },
            options = { listOf(
                o("single_hero", "One hero"), o("selectable_heroes", "Several unlockable heroes"),
                o("classes_loadouts", "Classes with loadouts"), o("party", "A party of characters")) },
            suggest = { t ->
                if (t.hasGenre("survivors_like") || t.hasGenre("action_roguelite")) Suggestion("selectable_heroes", "Unlockable heroes with unique starting kits are a core roguelite hook.")
                else Suggestion("single_hero", "Keeps scope focused.")
            }),

        Field(Keys.PROGRESSION, Category.GAMEPLAY, FieldKind.SINGLE, "Progression",
            "How does the player get stronger or unlock things over time?",
            "Progression is what keeps players coming back.",
            88, relevant = { it.genresKnown && !it.hasGenre("puzzle") || it.genres.size > 1 },
            options = { listOf(
                o("in_run_upgrades", "Upgrades during a run only"),
                o("meta_unlocks", "Permanent unlocks between runs"),
                o("both", "Both in-run upgrades and permanent unlocks"),
                o("xp_levels", "XP and levels"),
                o("tech_tree", "Tech / research tree"),
                o("content_unlocks", "Unlock new areas and content"),
                o("abilities_gear", "New abilities and equipment", "Power comes from what you can do and carry, not from separate levels."),
            ) },
            suggest = { t ->
                if (t.continuousWorld) Suggestion("abilities_gear", "A continuous world is best paced by new abilities and equipment rather than level unlocks.")
                else when (primary(t).id) {
                    "survivors_like", "action_roguelite", "card_deckbuilder" -> Suggestion("both", "Run-based games feel best with build-crafting in a run plus permanent growth.")
                    "factory_automation", "city_builder", "colony_sim", "fantasy_city_builder", "strategy_rts" -> Suggestion("tech_tree", "Unlocks gate complexity.")
                    "rpg", "metroidvania" -> Suggestion("xp_levels", "Classic fit.")
                    else -> Suggestion("content_unlocks", "Simple and satisfying.")
                }
            }),

        Field(Keys.ECONOMY, Category.CONTENT, FieldKind.TEXT, "Economy and resources",
            "What resources exist and how do they flow (earn, spend, upkeep)?",
            "Economies need numbers; unclear ones usually end up unbalanced.",
            90, relevant = { it.has(Tag.ECONOMY) },
            suggest = { Suggestion("A small set of primary resources with clear sources and sinks, tabulated costs/yields in data files, and a balance spreadsheet validated by headless simulation.", "Data-driven and testable.") }),

        Field(Keys.SURVIVAL_CRAFTING, Category.CONTENT, FieldKind.TEXT, "Survival and crafting",
            "What survival needs, gathering, and crafting do you want?",
            "Crafting UX and resource respawn are the usual weak spots, so we settle them up front.",
            91, relevant = { it.has(Tag.CRAFTING) },
            suggest = { Suggestion("Core survival needs (health plus one or two meters), tiered tools and stations, recipes in data files, a hotbar+inventory UI with search/filter, and renewable resource nodes with respawn timers.", "Covers the systems that are most often left undefined.") }),

        Field(Keys.AUTOMATION_SIM, Category.CONTENT, FieldKind.TEXT, "Simulation / building systems",
            "How do building, automation or simulation work (placement, throughput, citizens, tick speed)?",
            "Simulation games live or die on their rules and performance budget.",
            92, relevant = { it.has(Tag.BUILDING) || it.has(Tag.SIMULATION) },
            suggest = { Suggestion("Grid-based placement with validity rules, fixed-timestep deterministic simulation, pause/1x/2x/3x speeds, undo for placement, and an explicit per-tick entity budget.", "Deterministic and testable.") }),

        // Gate questions: when a genre only sometimes has a system, ask one short yes/no before any of that system's detail questions.
        Field(Keys.HAS_COMBAT, Category.GAMEPLAY, FieldKind.SINGLE, "Combat",
            "Does this game have combat - fighting enemies? Yes or no.",
            "A yes opens the combat, enemy and boss questions; a no skips them all.",
            79, relevant = { Gates.needed(it, Tag.COMBAT) },
            options = { listOf(o("yes", "Yes"), o("no", "No")) },
            suggest = { t -> Gates.suggest(t, Tag.COMBAT) }),

        Field(Keys.HAS_ECONOMY, Category.GAMEPLAY, FieldKind.SINGLE, "Economy",
            "Does this game have an economy - currency, shops, trading or resource costs? Yes or no.",
            "A yes opens the economy questions; a no skips them.",
            88, relevant = { Gates.needed(it, Tag.ECONOMY) },
            options = { listOf(o("yes", "Yes"), o("no", "No")) },
            suggest = { t -> Gates.suggest(t, Tag.ECONOMY) }),

        Field(Keys.HAS_CRAFTING, Category.GAMEPLAY, FieldKind.SINGLE, "Crafting",
            "Does this game have crafting or gathering resources to make things? Yes or no.",
            "A yes opens the crafting and survival questions; a no skips them.",
            89, relevant = { Gates.needed(it, Tag.CRAFTING) },
            options = { listOf(o("yes", "Yes"), o("no", "No")) },
            suggest = { t -> Gates.suggest(t, Tag.CRAFTING) }),

        Field(Keys.STORY, Category.CONTENT, FieldKind.SINGLE, "Story",
            "How much story does it have?",
            "Story adds writing, UI and content work.",
            95, relevant = { it.has(Tag.STORY) },
            options = { listOf(o("none", "None"), o("light_flavor", "Light flavor text"), o("full_story", "Full story with dialogue")) },
            suggest = { t -> Suggestion(if (t.hasGenre("narrative_adventure") || t.hasGenre("rpg")) "full_story" else "light_flavor", "Matches the genre's expectations.") }),

        Field(Keys.WIN_LOSS, Category.GAMEPLAY, FieldKind.TEXT, "Win and loss",
            "How does the player win, and how do they lose or fail?",
            "Every complete game needs a clear end state, even if it's a score.",
            100, relevant = { it.genresKnown && it.value(Keys.SESSION_STRUCTURE) != "endless" },
            suggest = { t ->
                if (t.continuousWorld && t.twoSides) return@Field Suggestion("Win by completing your side's traversal of the world to its far end; failure respawns at the last checkpoint.", "Fits a continuous world played from either end.")
                when (primary(t).id) {
                    "survivors_like" -> Suggestion("Win by surviving until the run timer ends or defeating the final boss; lose when health reaches zero. Results screen banks meta currency.", "Genre standard.")
                    "action_roguelite" -> Suggestion("Win by defeating the final boss; lose when health reaches zero (run ends, rewards banked).", "Genre standard.")
                    "platformer", "metroidvania" -> Suggestion("Win by reaching the final goal; failure respawns at the last checkpoint.", "Genre standard.")
                    "puzzle" -> Suggestion("Win a level by satisfying its goal condition; there is no hard fail, only restart/undo.", "Genre standard.")
                    else -> Suggestion("Win by completing the defined objectives; lose when the fail condition triggers, then offer retry or return to menu.", "Safe generic definition.")
                }
            }),

        Field(Keys.DIFFICULTY_FAILURE, Category.GAMEPLAY, FieldKind.SINGLE, "Difficulty and failure",
            "How harsh should failure be?",
            "This shapes how forgiving the game feels, and accessibility assists hang off it.",
            105, relevant = { it.genresKnown },
            options = { listOf(
                o("permadeath_meta", "Death ends the run; keep permanent rewards"),
                o("checkpoint_retry", "Checkpoints and quick retry"),
                o("adjustable", "Adjustable difficulty levels"),
                o("no_fail", "No failure state (relaxed)"),
            ) },
            suggest = { t ->
                when (primary(t).id) {
                    "survivors_like", "action_roguelite", "card_deckbuilder" -> Suggestion("permadeath_meta", "Genre expectation; rewards soften the sting.")
                    "puzzle", "sim_management", "management_sim", "city_builder", "fantasy_city_builder", "narrative_adventure" -> Suggestion("no_fail", "Relaxed play fits the genre.")
                    "platformer", "metroidvania", "shooter", "fighting" -> Suggestion("checkpoint_retry", "Action games feel best with checkpoints and a quick retry.")
                    else -> Suggestion("adjustable", "Lets different players enjoy the game, and doubles as an accessibility feature.")
                }
            }),

        Field(Keys.SAVE_SYSTEM, Category.TECHNICAL, FieldKind.SINGLE, "Saving",
            "How should saving work?",
            "On a phone the app can be killed at any time, so saving must be deliberate.",
            108, relevant = { it.genresKnown },
            options = { listOf(
                o("autosave_continue", "Autosave with Continue", "Always resume where you left off."),
                o("run_meta_save", "Save meta progress and current run state"),
                o("manual_slots", "Manual save slots"),
                o("checkpoint_only", "Checkpoints only"),
                o("progress_only", "Only progress and stats"),
            ) },
            suggest = { t ->
                when (primary(t).id) {
                    "survivors_like", "action_roguelite", "card_deckbuilder" -> Suggestion("run_meta_save", "Meta progress must persist; resuming an interrupted run is expected on phones.")
                    "puzzle", "platformer" -> Suggestion("progress_only", "Short sessions need only progress and best scores.")
                    "city_builder", "colony_sim", "fantasy_city_builder", "management_sim", "factory_automation", "survival_crafting", "sim_management", "rpg", "metroidvania" -> Suggestion("autosave_continue", "Long-lived worlds need robust autosave and versioned saves.")
                    else -> Suggestion("autosave_continue", "Safest default.")
                }
            }),

        Field(Keys.TUTORIAL, Category.GAMEPLAY, FieldKind.SINGLE, "Tutorial / onboarding",
            "How do new players learn the game?",
            "A first-run experience prevents the most common reason players quit.",
            110, relevant = { it.genresKnown },
            options = { listOf(
                o("guided_first_level", "Guided first level"), o("contextual_tips", "Contextual tips as things appear"),
                o("interactive_tutorial", "Dedicated interactive tutorial"), o("none", "No tutorial")) },
            suggest = { t -> Suggestion(if (primary(t).complexity >= 4) "interactive_tutorial" else "contextual_tips", "Matches the complexity of the systems.") }),

        Field(Keys.INPUT_METHODS, Category.PLATFORM, FieldKind.MULTI, "Controls",
            "How will players control it?",
            "Each input method needs its own tuned scheme and testing.",
            115, relevant = { it.platformsKnown },
            options = { listOf(o("touch", "Touch"), o("gamepad", "Gamepad"), o("keyboard_mouse", "Keyboard and mouse")) },
            suggest = { t ->
                val v = buildList {
                    if (t.isMobile) add("touch")
                    if (t.platforms.any { it in setOf(Platforms.WINDOWS, Platforms.MAC, Platforms.LINUX, Platforms.WEB) }) { add("keyboard_mouse"); add("gamepad") }
                }
                Suggestion(Decision_join(v.ifEmpty { listOf("keyboard_mouse") }), "Matches the chosen platforms.")
            }),

        Field(Keys.TOUCH_SCHEME, Category.PLATFORM, FieldKind.SINGLE, "Touch control scheme",
            "How should touch controls work?",
            "Touch needs deliberate design; a bad virtual stick ruins an action game.",
            118, relevant = { it.usesTouch },
            options = { listOf(
                o("floating_stick", "Floating virtual stick (+ buttons if needed)"),
                o("fixed_stick_buttons", "Fixed stick and buttons"),
                o("tap_drag", "Tap and drag directly on the world"),
                o("camera_pan_zoom", "Pan and pinch-zoom camera with tap to act"),
                o("ui_only", "Menus/cards only"),
            ) },
            suggest = { t ->
                when {
                    t.has(Tag.BUILDING) || t.has(Tag.TURN_BASED) -> Suggestion("camera_pan_zoom", "Builders and tactics need pan/zoom and precise taps.")
                    t.has(Tag.PUZZLE) -> Suggestion("tap_drag", "Direct manipulation is best for puzzles.")
                    primary(t).id == "card_deckbuilder" -> Suggestion("ui_only", "Cards are tap/drag UI.")
                    t.hasGenre("survivors_like") -> Suggestion("floating_stick", "One-thumb movement with auto-attack is the proven phone scheme.")
                    else -> Suggestion("floating_stick", "Most comfortable general scheme.")
                }
            }),

        Field(Keys.INPUT_REMAP, Category.PLATFORM, FieldKind.SINGLE, "Remappable controls",
            "Should keyboard/gamepad controls be remappable?",
            "Remapping is a major accessibility feature on PC.",
            120, relevant = { it.usesKeyboardOrPad },
            options = { listOf(o("full_remap", "Full remapping"), o("presets", "A few presets"), o("none", "Fixed")) },
            suggest = { Suggestion("full_remap", "Standard accessibility expectation.") }),

        Field(Keys.ART_DIRECTION, Category.ART, FieldKind.SINGLE, "Art style",
            "What should it look like?",
            "Art style determines the asset plan, which is often the biggest hidden cost.",
            125, relevant = { it.genresKnown },
            options = { t ->
                if (t.dimension == "3D") listOf(o("low_poly", "Low poly"), o("stylized_3d", "Stylized 3D"), o("voxel", "Voxel"), o("minimal_geometric", "Minimal geometric"))
                else listOf(o("pixel_art", "Pixel art"), o("hand_drawn", "Hand-drawn / painterly"), o("vector_flat", "Clean vector / flat"), o("minimal_geometric", "Minimal geometric"), o("voxel", "Voxel-look 2.5D"))
            },
            suggest = { t ->
                if (t.dimension == "3D") Suggestion("low_poly", "Cohesive and achievable with free or procedural assets.")
                else Suggestion("pixel_art", "Huge pool of CC0 pixel assets and the most consistent look achievable with procedural generation as fallback.")
            }),

        Field(Keys.COLOR_MOOD, Category.ART, FieldKind.TEXT, "Color and mood",
            "Any color palette or mood in mind? (Dark and moody, bright and candy, neon...)",
            "A defined palette keeps procedural and mixed assets visually coherent.",
            128, required = false, relevant = { it.genresKnown }),

        Field(Keys.VFX, Category.ART, FieldKind.SINGLE, "Effects and game feel",
            "How much visual punch - screen shake, particles, hit flashes?",
            "Juice makes a game feel alive. Reduced-motion settings keep it comfortable.",
            130, relevant = { it.genresKnown },
            options = { listOf(o("restrained", "Restrained"), o("moderate", "Moderate"), o("heavy", "Heavy and flashy")) },
            suggest = { t -> Suggestion(if (t.fastTwitch || t.has(Tag.COMBAT)) "moderate" else "restrained", "Clear feedback without visual overload.") }),

        Field(Keys.AUDIO, Category.ART, FieldKind.SINGLE, "Audio and music",
            "What audio does it need?",
            "Audio is part of feel. We'll plan CC0 sources or procedurally generated sound.",
            132, relevant = { it.genresKnown },
            options = { listOf(o("music_sfx", "Music and sound effects"), o("sfx_only", "Sound effects only"), o("ambient_sfx", "Ambient soundscape + effects"), o("silent", "Silent")) },
            suggest = { Suggestion("music_sfx", "Complete-feeling default; volume sliders and mute are included.") }),

        Field(Keys.HUD_UI, Category.ART, FieldKind.TEXT, "HUD and UI style",
            "What should the on-screen interface show and how should it look?",
            "UI is the surface the player touches the most.",
            135, relevant = { it.genresKnown },
            suggest = { t -> Suggestion("Clean readable HUD showing only essential state (${if (t.has(Tag.COMBAT)) "health, XP/progress, active abilities" else "key resources and goals"}), large touch-friendly targets, consistent iconography, and a pause menu reachable at all times.", "Readable and minimal.") }),

        Field(Keys.MENUS_SETTINGS, Category.TECHNICAL, FieldKind.MULTI, "Menus and settings",
            "Which menus and settings should exist?",
            "Settings screens are an easy place to forget required features.",
            138, relevant = { it.genresKnown },
            options = { listOf(
                o("main_menu", "Main menu"), o("pause_menu", "Pause menu"), o("audio_settings", "Volume controls"),
                o("graphics_settings", "Graphics quality"), o("controls_settings", "Controls"), o("accessibility_settings", "Accessibility"),
                o("language", "Language"), o("data_reset", "Reset/erase save data"), o("credits", "Credits and licenses")) },
            suggest = { Suggestion(Decision_join(listOf("main_menu", "pause_menu", "audio_settings", "graphics_settings", "controls_settings", "accessibility_settings", "data_reset", "credits")), "A complete baseline; credits also carry asset attributions.") }),

        Field(Keys.ACCESSIBILITY, Category.PLATFORM, FieldKind.MULTI, "Accessibility",
            "Which accessibility features should be included?",
            "Good accessibility widens your audience and is cheapest to build in from the start.",
            140, relevant = { it.genresKnown },
            options = { t -> buildList {
                add(o("text_scaling", "Text size options"))
                add(o("colorblind", "Colorblind-safe palette / modes"))
                add(o("reduced_motion", "Reduced motion / screen shake off"))
                add(o("subtitles", "Subtitles / captions"))
                if (t.isMobile) { add(o("haptics_control", "Vibration/haptics control")); add(o("large_touch_targets", "Large touch targets")) }
                if (t.has(Tag.COMBAT) || t.fastTwitch) add(o("difficulty_assists", "Difficulty assists"))
                if (t.usesKeyboardOrPad) add(o("remappable_controls", "Remappable controls"))
            } },
            suggest = { t ->
                val v = buildList {
                    add("text_scaling"); add("colorblind"); add("reduced_motion")
                    if (t.value(Keys.AUDIO) != "silent") add("subtitles")
                    if (t.isMobile) { add("haptics_control"); add("large_touch_targets") }
                    if (t.has(Tag.COMBAT) || t.fastTwitch) add("difficulty_assists")
                    if (t.usesKeyboardOrPad) add("remappable_controls")
                }
                Suggestion(Decision_join(v), "Recommended set for this genre and platform; irrelevant options are left out.")
            }),

        Field(Keys.PERFORMANCE, Category.TECHNICAL, FieldKind.SINGLE, "Performance target",
            "What frame rate should it aim for?",
            "The target sets the budget for effects, entity counts and battery use.",
            145, relevant = { it.platformsKnown },
            options = { listOf(o("30fps", "30 fps"), o("60fps", "60 fps"), o("60fps_adaptive", "60 fps with graceful degradation to 30"), o("120fps_capable", "120 fps on capable displays")) },
            suggest = { t -> if (t.dimension == "3D" && t.isMobile) Suggestion("60fps_adaptive", "3D on phones needs graceful degradation to stay smooth.") else Suggestion("60fps", "Smooth, responsive play that a 2D game can hold easily.") }),

        Field(Keys.MIN_HARDWARE, Category.TECHNICAL, FieldKind.TEXT, "Minimum hardware",
            "What is the oldest/weakest device it should run on?",
            "Defines how conservative performance must be.",
            148, required = false, relevant = { it.platformsKnown },
            suggest = { t ->
                val parts = buildList {
                    if (Platforms.ANDROID in t.platforms) add("Android 10 (API 29)+, 4 GB RAM, mid-range 2020-era SoC")
                    if (Platforms.IOS in t.platforms) add("iPhone with iOS 16+")
                    if (t.platforms.any { it in setOf(Platforms.WINDOWS, Platforms.MAC, Platforms.LINUX) }) add("Integrated-GPU laptop from the last 6 years")
                    if (Platforms.WEB in t.platforms) add("Current evergreen browsers with WebGL2")
                }
                Suggestion(parts.joinToString("; "), "Conservative baseline covering most active devices.")
            }),

        Field(Keys.ENGINE, Category.TECHNICAL, FieldKind.SINGLE, "Engine / framework",
            "Do you have an engine preference? If not, I'll recommend the best fit.",
            "The engine decides how easily Claude can build, test and package the game without manual editor work.",
            150, modes = Field.ALL_MODES, relevant = { it.platformsKnown && it.dimension != null },
            options = { t ->
                val ranked = EngineRecommender.rank(t.platforms, t.dimension, t.complexity, t.beginner, t.tags).map { Option(it.engine.id, it.engine.name, it.engine.strengths) }
                if (ranked.isEmpty()) EngineCatalog.all.map { Option(it.id, it.name, it.strengths) } else ranked
            },
            suggest = { t ->
                EngineRecommender.best(t.platforms, t.dimension, t.complexity, t.beginner, t.tags)?.let { Suggestion(it.engine.id, "${it.engine.name}: ${it.rationale}") }
            }),

        Field(Keys.TOOLCHAIN_PREFS, Category.TECHNICAL, FieldKind.TEXT, "Toolchain preferences",
            "Any IDE, language, library or pipeline constraints I should respect?",
            "Experts can pin choices; everyone else can skip.",
            152, required = false, expertOnly = true, modes = Field.ALL_MODES),

        Field(Keys.NETWORK_POLICY, Category.TECHNICAL, FieldKind.SINGLE, "Network use",
            "Should the game work fully offline?",
            "Offline games avoid servers, ongoing costs and privacy obligations. Online multiplayer design is not part of this version of Game Designer.",
            155, relevant = { it.genresKnown },
            options = { listOf(
                o("fully_offline", "Fully offline"),
                o("optional_online", "Offline play with optional online extras"),
                o("online_required", "Requires internet")) },
            suggest = { Suggestion("fully_offline", "No servers, no recurring cost, no privacy overhead.") }),

        Field(Keys.OTA_UPDATES, Category.TECHNICAL, FieldKind.SINGLE, "Over-the-air updates",
            "Do you want your game to be able to update itself over the air, without people reinstalling? If yes, I'll use the least intrusive way: quiet background checks, applied the next time the game starts.",
            "An update pipeline lets you fix balance, content and bugs after release. The least intrusive version only downloads data (levels, tuning, text, art), never code, so it needs no extra permissions, accounts or prompts and cannot break store rules.",
            156, relevant = { it.genresKnown && it.platformsKnown && it.platforms.any { p -> p != "web" } },
            options = { listOf(
                o("none", "No - updates come as a normal new install", "Simplest. Nothing extra to build or host."),
                o("content_ota", "Yes - over-the-air updates (least intrusive)", "Signed content and tuning updates, checked quietly, applied on next launch, automatic rollback.")) },
            suggest = { Suggestion("none", "Skip it unless you plan to update the game often after release; it can be added later.") }),

        Field(Keys.CI_BUILD, Category.TECHNICAL, FieldKind.SINGLE, "Build pipeline",
            "How should the installable build get produced?",
            "Claude's cloud workspace often can't install heavy SDKs, so a CI pipeline may produce the real build for it.",
            158, modes = Field.ALL_MODES, relevant = { it.platformsKnown },
            options = { listOf(
                o("github_actions", "GitHub Actions builds the artifact", "Works from anywhere; no GitHub token needed in the app."),
                o("claude_environment", "Claude builds in its own environment", "Only if the toolchain can be installed there."),
                o("local_machine", "I build on my own computer"),
            ) },
            suggest = { Suggestion("github_actions", "Reproducible, independent of any single machine, and produces a downloadable artifact you can install.") }),

        Field(Keys.TESTING, Category.TECHNICAL, FieldKind.TEXT, "Testing and validation",
            "What automated validation should run before you play it?",
            "The more Claude can verify itself, the fewer broken builds reach you.",
            160, modes = Field.ALL_MODES, relevant = { it.genresKnown },
            suggest = { t ->
                val smoke = t.smokeChecks().joinToString(" ")
                Suggestion("Deterministic unit tests for rules/data; automated build on every checkpoint; headless smoke playtest. $smoke Persistence round-trip and migration tests. Lint/static analysis where the toolchain supports it.", "Derived from the genre's required systems.")
            }),

        Field(Keys.ASSET_POLICY, Category.ASSETS, FieldKind.SINGLE, "Asset licensing policy",
            "Where may art and audio come from?",
            "Free to download is not the same as free to use. CC0/public domain is the safest.",
            165, modes = Field.ALL_MODES, relevant = { it.genresKnown },
            options = { listOf(
                o("cc0_default", "CC0 / public domain, else original/procedural", "Recommended: no attribution or license worries.", "cc0 only", "cc0", "public domain only", "public domain", "cc zero"),
                o("cc0_or_cc_by", "CC0 plus CC-BY with attribution", "Larger selection; credits screen required.", "cc by", "cc-by", "cc0 and cc by", "allow attribution", "attribution is fine"),
                o("original_only", "Only original/procedural assets", "Everything generated by code.", "original only", "only original", "original assets only", "procedural only", "only procedural", "generate everything"),
            ) },
            suggest = { Suggestion("cc0_default", "Safest default per your project rules; missing assets get original procedural replacements.") }),

        Field(Keys.BRAND_ICON, Category.ASSETS, FieldKind.SINGLE, "Game icon",
            "Do you have an app icon for this game?",
            "The icon represents your game on the home screen. I can create an original one.",
            170, relevant = { it.genresKnown },
            options = { brandingOptions },
            suggest = { Suggestion("generate_original", "Automatic original creation avoids leaving a placeholder.") }),

        Field(Keys.BRAND_STUDIO, Category.ASSETS, FieldKind.SINGLE, "Studio splash",
            "Do you have a studio/developer splash image to show at launch?",
            "Shown briefly before the game's own title screen.",
            172, relevant = { it.genresKnown },
            options = { brandingOptions },
            suggest = { Suggestion("generate_original", "A simple original studio card is better than none.") }),

        Field(Keys.BRAND_GAME_SPLASH, Category.ASSETS, FieldKind.SINGLE, "Game splash / title image",
            "Do you have a splash or title-screen image for the game?",
            "The first full-screen impression of the game.",
            174, relevant = { it.genresKnown },
            options = { brandingOptions },
            suggest = { Suggestion("generate_original", "Original title art generated to match the art direction.") }),

        Field(Keys.SCOPE_CHOICE, Category.CONTENT, FieldKind.SINGLE, "Scope",
            "I'll recommend a scope for the first playable build. Do you want to go with it, bigger, or smaller?",
            "I won't shrink your game to a bare prototype: the default is the biggest coherent first build that Claude can finish at good quality.",
            180, modes = Field.ALL_MODES, relevant = { it.genresKnown && it.platformsKnown },
            options = { listOf(o("recommended", "Go with your recommendation"), o("bigger", "Go bigger"), o("smaller", "Go smaller")) },
            suggest = { Suggestion("recommended", "Recommended scope is derived from complexity, platforms and your Claude plan.") }),

        Field(Keys.DISPLAY_NAME, Category.RELEASE, FieldKind.TEXT, "Game name",
            "What is the game called? A working title is fine.",
            "Shown under the icon and in the store listing; easy to change later.",
            185, relevant = { it.genresKnown },
            suggest = { t -> Suggestion(TitleSuggester.suggest(t), "Working title drawn from your concept; rename any time.") },
            validate = { _, v -> if (v.trim().length !in 1..30) "Use 1-30 characters." else null }),

        Field(Keys.PACKAGE_ID, Category.RELEASE, FieldKind.TEXT, "Package / app ID",
            "What package ID should the app use? I can propose one.",
            "A unique reverse-domain ID (like com.yourstudio.gamename) identifies the app; it can't change after release.",
            187, relevant = { it.genresKnown },
            suggest = { t -> Suggestion(TitleSuggester.packageId(t), "Valid default under your studio namespace.") },
            validate = { _, v -> if (!PACKAGE_ID_REGEX.matches(v.trim())) "Use lowercase letters, digits and underscores in dot-separated parts, e.g. com.hotatticgames.mygame." else null }),

        Field(Keys.VERSION_STRATEGY, Category.RELEASE, FieldKind.SINGLE, "Versioning",
            "How should version numbers work?",
            "Android needs an ever-increasing version code for every release.",
            190, relevant = { it.genresKnown },
            options = { listOf(
                o("semver_incrementing_code", "Semantic version + incrementing build code", "1.0.0 with versionCode +1 per build."),
                o("date_based", "Date-based versions")) },
            suggest = { Suggestion("semver_incrementing_code", "Standard, tool-friendly.") }),

        Field(Keys.STORE_PLAN, Category.RELEASE, FieldKind.SINGLE, "Distribution",
            "How will people get the game?",
            "Store publishing adds listing assets, signing and policy requirements; personal use does not.",
            192, relevant = { it.genresKnown },
            options = { listOf(
                o("personal_sideload", "Just me / sideload"),
                o("play_internal_testing", "Google Play internal/closed testing"),
                o("play_store", "Google Play public release"),
                o("app_store", "Apple App Store"),
                o("steam_or_pc_store", "Steam or other PC store"),
                o("itch_or_web", "itch.io or the web")) },
            suggest = { Suggestion("personal_sideload", "Matches personal use for now; release prep can be added later without redesign.") }),

        Field(Keys.MONETIZATION, Category.RELEASE, FieldKind.SINGLE, "Monetization",
            "How will it make money, if at all?",
            "Ads and purchases add services, privacy duties and design constraints.",
            195, relevant = { it.releaseOriented },
            options = { listOf(o("free_no_ads", "Free, no ads"), o("paid_upfront", "Paid upfront"), o("ads", "Free with ads"), o("iap", "In-app purchases"), o("undecided", "Undecided")) },
            suggest = { Suggestion("free_no_ads", "No ongoing costs or extra obligations.") }),

        Field(Keys.PRIVACY, Category.RELEASE, FieldKind.SINGLE, "Privacy",
            "What data, if any, does the game collect?",
            "Stores require a privacy position; the simplest is to collect nothing.",
            197, relevant = { it.releaseOriented || it.value(Keys.MONETIZATION) in setOf("ads", "iap") || (it.network != null && it.network != "fully_offline") },
            options = { listOf(o("no_data", "No data collected, all local"), o("anonymous_analytics", "Anonymous analytics"), o("accounts_online", "Accounts / online data")) },
            suggest = { Suggestion("no_data", "Minimum permissions, easiest store compliance.") }),

        Field(Keys.SIGNING, Category.RELEASE, FieldKind.SINGLE, "Signing",
            "How should release builds be signed?",
            "Keys are secrets and never go into the repository.",
            199, relevant = { it.storePlan in setOf("play_internal_testing", "play_store", "app_store") },
            options = { listOf(o("owner_keystore_ci_secrets", "My own keystore stored as CI secrets"), o("play_app_signing", "Google Play App Signing + upload key"), o("debug_only", "Debug-signed test builds only")) },
            suggest = { Suggestion("play_app_signing", "Google manages the app signing key; you hold only an upload key kept out of the repo.") }),

        Field(Keys.FIVE_MINUTES, Category.GAMEPLAY, FieldKind.TEXT, "A great five minutes",
            "Describe one great five minutes of playing this game. What are you doing, seeing and feeling?",
            "One vivid stretch of play tells me the loop, pace, feel and look at once, so I can skip a pile of smaller questions.",
            48, required = false, relevant = { it.genresKnown && DimensionCoverage.uncoveredCore(it.project) >= 3 }),

        Field(Keys.FIRST_SLICE, Category.CONTENT, FieldKind.TEXT, "First playable build",
            "Let's define the first playable build. What should it contain so you can judge whether the game is fun? (A smaller, polished slice usually beats a bigger unfinished one.)",
            "This is the line between a prototype that proves the idea and a pile of half-built content.",
            106, relevant = { it.genresKnown },
            suggest = { t -> SliceSuggester.suggest(t) }),

        Field(Keys.MUST_NOT_CHANGE, Category.GAMEPLAY, FieldKind.TEXT, "Must not change",
            "What are the things Claude absolutely must not reinterpret about this game? Say \"none\" or \"choose for me\" if you like.",
            "A few hard invariants stop a builder from quietly turning your game into a different, more conventional one.",
            203, relevant = { it.genresKnown },
            suggest = { t -> SliceSuggester.invariants(t) }),

        Field(Keys.DONE, Category.RELEASE, FieldKind.TEXT, "Definition of done",
            "What must be true for you to call the first build done?",
            "Gives Claude a concrete finish line instead of guessing.",
            205, modes = Field.ALL_MODES, relevant = { it.genresKnown },
            suggest = { t ->
                Suggestion("The complete core loop is playable start to finish with real visuals and audio, all required systems from the spec implemented and wired together, menus/settings/saves working, all automated validation green, and an installable build artifact for ${t.platforms.joinToString { Platforms.labels[it] ?: it }.ifEmpty { "the target platform" }} produced.", "Matches the playable-first-build standard.")
            }),

        Field(Keys.EXCLUSIONS, Category.RELEASE, FieldKind.TEXT, "Explicitly out of scope",
            "Is there anything you explicitly do NOT want or want to defer?",
            "Things deferred on purpose aren't treated as holes.",
            208, required = false, modes = Field.ALL_MODES, relevant = { it.genresKnown },
            suggest = { Suggestion("Online multiplayer, accounts and cloud services; monetization; store listing assets beyond what is already specified.", "Keeps V1 local, offline and free of recurring costs.") }),
    )

    val byKey: Map<String, Field> = all.associateBy { it.key }
    fun get(key: String): Field? = byKey[key]

    val brandingOptions: List<Option> = listOf(
        Option("upload", "Upload my own", "Pick an image from your phone."),
        Option("generate_original", "Create an original one for me", "Recommended."),
        Option("generic_temporary", "Use a clean generic placeholder", "Clearly replaceable."),
        Option("skip", "Skip it", "Only when genuinely unnecessary."),
    )

    val PACKAGE_ID_REGEX = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")

    @Suppress("FunctionName")
    private fun Decision_join(values: Collection<String>) = com.hotattic.gamedesigner.core.model.Decision.joinList(values)
}

/** Deterministic working-title and package-id suggestions. */
object TitleSuggester {
    private val stop = setOf(
        "a", "an", "the", "and", "or", "of", "to", "in", "on", "with", "for", "but", "i", "want", "make", "game", "like", "mixed", "mix",
        "that", "this", "my", "me", "is", "am", "be", "it", "its", "as", "at", "by", "from", "have", "has", "where", "who", "what", "which",
        "im", "i'm", "kind", "something", "little", "bit", "very", "really", "some", "similar", "style", "type", "build", "create", "design",
        "play", "player", "players", "you", "your", "they", "their", "can", "will", "would", "should", "could", "about", "into", "over",
        "defending", "defend", "crossed", "vampire", "survivors", "risk", "rain", "2",
    )

    fun suggest(t: Traits): String {
        val concept = t.value(Keys.CONCEPT) ?: return fallback(t)
        var text = ConceptText.fantasy(concept)
        t.project.references.forEach { r -> text = text.replace(r.name, " ", ignoreCase = true) }
        val words = text.split(Regex("[^A-Za-z']+")).map { it.trim('\'') }.filter { it.length > 2 && it.lowercase() !in stop }
        val picked = words.takeLast(2)
        if (picked.isEmpty()) return fallback(t)
        return picked.joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }.take(30)
    }

    private fun fallback(t: Traits) = ((t.genres.firstOrNull()?.label ?: "New Game").substringBefore(" (").substringBefore(" /") + " Project").take(30)

    fun slug(name: String): String {
        val s = name.lowercase().replace(Regex("[^a-z0-9]+"), "")
        return if (s.isEmpty() || s[0].isDigit()) "game$s" else s
    }

    fun packageId(t: Traits, studioNamespace: String = "com.hotatticgames"): String {
        val name = t.value(Keys.DISPLAY_NAME) ?: suggest(t)
        return "$studioNamespace.${slug(name)}"
    }
}


/**
 * Which keys make a derived (non-owner) decision stale. Roots (what the owner says about the game itself) are never derived.
 * "tag" is a pseudo-key that changes whenever the owner rejects/restores a gameplay tag such as TURN_BASED.
 */
object Dependencies {
    const val TAG = "tag"
    val roots = setOf(Keys.CONCEPT, Keys.GENRE, Keys.DIMENSION, Keys.PLATFORMS, Keys.REFERENCES, Keys.CONTINUATION_GOAL, Keys.PRESERVE_SYSTEMS, Keys.KNOWN_ISSUES)
    private val broad = setOf(Keys.GENRE, Keys.DIMENSION, Keys.PLATFORMS, TAG)
    private val specific: Map<String, Set<String>> = mapOf(
        Keys.PERSPECTIVE to setOf(Keys.DIMENSION, Keys.GENRE, TAG),
        Keys.DISPLAY_NAME to setOf(Keys.CONCEPT),
        Keys.PACKAGE_ID to setOf(Keys.DISPLAY_NAME),
        Keys.REFERENCE_ASPECTS to setOf(Keys.REFERENCES),
        Keys.CORE_FANTASY to setOf(Keys.CONCEPT),
        Keys.TOUCH_SCHEME to setOf(Keys.INPUT_METHODS, Keys.GENRE, TAG),
        Keys.INPUT_REMAP to setOf(Keys.INPUT_METHODS),
        Keys.ACCESSIBILITY to setOf(Keys.INPUT_METHODS, Keys.PLATFORMS, Keys.GENRE, Keys.AUDIO, TAG),
        Keys.MONETIZATION to setOf(Keys.STORE_PLAN),
        Keys.PRIVACY to setOf(Keys.STORE_PLAN, Keys.MONETIZATION, Keys.NETWORK_POLICY),
        Keys.SIGNING to setOf(Keys.STORE_PLAN),
        Keys.ORIENTATION to setOf(Keys.GENRE, Keys.PLATFORMS, TAG),
        Keys.MENUS_SETTINGS to emptySet(),
        Keys.SCOPE_CHOICE to emptySet(),
    )

    fun sourcesOf(field: Field): Set<String> = when {
        field.key in roots -> emptySet()
        field.derivedFrom != null -> field.derivedFrom
        else -> specific[field.key] ?: broad
    }
}
