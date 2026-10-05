package com.hotattic.gamedesigner.core.schema

/** Cross-cutting capabilities a genre implies; drive which interview fields are relevant. */
enum class Tag { COMBAT, ECONOMY, CRAFTING, STORY, PROCGEN, LEVELS, MOVEMENT, SIMULATION, TURN_BASED, PUZZLE, CHARACTERS, FAST_TWITCH, BUILDING }

/** A system a complete game of this genre needs. Used for the missing-systems audit and generated requirements. */
data class SystemReq(val id: String, val name: String, val detail: String)

data class Genre(
    val id: String,
    val label: String,
    val keywords: List<String>,
    /** 0..5 rough implementation weight, used by ScopeEngine. */
    val complexity: Int,
    val tags: Set<Tag>,
    val systems: List<SystemReq>,
    /** Default core-loop sentence skeleton used when the owner delegates the loop. */
    val loopTemplate: String,
    /** Validation hooks that make sense for an automated headless smoke playtest. */
    val smokeChecks: List<String>,
)

object GenreKnowledge {

    val all: List<Genre> = listOf(
        Genre(
            "survivors_like", "Survivors-like (horde survival)",
            listOf("vampire survivors", "survivors", "horde", "bullet heaven", "auto-attack", "auto attack", "brotato"),
            2, setOf(Tag.COMBAT, Tag.PROCGEN, Tag.CHARACTERS, Tag.MOVEMENT),
            listOf(
                SystemReq("spawn_director", "Enemy spawn director", "Time-scaled wave/budget spawner with density caps and elite/boss schedule."),
                SystemReq("auto_weapons", "Auto-firing weapons", "Multiple weapon archetypes that fire without aiming, with cooldown/area/projectile stats."),
                SystemReq("xp_levelup", "XP pickups and level-up choices", "Gem drops, magnet radius, level-up card selection UI with reroll/skip rules."),
                SystemReq("weapon_evolution", "Weapon/passive synergies", "Defined combinations that evolve or amplify weapons; fully tabulated."),
                SystemReq("run_timer_win", "Run timer and win/loss", "Run length, final boss/extraction rule, death/victory summary screen."),
                SystemReq("meta_progression", "Meta progression", "Persistent currency, unlock tree and character unlocks across runs."),
                SystemReq("perf_horde", "Horde performance budget", "Pooling, spatial hashing and an explicit on-screen entity cap."),
            ),
            "Move to survive escalating waves, collect XP, choose upgrades that combine into a build, defeat the run's boss, then spend meta rewards to start stronger.",
            listOf("Run a 60s simulated run with scripted input and assert no crash, enemy count stays under cap, XP rises, level-up UI appears."),
        ),
        Genre(
            "action_roguelite", "Action roguelite / roguelike",
            listOf("roguelite", "roguelike", "rogue-like", "risk of rain", "hades", "dead cells", "run-based", "permadeath"),
            3, setOf(Tag.COMBAT, Tag.PROCGEN, Tag.CHARACTERS, Tag.MOVEMENT, Tag.LEVELS),
            listOf(
                SystemReq("run_structure", "Run structure", "Stage sequence, difficulty scaling, boss encounters, run end and restart."),
                SystemReq("item_system", "Items and build synergies", "Data-driven pickups/relics with stacking rules and rarity."),
                SystemReq("procgen_levels", "Procedural stage generation", "Seeded generation with validity/reachability checks."),
                SystemReq("enemy_roster", "Enemy roster and AI", "Distinct enemy behaviors, elites, bosses with telegraphed attacks."),
                SystemReq("meta_progression", "Meta progression", "Persistent unlocks, characters/loadouts, currencies."),
                SystemReq("death_restart", "Death and restart flow", "Fast, clear death summary and one-tap restart; save-on-exit rules."),
            ),
            "Enter a procedurally assembled stage, fight and loot to build a synergistic loadout, beat the stage boss, escalate, and on death bank rewards that unlock new options for the next run.",
            listOf("Seeded run generation validity test across 100 seeds; scripted fight finishes a stage without softlock."),
        ),
        Genre(
            "platformer", "Platformer / vertical scroller",
            listOf("platformer", "platform game", "action platformer", "action/platform", "mario", "celeste", "jump", "side-scroller", "side scroller", "vertical scroller", "vertical-scrolling", "vertical scrolling", "scroller"),
            2, setOf(Tag.MOVEMENT, Tag.LEVELS, Tag.FAST_TWITCH, Tag.COMBAT),
            listOf(
                SystemReq("movement_model", "Movement model", "Run/jump/coyote time/jump buffer constants documented and tunable."),
                SystemReq("level_set", "Hand-built level set", "Ordered levels with checkpoints, hazards, collectibles and a final goal."),
                SystemReq("checkpoint_respawn", "Checkpoints and respawn", "Instant respawn, state restoration rules."),
                SystemReq("hazards_enemies", "Hazards and enemies", "Catalogue with behavior and damage rules."),
                SystemReq("level_select_progress", "Level select and progress save", "Unlock order, best times/collectibles persisted."),
            ),
            "Traverse hand-designed levels using tight movement, avoid hazards and enemies, collect objectives, and reach the goal to unlock the next level.",
            listOf("Scripted playthrough of level 1 reaches the goal; every level has reachable start-to-goal path (automated path check)."),
        ),
        Genre(
            "metroidvania", "Metroidvania",
            listOf("metroidvania", "hollow knight", "metroid", "castlevania"),
            4, setOf(Tag.MOVEMENT, Tag.LEVELS, Tag.COMBAT, Tag.STORY, Tag.CHARACTERS),
            listOf(
                SystemReq("interconnected_map", "Interconnected map with gating", "Region graph, ability/key gates, backtracking shortcuts, map screen."),
                SystemReq("ability_unlocks", "Ability progression", "Movement/combat abilities that gate the map; sequence-break rules."),
                SystemReq("combat_system", "Combat and bosses", "Melee/ranged moveset, enemy roster, boss patterns."),
                SystemReq("save_rooms", "Save points", "Save room or autosave with respawn rules."),
            ),
            "Explore an interconnected world, gain abilities that open previously blocked paths, defeat bosses and uncover the story.",
            listOf("Graph reachability test: every required ability gate is reachable before it is required."),
        ),
        Genre(
            "shooter", "Shooter",
            listOf("shooter", "fps", "third-person shooter", "twin-stick", "twin stick", "bullet hell", "shmup", "shoot em up"),
            3, setOf(Tag.COMBAT, Tag.MOVEMENT, Tag.FAST_TWITCH, Tag.LEVELS),
            listOf(
                SystemReq("weapon_set", "Weapon set", "Weapons with fire modes, recoil/spread, ammo and reload rules."),
                SystemReq("enemy_ai", "Enemy AI", "Behaviors, aggro, cover/pathing as relevant."),
                SystemReq("damage_health", "Damage and health model", "Hit detection, armor/health, regen rules, feedback."),
                SystemReq("level_flow", "Level/encounter flow", "Encounters, checkpoints, win/loss."),
                SystemReq("aim_controls", "Aim and fire controls", "Aim assist rules and touch/gamepad/keyboard bindings."),
            ),
            "Aim and fire weapons at enemy groups across a series of encounters, manage health and ammo, and clear each level or survive each wave.",
            listOf("Scripted bot clears encounter 1; hit detection unit tests; ammo/reload state machine tests."),
        ),
        Genre(
            "city_builder", "City builder",
            listOf("city builder", "city-builder", "cities skylines", "simcity", "town builder", "colony", "settlement", "banished"),
            4, setOf(Tag.ECONOMY, Tag.BUILDING, Tag.SIMULATION, Tag.LEVELS),
            listOf(
                SystemReq("grid_placement", "Placement and zoning", "Grid/roads/zones with validity rules and demolish/undo."),
                SystemReq("resource_economy", "Resource and budget economy", "Production, consumption, upkeep, taxes; fully tabulated and balanced."),
                SystemReq("population_sim", "Population simulation", "Needs, happiness, growth/decline, immigration."),
                SystemReq("goals_events", "Goals and events", "Objectives, disasters or events, win/loss states."),
                SystemReq("sim_speed_save", "Simulation speed and save/load", "Pause/1x/2x/3x, deterministic tick, save versioning."),
                SystemReq("overlay_ui", "Info overlays and management UI", "Data views, tooltips, build menu categories."),
            ),
            "Place buildings and infrastructure, balance resources and citizen needs, grow the settlement through milestones, and respond to events.",
            listOf("Headless sim runs 10 simulated years; assert determinism for a seed, no negative resources without cause, save/load round trip."),
        ),
        Genre(
            "factory_automation", "Factory / automation builder",
            listOf("factory", "factorio", "automation", "satisfactory", "conveyor", "production chain", "shapez", "mindustry"),
            5, setOf(Tag.ECONOMY, Tag.BUILDING, Tag.SIMULATION, Tag.CRAFTING, Tag.LEVELS),
            listOf(
                SystemReq("recipe_graph", "Recipe/production graph", "Complete data-driven item and recipe tables with a validity check (no dead ends, no cycles without outputs)."),
                SystemReq("logistics", "Logistics", "Belts/pipes/vehicles with throughput rules and blocking behavior."),
                SystemReq("machines_power", "Machines and power/other constraints", "Machine tiers, power or equivalent constraints."),
                SystemReq("tech_tree", "Tech tree", "Research unlocking recipes/buildings; goal item/endgame condition."),
                SystemReq("blueprint_save", "Blueprints, undo and save", "Copy/paste layouts, undo/redo, versioned saves, performance at scale."),
                SystemReq("sim_perf", "Simulation performance budget", "Entity/tick budget with chunking or batching strategy."),
            ),
            "Extract resources, design production lines that turn them into increasingly complex items, research new technology, scale throughput, and reach the target endgame item.",
            listOf("Recipe graph validator; headless factory runs N ticks, output rate matches analytic expectation; save/load round trip."),
        ),
        Genre(
            "survival_crafting", "Survival / crafting",
            listOf("survival", "crafting", "minecraft", "valheim", "terraria", "don't starve", "base building", "open world survival"),
            4, setOf(Tag.CRAFTING, Tag.COMBAT, Tag.PROCGEN, Tag.BUILDING, Tag.MOVEMENT),
            listOf(
                SystemReq("survival_stats", "Survival needs", "Hunger/thirst/health/temperature (as designed) with tuned rates and failure consequences."),
                SystemReq("gathering_respawn", "Resource gathering and respawn", "Node types, tools, yields, respawn timers/regeneration."),
                SystemReq("crafting_ui", "Crafting system and UX", "Recipe tables, stations, queueing, discoverability."),
                SystemReq("inventory", "Inventory", "Slots/weight, stacks, hotbar, drop/pickup, storage containers."),
                SystemReq("world_gen", "World generation and persistence", "Seeded world, chunk persistence, save format."),
                SystemReq("threats", "Threats and combat", "Hostile creatures/hazards, day-night or difficulty pacing."),
                SystemReq("goal_progression", "Goal and progression", "Tech/tool tiers and a defined end goal or sandbox rule."),
            ),
            "Gather resources, craft tools and shelter, survive escalating threats and needs, and push toward a defined long-term goal.",
            listOf("Seeded world generation validity; scripted agent gathers, crafts a tool tier, survives N in-game days; save/load round trip."),
        ),
        Genre(
            "strategy_rts", "Real-time strategy",
            listOf("rts", "real-time strategy", "real time strategy", "starcraft", "age of empires", "command and conquer"),
            5, setOf(Tag.COMBAT, Tag.ECONOMY, Tag.BUILDING, Tag.LEVELS, Tag.SIMULATION),
            listOf(
                SystemReq("unit_roster", "Unit and building roster", "Complete stat tables with counters/balance matrix."),
                SystemReq("economy_loop", "Economy loop", "Gathering, spending, supply limits."),
                SystemReq("command_ui", "Selection and command UI", "Select/box/group, orders, minimap; touch alternative if mobile."),
                SystemReq("pathfinding", "Pathfinding and collision", "Flow-field or navmesh with large-group performance target."),
                SystemReq("opponent_ai", "Opponent AI", "Skirmish AI with difficulty levels."),
                SystemReq("match_flow", "Match flow", "Win/loss conditions, skirmish setup, campaign or mission list."),
            ),
            "Gather resources, build a base and army, command units against an AI opponent, and win through objectives.",
            listOf("AI-vs-AI headless match completes with a winner; pathfinding stress test within frame budget."),
        ),
        Genre(
            "turn_based_strategy", "Turn-based tactics / strategy",
            listOf("turn-based", "turn based", "tactics", "xcom", "fire emblem", "civilization", "4x", "grid combat"),
            4, setOf(Tag.COMBAT, Tag.TURN_BASED, Tag.LEVELS, Tag.CHARACTERS),
            listOf(
                SystemReq("turn_engine", "Turn engine", "Turn order, action points/phases, undo rules."),
                SystemReq("unit_stats", "Units and abilities", "Complete data tables and ability resolution rules."),
                SystemReq("map_scenarios", "Maps and scenarios", "Map set, objectives, win/loss."),
                SystemReq("opponent_ai", "Opponent AI", "Decision logic with difficulty levels."),
                SystemReq("battle_ui", "Battle UI and feedback", "Range/threat overlays, previews of hit chance/outcome."),
            ),
            "Plan each turn, move and use abilities with squad units on a grid or map, and defeat the opposing force or complete objectives.",
            listOf("AI-vs-AI headless scenario completes; rules unit tests for ability resolution."),
        ),
        Genre(
            "tower_defense", "Tower defense",
            listOf("tower defense", "tower-defense", "td game", "bloons"),
            2, setOf(Tag.COMBAT, Tag.ECONOMY, Tag.LEVELS),
            listOf(
                SystemReq("tower_set", "Tower roster and upgrades", "Complete stats, targeting modes, upgrade paths."),
                SystemReq("enemy_waves", "Enemy types and wave design", "Wave tables per level with scaling."),
                SystemReq("economy", "In-level economy", "Currency, sell/refund rules, interest if any."),
                SystemReq("maps_progress", "Maps and progression", "Map set, unlocks, stars/scores."),
            ),
            "Place and upgrade defenses along enemy routes, earn currency from kills, and survive every wave.",
            listOf("Headless wave simulation per map completes; a baseline tower setup beats wave 1-3 and fails late waves (balance sanity)."),
        ),
        Genre(
            "rpg", "RPG",
            listOf("rpg", "jrpg", "role-playing", "role playing", "action rpg", "arpg", "diablo", "skyrim", "dungeon crawler"),
            4, setOf(Tag.COMBAT, Tag.STORY, Tag.CHARACTERS, Tag.LEVELS, Tag.CRAFTING),
            listOf(
                SystemReq("character_progression", "Character stats and progression", "Stats, levels, skill/talent structure, formulas."),
                SystemReq("combat_system", "Combat system", "Real-time or turn-based rules, abilities, damage formulas."),
                SystemReq("inventory_equipment", "Inventory and equipment", "Item types, rarity, equip slots, vendors."),
                SystemReq("quests_dialogue", "Quests and dialogue", "Quest state machine, dialogue UI, journal."),
                SystemReq("world_content", "World and content", "Zones, NPCs, enemies, bosses, a defined ending."),
                SystemReq("save_load", "Save/load", "Multiple slots or autosave with quest/world state versioning."),
            ),
            "Explore the world, take on quests, fight enemies, grow the character's power and gear, and see the story through to its ending.",
            listOf("Quest state machine tests; scripted agent completes first quest chain; save/load round trip mid-quest."),
        ),
        Genre(
            "puzzle", "Puzzle",
            listOf("puzzle", "match-3", "match 3", "sokoban", "tetris", "logic game", "word game", "brain"),
            1, setOf(Tag.PUZZLE, Tag.LEVELS),
            listOf(
                SystemReq("rules_engine", "Rules engine", "Deterministic, unit-tested rules for moves and win detection."),
                SystemReq("level_set", "Level set and difficulty curve", "Authored or generated levels with solvability validation and a curve."),
                SystemReq("hints_undo", "Undo, restart and hints", "Undo/redo, restart, optional hint system."),
                SystemReq("progress_save", "Progress and stats", "Level unlocks, stars/best scores persisted."),
            ),
            "Read the board, find the solution using the core rule, complete increasingly difficult levels, and earn ratings.",
            listOf("Solver proves every shipped level is solvable; rules unit tests."),
        ),
        Genre(
            "racing", "Racing",
            listOf("racing", "racer", "kart", "mario kart", "driving"),
            4, setOf(Tag.MOVEMENT, Tag.LEVELS, Tag.FAST_TWITCH),
            listOf(
                SystemReq("vehicle_model", "Vehicle handling model", "Tuned physics/arcade model with documented parameters."),
                SystemReq("tracks", "Track set", "Tracks with checkpoints, lap logic, shortcuts/hazards."),
                SystemReq("opponent_ai", "Opponent AI", "Racing line AI with difficulty levels, rubber-banding rules."),
                SystemReq("race_flow", "Race flow and progression", "Start/finish, results, cups/unlocks."),
            ),
            "Race tuned vehicles around tracks against AI opponents, master handling, and unlock new cars and cups.",
            listOf("AI race completes with valid lap times; checkpoint ordering tests."),
        ),
        Genre(
            "card_deckbuilder", "Card / deckbuilder",
            listOf("deckbuilder", "deck builder", "card game", "slay the spire", "card battler", "tcg"),
            3, setOf(Tag.COMBAT, Tag.TURN_BASED, Tag.PROCGEN, Tag.CHARACTERS),
            listOf(
                SystemReq("card_data", "Card database and rules", "Complete card list with effect rules and keyword glossary."),
                SystemReq("battle_engine", "Battle engine", "Turn structure, energy/mana, status effects, enemy intents."),
                SystemReq("run_map", "Run map and rewards", "Map nodes, reward selection, shops, events."),
                SystemReq("meta_progression", "Meta progression", "Unlocks across runs."),
            ),
            "Build a deck across a branching run, play cards in turn-based battles against telegraphed enemies, and beat the final boss.",
            listOf("Headless auto-play of full runs with random policy never softlocks; card effect unit tests."),
        ),
        Genre(
            "narrative_adventure", "Narrative / adventure",
            listOf("visual novel", "adventure game", "point and click", "point-and-click", "walking simulator", "interactive fiction", "story game"),
            2, setOf(Tag.STORY, Tag.CHARACTERS, Tag.PUZZLE),
            listOf(
                SystemReq("story_graph", "Story graph and branching", "Complete scene graph with state variables and endings."),
                SystemReq("dialogue_ui", "Dialogue and choice UI", "Text display, history/backlog, skip/auto, choices."),
                SystemReq("save_load", "Save/load and chapter select", "Slots, autosave, replay of seen content."),
                SystemReq("endings", "Defined endings", "All endings reachable and verified."),
            ),
            "Read and interact through a branching story, make choices that matter, and reach one of the defined endings.",
            listOf("Story graph reachability: every ending reachable, no dead ends; save/load mid-scene round trip."),
        ),
        Genre(
            "sim_management", "Simulation / management / idle",
            listOf("idle", "incremental", "clicker", "tycoon", "management sim", "farming sim", "simulator"),
            3, setOf(Tag.ECONOMY, Tag.SIMULATION, Tag.BUILDING),
            listOf(
                SystemReq("economy_model", "Economy model", "Currencies, generators, costs formulas; balance spreadsheet."),
                SystemReq("progression_unlocks", "Progression and prestige/unlocks", "Milestones and long-term goals."),
                SystemReq("offline_time", "Time handling", "Offline progress/tick rules if applicable; pause semantics."),
                SystemReq("save_load", "Save/load", "Versioned saves with migration."),
            ),
            "Invest resources into growing systems, unlock new capabilities, and reach long-term milestones.",
            listOf("Headless economy simulation over N hours matches analytic bounds; save/load round trip."),
        ),
        Genre(
            "fighting", "Fighting / brawler",
            listOf("fighting game", "street fighter", "smash", "brawler", "beat em up", "beat 'em up"),
            4, setOf(Tag.COMBAT, Tag.FAST_TWITCH, Tag.CHARACTERS, Tag.MOVEMENT),
            listOf(
                SystemReq("move_framedata", "Move set and frame data", "Per-character moves with startup/active/recovery data."),
                SystemReq("hit_hurtboxes", "Hit/hurt boxes", "Deterministic collision and hitstun rules."),
                SystemReq("opponent_ai", "Opponent AI", "CPU opponents with difficulty."),
                SystemReq("match_flow", "Match flow", "Rounds, health, win/loss, character select, modes."),
            ),
            "Fight opponents with a roster of distinct characters, mastering spacing, timing and combos to win rounds.",
            listOf("AI-vs-AI match completes; frame-data unit tests."),
        ),
    )

    val byId: Map<String, Genre> = all.associateBy { it.id }
    val other = Genre("other", "Other / custom", emptyList(), 3, setOf(Tag.MOVEMENT), emptyList(),
        "Perform the core action, receive feedback, progress toward goals, and reach a defined end state.",
        listOf("Scripted playthrough of the core loop completes without crash."))

    fun resolve(id: String): Genre = byId[id] ?: other

    /** Finds every known genre whose keywords appear in [text]. */
    fun detect(text: String): List<Genre> {
        val t = text.lowercase()
        return all.filter { g -> g.keywords.any { k -> containsWord(t, k) } }
    }

    internal fun containsWord(haystack: String, needle: String): Boolean {
        var from = 0
        while (true) {
            val i = haystack.indexOf(needle, from)
            if (i < 0) return false
            val before = if (i == 0) ' ' else haystack[i - 1]
            val after = if (i + needle.length >= haystack.length) ' ' else haystack[i + needle.length]
            if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
            from = i + 1
        }
    }
}
