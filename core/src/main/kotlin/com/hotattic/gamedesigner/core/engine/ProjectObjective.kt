package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.schema.Keys

/** What the owner said the first build must be. "Fully functional prototype" is NOT "complete game". */
enum class BuildObjective { PROTOTYPE, COMPLETE }

object ProjectObjective {
    private val negatedPrototype = Regex("(?i)\\b(not|no|never|isn't|aren't|rather than|instead of|more than)\\s+(?:just\\s+|only\\s+|merely\\s+)?(?:a\\s+|an\\s+|the\\s+)?(?:\\w+\\s+){0,2}prototype")

    /**
     * Read from the owner's own words: their definition of done, their first-build scope, or what they said in their facts.
     * Silence means the default, a complete first playable.
     */
    fun of(p: Project): BuildObjective {
        val sources = buildList {
            p.decision(Keys.DONE)?.takeIf { it.ownerAuthored }?.let { add(it.value) }
            p.decision(Keys.FIRST_SLICE)?.takeIf { it.ownerAuthored }?.let { add(it.value) }
            p.activeFacts().forEach { add(it.text) }
        }
        val saysPrototype = sources.any { Regex("(?i)\\bprototype\\b").containsMatchIn(it) && !negatedPrototype.containsMatchIn(it) }
        return if (saysPrototype) BuildObjective.PROTOTYPE else BuildObjective.COMPLETE
    }
}
