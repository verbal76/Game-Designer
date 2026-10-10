package com.hotattic.gamedesigner.core.engine

/**
 * Game Designer is a launch tool. Its generated CLAUDE.md carries an explicit lifecycle marker so that, in any fresh conversation and
 * without Game Designer present, an assistant can tell whether the prototype-era scope controls still apply or the owner now directs
 * the game. The marker is the single source of truth; prototype-only text is tagged so it can be recognised and expired mechanically.
 */
object PrototypeLifecycle {
    enum class Phase { PROTOTYPE, OWNER_DIRECTED }

    const val MARKER_PREFIX = "<!-- GAME-DESIGNER-LIFECYCLE:"
    const val PROTOTYPE_ONLY = "[prototype-only]"
    private val markerRx = Regex("<!-- GAME-DESIGNER-LIFECYCLE: (PROTOTYPE|OWNER_DIRECTED) -->")

    fun marker(phase: Phase) = "$MARKER_PREFIX ${phase.name} -->"

    fun phase(claudeMd: String): Phase? = markerRx.find(claudeMd)?.groupValues?.get(1)?.let { Phase.valueOf(it) }

    /** Everything tagged prototype-only that still binds. After handoff the answer is always empty. */
    fun restrictionsInForce(claudeMd: String): List<String> =
        if (phase(claudeMd) == Phase.PROTOTYPE) claudeMd.lines().filter { PROTOTYPE_ONLY in it }.map { it.trim().removePrefix("- ") } else emptyList()

    /** The documented handoff, as a pure transformation: flip the marker and record it. (Claude performs the same edit by hand.) */
    fun handoff(claudeMd: String, dateIso: String, trigger: String): String {
        if (phase(claudeMd) != Phase.PROTOTYPE) return claudeMd
        val flipped = markerRx.replace(claudeMd, marker(Phase.OWNER_DIRECTED))
        return flipped.trimEnd() + "\n\n## Handoff record\n\n- $dateIso: prototype handed off to the owner ($trigger). Prototype scope controls, designer assumptions and the prototype master prompt no longer govern; the owner's current instructions do.\n"
    }

    /** Section 0a, rendered into CLAUDE.md. Concise and operational. */
    fun section(): List<String> = listOf(
        "Game Designer is a temporary design-and-prototyping tool. It generated this file and the master prompt to get a **playable proof of concept** built. After handoff it has no role, and it does not need to be present.",
        "**Phase** is the marker at the top of this file: `PROTOTYPE` means the prototype is being built; `OWNER_DIRECTED` means the owner now decides everything about the game.",
        "**Handoff happens when ANY of these is true:** the owner accepts the prototype; the owner says they are starting production or post-prototype work; or, after a playable prototype has been delivered, the owner gives an explicit instruction that goes beyond the prototype scope (new mechanics, enemies, worlds, characters, platforms, visuals, architecture changes).",
        "**On handoff, without asking and without Game Designer:** change the marker to `OWNER_DIRECTED`, append a dated entry to `docs/HANDOFF.md` (trigger, what was delivered), and carry on with the owner's request.",
        "**What expires at handoff:** every item tagged `$PROTOTYPE_ONLY`, the prototype scope controls, all designer recommendations and provisional assumptions, and the master prompt's prototype workflow. The original specification becomes historical reference. Never cite an expired limitation to obstruct something the owner has asked for.",
        "**What survives:** constraints the owner set themselves (until they change them), technical documentation (architecture, build, update system, asset provenance), and genuine external constraints (security, licensing, platform and store rules, saved-data compatibility).",
        "**Before handoff:** an explicit owner instruction still outranks any scope control here; a scope control means 'not required for this prototype', never 'forbidden'. A question from the owner is a question: answer it, and do not implement until they instruct.",
        "**New conversation?** Read the marker. `PROTOTYPE`: continue or finish the prototype from this file. `OWNER_DIRECTED`: follow the owner's current instructions and use this file as project context only.",
    )
}
