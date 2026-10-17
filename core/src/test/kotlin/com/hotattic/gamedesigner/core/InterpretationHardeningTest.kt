package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.director.Director
import com.hotattic.gamedesigner.core.director.DirectorAction
import com.hotattic.gamedesigner.core.engine.DecisionExtractor
import com.hotattic.gamedesigner.core.generate.ClaudeMdGenerator
import com.hotattic.gamedesigner.core.generate.SpecVersioning
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.VersionKind
import com.hotattic.gamedesigner.core.schema.Gates
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Platforms
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Interpretation and decision-integrity regressions found by the core-logic audit (docs/CORE_LOGIC_AUDIT.md).
 * Evidence: AUTOMATED on synthetic projects, rules only, no model, no network.
 */
class InterpretationHardeningTest {
    private val songs = "I want a pixel art RPG. I like the population simulation in Songs of Syx, but I don't want its combat system. Android is my primary platform, and I might bring it to Windows later."

    private fun ans(k: String?) = when {
        k == "__proposals__" -> "yes"; k == "__asset_plan__" -> "looks good"; k == "__review__" -> "looks right"
        k?.startsWith("__conflict") == true -> "use alternative 1"; k == "__ready__" -> "generate"
        k == Keys.FIVE_MINUTES || k == Keys.CORE_FANTASY -> "Playing it should feel calm and rewarding."
        else -> "choose for me"
    }

    private suspend fun first(text: String, d: Director = director()): Pair<Director, Project> = d to d.handleUserMessage(d.start(newProject(), settings), settings, text).project

    private suspend fun toGeneration(d: Director, p0: Project): Project {
        var p = p0
        repeat(160) { val t = d.handleUserMessage(p, settings, ans(p.pendingFieldKey)); p = t.project; if (t.action == DirectorAction.GenerateSpec) return p }
        error("no generation; pending=${p.pendingFieldKey}")
    }

    private fun spec(p: Project): String {
        val g = SpecVersioning.generate(p, VersionKind.INITIAL, p.updatedAt + 1, "2026-10-17T00:00:00Z")
        assertFalse(g.blocked, g.review.findings.joinToString { "${it.code}: ${it.message}" })
        return g.project.versions.last().claudeMd
    }

    // ---- exclusions are understood (P0) ---------------------------------------------------------------------------

    @Test fun deniedSystemsAreRecognisedWhateverWordsSitBetweenTheNegationAndTheSystem() {
        val combat = Gates.byKey.getValue(Keys.HAS_COMBAT)
        for (t in listOf(
            "I don't want its combat system.", "No combat.", "I don't want any combat in it.", "I like the economy in Dwarf Fortress but not the combat.",
            "Without any fighting at all.", "Skip the combat, please.", "Leave combat out.", "It should have no enemies.", "I'd rather not have battles in this.",
            "Combat isn't something I want.", "There will be no violence.", "Cut the combat entirely.",
        )) assertTrue(combat in Gates.deniedIn(t), "should deny combat: \"$t\"")
    }

    @Test fun mentioningASystemIsNotAskingForIt() {
        val combat = Gates.byKey.getValue(Keys.HAS_COMBAT)
        for (t in listOf(
            "I don't want its combat system.", "What would combat look like in this?", "I like the population simulation in Songs of Syx, but I don't want its combat system.",
            "Should it have enemies?", "No combat and no enemies.", "I like the economy in Dwarf Fortress but not the combat.",
            "I love how Songs of Syx handles its combat, but that is not for my game.",
        )) assertFalse(combat in Gates.affirmedIn(t), "must not affirm combat: \"$t\"")
        for (t in listOf("I want enemies and boss fights.", "It needs combat with swords.", "Players fight monsters in the dungeon."))
            assertTrue(combat in Gates.affirmedIn(t) && combat !in Gates.deniedIn(t), "should affirm combat: \"$t\"")
    }

    @Test fun anExcludedReferenceSystemBecomesAnOwnerDecisionNotAFact() = runBlocking {
        val (_, p) = first(songs)
        val d = p.decision(Keys.HAS_COMBAT)
        assertNotNull(d, "the exclusion is recorded as a decision")
        assertEquals("no", d.value)
        assertTrue(d.prov.ownerAuthored)
        assertTrue(p.decision(Keys.ENEMIES_BOSSES) == null && p.decision(Keys.COMBAT_MODEL) == null, "no combat detail is invented")
    }

    @Test fun theGeneratedSpecDoesNotReintroduceWhatTheOwnerExcluded() = runBlocking {
        val (d, p0) = first(songs)
        val md = spec(toGeneration(d, p0))
        assertFalse(Regex("(?i)\\*\\*Enemies and bosses").containsMatchIn(md), "no enemies requirement")
        assertFalse(Regex("(?i)fight enemies|defeat (the )?boss|bosses: \\*\\*|enemy archetypes").containsMatchIn(md), "no combat requirements: " + md.lines().filter { Regex("(?i)fight enemies|boss|enemy archetypes").containsMatchIn(it) }.take(3))
        assertFalse(md.lineSequence().first().contains("Combat", true), "the title is not taken from the exclusion: ${md.lineSequence().first()}")
        assertTrue(Regex("(?i)(exclu|not (included|wanted)|ruled out|no combat)").containsMatchIn(md) && md.contains("Combat", true), "the exclusion is stated in the spec")
    }

    // ---- references are inspiration, not requirements -------------------------------------------------------------------

    @Test fun aGenreNameIsNeverAReferenceGame() {
        assertEquals(listOf("Songs of Syx"), DecisionExtractor.referenceGames(songs))
        for (t in listOf("I want a pixel art RPG.", "A Roguelike with cards.", "An RPG with a Tower Defense twin.", "It is a City Builder.", "A Platformer for kids."))
            assertEquals(emptyList(), DecisionExtractor.referenceGames(t), "\"$t\"")
    }

    @Test fun referenceAspectsAreTheOwnersWordsAndExclusionsStay() = runBlocking {
        val (_, p) = first(songs)
        val ref = p.references.single { it.name == "Songs of Syx" }
        assertTrue(ref.aspects.any { it.contains("population simulation", true) }, "owner's named aspect: ${ref.aspects}")
        assertTrue(ref.excluded.any { it.contains("combat", true) }, "owner's rejected characteristic: ${ref.excluded}")
        assertFalse(ref.aspects.any { it.contains("combat", true) })
    }

    @Test fun anUnspecifiedReferenceInheritsNothing() = runBlocking {
        val (d, p0) = first("A cozy puzzle game inspired by Monument Valley.")
        val md = spec(toGeneration(d, p0))
        assertFalse(md.contains("core loop structure, pacing and the way build choices compound"), "no invented inherited aspects")
        assertTrue(Regex("(?i)(inspiration only|nothing (specific )?is (inherited|copied)|no specific)").containsMatchIn(md), "states that nothing specific is inherited")
    }

    // ---- platforms: confirmed versus possible later -------------------------------------------------------------------

    @Test fun aPlatformMentionedAsPossibleLaterIsNotACommitment() {
        val ex = DecisionExtractor.extract("Android is my primary platform, and I might bring it to Windows later.")
        assertEquals("android", ex.values[Keys.PLATFORMS])
        for (t in listOf("Maybe iPhone one day.", "Windows eventually, if it goes well.", "I hope to do Linux someday.", "Possibly Steam Deck in the future."))
            assertTrue(DecisionExtractor.extract(t).values[Keys.PLATFORMS] == null, "\"$t\" is not a platform decision")
        assertEquals("windows", DecisionExtractor.extract("It will launch on Android and Windows.").values[Keys.PLATFORMS]?.split("|")?.firstOrNull { it == "windows" })
    }

    @Test fun theFirstMessageKeepsItsPlatformAndPrimaryInsteadOfDroppingThem() = runBlocking {
        val (_, p) = first(songs)
        assertEquals("android", p.value(Keys.PLATFORMS), "platforms from the first message: ${p.decisions[Keys.PLATFORMS]}")
        val (_, q) = first("A 2D game for Android and iPhone. Android is my primary platform.")
        assertEquals(setOf(Platforms.ANDROID, Platforms.IOS), q.list(Keys.PLATFORMS).toSet())
        assertEquals(Platforms.ANDROID, q.value(Keys.PRIMARY_PLATFORM))
    }

    @Test fun aLaterPlatformIsRecordedAsPossibleNotAsATarget() = runBlocking {
        val (d, p0) = first(songs)
        val md = spec(toGeneration(d, p0))
        assertFalse(Regex("(?i)target platforms?:[^\\n]*windows").containsMatchIn(md), "Windows is not a target platform")
        assertTrue(Regex("(?i)(possible|later|future)[^\\n]*windows|windows[^\\n]*(possible|later|future)").containsMatchIn(md), "the owner's possible-later note is kept")
    }

    @Test fun theTitleAndCoreFantasyDoNotComeFromAnExclusionSentence() = runBlocking {
        val (_, p) = first(songs)
        val fantasy = p.value(Keys.CORE_FANTASY).orEmpty()
        assertFalse(fantasy.contains("don't want", true) || fantasy.contains("do not want", true), "core fantasy: $fantasy")
        assertFalse((p.value(Keys.PACKAGE_ID).orEmpty() + p.value(Keys.DISPLAY_NAME).orEmpty()).contains("combat", true))
    }
}
