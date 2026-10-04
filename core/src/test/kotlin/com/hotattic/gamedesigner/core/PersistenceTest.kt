package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.engine.ProjectOps
import com.hotattic.gamedesigner.core.generate.SpecVersioning
import com.hotattic.gamedesigner.core.model.AppSettings
import com.hotattic.gamedesigner.core.model.DecisionSource
import com.hotattic.gamedesigner.core.model.FeedbackSeverity
import com.hotattic.gamedesigner.core.model.FeedbackStatus
import com.hotattic.gamedesigner.core.model.PROJECT_SCHEMA_VERSION
import com.hotattic.gamedesigner.core.model.PlaytestFeedback
import com.hotattic.gamedesigner.core.model.ProjectMode
import com.hotattic.gamedesigner.core.model.VersionKind
import com.hotattic.gamedesigner.core.persist.FileProjectStore
import com.hotattic.gamedesigner.core.persist.Migration
import com.hotattic.gamedesigner.core.persist.MigrationRegistry
import com.hotattic.gamedesigner.core.persist.ProjectCodec
import com.hotattic.gamedesigner.core.persist.UnsupportedSchemaException
import com.hotattic.gamedesigner.core.schema.Keys
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersistenceTest {
    private fun tmp(): File = Files.createTempDirectory("gd-test").toFile()

    @Test fun roundTripAndList() {
        val store = FileProjectStore(tmp())
        val p = ProjectOps.setDecision(newProject().copy(name = "Alpha"), Keys.GENRE, "puzzle", DecisionSource.USER, 5L)
        store.save(p)
        assertEquals(p, store.load("p1"))
        assertEquals(listOf("Alpha"), store.list().map { it.name })
    }

    @Test fun settingsRoundTrip() {
        val store = FileProjectStore(tmp())
        assertEquals(AppSettings(), store.loadSettings())
        val s = AppSettings(directorName = "Glaxor", internetResearchAllowed = true)
        store.saveSettings(s)
        assertEquals(s, store.loadSettings())
    }

    @Test fun corruptFileRecoversFromBackupAndKeepsEvidence() {
        val store = FileProjectStore(tmp())
        val p1 = newProject().copy(name = "One")
        store.save(p1)
        val p2 = p1.copy(name = "Two")
        store.save(p2) // .bak now holds "One"
        val f = File(store.projectDir("p1"), "project.json")
        f.writeText("{ this is not json")
        val loaded = store.load("p1")
        assertEquals("One", loaded!!.name)
        assertTrue(File(f.path + ".corrupt").exists())
    }

    @Test fun deleteRemovesProject() {
        val store = FileProjectStore(tmp()); store.save(newProject())
        assertTrue(store.delete("p1")); assertNull(store.load("p1"))
    }

    @Test fun migrationChainRunsOnRawJson() {
        val old = buildJsonObject {
            put("schemaVersion", JsonPrimitive(1)); put("id", JsonPrimitive("m")); put("title", JsonPrimitive("Legacy"))
            put("createdAt", JsonPrimitive(1)); put("updatedAt", JsonPrimitive(1))
        }
        val registry = MigrationRegistry(mapOf(1 to Migration { o -> JsonObject((o - "title") + ("name" to (o["title"] ?: JsonPrimitive("?")))) }), currentVersion = 2)
        val up = registry.upgrade(old)
        assertEquals(JsonPrimitive(2), up["schemaVersion"])
        assertEquals(JsonPrimitive("Legacy"), up["name"])
        assertTrue("title" !in up)
    }

    @Test fun newerSchemaIsRejectedWithoutDataLoss() {
        val text = ProjectCodec.encode(newProject()).replace("\"schemaVersion\": $PROJECT_SCHEMA_VERSION", "\"schemaVersion\": ${PROJECT_SCHEMA_VERSION + 5}")
        assertFailsWith<UnsupportedSchemaException> { ProjectCodec.decode(text) }
        val store = FileProjectStore(tmp())
        File(store.projectDir("x"), "project.json").writeText(text)
        assertFailsWith<UnsupportedSchemaException> { store.load("x") }
        assertTrue(File(store.projectDir("x"), "project.json").readText() == text, "file must not be modified")
    }

    @Test fun missingMigrationIsAnError() {
        assertFailsWith<IllegalStateException> { MigrationRegistry(emptyMap(), currentVersion = 3).upgrade(buildJsonObject { put("schemaVersion", JsonPrimitive(1)) }) }
    }

    @Test fun unknownFieldsAreIgnoredForForwardCompat() {
        val text = ProjectCodec.encode(newProject()).replaceFirst("{", "{ \"someFutureField\": 1,")
        assertNotNull(ProjectCodec.decode(text))
    }
}

class VersioningAndPlaytestTest {
    @Test fun versionsAreAppendOnlyAndDiffable() = runBlocking {
        val (p, _) = driveToReady(director(), newProject(), "A cozy 2D puzzle game for my android phone about arranging cats")
        val v1 = SpecVersioning.createVersion(p, VersionKind.INITIAL, 10L, "2026-10-04")
        val changed = ProjectOps.setDecision(v1, Keys.ORIENTATION, "landscape", DecisionSource.USER, 20L)
        val v2 = SpecVersioning.createVersion(changed, VersionKind.REVISION, 30L, "2026-10-05", "Tweaks")
        assertEquals(listOf(1, 2), v2.versions.map { it.number })
        assertEquals(v1.versions[0].claudeMd, v2.versions[0].claudeMd, "earlier version untouched")
        val diff = SpecVersioning.diff(v2.versions[0], v2.versions[1])
        assertTrue(diff.any { it.key == Keys.ORIENTATION })
        assertEquals(VersionKind.INITIAL, SpecVersioning.suggestedKind(p))
        assertEquals(VersionKind.REVISION, SpecVersioning.suggestedKind(v1))
    }

    @Test fun playtestFeedbackFlowsIntoContinuationSpec() = runBlocking {
        val d = director()
        val (p, _) = driveToReady(d, newProject(), "A cozy 2D puzzle game for my android phone about arranging cats")
        val v1 = SpecVersioning.createVersion(p, VersionKind.INITIAL, 10L, "2026-10-04")
        val pt = v1.copy(mode = ProjectMode.PLAYTEST_CONTINUE, messages = emptyList(), pendingFieldKey = null)
        var q = d.start(pt, settings)
        q = d.handleUserMessage(q, settings, "The game crashes when I rotate the phone on level 3").project
        q = d.handleUserMessage(q, settings, "It would be nice to have a hint button").project
        assertEquals(listOf(FeedbackSeverity.BLOCKER, FeedbackSeverity.SUGGESTION), q.feedback.map { it.severity })
        val gen = d.handleUserMessage(q, settings, "generate")
        assertEquals(com.hotattic.gamedesigner.core.director.DirectorAction.GenerateSpec, gen.action)
        assertEquals(VersionKind.PLAYTEST_REPAIR, SpecVersioning.suggestedKind(gen.project))
        val v2 = SpecVersioning.createVersion(gen.project, VersionKind.PLAYTEST_REPAIR, 40L, "2026-10-06")
        assertTrue(v2.versions.last().claudeMd.contains("crashes when I rotate"))
        assertTrue(v2.versions.last().claudeMd.contains("Reproduce each item first"))
        assertTrue(v2.feedback.all { it.status == FeedbackStatus.INCORPORATED })
        assertTrue(v2.versions.last().masterPrompt.contains("playtest feedback", ignoreCase = true) || v2.versions.last().masterPrompt.contains("PLAYTEST", ignoreCase = false))
    }
}
