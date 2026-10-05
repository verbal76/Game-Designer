package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.schema.Keys

/** What the owner said the first build must be. "Fully functional prototype" is NOT "complete game". */
enum class BuildObjective { PROTOTYPE, COMPLETE }

object ProjectObjective {
    private val negatedPrototype = Regex("(?i)\\b(not|no|never|isn't|aren't|rather than|instead of|more than)\\s+(?:just\\s+|only\\s+|merely\\s+)?(?:a\\s+|an\\s+|the\\s+)?(?:\\w+\\s+){0,2}prototype")

    /** Read from the owner's own definition of done. Absent or silent means the default: a complete first playable. */
    fun of(p: Project): BuildObjective {
        val d = p.decision(Keys.DONE)
        if (d == null || !d.ownerAuthored) return BuildObjective.COMPLETE
        val t = d.value
        return if (Regex("(?i)\\bprototype\\b").containsMatchIn(t) && !negatedPrototype.containsMatchIn(t)) BuildObjective.PROTOTYPE else BuildObjective.COMPLETE
    }
}
