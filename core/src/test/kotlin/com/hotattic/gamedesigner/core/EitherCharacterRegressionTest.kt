package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.attach.AttachmentIngest
import com.hotattic.gamedesigner.core.attach.IngestFailure
import com.hotattic.gamedesigner.core.attach.IngestResult
import com.hotattic.gamedesigner.core.director.Director
import com.hotattic.gamedesigner.core.director.DirectorAction
import com.hotattic.gamedesigner.core.engine.ReviewGate
import com.hotattic.gamedesigner.core.generate.ExportPackage
import com.hotattic.gamedesigner.core.generate.SpecVersioning
import com.hotattic.gamedesigner.core.model.BrandingMode
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.Role
import com.hotattic.gamedesigner.core.model.VersionKind
import com.hotattic.gamedesigner.core.persist.ProjectCodec
import com.hotattic.gamedesigner.core.schema.Keys
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A provider-style stream: unknown length, `available()` is 0, and reads return tiny partial chunks (like a pipe from a document provider). */
private class DocumentProviderStream(private val data: ByteArray, private val chunk: Int = 7) : InputStream() {
    private var pos = 0
    override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xFF else -1
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (pos >= data.size) return -1
        val n = minOf(chunk, len, data.size - pos)
        System.arraycopy(data, pos, b, off, n); pos += n; return n
    }
    override fun available(): Int = 0
}

/**
 * The owner's physical Either Character test as a durable regression: natural-language concept, corrections and meta-talk, the
 * studio-logo upload through both a plain file stream and a Browse -> Files document-provider stream, duplicate callbacks,
 * restart, "A fully functional prototype game", "CC0 only", the review gate, and the exported ZIP.
 */
class EitherCharacterRegressionTest {

    private val concept = "I want to make a game called Either Character. The whole game is one enormous vertical shaft that goes from the surface deep underground. " +
        "You choose which side to play. You can descend from the surface as a military explorer investigating what is below, or you can ascend from the depths as an underground humanoid creature attacking toward the surface. " +
        "It's a 2D side-view action platformer for my Android phone. The mood is claustrophobic and tense, with a sense of dread the deeper you go. " +
        "You fight with a rifle and flares as the explorer, and with claws and ambushes as the creature."

    private val logo = File("../Hot_Attic_Games_Master_Logo_ALPHA_FINAL.png")
    private val canonical = "e3d9bb5653eafb783eede827606e7ac73a4e45564a1c25b1ed13ad1429f48c4e"

    private fun script(pending: String?): String = when (pending) {
        "__proposals__" -> "yes"
        "__asset_plan__" -> "looks good"
        "__review__" -> "looks right"
        "__ready__" -> "generate"
        Keys.ASSET_POLICY -> "CC0 only"
        Keys.DONE -> "A fully functional prototype game"
        else -> if (pending?.startsWith("__conflict:") == true) "use alternative 1" else "choose for me"
    }

    private suspend fun drive(d: Director, start: Project, stopAt: (Project) -> Boolean, maxTurns: Int = 120): Pair<Project, Boolean> {
        var p = start
        repeat(maxTurns) {
            if (stopAt(p)) return p to false
            val t = d.handleUserMessage(p, settings, script(p.pendingFieldKey))
            p = t.project
            if (t.action == DirectorAction.GenerateSpec) return p to true
        }
        error("stuck at ${p.pendingFieldKey}: " + p.messages.takeLast(3).joinToString(" / ") { it.text.take(900) })
    }

    @Test fun studioLogoFlowEndToEnd() = runBlocking {
        assertTrue(logo.exists(), "canonical logo fixture missing at ${logo.absolutePath}")
        val logoBytes = logo.readBytes()
        assertEquals(canonical, AttachmentIngest.sha256(logoBytes))
        val d = director()
        var p = d.handleUserMessage(d.start(newProject(), settings), settings, concept).project
        p = drive(d, p, { it.pendingFieldKey == Keys.BRAND_STUDIO }).first
        assertEquals(Keys.BRAND_STUDIO, p.pendingFieldKey)

        // Meta-conversation about using the app is not a requirement, and does not answer the question.
        p = d.handleUserMessage(p, settings, "I've already added it twice.").project
        assertEquals(Keys.BRAND_STUDIO, p.pendingFieldKey)
        assertTrue(p.activeFacts().none { it.text.contains("twice", true) }, "meta remark leaked into facts")

        // Owner chooses to upload; the app is asked to open the picker exactly once.
        val chosen = d.submitSelection(p, settings, Keys.BRAND_STUDIO, listOf("upload"))
        assertEquals(DirectorAction.RequestUpload("studio_splash"), chosen.action)
        p = chosen.project
        val ask = "I'll keep it as the untouched master"
        assertEquals(1, p.messages.count { it.role == Role.DIRECTOR && it.text.contains(ask) }, "Bob must not emit the upload prompt twice")
        // A second tap on the same question card is a duplicate, not a second turn.
        val tapAgain = d.submitSelection(p, settings, Keys.BRAND_STUDIO, listOf("upload"))
        assertTrue(tapAgain.duplicate); assertEquals(p, tapAgain.project)

        // Route A: ordinary file/media stream. Route B: Browse -> Files document-provider stream (chunked, unknown length).
        val dirA = Files.createTempDirectory("gd-a").toFile(); val dirB = Files.createTempDirectory("gd-b").toFile()
        val a = AttachmentIngest.ingest("studio_splash", "Hot_Attic_Games_Master_Logo_ALPHA_FINAL.png", { FileInputStream(logo) }, dirA, 1L) as IngestResult.Ok
        val b = AttachmentIngest.ingest("studio_splash", "Hot_Attic_Games_Master_Logo_ALPHA_FINAL.png", { DocumentProviderStream(logoBytes) }, dirB, 2L) as IngestResult.Ok
        assertEquals(canonical, a.asset.sha256); assertEquals(canonical, b.asset.sha256)
        assertEquals(a.asset.width, b.asset.width); assertTrue(a.asset.width > 0 && a.asset.height > 0)
        assertTrue(b.file.exists() && b.file.readBytes().contentEquals(logoBytes), "original bytes must be copied untouched into app storage")

        val cardsBefore = p.messages.count { it.question?.fieldKey == Keys.BRAND_STUDIO }
        val att = d.attachmentReceived(p, settings, "studio_splash", b.asset)
        p = att.project
        assertFalse(att.duplicate)
        assertEquals("upload", p.value(Keys.BRAND_STUDIO))
        assertEquals(BrandingMode.UPLOADED, p.branding["studio_splash"]!!.mode)
        assertEquals(canonical, p.branding["studio_splash"]!!.sha256)
        assertTrue(p.pendingFieldKey != Keys.BRAND_STUDIO, "question must advance exactly once")
        assertEquals(cardsBefore, p.messages.count { it.question?.fieldKey == Keys.BRAND_STUDIO }, "the studio-logo question must never be repeated after the file arrived")

        // Duplicate result callback, before and after a restart: no second advance, no state change.
        val dup = d.attachmentReceived(p, settings, "studio_splash", b.asset)
        assertTrue(dup.duplicate); assertEquals(p, dup.project)
        val restarted = ProjectCodec.decode(ProjectCodec.encode(p))
        assertEquals(p, restarted)
        assertTrue(d.attachmentReceived(restarted, settings, "studio_splash", a.asset).duplicate, "same bytes after restart are still a duplicate")
        assertTrue(File(dirB, b.asset.localFile!!.substringAfterLast('/')).exists(), "the master survives in project-owned storage")

        // Exactly-once for a text turn.
        val t1 = d.handleUserMessage(restarted, settings, script(restarted.pendingFieldKey), turnId = "turn-1")
        val t2 = d.handleUserMessage(t1.project, settings, script(restarted.pendingFieldKey), turnId = "turn-1")
        assertTrue(t2.duplicate); assertEquals(t1.project, t2.project)

        // The rest of the interview, answered like the owner did.
        val (done, generated) = drive(d, t1.project, { false })
        assertTrue(generated)
        p = done
        assertTrue(ReviewGate.approved(p))
        val noRepeats = p.messages.filter { it.role == Role.DIRECTOR }.zipWithNext().none { (x, y) -> x.text == y.text }
        assertTrue(noRepeats, "consecutive identical Bob messages")
        assertEquals(cardsBefore, p.messages.count { it.question?.fieldKey == Keys.BRAND_STUDIO })
        assertEquals(1, p.messages.count { it.question?.fieldKey == Keys.DONE }, "the done question is asked once")

        val gen = SpecVersioning.generate(p, VersionKind.INITIAL, 1_700_000_900_000L, "2026-10-05")
        assertFalse(gen.blocked, gen.review.findings.joinToString("\n") { it.message + " :: " + it.line })
        val v = gen.project.versions.single()
        val md = v.claudeMd; val prompt = v.masterPrompt
        val assetsMd = ExportPackage.assetsMarkdown(gen.project)
        // Prototype stays prototype.
        assertTrue("FULLY FUNCTIONAL PROTOTYPE" in md.uppercase() && "PROTOTYPE" in prompt.uppercase())
        for (bad in listOf("complete game", "not a prototype", "complete, genuinely playable")) { assertFalse(bad in md.lowercase(), "claude.md: $bad"); assertFalse(bad in prompt.lowercase(), "prompt: $bad") }
        // CC0 stays CC0.
        assertEquals("cc0_default", gen.project.value(Keys.ASSET_POLICY))
        assertFalse(md.contains("Only original/procedural assets") || prompt.contains("Only original/procedural assets"))
        // Continuous shaft stays continuous; no invented level structure.
        assertEquals("vertical_shaft", gen.project.value(Keys.WORLD_STRUCTURE))
        for (bad in listOf("unlock the next level", "hand-designed levels", "level select and progress save", "hand-built level set")) assertFalse(bad in md.lowercase(), "invented level structure: $bad")
        assertTrue("one continuous vertical" in md.lowercase() && "continuous" in prompt.lowercase())
        // Meta-conversation never reaches the documents.
        for (doc in listOf(md, prompt)) assertFalse(doc.contains("twice") && doc.contains("already added"), "meta remark leaked")
        // The owner-supplied logo outranks generation everywhere.
        assertTrue(md.contains("OWNER-SUPPLIED") && md.contains("Hot_Attic_Games_Master_Logo_ALPHA_FINAL.png"))
        assertFalse(Regex("(?i)studio[^\\n]{0,60}create an original").containsMatchIn(md))
        assertTrue(assetsMd.contains(canonical) && assetsMd.contains("branding/master/"))
        // The exported ZIP carries the untouched master.
        val files = ExportPackage.files(gen.project, v)
        val masters = ExportPackage.binaryFiles(gen.project) { asset -> File(dirB, asset.localFile!!.substringAfterLast('/')).readBytes() }
        val zip = ExportPackage.zip(files, masters)
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z -> var e = z.nextEntry; while (e != null) { entries[e.name] = z.readBytes(); e = z.nextEntry } }
        val masterEntry = entries.entries.single { it.key.startsWith("branding/master/") }
        assertEquals(canonical, AttachmentIngest.sha256(masterEntry.value))
        assertTrue(entries.keys.containsAll(listOf("CLAUDE.md", "MASTER_PROMPT.md", "ASSETS.md", "game-designer/project.json")))
        java.io.File("build/sample").also { it.mkdirs() }.let { dir ->
            dir.resolve("either-character-CLAUDE.md").writeText(md); dir.resolve("either-character-MASTER_PROMPT.md").writeText(prompt); dir.resolve("either-character-ASSETS.md").writeText(assetsMd)
            dir.resolve("either-character-conversation.txt").writeText(p.messages.joinToString("\n\n") { "[${it.role}] ${it.text}" })
        }
    }

    @Test fun ingestFailuresAreReportedNotSwallowed() {
        val dir = Files.createTempDirectory("gd-f").toFile()
        fun fail(open: () -> InputStream?) = AttachmentIngest.ingest("icon", "x.png", open, dir, 1L)
        assertEquals(IngestFailure.CANNOT_READ, (fail { null } as IngestResult.Failed).kind)
        assertEquals(IngestFailure.PERMISSION, (fail { throw SecurityException("denied") } as IngestResult.Failed).kind)
        assertEquals(IngestFailure.CANNOT_READ, (fail { object : InputStream() { override fun read(): Int = throw IOException("pipe broke") } } as IngestResult.Failed).kind)
        assertEquals(IngestFailure.EMPTY, (fail { ByteArrayInputStream(ByteArray(0)) } as IngestResult.Failed).kind)
        assertEquals(IngestFailure.UNSUPPORTED, (fail { ByteArrayInputStream("GIF89a....".toByteArray()) } as IngestResult.Failed).kind)
        val png = logo.readBytes()
        assertEquals(IngestFailure.CORRUPT, (fail { ByteArrayInputStream(png.copyOf(20)) } as IngestResult.Failed).kind)
        assertEquals(IngestFailure.UNSUPPORTED, (fail { ByteArrayInputStream("not an image at all".toByteArray()) } as IngestResult.Failed).kind)
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") || it.length() == 0L }, "failed ingests leave nothing behind")
        // A failure never touches project state: the Director is only told about successes.
        assertTrue((fail { ByteArrayInputStream(png) } as IngestResult.Ok).asset.sha256 == canonical)
    }

    @Test fun overwritingTheSameSlotKeepsOneMasterAndSurvivesRestart() = runBlocking {
        val d = director()
        var p = d.handleUserMessage(d.start(newProject(), settings), settings, concept).project
        p = drive(d, p, { it.pendingFieldKey == Keys.BRAND_STUDIO }).first
        p = d.submitSelection(p, settings, Keys.BRAND_STUDIO, listOf("upload")).project
        val dir = Files.createTempDirectory("gd-c").toFile()
        val ok = AttachmentIngest.ingest("studio_splash", "logo.png", { FileInputStream(logo) }, dir, 5L) as IngestResult.Ok
        p = d.attachmentReceived(p, settings, "studio_splash", ok.asset).project
        val back = ProjectCodec.decode(ProjectCodec.encode(p))
        assertNotNull(back.branding["studio_splash"]); assertEquals(canonical, back.branding["studio_splash"]!!.sha256)
        assertEquals(1, dir.listFiles().orEmpty().count { it.name.startsWith("studio_splash_") })
    }
}
