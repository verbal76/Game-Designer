package com.hotattic.gamedesigner.core.research

import com.hotattic.gamedesigner.core.model.ReferenceGame
import com.hotattic.gamedesigner.core.model.ResearchNote

sealed class ResearchOutcome<out T> {
    data class Found<T>(val value: T) : ResearchOutcome<T>()
    data class NotFound(val what: String) : ResearchOutcome<Nothing>()
    /** Offline, blocked, or the source failed. Never fatal. */
    data class Unavailable(val reason: String) : ResearchOutcome<Nothing>()
}

/** Boundary for internet research, kept separate from model memory. Implementations record sources/provenance. */
interface ResearchProvider {
    suspend fun researchReferenceGame(name: String): ResearchOutcome<ReferenceGame>
    suspend fun toolchainFacts(engineId: String): ResearchOutcome<List<ResearchNote>>
    /** Looks up a design term the owner typed ("souls-like dodge combat"). Optional: providers without it report Unavailable. */
    suspend fun researchTopic(term: String): ResearchOutcome<ResearchNote> = ResearchOutcome.Unavailable("topic research not supported")
}
