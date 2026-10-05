package com.hotattic.gamedesigner.core.generate

import com.hotattic.gamedesigner.core.engine.BuildObjective
import com.hotattic.gamedesigner.core.engine.ProjectObjective
import com.hotattic.gamedesigner.core.engine.ResourceLevel
import com.hotattic.gamedesigner.core.engine.ScopeEngine
import com.hotattic.gamedesigner.core.model.FeedbackStatus
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.ProjectMode
import com.hotattic.gamedesigner.core.model.UsageStyle
import com.hotattic.gamedesigner.core.schema.Keys

/**
 * The compact companion prompt. All substance lives in CLAUDE.md; this prompt only tells Claude Code how to execute it,
 * so the owner pastes something short instead of one enormous fragile prompt.
 */
object MasterPromptGenerator {

    fun generate(project: Project, versionNumber: Int): String {
        val title = project.value(Keys.DISPLAY_NAME) ?: project.name
        val scope = ScopeEngine.recommend(project)
        val repo = project.repo?.let { "${it.owner}/${it.repo}" }
        val usage = when (project.prefs.usageStyle) {
            UsageStyle.CONSERVATIVE -> "The owner shares one usage allowance across many projects: work economically and stop cleanly at a checkpoint rather than burning the whole window."
            UsageStyle.BALANCED -> "Work at a steady, economical pace; checkpoint often so a context reset costs nothing."
            UsageStyle.AGGRESSIVE -> "The owner wants this finished quickly: use the allowance as needed, but still checkpoint at every stable milestone."
        }
        val heavy = if (scope.resources.level >= ResourceLevel.HEAVY) "This is a ${scope.resources.level.label} effort: expect many sessions; keep HANDOFF.md current." else ""
        val openFeedback = project.feedback.count { it.status == FeedbackStatus.OPEN }

        return buildString {
            appendLine("You are Claude Code, the autonomous lead engineer for \"$title\" (spec v$versionNumber).")
            appendLine()
            appendLine("REPOSITORY: ${repo ?: "the current repository"}. Work on the designated branch; commit and push stable checkpoints.")
            appendLine()
            appendLine("FIRST ACTION: read the repository-root `CLAUDE.md` in full. It is the authoritative specification; do not substitute a summary of it.")
            appendLine("If `CLAUDE.md` is missing, tell the owner it must be added at the repository root before you proceed.")
            appendLine()
            when (project.mode) {
                ProjectMode.NEW_GAME -> appendLine(if (ProjectObjective.of(project) == BuildObjective.PROTOTYPE) "MISSION: build the fully functional PROTOTYPE described in `CLAUDE.md`: the intended game and core loop genuinely playable, with only the content needed to prove the design (see section 5). Not a mockup or gray-box, and not a full production game. Implement, integrate, test and repair everything the prototype needs before involving the owner."
                else "MISSION: build the complete, genuinely playable first version described in `CLAUDE.md`. Not a prototype, mockup or gray-box. Implement, integrate, test, repair and polish everything practical before involving the owner.")
                ProjectMode.EXISTING_GAME -> appendLine("MISSION: continue the existing game as described in `CLAUDE.md` section 17. Inspect the repository first, separate verified facts from assumptions, create a rollback tag/branch, preserve working systems, then implement the plan.")
                ProjectMode.PLAYTEST_CONTINUE -> appendLine("MISSION: apply the playtest feedback in `CLAUDE.md` section 17 ($openFeedback open item(s)). Reproduce each issue first, fix root causes, verify, and keep everything that already works.")
            }
            appendLine()
            appendLine("EXECUTION RULES")
            appendLine("- Work autonomously through implementation, integration, automated testing, repair and polish. Follow the phases in section 15.")
            appendLine("- Make reasonable, reversible engineering decisions yourself and record them in `docs/DECISIONS.md`. Research current facts (versions, SDK requirements, licenses) when they matter and record sources; do not repeat settled research.")
            appendLine("- Never ask the owner anything `CLAUDE.md` already answers. Escalate only: a genuine creative decision not covered, credentials/permissions only the owner can supply, or a verified blocker with no engineering alternative.")
            appendLine("- Validate continuously using the validation ladder in section 13: targeted tests while iterating, the full suite and real build at phase gates. Report only checks you actually ran.")
            appendLine("- Produce the real installable build artifact through the pipeline in section 9. If the toolchain cannot be installed in your environment, use CI and read its results.")
            appendLine("- Assets: obey the license policy and provenance rules in section 8. Never leave an asset unresolved; build the original procedural replacement instead.")
            appendLine("- Never commit secrets. Never force-push or rewrite shared history. Destructive repository actions need explicit owner confirmation.")
            appendLine()
            appendLine("RESOURCE POLICY: $usage Use subagents only when their value clearly exceeds their cost; avoid parallel swarms; do not shrink product quality to save effort. $heavy".trim())
            appendLine()
            appendLine("CHECKPOINTS: commit stable, tested milestones. When a context nears its limit or a phase ends, update `HANDOFF.md` with branch + SHA, what is implemented, test/build status, known defects, and next dependency-ordered work.")
            appendLine()
            appendLine("DONE means: everything in `CLAUDE.md` implemented, automated validation green, installable artifact built and its location reported. Hand off to the owner only when the remaining meaningful validation genuinely requires human playtesting; list exactly what to test.")
        }.trimEnd() + "\n"
    }
}
