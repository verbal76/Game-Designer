package com.hotattic.gamedesigner.core.generate

import com.hotattic.gamedesigner.core.engine.BuildObjective
import com.hotattic.gamedesigner.core.engine.ProjectObjective
import com.hotattic.gamedesigner.core.model.BrandingMode
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.schema.Fields
import com.hotattic.gamedesigner.core.schema.GenreKnowledge
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Platforms
import com.hotattic.gamedesigner.core.schema.Traits

/** The plain-English "here's the game I think we're making" the owner approves before anything authoritative is generated. */
object DesignReview {

    /** Words an owner uses to name a part of the review, mapped to the question that edits it. */
    val sections: List<Pair<Regex, String>> = listOf(
        Regex("(?i)\\b(world|topology|structure|shaft|map)\\b") to Keys.WORLD_STRUCTURE,
        Regex("(?i)\\b(loop|core)\\b") to Keys.CORE_LOOP,
        Regex("(?i)\\b(fail|failure|death|dying|checkpoint|respawn|challenge)\\b") to Keys.DIFFICULTY_FAILURE,
        Regex("(?i)\\b(progress|progression|upgrade|upgrades)\\b") to Keys.PROGRESSION,
        Regex("(?i)\\b(scope|first build|first playable|slice|prototype)\\b") to Keys.FIRST_SLICE,
        Regex("(?i)\\b(look|visual|visuals|art|style)\\b") to Keys.ART_DIRECTION,
        Regex("(?i)\\b(sound|audio|music)\\b") to Keys.AUDIO,
        Regex("(?i)\\b(complete|completion|win|done|ending)\\b") to Keys.DONE,
        Regex("(?i)\\b(invariant|invariants|must not|reinterpret|constraints?)\\b") to Keys.MUST_NOT_CHANGE,
        Regex("(?i)\\b(feel|feeling|mood)\\b") to Keys.PLAYER_FEELING,
        Regex("(?i)\\b(movement|controls?|camera)\\b") to Keys.MOVEMENT_CAMERA,
        Regex("(?i)\\b(fantasy|vision|idea)\\b") to Keys.CORE_FANTASY,
    )

    fun sectionKeyFor(text: String): String? = sections.firstOrNull { it.first.containsMatchIn(text) }?.second

    private fun label(p: Project, key: String): String? {
        val v = p.value(key) ?: return null
        val f = Fields.get(key) ?: return v
        if (f.kind == com.hotattic.gamedesigner.core.schema.FieldKind.TEXT) return v
        val t = Traits(p)
        return v.split("|").joinToString(", ") { id -> f.options(t).firstOrNull { it.id == id }?.label ?: when (key) { Keys.GENRE -> GenreKnowledge.resolve(id).label; Keys.PLATFORMS -> Platforms.labels[id] ?: id; else -> id } }
    }

    private fun clean(s: String) = s.trim().trimEnd('.', ' ')

    fun compose(p: Project): String {
        val t = Traits(p)
        val title = p.value(Keys.DISPLAY_NAME) ?: p.name.ifBlank { "Your game" }
        val out = mutableListOf<String>()
        out += "**Here's the game I think we're making: $title.**"
        val fantasy = p.value(Keys.CORE_FANTASY) ?: p.originalConcept.lines().firstOrNull().orEmpty()
        if (fantasy.isNotBlank()) out += "**The idea.** ${clean(fantasy)}."
        val role = p.activeFacts().map { it.text }.firstOrNull { Regex("(?i)\\b(you play|you are|you control|two characters|character|player)\\b").containsMatchIn(it) }
        if (role != null && role !in fantasy) out += "**Who you play.** ${clean(role)}."
        label(p, Keys.PLAYER_FEELING)?.let { out += "**How it should feel.** ${clean(it)}." }
        label(p, Keys.CORE_LOOP)?.let { out += "**What you do, moment to moment.** ${clean(it)}." }
        val moves = listOfNotNull(label(p, Keys.MOVEMENT_CAMERA)?.let { "Movement and camera: ${clean(it)}" }, label(p, Keys.COMBAT_MODEL)?.let { "Combat: ${clean(it).lowercase()}" })
        if (moves.isNotEmpty()) out += "**Movement and interaction.** ${moves.joinToString(". ")}."
        val world = when (p.value(Keys.WORLD_STRUCTURE)) {
            "vertical_shaft" -> "One continuous vertical world - a single shaft, not a series of separate levels."
            "open_map" -> "One large connected map."
            "single_arena" -> "A single arena."
            "authored_levels" -> "A set of hand-built levels."
            "procedural_stages" -> "Procedurally generated stages."
            "hub_missions" -> "A hub with missions."
            else -> label(p, Keys.WORLD_STRUCTURE)?.let { "$it." }
        }
        if (world != null) out += "**The world.** $world"
        val fp = listOfNotNull(label(p, Keys.DIFFICULTY_FAILURE)?.let { "When you fail: ${clean(it).lowercase()}" }, label(p, Keys.PROGRESSION)?.let { "As you continue: ${clean(it).lowercase()}" })
        if (fp.isNotEmpty()) out += "**Challenge and progress.** ${fp.joinToString(". ")}."
        val proto = ProjectObjective.of(p) == BuildObjective.PROTOTYPE
        p.value(Keys.FIRST_SLICE)?.let { out += "**The first playable build.** ${clean(it)}.${if (proto) " This is a fully functional prototype, not a full commercial game." else ""}" }
        val ups = p.branding.values.filter { it.mode == BrandingMode.UPLOADED }
        val look = listOfNotNull(label(p, Keys.ART_DIRECTION)?.let { "Art: ${clean(it)}" }, label(p, Keys.AUDIO)?.let { "Audio: ${clean(it).lowercase()}" },
            label(p, Keys.ASSET_POLICY)?.let { "Assets: ${clean(it)}" }, ups.takeIf { it.isNotEmpty() }?.let { "Supplied by you: ${it.joinToString { b -> b.slot.replace('_', ' ') }}" })
        if (look.isNotEmpty()) out += "**Look, sound and assets.** ${look.joinToString(". ")}."
        val done = label(p, Keys.WIN_LOSS) ?: label(p, Keys.DONE)
        if (done != null) out += "**It's complete when.** ${clean(done)}."
        p.value(Keys.MUST_NOT_CHANGE)?.takeIf { it.trim().lowercase() != "none" }?.let { out += "**Claude must not change.** ${clean(it)}." }
        out += "**Platform.** ${t.platforms.joinToString { Platforms.labels[it] ?: it }.ifEmpty { "not set yet" }}."
        out += "Is that the game you have in your head? Say \"looks right\", tell me what to change in your own words, or name a part to edit (world, loop, failure, first build, look...)."
        return out.joinToString("\n\n")
    }
}
