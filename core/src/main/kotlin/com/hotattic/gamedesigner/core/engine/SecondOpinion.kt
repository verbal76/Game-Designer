package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.director.Messages
import com.hotattic.gamedesigner.core.model.DecisionStatus
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.Provenance
import com.hotattic.gamedesigner.core.schema.Fields

/** Asks a DIFFERENT AI than the one that helped design the game to critique it. Deterministic prompt, bounded size, advice only. */
object SecondOpinion {
    const val SYSTEM = "You are an independent, skeptical senior game designer reviewing a design brief that an autonomous coding agent will build from. You did not write it. " +
        "Report only real problems: contradictions, missing systems the game needs, assumptions that look like guesses rather than the owner's words, feasibility risks, and vague requirements likely to be built wrongly. " +
        "Where something looks like a guess, say so. Reply with at most 8 bullet points, each one sentence starting with \"- \". Do not rewrite the design and do not praise it."

    /** The design as the reviewer sees it: the owner's words first, then decisions marked by who stands behind them. */
    fun brief(p: Project): String = buildString {
        if (p.originalConcept.isNotBlank()) appendLine("OWNER'S ORIGINAL IDEA (verbatim): ${p.originalConcept.take(1500)}")
        val facts = p.activeFacts().map { it.text }.filter { it.length > 8 }.take(25)
        if (facts.isNotEmpty()) { appendLine("OWNER'S STATEMENTS:"); facts.forEach { appendLine("- ${it.take(240)}") } }
        appendLine("DECISIONS (marked OWNER, ACCEPTED-FROM-BOB or BOB-GUESS):")
        for ((k, d) in p.decisions.entries.sortedBy { Fields.get(it.key)?.priority ?: 999 }) {
            if (d.value.isBlank() || d.status == DecisionStatus.DEFERRED) continue
            val who = when (d.prov) { Provenance.OWNER_CORRECTION, Provenance.OWNER_EXPLICIT -> "OWNER"; Provenance.OWNER_ACCEPTED_RECOMMENDATION -> "ACCEPTED-FROM-BOB"; else -> "BOB-GUESS" }
            appendLine("- ${Fields.get(k)?.title ?: k} [$who]: ${runCatching { Messages.display(p, k, d.value) }.getOrDefault(d.value).take(200)}")
        }
        val rej = p.rejected.flatMap { (k, v) -> v.map { "$k=$it" } }
        if (rej.isNotEmpty()) appendLine("RULED OUT BY THE OWNER: ${rej.joinToString()}")
        p.reeval?.let { r ->
            val open = r.items.filter { it.kind == "CONTRADICTION" || it.kind == "NEEDS_DECISION" }
            if (open.isNotEmpty()) { appendLine("ALREADY KNOWN OPEN ISSUES:"); open.take(12).forEach { appendLine("- ${it.title}: ${it.note.take(120)}") } }
        }
    }.take(8000)

    /** The reviewer's bullet points, cleaned. */
    fun points(text: String): List<String> = text.lines().map { it.trim() }.filter { it.startsWith("- ") || it.startsWith("* ") || Regex("^\\d+[.)] ").containsMatchIn(it) }
        .map { it.replace(Regex("^(- |\\* |\\d+[.)] )"), "").trim() }.filter { it.length in 12..400 }.take(8)
}
