package com.hotattic.gamedesigner.core.generate

import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.SpecVersion

/** One document of a generated handoff package, as the Spec screen lists it. */
data class HandoffDoc(val path: String, val title: String, val text: String)

/** The stages of producing the package. The UI shows exactly these, in this order, with no invented percentages. */
enum class HandoffStage(val label: String) {
    PREPARING("Preparing documents"), VALIDATING("Checking the design for contradictions"), GENERATING("Writing the files"), COMPLETED("Package ready"), FAILED("Could not finish");
}

object HandoffPackage {
    private val titles = mapOf(
        "CLAUDE.md" to "CLAUDE.md (the specification)", "MASTER_PROMPT.md" to "Master development prompt", "ASSETS.md" to "Assets and provenance",
        "docs/GAME_DESIGN.md" to "Game design", "docs/REQUIREMENTS_LEDGER.md" to "Requirements ledger", "docs/EXCLUSIONS.md" to "Explicit exclusions",
        "docs/ARCHITECTURE_DECISIONS.md" to "Technical decisions", "docs/TECH_STACK.md" to "Technology stack", "docs/ASSET_PLAN.md" to "Asset and licensing plan",
        "docs/BUILD_AND_RELEASE.md" to "Build and release", "docs/TEST_PLAN.md" to "Test and validation plan", "docs/GAMEPLAY_INVARIANTS.md" to "Gameplay invariants",
        "docs/MILESTONES.md" to "Milestones", "docs/COMPLETION_CRITERIA.md" to "Completion criteria", "docs/OWNER_AUTHORIZATION.md" to "Release permissions",
        "docs/FAILURE_ESCALATION.md" to "Failure escalation", "docs/PROGRESS_REPORTING.md" to "Progress reporting", "docs/RESEARCH.md" to "Research notes", "docs/SPEC_HISTORY.md" to "Version history",
    )

    /** Every text document in the package for [version], in reading order (the same files the .zip export contains). */
    fun documents(project: Project, version: SpecVersion): List<HandoffDoc> =
        ExportPackage.files(project, version).filterKeys { it.endsWith(".md") }.map { (path, text) -> HandoffDoc(path, titles[path] ?: path.substringAfterLast('/'), text) }
}
