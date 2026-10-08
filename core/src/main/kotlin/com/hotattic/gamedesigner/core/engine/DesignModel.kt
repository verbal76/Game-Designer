package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.model.Decision
import com.hotattic.gamedesigner.core.model.DecisionStatus
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.Provenance
import com.hotattic.gamedesigner.core.schema.Fields
import com.hotattic.gamedesigner.core.schema.Gates
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Tag
import com.hotattic.gamedesigner.core.schema.Traits

/** Whether an optional system belongs in the game. ABSENT is a complete, resolved answer; UNKNOWN is the only state that is a gap. */
enum class SystemState { PRESENT, ABSENT, UNKNOWN, NOT_APPLICABLE }

enum class SystemId(val label: String) {
    COMBAT("combat"), ECONOMY("economy"), CRAFTING("crafting"), CHARACTER_POWER("character power progression"), NARRATIVE("narrative"),
    MULTIPLAYER("multiplayer"), PROCEDURAL_GENERATION("procedural generation"), OTA_UPDATES("over-the-air updates"), SUPPLIED_ASSETS("owner-supplied assets"),
}

/** What the owner's "lucky" (or any emotion word) most plausibly asks the mechanics to deliver. */
enum class LuckMeaning { NONE, SKILLED_NEAR_MISS, RANDOMNESS, AMBIGUOUS }

data class ExperienceReading(val ownerWords: String, val luck: LuckMeaning, val dynamics: List<String>) {
    val skilledNearMiss get() = luck == LuckMeaning.SKILLED_NEAR_MISS
}

/** MDA in miniature: from the feeling the owner wants to the runtime dynamics that would create it. Deterministic and context-aware. */
object ExperienceLens {
    private val luckWord = Regex("(?i)\\bluck(?:y|ily|iness)?\\b")
    private val skillMarkers = Regex("(?i)\\b(skill(?:s|ed|ful)?|skill[- ]based|barely|just barely|near[- ]miss|narrow(?:ly)?|clutch|just made it|just in time|pull(?:ed|ing)? (?:it|that) off|can'?t believe i made|improbable|last (?:second|moment)|made it by)\\b")
    private val randomMarkers = Regex("(?i)\\b(random(?:ly)?|rng|chance|dice|gambl\\w*|loot drops?|drops?|procs?|critical hits?|probabilit\\w*|roll(?:s|ing)?)\\b")

    /** [ownerWords] is the feeling answer; [context] is the rest of what the owner said about moment-to-moment play. */
    fun read(ownerWords: String, context: String = ""): ExperienceReading {
        if (!luckWord.containsMatchIn(ownerWords)) return ExperienceReading(ownerWords, LuckMeaning.NONE, emptyList())
        val skill = skillMarkers.containsMatchIn(ownerWords) || skillMarkers.containsMatchIn(context)
        // "not random luck" rules randomness OUT; only an un-negated mention counts.
        val random = randomMarkers.containsMatchIn(ownerWords.replace(Regex("(?i)\\b(?:not|never|no|isn'?t|without)\\s+(?:real\\s+)?(?:random\\w*|chance|rng|dice)(?:\\s+luck)?"), " "))
        val meaning = when {
            skill && !random -> LuckMeaning.SKILLED_NEAR_MISS
            random && !skill -> LuckMeaning.RANDOMNESS
            else -> LuckMeaning.AMBIGUOUS
        }
        val dynamics = when (meaning) {
            LuckMeaning.SKILLED_NEAR_MISS -> listOf(
                "narrow-escape moments produced by skilled execution: tight but fair margins, last-moment catches, recoverable near-failures",
                "success is decided by the player's skill, never by hidden randomness",
            )
            LuckMeaning.RANDOMNESS -> listOf("outcomes that real randomness can swing in the player's favour (fair, readable odds)")
            else -> emptyList()
        }
        return ExperienceReading(ownerWords, meaning, dynamics)
    }
}

/** What a procedural generator must preserve, derived from the design rather than from "make it random". */
object ProceduralInvariants {
    private val grammar = listOf("jump", "long jump", "trampoline", "bounce", "rope", "cable", "swing", "moving platform", "crumbling", "ledge", "grab", "catch", "pull", "grapple", "zip", "teleport", "door", "key", "gap", "wall", "slide", "dash")

    fun traversalGrammar(p: Project): List<String> {
        val corpus = (listOf(p.originalConcept) + p.activeFacts().map { it.text } + listOfNotNull(p.decision(Keys.CORE_LOOP)?.value, p.decision(Keys.FIRST_SLICE)?.value)).joinToString(" ").lowercase()
        return grammar.filter { Regex("\\b${Regex.escape(it)}").containsMatchIn(corpus) }.distinct()
    }

    /** (must hold in every generated result, may vary inside those bounds). */
    fun derive(p: Project): Pair<List<String>, List<String>> {
        val t = Traits(p)
        val exp = DesignModel.of(p).experience
        val must = mutableListOf(
            "Every generated route is completable from start to goal; an automated validator or solver proves it for every seed the build can produce.",
            "Destinations and hazards are readable before the player commits to a move (affordances are clear, nothing is invisible or ambiguous).",
            "Difficulty is fair: hard moments are deliberate and recoverable, never accidental or impossible, and the overall difficulty ramps toward completion.",
            "Challenge alternates with recovery (checkpoints, safe footing or resting points) so tension has a rhythm.",
            "Style, scale and visual language stay coherent from stage to stage.",
        )
        if (exp.skilledNearMiss) must += "Margins are tuned so skilled play regularly lands near-misses and last-moment catches; generation never relies on luck to make a stage passable."
        if (t.continuousWorld || t.verticalWorld || p.value(Keys.WORLD_STRUCTURE) == "procedural_stages") must += "The overall topology the owner described survives generation (for example an unbroken upward ascent), not a pile of disconnected pieces."
        val g = traversalGrammar(p)
        if (g.isNotEmpty()) must += "Traversal grammar: only the owner's traversal elements (${g.joinToString(", ")}) and their combinations are used; each combination is checked for fairness."
        val vary = listOf("order, spacing and combination of obstacles within the safe bounds above", "seed-driven layout and pacing of difficulty beats", "visual variants within the established style")
        return must to vary
    }
}

/**
 * A read-only, derived view of the game being designed, computed from the stored decisions, facts and provenance (so nothing new is
 * persisted and old projects need no migration). It is what the reconciliation pass, the export and the lenses reason over.
 */
class DesignModel private constructor(val project: Project) {
    private val t = Traits(project)

    private fun dec(key: String): Decision? = project.decision(key)?.takeIf { it.status != DecisionStatus.DEFERRED && it.value.isNotBlank() }
    private fun ownerOrAccepted(key: String) = dec(key)?.takeIf { it.prov != Provenance.DEFAULT }?.value

    private fun gateState(key: String, tag: Tag?): SystemState {
        if (!t.genresKnown) return SystemState.UNKNOWN
        when (dec(key)?.takeIf { it.status == DecisionStatus.CONFIRMED }?.value) { "yes" -> return SystemState.PRESENT; "no" -> return SystemState.ABSENT }
        val g = Gates.byKey.getValue(key)
        val gateAsked = if (tag != null) Gates.needed(t, tag) else Gates.neededFor(t, key)
        return when {
            gateAsked -> SystemState.UNKNOWN
            tag != null -> if (t.has(tag)) SystemState.PRESENT else SystemState.NOT_APPLICABLE
            else -> if (t.genres.any { it.id in g.core }) SystemState.PRESENT else SystemState.NOT_APPLICABLE
        }
    }

    val systems: Map<SystemId, SystemState> by lazy {
        linkedMapOf(
            SystemId.COMBAT to gateState(Keys.HAS_COMBAT, Tag.COMBAT),
            SystemId.ECONOMY to gateState(Keys.HAS_ECONOMY, Tag.ECONOMY),
            SystemId.CRAFTING to gateState(Keys.HAS_CRAFTING, Tag.CRAFTING),
            SystemId.CHARACTER_POWER to gateState(Keys.HAS_PROGRESSION, null),
            SystemId.NARRATIVE to when (dec(Keys.STORY)?.value) { null -> if (t.has(Tag.STORY)) SystemState.UNKNOWN else SystemState.NOT_APPLICABLE; "none" -> SystemState.ABSENT; else -> SystemState.PRESENT },
            SystemId.MULTIPLAYER to if (dec(Keys.NETWORK_POLICY)?.value == "fully_offline" || dec(Keys.NETWORK_POLICY) == null) SystemState.ABSENT else SystemState.UNKNOWN,
            SystemId.PROCEDURAL_GENERATION to when (dec(Keys.WORLD_STRUCTURE)?.value) { null -> SystemState.UNKNOWN; "procedural_stages" -> SystemState.PRESENT; else -> SystemState.ABSENT },
            SystemId.OTA_UPDATES to when (dec(Keys.OTA_UPDATES)?.value) { null -> SystemState.UNKNOWN; "none" -> SystemState.ABSENT; else -> SystemState.PRESENT },
            SystemId.SUPPLIED_ASSETS to if (dec(Keys.ASSET_POLICY)?.value?.startsWith("supplied") == true) SystemState.PRESENT else if (dec(Keys.ASSET_POLICY) == null) SystemState.UNKNOWN else SystemState.ABSENT,
        )
    }

    fun state(id: SystemId) = systems.getValue(id)
    val intendedAbsences: List<SystemId> get() = systems.filterValues { it == SystemState.ABSENT }.keys.toList()
    /** Absent because the owner (or a settled gate) said so, as opposed to simply not applying. */
    fun ownerRuledOut(id: SystemId): Boolean = state(id) == SystemState.ABSENT

    val experience: ExperienceReading by lazy {
        val words = dec(Keys.PLAYER_FEELING)?.value.orEmpty()
        val context = listOfNotNull(dec(Keys.FIVE_MINUTES)?.value, dec(Keys.CORE_LOOP)?.value, dec(Keys.FIRST_SLICE)?.value).joinToString(" ")
        ExperienceLens.read(words, context)
    }

    /** The three things "progression" can mean; only the third requires XP, stats or unlocks. */
    val characterPowerProgression: SystemState get() = state(SystemId.CHARACTER_POWER)
    val playerMasteryIsProgression: Boolean get() = characterPowerProgression == SystemState.ABSENT
    val procedural: Boolean get() = state(SystemId.PROCEDURAL_GENERATION) == SystemState.PRESENT

    val ownerRequirements: Map<String, Decision> get() = project.decisions.filter { (_, d) -> d.ownerAuthored && d.value.isNotBlank() && d.status == DecisionStatus.CONFIRMED }
    val acceptedRecommendations: Map<String, Decision> get() = project.decisions.filter { (_, d) -> d.prov == Provenance.OWNER_ACCEPTED_RECOMMENDATION && d.value.isNotBlank() }
    val inferences: Map<String, Decision> get() = project.decisions.filter { (_, d) -> d.prov == Provenance.SYSTEM_INFERENCE && d.value.isNotBlank() }
    val defaults: Map<String, Decision> get() = project.decisions.filter { (_, d) -> d.prov == Provenance.DEFAULT && d.value.isNotBlank() }

    /** Material decisions still open: required, relevant, not postponed away into nothing. */
    val unresolvedMaterialDecisions: List<String> by lazy { CompletenessEngine.compute(project).missingRequired.map { it.key } }

    /** Plain-English summary of the mastery arc used when character power is intentionally absent. */
    val masteryNote: String = "Progression is the player's own mastery: discovering what actions are possible, combining them, applying them to harder situations and internalising the timing, together with physical progress through the course. The avatar itself never gets mechanically stronger."

    companion object { fun of(p: Project) = DesignModel(p) }
}
