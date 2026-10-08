package com.hotattic.gamedesigner.core.session

import com.hotattic.gamedesigner.core.model.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single writer of the open project. Every change - a chat turn, an attachment, a rename - is applied by [mutate] to the
 * FRESHEST committed state while holding one lock, then persisted, then published. Nothing may carry a project snapshot across
 * a slow await (file IO, an LLM call) and commit it afterwards: that lost-update race silently rewinds the conversation, which
 * re-asks questions the owner already answered.
 */
class ProjectSession(private val persist: suspend (Project) -> Unit) {
    private val mutex = Mutex()
    private val state = MutableStateFlow<Project?>(null)
    val current: StateFlow<Project?> = state.asStateFlow()

    /** Replaces the open project (open/create/close). */
    suspend fun open(p: Project?) = mutex.withLock { state.value = p }

    /** Applies [f] to the latest project; persists before publishing so a crash never shows state that was not saved. */
    suspend fun <T> mutate(f: suspend (Project) -> Pair<Project, T>): T? = mutex.withLock {
        val p = state.value ?: return@withLock null
        val (next, result) = f(p)
        if (next !== p) { persist(next); state.value = next }
        result
    }

    suspend fun update(f: suspend (Project) -> Project) { mutate { p -> f(p) to Unit } }
}
