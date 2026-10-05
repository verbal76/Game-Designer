package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.model.Decision
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.schema.Fields
import com.hotattic.gamedesigner.core.schema.FieldKind
import com.hotattic.gamedesigner.core.schema.GenreKnowledge
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Tag
import com.hotattic.gamedesigner.core.schema.Traits

enum class ReviewLevel { ERROR, WARNING }

data class ReviewFinding(val level: ReviewLevel, val code: String, val message: String, val line: String = "")

data class ReviewReport(val findings: List<ReviewFinding>) {
    val errors get() = findings.filter { it.level == ReviewLevel.ERROR }
    val clean get() = errors.isEmpty()
}

/**
 * Mandatory pre-export consistency review. It checks both the structured state and the generated text, because the
 * export is what Claude Code will actually obey. Contradictions that owner precedence can settle are settled by
 * [resolve] before generation; anything left over blocks export and goes back to the owner.
 */
object ConsistencyReview {

    const val VERBATIM_OPEN = "<!--verbatim-->"
    const val VERBATIM_CLOSE = "<!--/verbatim-->"

    private val negationOnLine = Regex("(?i)\\b(not|no|never|without|withdrawn|rejected|do not|don't|must not|excluded|exclusion|out of scope|ruled out)\\b")
    private val gridTacticalPhrases = listOf("grid-based tactical", "grid based tactical", "tactical grid", "grid tactics", "grid-based movement", "plan each turn", "plan every turn")
    private val turnPhrases = listOf("turn-based", "turn based", "each turn", "every turn", "per turn", "turn order", "action points", "plan each turn", "squad units", "squad tactics")

    /** Settles contradictions that the owner's own words already decide. Returns the project and what it changed. */
    fun resolve(p0: Project, now: Long): Pair<Project, List<String>> {
        var p = p0
        val notes = mutableListOf<String>()
        // A multi-select answer where the owner said "all" must hold every option.
        for ((key, d) in p0.decisions) {
            val f = Fields.get(key) ?: continue
            if (f.kind != FieldKind.MULTI || !d.ownerAuthored) continue
            val opts = f.options(Traits(p)).map { it.id }
            if (opts.isEmpty() || !saysAll(d.rawAnswer)) continue
            val have = d.list()
            val missing = opts.filter { it !in have && !p.isRejected(key, it) }
            if (missing.isNotEmpty() && !hasExceptionCue(d.rawAnswer)) {
                p = p.copy(decisions = p.decisions + (key to d.copy(value = Decision.joinList(have + missing), updatedAt = now)))
                notes += "${f.title}: you said \"${d.rawAnswer.take(40)}\", so I included all ${opts.size} options (was ${have.size})."
            }
        }
        return p to notes
    }

    fun saysAll(raw: String): Boolean = Regex("(?i)\\b(all|everything|every one|the lot)\\b").containsMatchIn(raw)
    fun hasExceptionCue(raw: String): Boolean = Regex("(?i)\\b(except|but not|other than|excluding|apart from|without|minus)\\b").containsMatchIn(raw)

    /** Reviews the structured state and the generated markdown. */
    fun review(p: Project, markdown: String): ReviewReport {
        val t = Traits(p)
        val out = mutableListOf<ReviewFinding>()
        val lines = scannable(markdown)

        // 1. Turn-based / squad / grid language in a design that is not turn based.
        val turnActive = t.has(Tag.TURN_BASED) || p.value(Keys.COMBAT_MODEL) in setOf("turn_based", "tactical_grid")
        if (!turnActive) {
            for (l in lines) {
                val low = l.lowercase()
                if (negationOnLine.containsMatchIn(low)) continue
                val hit = turnPhrases.firstOrNull { it in low }
                if (hit != null) out += ReviewFinding(ReviewLevel.ERROR, "turn_language", "The spec mentions \"$hit\" but the design is not turn based.", l.trim().take(160))
            }
        }
        // 2. Grid-tactical phrasing in a scroller/platformer.
        val scroller = t.hasGenre("platformer") || t.perspectiveIsScroller() || p.value(Keys.PERSPECTIVE) == "side_view"
        if (scroller && p.value(Keys.COMBAT_MODEL) != "tactical_grid") {
            for (l in lines) {
                val low = l.lowercase()
                if (negationOnLine.containsMatchIn(low)) continue
                val hit = gridTacticalPhrases.firstOrNull { it in low }
                if (hit != null) out += ReviewFinding(ReviewLevel.ERROR, "grid_in_scroller", "A scroller/platformer spec mentions \"$hit\".", l.trim().take(160))
            }
        }
        // 3. Multi-select answers that disagree with what the owner said.
        for ((key, d) in p.decisions) {
            val f = Fields.get(key) ?: continue
            if (f.kind != FieldKind.MULTI || !d.ownerAuthored) continue
            val opts = f.options(t).map { it.id }
            if (saysAll(d.rawAnswer) && !hasExceptionCue(d.rawAnswer) && opts.any { it !in d.list() && !p.isRejected(key, it) })
                out += ReviewFinding(ReviewLevel.ERROR, "multi_select_truncated", "${f.title}: the owner said \"${d.rawAnswer.take(40)}\" but only ${d.list().size} of ${opts.size} options are recorded.")
        }
        // 4. Rejected genres/tags still present in the structured design.
        for (g in p.list(Keys.GENRE)) if (p.isRejected(Keys.GENRE, g)) out += ReviewFinding(ReviewLevel.ERROR, "rejected_genre_present", "Genre ${GenreKnowledge.resolve(g).label} was rejected by the owner but is still selected.")
        for ((k, d) in p.decisions) {
            val rej = p.rejected[k].orEmpty()
            if (rej.isNotEmpty() && d.list().any { it in rej }) out += ReviewFinding(ReviewLevel.ERROR, "rejected_option_present", "${Fields.get(k)?.title ?: k} still holds an option the owner rejected.")
        }
        // 5. Rejected genres named in the text as requirements.
        for (g in p.rejected[Keys.GENRE].orEmpty()) {
            val label = GenreKnowledge.resolve(g).label.substringBefore(" (").lowercase()
            for (l in lines) {
                val low = l.lowercase()
                if (negationOnLine.containsMatchIn(low)) continue
                if (label.length > 4 && label in low) out += ReviewFinding(ReviewLevel.ERROR, "rejected_genre_text", "The spec names the rejected genre \"$label\".", l.trim().take(160))
            }
        }
        // 6. Menus: several requested but one recorded.
        p.decision(Keys.MENUS_SETTINGS)?.let { d ->
            if (d.ownerAuthored && d.list().size == 1 && Regex("(?i)\\b(and|,|all|both)\\b").containsMatchIn(d.rawAnswer) && d.rawAnswer.split(Regex("(?i)\\band\\b|,")).count { it.isNotBlank() } >= 2 && !hasExceptionCue(d.rawAnswer))
                out += ReviewFinding(ReviewLevel.ERROR, "menus_truncated", "Several menus were requested (\"${d.rawAnswer.take(60)}\") but only one is recorded.")
        }
        return ReviewReport(out.distinctBy { it.code + it.message + it.line })
    }

    /** Lines to scan: everything except the verbatim concept block and the withdrawn/rejected sections (they quote the owner). */
    private fun scannable(md: String): List<String> {
        val out = mutableListOf<String>()
        var verbatim = false
        for (l in md.lines()) {
            if (l.contains(VERBATIM_OPEN)) { verbatim = true; continue }
            if (l.contains(VERBATIM_CLOSE)) { verbatim = false; continue }
            if (!verbatim) out += l
        }
        return out
    }
}
