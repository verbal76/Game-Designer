package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.generate.SpecVersioning
import com.hotattic.gamedesigner.core.model.VersionKind
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test

/** Writes a sample generated package to build/sample for manual inspection. Not an assertion test. */
class SampleDumpTest {
    @Test fun dump() = runBlocking {
        val (p, _) = driveToReady(director(), newProject(), "I want Risk of Rain 2 mixed with Vampire Survivors, but I'm a wizard defending a moving castle. I'll play it on my Android phone.")
        val out = SpecVersioning.createVersion(p, VersionKind.INITIAL, 1_700_000_100_000L, "2026-10-04")
        val dir = File("build/sample").also { it.mkdirs() }
        File(dir, "CLAUDE.md").writeText(out.versions.single().claudeMd)
        File(dir, "MASTER_PROMPT.md").writeText(out.versions.single().masterPrompt)
        File(dir, "conversation.txt").writeText(out.messages.joinToString("\n\n") { "[${it.role}] ${it.text}" })
    }
}
