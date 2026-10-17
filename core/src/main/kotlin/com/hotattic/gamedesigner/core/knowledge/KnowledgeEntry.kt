package com.hotattic.gamedesigner.core.knowledge

/** Where entries come from. [locator] says how an entry points into the source: "timestamp" or "section". [status] is shown with every card from this source. */
data class KnowledgeSource(val id: String, val title: String, val creator: String, val kind: String, val locator: String, val status: String)

/** A named design topic and the owner phrases that point at it. Part of the generated data; retrieval is keyed on these, not on titles. */
data class KnowledgeConcept(val name: String, val phrases: List<String>)

/**
 * One source-supported design principle, written in original words. There is deliberately no field for source wording: provenance is a
 * timestamp range into the named source, so nothing here can reproduce the narration.
 *
 * [mechanic], [effect] and [tradeoffs] are what the source states; [inference] is analysis added during extraction and is always shown
 * labelled as such.
 */
data class KnowledgeEntry(
    val id: String,
    val game: String,
    val start: String,
    val end: String,
    val tags: List<String>,
    val concepts: List<String>,
    val problem: String,
    val mechanic: String,
    val effect: String,
    val tradeoffs: String?,
    val inference: String?,
    val question: String,
    val related: List<String>,
    val confidence: String,
    /** Only pilot entries are retrieved; the rest are validated and waiting to be activated. */
    val pilot: Boolean,
    val sourceId: String = "gmtk",
    /** SOURCE_SUPPORTED (a stated design principle), SOURCE_CONSTRUCT / SOURCE_FRAMEWORK / SOURCE_OBSERVATION / SOURCE_CAVEAT for the research paper. */
    val classification: String = "SOURCE_SUPPORTED",
    /** Never offered by the "Show an example" button; only when the owner asks. */
    val explicitOnly: Boolean = false,
) {
    val source get() = KnowledgeLibraryData.sources.getValue(sourceId)
    val extractionStatus get() = "VALIDATED"
    val reviewedBy get() = "none (machine-validated only; owner review pending)"
    val sourceTitle get() = source.title
    val sourceCreator get() = source.creator
    val sourceKind get() = source.kind
    val contentVersion get() = KnowledgeLibraryData.CONTENT_VERSION
    /** Timestamp range for a video, section name for a paper. Never copied text. */
    val timestamp get() = if (end.isBlank()) start else "$start-$end"
    val fromPaper get() = source.locator == "section"
}
