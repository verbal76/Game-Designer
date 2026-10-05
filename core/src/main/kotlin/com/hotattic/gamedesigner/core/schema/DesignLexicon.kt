package com.hotattic.gamedesigner.core.schema

import com.hotattic.gamedesigner.core.engine.MetaConversation
import com.hotattic.gamedesigner.core.model.Project

/** The build-critical design dimensions Bob reasons about. Completeness is measured over these, not over a question list. */
enum class DimId(val title: String, val weight: Int) {
    VISION("Core vision and player fantasy", 3),
    FEELING("Desired player feeling", 2),
    LOOP("Moment-to-moment core loop", 4),
    MOVEMENT("Movement and traversal feel", 2),
    INTERACTION("Combat / primary interaction", 3),
    WORLD("World and level topology", 4),
    FAILURE("Challenge, failure and recovery", 3),
    PROGRESSION("Progression", 2),
    SESSION("Session, beginning to end", 2),
    SLICE("First playable build scope", 4),
    VISUAL("Visual identity", 3),
    AUDIO("Audio and presentation", 1),
    CONTROLS("Controls and target platform", 2),
    ASSETS("Asset policy and supplied assets", 2),
    INVARIANTS("Must-not-change constraints", 2),
    COMPLETION("Completion and win condition", 3),
    DISTRIBUTION("Distribution and delivery", 1),
}

/**
 * Cheap, conservative sentence classification: which design dimensions does an owner sentence speak to? It only seeds and gates
 * (skip questions already answered; offer the "great five minutes" question when little is known); the owner's final design
 * review is the real check, so precision matters more than recall.
 */
object DimensionLexicon {
    private val rules: Map<DimId, List<Regex>> = mapOf(
        DimId.LOOP to listOf(
            Regex("(?i)\\b(core|gameplay|main) loop\\b"),
            Regex("(?i)\\b(explore|fight|collect|upgrade|build|craft|dodge|shoot|climb|descend|ascend|dig|survive|defend|race|solve|scavenge|hunt|gather|trade|attack|sneak|swing|jump|run)\\b.{0,60}\\b(then|and then|and|,|to)\\b.{0,40}\\b(explore|fight|collect|upgrade|build|craft|dodge|shoot|climb|descend|ascend|dig|survive|defend|race|solve|scavenge|hunt|gather|trade|attack|sneak|reach|unlock|find|avoid|discover)\\b"),
            Regex("(?i)\\b(most of the time|moment to moment|minute to minute|each run|every run|you spend)\\b"),
        ),
        DimId.MOVEMENT to listOf(Regex("(?i)\\b(run|running|jump|jumping|double.?jump|climb|climbing|swim|swimming|fly|flying|glide|gliding|dash|dodge|grapple|sprint|momentum|gravity|traverse|traversal|movement|snappy|floaty|heavy|precise|wall.?(run|jump)|slide|crawl|descend|ascend|fall(ing)?)\\b")),
        DimId.FEELING to listOf(Regex("(?i)\\b(feel|feels|feeling|feelings)\\b"), Regex("(?i)\\b(tense|tension|cozy|relaxing|relaxed|wonder|dread|powerful|vulnerable|frantic|calm|eerie|scary|joyful|satisfying|mood|atmosphere|atmospheric|claustrophobic|lonely|isolated|hopeful|melancholy|oppressive|thrilling|chaotic)\\b")),
        DimId.INTERACTION to listOf(Regex("(?i)\\b(fight|fighting|enemy|enemies|boss|bosses|attack|attacks|weapon|weapons|shoot|shooting|sword|combat|kill|monster|monsters|creature|creatures)\\b")),
        DimId.WORLD to listOf(Regex("(?i)\\b(shaft|tower|pit|world|map|levels?|stages?|rooms?|hub|open.?world|biome|zone|zones|arena|track|dungeon|procedural|procedurally|continuous|enormous|giant|huge|vertical|underground|surface|caverns?|chasm)\\b")),
        DimId.FAILURE to listOf(Regex("(?i)\\b(die|dies|dying|death|respawn|checkpoint|retry|restart|game over|lose|loses|lives|permadeath|fail|fails|defeat|defeated|health reaches)\\b")),
        DimId.PROGRESSION to listOf(Regex("(?i)\\b(upgrade|upgrades|unlock|unlocks|level up|stronger|abilities|ability|equipment|gear|skill tree|experience|xp|power up|power-up|earn|grow|evolve)\\b")),
        DimId.COMPLETION to listOf(Regex("(?i)\\b(win|wins|goal|reach the|escape|finish|victory|ending|objective|beat the game|complete the)\\b")),
        DimId.VISUAL to listOf(Regex("(?i)\\b(art style|pixel|palette|color|colour|lighting|looks like|visual|graphics|aesthetic|silhouette|hand.?drawn|low.?poly|voxel|cartoon|realistic|stylized|stylised)\\b")),
        DimId.AUDIO to listOf(Regex("(?i)\\b(music|soundtrack|sound|audio|ambient|ambience|sfx|score)\\b")),
    )

    fun classify(sentence: String): Set<DimId> =
        if (MetaConversation.isMeta(sentence)) emptySet() else rules.filter { (_, rs) -> rs.any { it.containsMatchIn(sentence) } }.keys

    fun sentencesFor(project: Project, dim: DimId): List<String> =
        project.activeFacts().map { it.text }.filter { dim in classify(it) }
}

/** Which of the dimensions that most shape the game has the owner already spoken to? */
object DimensionCoverage {
    private val core = listOf(DimId.LOOP, DimId.FEELING, DimId.WORLD, DimId.FAILURE, DimId.PROGRESSION, DimId.MOVEMENT)

    fun covered(project: Project, dim: DimId): Boolean {
        val keys = when (dim) {
            DimId.LOOP -> listOf(Keys.CORE_LOOP); DimId.FEELING -> listOf(Keys.PLAYER_FEELING); DimId.WORLD -> listOf(Keys.WORLD_STRUCTURE)
            DimId.FAILURE -> listOf(Keys.DIFFICULTY_FAILURE); DimId.PROGRESSION -> listOf(Keys.PROGRESSION); DimId.MOVEMENT -> listOf(Keys.MOVEMENT_CAMERA)
            else -> emptyList()
        }
        if (keys.any { k -> project.decision(k)?.let { it.value.isNotBlank() || it.status == com.hotattic.gamedesigner.core.model.DecisionStatus.DEFERRED } == true }) return true
        return DimensionLexicon.sentencesFor(project, dim).isNotEmpty()
    }

    /** How many core dimensions are still unspoken. Movement only counts for games that have movement. */
    fun uncoveredCore(project: Project): Int {
        val hasMovement = Traits(project).has(Tag.MOVEMENT)
        return core.filter { it != DimId.MOVEMENT || hasMovement }.count { !covered(project, it) }
    }
}

/** Reads unambiguous world/failure wording from the owner's own sentences. Returns an option id only on a single clear match. */
object SelectLexicon {
    private val world = linkedMapOf(
        "vertical_shaft" to Regex("(?i)\\b(shaft|tower|pit|chasm|vertical (world|map)|one (enormous|giant|huge|continuous|massive) (vertical )?(world|shaft|tower|pit)|single (continuous|vertical) (world|shaft|tower))\\b"),
        "open_map" to Regex("(?i)\\b(open.?world|large (connected )?map|sprawling|interconnected)\\b"),
        "procedural_stages" to Regex("(?i)\\b(procedural(ly)?|randomly generated|random(ised|ized)? levels|different every (run|time))\\b"),
        "hub_missions" to Regex("(?i)\\b(hub and (missions|spokes?)|hub world|missions from a hub)\\b"),
        "single_arena" to Regex("(?i)\\b(single arena|one arena|arena survival|survive waves in an arena)\\b"),
        "authored_levels" to Regex("(?i)\\b(hand.?(crafted|built|authored|designed) levels|series of levels|level by level|a set of levels|separate levels)\\b"),
    )
    private val failure = linkedMapOf(
        "permadeath_meta" to Regex("(?i)\\b(permadeath|permanent death|roguelike|roguelite)\\b"),
        "checkpoint_retry" to Regex("(?i)\\b(checkpoint|checkpoints|respawn(s)? (at|from)|retry (from|at))\\b"),
        "no_fail" to Regex("(?i)\\b(no fail|can'?t die|cannot die|no game over|no failure|no way to (lose|die))\\b"),
        "adjustable" to Regex("(?i)\\b(difficulty (levels|settings|options)|adjustable difficulty)\\b"),
    )

    private fun unique(map: Map<String, Regex>, text: String): String? = map.filter { it.value.containsMatchIn(text) }.keys.singleOrNull()
    fun worldStructure(text: String): String? = unique(world, text)
    fun failureModel(text: String): String? = unique(failure, text)

    fun forField(key: String, text: String): String? = when (key) { Keys.WORLD_STRUCTURE -> worldStructure(text); Keys.DIFFICULTY_FAILURE -> failureModel(text); else -> null }
}

/** Drafts for "choose for me" on the first-playable-slice and must-not-change questions, built only from what is known. */
object SliceSuggester {
    fun suggest(t: Traits): Suggestion {
        val parts = mutableListOf<String>()
        parts += when (t.value(Keys.WORLD_STRUCTURE)) {
            "vertical_shaft" -> "one continuous stretch of the vertical world, traversable end to end without level boundaries"
            "single_arena" -> "one arena"
            "authored_levels" -> "a small set of hand-built levels"
            "procedural_stages" -> "one procedurally generated stage"
            "open_map" -> "one representative region of the world"
            "hub_missions" -> "the hub plus one mission"
            else -> "one representative area"
        }
        parts += "every playable character or mode the owner described, each genuinely playable"
        parts += "the complete core loop"
        if (t.has(Tag.COMBAT)) parts += "representative combat against a few distinct enemy types"
        if (t.value(Keys.PROGRESSION) != null) parts += "at least one real progression step"
        parts += "the failure-and-recovery loop"
        parts += "a clear completion state and a way to restart"
        return Suggestion("A fully playable slice containing ${parts.joinToString("; ")}. Visuals, audio and feel are polished enough to judge whether the game is fun - smaller and polished beats larger and unfinished.",
            "A focused, polished slice proves the idea faster than a wide unfinished build.")
    }

    /** Hard invariants drawn from what the owner already fixed. "none" when nothing has been fixed. */
    fun invariants(t: Traits): Suggestion {
        val p = t.project
        val items = mutableListOf<String>()
        when (p.value(Keys.WORLD_STRUCTURE)) {
            "vertical_shaft" -> items += "The world is one continuous vertical space; never convert it into separate levels, rooms or a level-select."
            "open_map" -> items += "The world is one connected open map; never convert it into discrete levels."
            "single_arena" -> items += "The play space is a single arena; do not split it into stages."
        }
        if (p.decision(Keys.DONE)?.ownerAuthored == true && com.hotattic.gamedesigner.core.engine.ProjectObjective.of(p) == com.hotattic.gamedesigner.core.engine.BuildObjective.PROTOTYPE)
            items += "The first build is a fully functional prototype, not a full commercial game."
        if (p.rejected[Dependencies.TAG].orEmpty().contains("TURN_BASED")) items += "The game is real-time and is not turn-based."
        p.value(Keys.ASSET_POLICY)?.takeIf { p.decision(Keys.ASSET_POLICY)?.ownerAuthored == true }?.let { items += "Asset policy: $it (as chosen by the owner)." }
        p.branding.values.filter { it.mode == com.hotattic.gamedesigner.core.model.BrandingMode.UPLOADED }.forEach { items += "Use the owner-supplied ${it.slot.replace('_', ' ')} exactly as provided." }
        return if (items.isEmpty()) Suggestion("none", "Nothing has been fixed yet that a builder could plausibly reinterpret.")
        else Suggestion(items.joinToString(" "), "These are the things you already fixed that a builder might otherwise 'improve'.")
    }
}
