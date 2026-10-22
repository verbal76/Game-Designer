package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.engine.ProjectOps
import com.hotattic.gamedesigner.core.model.ProjectMode
import com.hotattic.gamedesigner.core.model.ProjectPrefs
import com.hotattic.gamedesigner.core.model.Role
import com.hotattic.gamedesigner.core.persist.FileProjectStore
import com.hotattic.gamedesigner.core.persist.ProjectCodec
import com.hotattic.gamedesigner.core.persist.ProjectTransfer
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The side-by-side TEST 2 migration: a backup made in one app is imported into a completely separate store (a different application's private storage)
 * and every project must come back exactly, including fields this build does not know, and a damaged project must be recovered from its good copy.
 * Synthetic projects only. A backup contains no application id, so it moves between the two TEST apps unchanged.
 */
class MigrationPortabilityTest {
    private fun store() = FileProjectStore(Files.createTempDirectory("gd-migrate").toFile())
    private fun project(id: String, name: String, text: String) =
        ProjectOps.setOriginalConcept(ProjectOps.addMessage(ProjectOps.newProject(id, name, ProjectMode.NEW_GAME, ProjectPrefs(), 1_700_000_000_000L), Role.USER, text, 1_700_000_001_000L), text)

    @Test fun everyProjectIncludingUnknownFieldsSurvivesAMoveToASeparateAppStore() {
        val old = store()
        val a = project("p-a", "Garden", "A calm garden puzzle."); val b = project("p-b", "Racer", "A racing game with a ghost car.")
        old.save(a); old.save(b)
        // a project written by a newer build carries a top-level field this build has never heard of
        val f = File(old.projectDir("p-b"), "project.json")
        val withFuture = f.readText().trimEnd().removeSuffix("}") + ",\"futureTopLevelField\":{\"keep\":[1,2,3]}}"
        f.writeText(withFuture)
        val bytes = ProjectTransfer.createBackup(old, "5.0.0-test", 9L)
        assertTrue(ProjectTransfer.verify(bytes).ok, "the backup verifies before it is trusted")

        val other = store()                                   // the TEST 2 app's own private storage
        val r = ProjectTransfer.import(other, bytes)
        assertTrue(r.success, r.summary); assertEquals(2, r.imported.size)
        assertEquals(old.load("p-a"), other.load("p-a"), "an ordinary project is identical after the move")
        assertEquals(old.load("p-b"), other.load("p-b"))
        assertTrue(File(other.projectDir("p-b"), "project.json").readText().contains("futureTopLevelField"), "the unknown field travelled with the project")
        assertEquals(setOf("p-a", "p-b"), old.list().map { it.id }.toSet(), "the source store was only read")
        assertEquals(withFuture, f.readText(), "the original app's file is untouched")
    }

    @Test fun aDamagedProjectIsRecoveredFromItsGoodCopyAndTheBackupSaysSo() {
        val old = store(); val p = project("p-x", "Hurt", "the owner's own words"); old.save(p)
        val dir = old.projectDir("p-x"); File(dir, "project.json.bak").writeText(ProjectCodec.encode(p)); File(dir, "project.json").writeText("{ truncated")
        val bytes = ProjectTransfer.createBackup(old, "5.0.0-test", 9L)
        val other = store(); val r = ProjectTransfer.import(other, bytes)
        assertEquals(1, r.imported.size); assertEquals("the owner's own words", other.load("p-x")!!.originalConcept)
        assertTrue(ProjectTransfer.preview("b.zip", bytes).headline.startsWith("INCOMPLETE"), "a recovered project is never presented as a clean backup")
    }

    @Test fun importingTwiceNeverOverwritesAndLeavesCopies() {
        val old = store(); old.save(project("p-a", "Garden", "A garden puzzle."))
        val bytes = ProjectTransfer.createBackup(old, "5.0.0-test", 9L)
        val other = store(); ProjectTransfer.import(other, bytes); val again = ProjectTransfer.import(other, bytes)
        assertEquals(2, other.list().size, "the second import becomes a separate copy"); assertTrue(again.imported.single().renamed)
    }
}
