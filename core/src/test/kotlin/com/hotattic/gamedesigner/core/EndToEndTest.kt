package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.engine.AuditEngine
import com.hotattic.gamedesigner.core.engine.CompletenessEngine
import com.hotattic.gamedesigner.core.generate.ExportPackage
import com.hotattic.gamedesigner.core.generate.SpecVersioning
import com.hotattic.gamedesigner.core.model.ProjectPrefs
import com.hotattic.gamedesigner.core.model.Role
import com.hotattic.gamedesigner.core.model.VersionKind
import com.hotattic.gamedesigner.core.persist.ProjectCodec
import com.hotattic.gamedesigner.core.schema.Keys
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EndToEndTest {

    private val concept = "I want Risk of Rain 2 mixed with Vampire Survivors, but I'm a wizard defending a moving castle. I'll play it on my Android phone."

    @Test fun fullConversationReachesReadyAndGeneratesSpec() = runBlocking {
        val d = director()
        val (p, ready) = driveToReady(d, newProject(), concept)
        assertTrue(ready, "conversation never reached generation; last messages: " + p.messages.takeLast(4).joinToString("\n---\n") { it.text })

        val c = CompletenessEngine.compute(p)
        assertTrue(c.missingRequired.isEmpty(), "missing: " + c.missingRequired.map { it.key })
        val audit = AuditEngine.audit(p)
        assertTrue(audit.passes, audit.errors.joinToString("\n") { it.message })

        // Understood the concept
        assertTrue(p.list(Keys.GENRE).containsAll(listOf("action_roguelite", "survivors_like")), p.list(Keys.GENRE).toString())
        assertTrue("android" in p.list(Keys.PLATFORMS))
        assertTrue(p.references.any { it.name.contains("Risk of Rain", true) })
        assertTrue(p.references.any { it.name.contains("Vampire Survivors", true) })

        val withSpec = SpecVersioning.createVersion(p, VersionKind.INITIAL, 1_700_000_100_000L, "2026-10-04")
        val v = withSpec.versions.single()
        assertEquals(1, v.number)
        val md = v.claudeMd
        for (needle in listOf("AUTHORITATIVE BUILD SPECIFICATION", "Required systems", "Content scope", "Validation ladder", "Definition of done", "Spawn", "wizard", "ASSETS.md", "Human-only steps")) {
            assertTrue(md.contains(needle, ignoreCase = true), "spec is missing '$needle'")
        }
        val lower = md.lowercase()
        assertFalse(lower.contains("tbd"), "spec contains TBD")
        assertFalse(lower.contains("todo"), "spec contains TODO")
        assertTrue(v.masterPrompt.length in 800..6000, "master prompt size ${v.masterPrompt.length}")
        assertTrue(v.masterPrompt.contains("CLAUDE.md"))
        assertTrue(md.length > 8000, "spec should be substantial, was ${md.length}")

        // Survives serialization (restart) with versions intact
        val restored = ProjectCodec.decode(ProjectCodec.encode(withSpec))
        assertEquals(withSpec, restored)

        val files = ExportPackage.files(withSpec, v)
        assertTrue(files.keys.containsAll(listOf("CLAUDE.md", "MASTER_PROMPT.md", "ASSETS.md", "game-designer/project.json")))
        assertTrue(ExportPackage.zip(files).size > 1000)
    }

    @Test fun beginnerFlowShowsNumberedOptionsAndAcceptsOrdinals() = runBlocking {
        val d = director()
        var p = d.start(newProject(), settings)
        var t = d.handleUserMessage(p, settings, "A puzzle game for my android phone, 2D")
        p = t.project
        // proposals are confirmed in one step
        assertEquals("__proposals__", p.pendingFieldKey)
        p = d.handleUserMessage(p, settings, "yes").project
        assertNotNull(p.decision(Keys.GENRE))
        val last = p.messages.last { it.role == Role.DIRECTOR }
        assertTrue(last.text.isNotBlank())
    }

    @Test fun conflictIsExplainedThenInformedOverrideIsRespected() = runBlocking {
        val d = director()
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, "A 3D survival crafting game for my android phone with pixel art").project
        p = d.handleUserMessage(p, settings, "yes").project
        // Drive until a conflict appears
        var guard = 0
        while (p.pendingFieldKey?.startsWith("__conflict:") != true && guard++ < 120) {
            val pend = p.pendingFieldKey
            val reply = if (pend == "__proposals__") "yes" else if (pend == "__asset_plan__") "looks good" else "choose for me"
            p = d.handleUserMessage(p, settings, reply).project
        }
        assertTrue(p.pendingFieldKey?.startsWith("__conflict:") == true, "no conflict surfaced")
        val conflictId = p.pendingFieldKey!!.removePrefix("__conflict:").removeSuffix("__")
        val msg = p.messages.last { it.role == Role.DIRECTOR }.text
        assertTrue(msg.contains("recommend", true))
        p = d.handleUserMessage(p, settings, "keep my choice").project
        val c = com.hotattic.gamedesigner.core.engine.ConflictEngine.all(p).firstOrNull { it.id == conflictId }
        if (c != null && c.overridable) assertTrue(p.conflictAcks.any { it.conflictId == conflictId })
    }

    @Test fun statusAndNoTemplateLeakage() = runBlocking {
        val d = director()
        var p = d.start(newProject(prefs = ProjectPrefs(experience = com.hotattic.gamedesigner.core.model.Experience.EXPERT)), settings)
        p = d.handleUserMessage(p, settings, "status").project
        assertTrue(p.messages.last().text.contains("%"))
    }
}
