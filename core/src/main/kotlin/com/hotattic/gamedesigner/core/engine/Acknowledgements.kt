package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Platforms
import com.hotattic.gamedesigner.core.schema.Traits

/**
 * What Bob says about a meaningful decision: what it changes next, in one or two sentences. It is a partner explaining a consequence, never praise, never a
 * claim that anything has been built. Only decisions that really change what comes next get one; most answers get none.
 */
object Acknowledgements {
    private fun plat(id: String) = Platforms.labels[id] ?: id

    fun forDecision(p: Project, key: String): String? {
        val v = p.value(key) ?: return null
        val t = Traits(p)
        return when (key) {
            Keys.PRIMARY_PLATFORM -> {
                val rest = (t.platforms - v).map { plat(it) }
                val how = when (v) {
                    Platforms.ANDROID -> "The first build will be an installable Android app (an APK) that you can try on your phone, the controls will be designed for touch first, and the engine has to export to Android well."
                    Platforms.IOS -> "iPhone and iPad builds need a Mac with Xcode and Apple signing, so those steps are yours to do, and the controls will be designed for touch first."
                    Platforms.WINDOWS -> "The first build will be a Windows program, with keyboard, mouse and gamepad designed first and the engine chosen to export to Windows well."
                    Platforms.STEAM_DECK -> "The first build will be designed controller-first for the Steam Deck's 1280x800 screen, with every menu usable without a mouse."
                    else -> "Early builds, controls and performance targets will be tuned for ${plat(v)} first."
                }
                "Starting with ${plat(v)}. $how" + if (rest.isNotEmpty()) " ${rest.joinToString(" and ")} stay${if (rest.size == 1) "s" else ""} supported: it is one game with a thin layer per platform, and the feel on ${plat(v)} is never traded away to suit another." else ""
            }
            Keys.PLATFORMS -> if (t.platforms.size > 1) "More than one platform means one shared game with a small layer for each device, and more testing. Next I'll ask which one to build and test first." else null
            Keys.DIMENSION -> when (v) {
                "3D" -> "3D narrows the engine choices and raises the art and testing needs, so I'll weigh that when I recommend an engine."
                "2D" -> "2D keeps art, controls and testing manageable, which also keeps more engines available."
                "2.5D" -> "2.5D gives a 3D look with simpler 2D play; it still needs 3D-capable tools."
                else -> null
            }
            Keys.MULTIPLAYER_INTENT -> when (v) {
                "single_player" -> "Single-player keeps the build simpler and cheaper. I'll still build it so multiplayer is possible later if you change your mind."
                "online", "both" -> "Online play shapes the whole architecture, so I'll ask how players connect before I recommend an engine."
                "local" -> "Local play means everyone is in the same room, so the next question is one shared screen or separate devices."
                "undecided" -> "I'll choose a reversible architecture and tell you what adding multiplayer later would cost."
                else -> null
            }
            Keys.OTA_UPDATES -> when (v) {
                "content_ota" -> "Updates after install affect the engine and how the game is built, so this is the time to decide exactly what must be updateable. Next: which parts."
                "none" -> "Every change will then ship as a new install, which is the simplest approach."
                else -> null
            }
            Keys.OTA_CONTENT -> if (UpdateStrategy.wantsCode(p)) "You want gameplay logic updated too. That needs the logic to live in scripts or data the game loads, which narrows the engine choice, and the app stores restrict it on some platforms. I'll tell you exactly where." else "Those are all content, which is data the game loads, so they can be replaced after install. Code changes would still need a new install."
            Keys.HAS_COMBAT -> if (v == "no") "Noted: no combat. I won't ask about enemies, weapons or health." else null
            Keys.BUDGET -> if (v == "free_only") "With no budget for paid services I'll stay on free engines and assets, and any online play would have to be hosted by a player rather than on paid servers." else null
            Keys.WORLD_STRUCTURE -> if (v == "procedural_stages") "Generated levels need an automatic check that every one can be completed, so I'll plan that into the testing." else null
            else -> null
        }
    }
}
