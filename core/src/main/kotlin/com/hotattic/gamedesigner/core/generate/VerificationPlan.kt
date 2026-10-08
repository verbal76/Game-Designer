package com.hotattic.gamedesigner.core.generate

import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Platforms
import com.hotattic.gamedesigner.core.schema.Tag
import com.hotattic.gamedesigner.core.schema.Traits

/** A game-specific plan of the player paths a builder must actually exercise before claiming success. */
object VerificationPlan {

    private val traversalVerbs = listOf("jump", "double jump", "climb", "dash", "glide", "swim", "fly", "wall run", "grapple", "sprint", "dodge", "slide", "descend", "ascend")

    fun steps(p: Project): List<String> {
        val t = Traits(p)
        fun ownerValue(k: String) = p.decision(k)?.takeIf { it.ownerAuthored && it.value.isNotBlank() }?.value
        val corpus = (listOf(p.originalConcept) + p.activeFacts().map { it.text } + listOfNotNull(ownerValue(Keys.MOVEMENT_CAMERA), ownerValue(Keys.CORE_LOOP), ownerValue(Keys.FIRST_SLICE))).joinToString(" ").lowercase()
        val out = mutableListOf<String>()
        val platform = t.platforms.joinToString { Platforms.labels[it] ?: it }.ifEmpty { "the target platform" }
        out += "Launch the real build on $platform from a cold start and reach live gameplay with no errors."
        if (t.has(Tag.MOVEMENT)) {
            val verbs = traversalVerbs.filter { Regex("\\b${Regex.escape(it)}").containsMatchIn(corpus) }
            out += "Exercise movement: ${if (verbs.isEmpty()) "every movement action the design describes" else verbs.joinToString(", ")}. Confirm the camera never clips or hides the player, collision holds (no falling through the world, no sticking), and the feel matches the owner's description."
        }
        when (p.value(Keys.WORLD_STRUCTURE)) {
            "vertical_shaft" -> out += "Traverse the entire continuous world from one extreme to the other (and back if the design allows) with no level boundary, soft-lock or unreachable section."
            "authored_levels" -> out += "Start and finish every level in the first build."
            "open_map" -> out += "Travel between every region in the first build; confirm connections work in both directions."
            "procedural_stages" -> out += "Generate several different stages and confirm each is valid, completable and different."
            "single_arena" -> out += "Play the arena from empty to the escalation the design describes; confirm it stays stable under the maximum entity load."
            else -> out += "Play from the beginning of the first-build slice to its end."
        }
        val modes = Regex("(?i)\\b(either character|two characters|both characters|two modes|two sides)\\b").containsMatchIn(corpus)
        if (modes || p.value(Keys.CHARACTERS) != null) out += "Play every playable character or mode from its start through its own completion path; confirm they genuinely differ as designed."
        if (t.has(Tag.COMBAT) || p.value(Keys.COMBAT_MODEL) != null) out += "Fight every enemy type in the first build: deal damage, take damage, defeat them, and confirm feedback (hit reaction, sound, effects). Fight any boss or set-piece encounter the design includes."
        if (t.has(Tag.CRAFTING) || t.has(Tag.ECONOMY) || t.has(Tag.BUILDING)) out += "Exercise every resource, crafting, build or trade interaction in the first build end to end."
        if (p.value(Keys.DIFFICULTY_FAILURE) != "no_fail") out += "Fail on purpose: confirm the failure-and-recovery behaviour (${com.hotattic.gamedesigner.core.generate.PlainLabels.of(p, Keys.DIFFICULTY_FAILURE) ?: "defined recovery"}) works, restores a correct state and cannot soft-lock."
        if (p.value(Keys.PROGRESSION) != null && p.value(Keys.HAS_PROGRESSION) != "no") out += "Earn at least one progression step and confirm its effect is felt in play."
        if (p.value(Keys.SAVE_SYSTEM).let { it != null && it != "none" } || t.isMobile) out += "Pause and resume (and on a phone, background and return); if saving exists, save, kill the app, relaunch and load."
        if (p.value(Keys.OTA_UPDATES) == "content_ota") out += "Exercise the update pipeline: serve a valid update and confirm it applies on the NEXT launch only; serve a tampered and a corrupt one and confirm both are rejected with the game still starting on bundled content; confirm the game starts and plays with no network."
        if (p.value(Keys.HAS_PROGRESSION) == "no") out += "Confirm there is no player power progression anywhere: nothing the player earns makes later climbs mechanically easier."
        if (p.value(Keys.ASSET_POLICY)?.let { com.hotattic.gamedesigner.core.engine.AssetStrategy.of(it).usesSupplied } == true) out += "Confirm the owner-supplied asset packs are actually used in the build (list which pack feeds which asset need in ASSETS.md) and nothing supplied was silently replaced."
        out += "Reach the completion state defined for the first build, then restart cleanly."
        out += "Capture representative gameplay screenshots (or video) on the real build where tooling permits, inspect them for bad composition, camera clipping, unreadable HUD, placeholder assets, empty environments, scale problems, broken materials, visual obstruction, poor character readability, weak lighting and unfinished geometry, repair what you find, and inspect again."
        out += "Report honestly what you could and could not test. Successful compilation, an editor screenshot, a tool handshake, or unit tests alone are NOT gameplay verification."
        return out
    }
}

/** Option-label lookup shared by generators. */
object PlainLabels {
    fun of(p: Project, key: String): String? {
        val v = p.value(key) ?: return null
        val f = com.hotattic.gamedesigner.core.schema.Fields.get(key) ?: return v
        if (f.kind == com.hotattic.gamedesigner.core.schema.FieldKind.TEXT) return v
        if (key == Keys.ASSET_POLICY) com.hotattic.gamedesigner.core.engine.AssetStrategy.label(v)?.let { return it }
        val t = Traits(p)
        return v.split("|").joinToString(", ") { id -> f.options(t).firstOrNull { it.id == id }?.label ?: id }
    }
}
