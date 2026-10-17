package com.hotattic.gamedesigner.core.knowledge

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
) {
    val extractionStatus get() = "VALIDATED"
    val reviewedBy get() = "none (machine-validated only; owner review pending)"
    val sourceTitle get() = KnowledgeLibrary.SOURCE_TITLE
    val sourceCreator get() = KnowledgeLibrary.SOURCE_CREATOR
    val sourceKind get() = KnowledgeLibrary.SOURCE_KIND
    val contentVersion get() = KnowledgeLibraryData.CONTENT_VERSION
    val timestamp get() = "$start-$end"
}
