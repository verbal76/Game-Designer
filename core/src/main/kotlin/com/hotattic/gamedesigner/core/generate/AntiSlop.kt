package com.hotattic.gamedesigner.core.generate

import com.hotattic.gamedesigner.core.engine.BuildObjective
import com.hotattic.gamedesigner.core.engine.ProjectObjective
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Tag
import com.hotattic.gamedesigner.core.schema.Traits

/**
 * Design-specific statements of what does NOT satisfy a requirement. A builder told "lush jungle" will happily ship a flat plane
 * with stock trees; naming the shortcut that would betray THIS owner's vision closes it. Only rules whose trigger the owner
 * actually spoke to are emitted - no generic negativity.
 */
object AntiSlop {

    private val environments = listOf("jungle", "forest", "city", "cave", "cavern", "desert", "ocean", "underwater", "space station", "castle", "village", "swamp", "mountain", "ruins", "factory", "dungeon", "arctic", "volcano", "planet", "sewer", "mine", "laboratory", "temple")

    fun derive(p: Project): List<String> {
        val t = Traits(p)
        // Only what the owner themselves said: Bob's own drafted text must not trigger rules about things the owner never mentioned.
        fun ownerValue(k: String) = p.decision(k)?.takeIf { it.ownerAuthored && it.value.isNotBlank() }?.value
        val corpus = (listOf(p.originalConcept) + p.activeFacts().map { it.text } +
            listOf(Keys.CORE_LOOP, Keys.MOVEMENT_CAMERA, Keys.COLOR_MOOD, Keys.FIRST_SLICE, Keys.PLAYER_FEELING, Keys.WIN_LOSS, Keys.CORE_FANTASY).mapNotNull { ownerValue(it) }).joinToString(" ").lowercase()
        fun has(vararg w: String) = w.any { Regex("\\b${Regex.escape(it)}s?\\b").containsMatchIn(corpus) }
        val out = mutableListOf<String>()

        // Topology the owner fixed.
        when (p.value(Keys.WORLD_STRUCTURE)) {
            "vertical_shaft" -> out += "A series of separate levels, rooms, load screens or a level-select does not satisfy the single continuous vertical world; the player must be able to traverse it as one connected space, and a short repeating column of identical tiles does not satisfy depth - show real progression (distinct strata or zones, changing light, scale cues, background depth)."
            "open_map" -> out += "Disconnected levels or a menu of stages does not satisfy a connected open map; regions must connect and be traversable without a level-select."
            "single_arena" -> out += "Splitting play into separate stages does not satisfy a single arena."
        }
        environments.firstOrNull { has(it) }?.let { env ->
            out += "A flat or empty $env with a few randomly placed stock props does not satisfy the visual target. The $env must read as a specific place: layered foreground and background, distinct landmarks, consistent lighting and scale, and detail across the whole traversable space."
        }
        if (has("character", "hero", "explorer", "soldier", "creature", "humanoid", "protagonist", "wizard", "knight") || ownerValue(Keys.CHARACTERS) != null)
            out += "A default capsule, an unchanged engine mannequin or a coloured rectangle does not satisfy character presentation. Each playable character needs a distinct silhouette, readable animation states (idle, move, act, hurt, defeated as relevant) and a recognisable identity."
        if (has("two characters", "both characters", "either character", "two modes", "two sides", "two playable") || Regex("\\beither (?:character|mode|side|one)\\b.{0,40}\\bor\\b").containsMatchIn(corpus))
            out += "Implementing only one of the described characters or modes, or making the other a reskin with identical behaviour, does not satisfy the design; each must play differently in the ways the owner described."
        listOf("gravity", "spherical", "grapple", "glide", "gliding", "swim", "swimming", "fly", "flying", "wall run", "double jump", "dash").firstOrNull { has(it) }?.let { m ->
            out += "A mechanic the owner named ($m) must be a real, controllable mechanic with consequences in play; a visual effect or an animation alone does not satisfy it."
        }
        if (has("2.5d", "two and a half d", "2d side view with depth"))
            out += "A flat single-plane scene does not satisfy 2.5D: the playfield must have genuine depth layering (parallax backgrounds, foreground occluders, depth-staggered lighting) while play stays on the intended plane."
        if (has("cozy", "relaxing", "wholesome", "calm", "peaceful", "gentle"))
            out += "Harsh palettes, abrupt jump-scares, punishing fail states or aggressive audio do not satisfy a cozy, relaxing mood; warm colour, soft motion, forgiving failure and gentle sound are required."
        if (has("claustrophobic", "dread", "tense", "horror", "oppressive", "grim", "brutal"))
            out += "Even, bright, well-lit visuals and cheerful audio do not satisfy a tense or dreadful mood; use restricted light, narrow sightlines, heavy darkness gradients and sound that builds unease."
        if (has("farm", "farming", "crops", "garden", "plant", "harvest"))
            out += "Crops that pop from seed to ripe in one step, or that look identical at every stage, do not satisfy farming; each growth stage must be visibly distinct and take real time."
        if (has("rifle", "gun", "shooter", "shooting", "pistol", "shotgun", "blaster"))
            out += "A gun that fires with no muzzle effect, recoil, impact feedback or audio does not satisfy a shooting game; hits must be felt and readable."
        if (has("first person", "first-person", "fps"))
            out += "A camera that clips through geometry, lacks weapon or hand presence, or ignores look sensitivity settings does not satisfy a first-person view."
        if (t.has(Tag.COMBAT) || p.value(Keys.COMBAT_MODEL) != null)
            out += "Enemies that stand still or cannot hurt the player, or attacks with no feedback (hit flash, sound, knockback or equivalent), do not satisfy combat."
        if ((p.value(Keys.DIFFICULTY_FAILURE) != null && p.value(Keys.DIFFICULTY_FAILURE) != "no_fail") || has("die", "death", "respawn", "checkpoint"))
            out += "A death with no consequence, a recovery that can soft-lock, or a restart that leaves stale state does not satisfy the failure-and-recovery design."
        if (p.value(Keys.PROGRESSION) != null && p.value(Keys.HAS_PROGRESSION) != "no")
            out += "Progression that changes only a hidden number the player cannot feel does not satisfy progression; each step must visibly change what the player can do or how they play."
        if (t.isMobile) out += "A HUD too small to read on a phone, or touch controls that cover critical play, do not satisfy the controls and presentation requirements."
        out += "Engine-default, placeholder-looking or visually incoherent output does not satisfy " + (if (ProjectObjective.of(p) == BuildObjective.PROTOTYPE) "even a prototype: scope may be small, but it must be polished enough to judge the game. Smaller and polished beats larger and unfinished." else "the visual target: smaller and polished beats larger and unfinished.")
        return out.distinct()
    }
}
