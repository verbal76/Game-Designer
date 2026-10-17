package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.schema.Platforms

enum class PlatformStepKind { ONLY, ADD, REMOVE, PRIMARY, KEEP }

data class PlatformStep(val kind: PlatformStepKind, val ids: List<String>)

/** [platforms] is the resulting target list (never empty when [valid]); [primary] the platform to build and test first, if one is chosen. */
data class PlatformOutcome(val platforms: List<String>, val primary: String?, val primaryRequested: String?, val valid: Boolean)

/**
 * An unmistakable instruction about platforms in the owner's own words ("forget Android, it is a PC game now", "add PC as the primary
 * platform", "make PC primary, keep Android secondary"). Deterministic and conservative: a plain mention ("players on Android will
 * love it") is never an instruction. [pure] is true when the message is nothing but such instructions, so it cannot also be the
 * answer to an open question.
 */
data class PlatformInstruction(val steps: List<PlatformStep>, val pure: Boolean) {

    /** Applies the steps in the order the owner said them, so the latest word wins. */
    fun applyTo(current: List<String>, currentPrimary: String?): PlatformOutcome {
        var set = LinkedHashSet(current)
        var primary = currentPrimary
        var requested: String? = null
        for (s in steps) when (s.kind) {
            PlatformStepKind.ONLY -> set = LinkedHashSet(s.ids)
            PlatformStepKind.ADD, PlatformStepKind.KEEP -> set.addAll(s.ids)
            PlatformStepKind.REMOVE -> { set.removeAll(s.ids.toSet()); if (primary in s.ids) primary = null; if (requested in s.ids) requested = null }
            PlatformStepKind.PRIMARY -> { set.add(s.ids.first()); primary = s.ids.first(); requested = primary }
        }
        if (primary != null && primary !in set) primary = null
        if (set.size < 2) { primary = null; requested = null }
        val ordered = Platforms.labels.keys.filter { it in set }
        return PlatformOutcome(ordered, primary, requested, valid = ordered.isNotEmpty())
    }
}

object PlatformInstructions {

    private val vocabulary = listOf(
        "steam deck" to Platforms.STEAM_DECK, "windows pc" to Platforms.WINDOWS, "web browser" to Platforms.WEB, "mac os" to Platforms.MAC, "google play" to Platforms.ANDROID,
        "app store" to Platforms.IOS, "android" to Platforms.ANDROID, "iphone" to Platforms.IOS, "ipad" to Platforms.IOS, "ios" to Platforms.IOS, "windows" to Platforms.WINDOWS,
        "desktop" to Platforms.WINDOWS, "pc" to Platforms.WINDOWS, "macos" to Platforms.MAC, "macbook" to Platforms.MAC, "mac" to Platforms.MAC, "linux" to Platforms.LINUX,
        "browser" to Platforms.WEB, "html5" to Platforms.WEB, "webgl" to Platforms.WEB, "web" to Platforms.WEB,
    )
    private val platformAlternatives = vocabulary.joinToString("|") { Regex.escape(it.first) }
    private val platformWord = Regex("\\b(?:$platformAlternatives)\\b")

    private val clauseSplit = Regex("[,;]|\\bbut\\b|\\bthen\\b|\\band(?=\\s+(?:keep|also|add|make|drop|forget|remove|leave|switch|skip)\\b)")
    private val sentenceSplit = Regex("(?<=[.!?])\\s+|\\n+")

    private val removeCue = Regex("\\b(?:forget|drop|remove|delete|scrap|skip|cut|ditch|ignore|without|no longer|not anymore|not any more|stop (?:targeting|supporting|building))\\b|\\b(?:don'?t|do not) (?:want|need|care about|target|support|build)\\b")
    private val notBefore = Regex("(?:^|\\s)(?:not|no|nor)\\s+(?:for\\s+|on\\s+)?$")
    private val strongPrimary = Regex("\\b(?:primary|main|lead|priority|number one)\\b")
    private val firstWord = Regex("\\bfirst\\b(?![- ](?:person|time|level|build|playable|slice|prototype|release|minute|hour|day))")
    private val firstContext = Regex("\\b(?:built|build|tested|test|version|platform|develop(?:ed)?|release[d]?|ship(?:ped)?|launch(?:ed)?|start(?:ed)?|port(?:ed)?|make|do)\\b")
    private val secondaryCue = Regex("\\b(?:secondary|second|later|afterwards?|eventually)\\b")
    // "only" and "just" count only right next to a platform, so "only touch controls, for Android" is not a platform instruction.
    private val replaceCue = Regex("\\b(?:switch|change|move|go|convert|turn|swap|flip)\\w*\\b[^.]{0,20}\\bto\\b|\\b(?:only|just)\\s+(?:on\\s+|for\\s+)?(?:$platformAlternatives)\\b|\\b(?:$platformAlternatives)\\s+only\\b|\\bexclusively\\b|\\bit(?:'s| is)\\b[^.]*\\bnow\\b")
    private val addCue = Regex("\\b(?:add|include|also|plus|as well|too|support|target|bring (?:it )?to|port (?:it )?to|release (?:it )?(?:on|for))\\b")
    private val instead = Regex("^(.*)\\binstead of\\b(.*)$")

    private data class Mention(val id: String, val start: Int)

    private fun mentions(c: String) = platformWord.findAll(c).map { m -> Mention(vocabulary.first { it.first == m.value }.second, m.range.first) }.toList()

    private fun ids(ms: List<Mention>) = ms.map { it.id }.distinct()

    private fun clauseSteps(raw: String): List<PlatformStep> {
        val c = raw.lowercase().replace("’", "'").trim()
        val ms = mentions(c)
        if (ms.isEmpty()) return emptyList()
        instead.find(c)?.let { m ->
            val before = ids(mentions(m.groupValues[1])); val after = ids(mentions(m.groupValues[2]))
            if (before.isNotEmpty() && after.isNotEmpty()) return listOf(PlatformStep(PlatformStepKind.ONLY, before), PlatformStep(PlatformStepKind.REMOVE, after))
        }
        val remove = removeCue.find(c)
        if (remove != null) {
            val after = ms.filter { it.start >= remove.range.last }
            return listOf(PlatformStep(PlatformStepKind.REMOVE, ids(after.ifEmpty { ms })))
        }
        // "not iPhone": a bare negation directly before the platform.
        val negated = ms.filter { notBefore.containsMatchIn(c.substring(0, it.start)) }
        if (negated.isNotEmpty()) return listOf(PlatformStep(PlatformStepKind.REMOVE, ids(negated)))

        val secondary = secondaryCue.containsMatchIn(c)
        val primaryMatch = strongPrimary.find(c) ?: firstWord.find(c)?.takeIf { firstContext.containsMatchIn(c) }
        if (primaryMatch != null && !secondary) {
            val pick = ms.minByOrNull { minOf(kotlin.math.abs(it.start - primaryMatch.range.first), kotlin.math.abs(it.start - primaryMatch.range.last)) }!!
            return listOf(PlatformStep(PlatformStepKind.PRIMARY, listOf(pick.id)))
        }
        if (secondary) return listOf(PlatformStep(PlatformStepKind.KEEP, ids(ms)))
        if (replaceCue.containsMatchIn(c)) return listOf(PlatformStep(PlatformStepKind.ONLY, ids(ms)))
        if (addCue.containsMatchIn(c)) return listOf(PlatformStep(PlatformStepKind.ADD, ids(ms)))
        return emptyList()
    }

    /** Null when the text contains no platform instruction. */
    fun parse(text: String): PlatformInstruction? {
        val sentences = text.split(sentenceSplit).map { it.trim() }.filter { it.isNotEmpty() }
        val steps = mutableListOf<PlatformStep>()
        var pure = text.trim().split(Regex("\\s+")).size <= 30
        for (s in sentences) {
            val found = s.split(clauseSplit).flatMap { clauseSteps(it) }
            steps += found
            if (found.isEmpty() && s.split(Regex("\\s+")).size > 6) pure = false
        }
        if (steps.isEmpty()) return null
        return PlatformInstruction(steps, pure)
    }
}
