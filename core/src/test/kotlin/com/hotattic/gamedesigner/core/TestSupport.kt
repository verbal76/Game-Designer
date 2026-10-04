package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.director.Director
import com.hotattic.gamedesigner.core.director.DirectorAction
import com.hotattic.gamedesigner.core.director.DirectorDeps
import com.hotattic.gamedesigner.core.engine.ProjectOps
import com.hotattic.gamedesigner.core.model.AppSettings
import com.hotattic.gamedesigner.core.model.Experience
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.ProjectMode
import com.hotattic.gamedesigner.core.model.ProjectPrefs

class FakeClock(var now: Long = 1_700_000_000_000L) : () -> Long {
    override fun invoke(): Long { now += 1000; return now }
}

fun newProject(mode: ProjectMode = ProjectMode.NEW_GAME, prefs: ProjectPrefs = ProjectPrefs()) =
    ProjectOps.newProject("p1", "", mode, prefs, 1_700_000_000_000L)

val settings = AppSettings(directorName = "Bob", onboardingComplete = true, internetResearchAllowed = false)

fun director(deps: DirectorDeps = DirectorDeps(clock = FakeClock())) = Director(deps)

/** Drives a conversation to completion by always delegating, returning the final project and whether generation was requested. */
suspend fun driveToReady(director: Director, start: Project, concept: String, maxTurns: Int = 200): Pair<Project, Boolean> {
    var p = director.start(start, settings)
    var t = director.handleUserMessage(p, settings, concept)
    p = t.project
    var turns = 0
    while (turns++ < maxTurns) {
        val pending = p.pendingFieldKey
        val reply = when {
            pending == "__proposals__" -> "yes"
            pending == "__asset_plan__" -> "looks good"
            pending?.startsWith("__conflict:") == true -> "use alternative 1"
            pending == "__ready__" -> "generate"
            else -> "choose for me"
        }
        t = director.handleUserMessage(p, settings, reply)
        p = t.project
        if (t.action == DirectorAction.GenerateSpec) return p to true
    }
    return p to false
}
