package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.EngineCatalog
import com.hotattic.gamedesigner.core.schema.EngineRecommender
import com.hotattic.gamedesigner.core.schema.Platforms
import com.hotattic.gamedesigner.core.schema.Traits

/** Whether gameplay LOGIC can be delivered after install on a platform/channel. Assets, audio and data can nearly always be. */
enum class CodeUpdates(val label: String) {
    FULL("gameplay code can ship in signed update bundles"),
    INTERPRETED_ONLY("gameplay logic only as data/scripts run by an interpreter or VM already in the app; no downloaded native or executable code"),
    STORE_MANAGED("the store delivers the update"),
    DEPLOY("an update is simply a new deployment of the static files"),
}

/** [capability] is what is technically possible on the platform; [policy] is what the distribution channel allows. They are kept apart so a policy limit is never mistaken for a technical one. */
data class PlatformUpdate(val platform: String, val channel: String, val mechanism: String, val code: CodeUpdates, val capability: String = "", val policy: String = "", val channelAssumed: Boolean = false)

/**
 * OTA is a functional requirement first (what must be updateable, how automatic), and an implementation second. The implementation is
 * derived per platform and distribution channel from those requirements; the Mote architecture is the reference where it fits. Store
 * statements are conservative summaries the builder must re-verify against the current policies.
 */
object UpdateStrategy {
    private val labels = mapOf(
        "gameplay_code" to "gameplay fixes and new gameplay logic", "assets" to "art, models and other visual assets", "audio" to "audio and music",
        "data" to "text, levels and tuning data", "features" to "whole new features and content",
    )

    fun wanted(p: Project): Boolean = PlatformPolicy.otaState(p) == SystemState.PRESENT
    fun content(p: Project): List<String> = p.list(Keys.OTA_CONTENT)
    fun contentLabels(p: Project): String = content(p).map { labels[it] ?: it }.joinToString(", ")
    val wantsCode: (Project) -> Boolean = { content(it).any { c -> c == "gameplay_code" || c == "features" } }

    fun behavior(p: Project): String = when (p.value(Keys.OTA_BEHAVIOR)) {
        "next_launch" -> "Automatic: check at launch, download in the background, apply the next time the game starts."
        "ask" -> "Notify the player that an update is ready and let them choose when to apply it."
        else -> "Fully automatic: check at launch, download in the background (never interrupting active play), apply at a safe moment such as a menu or session boundary, and restart automatically when needed. Active sessions and saves are protected; the player sees only a brief status."
    }

    fun perPlatform(p: Project): List<PlatformUpdate> {
        val t = Traits(p)
        val chosen = p.value(Keys.STORE_PLAN)
        val channel = chosen ?: "personal_sideload"
        val assumed = chosen == null || p.decision(Keys.STORE_PLAN)?.prov.let { it == com.hotattic.gamedesigner.core.model.Provenance.DEFAULT || it == com.hotattic.gamedesigner.core.model.Provenance.SYSTEM_INFERENCE }
        val play = channel in setOf("play_store", "play_internal_testing")
        return t.platforms.map { pl ->
            when (pl) {
                Platforms.ANDROID -> if (play) PlatformUpdate(pl, "Google Play", "Signed content/script bundles for assets, audio, data and interpreted gameplay logic; native changes ship through Play releases.", CodeUpdates.INTERPRETED_ONLY,
                        "Technically, Android can load updated application code.", "Google Play policy does not allow an app to download executable code from outside Play (re-verify the current policy).", assumed)
                    else PlatformUpdate(pl, "sideload / own distribution", "Native shell plus a signed, compatibility-checked, updatable application layer (the Mote model); gameplay code can be updated this way.", CodeUpdates.FULL,
                        "Android can load an updated, signed application layer.", "No store policy applies to sideloaded or self-distributed builds. Choosing Google Play later would restrict downloaded code.", assumed)
                Platforms.IOS -> PlatformUpdate(pl, if (channel == "app_store") "Apple App Store" else "iOS distribution", "Signed content bundles for assets, audio and data; gameplay logic only as interpreted data/scripts within Apple's guidelines; native changes ship as new builds.", CodeUpdates.INTERPRETED_ONLY,
                    "Technically, an app can run downloaded scripts or data through an interpreter or VM that ships inside the app.", "Apple's App Store rules limit downloaded executable code (re-verify the current guidelines); downloaded native code is not allowed.", assumed && channel == "personal_sideload")
                Platforms.WINDOWS, Platforms.MAC, Platforms.LINUX ->
                    if (channel == "steam_or_pc_store") PlatformUpdate(pl, "Steam / PC store", "The store delivers updates; build content so it can be patched, and do not duplicate the store's updater.", CodeUpdates.STORE_MANAGED,
                        "Desktop apps can replace their own files.", "The store's updater owns delivery; follow its depot and patching rules.")
                    else PlatformUpdate(pl, "standalone distribution", "A signed self-updater with the same compatibility, staging and rollback rules; gameplay code can ship in update packages.", CodeUpdates.FULL,
                        "Desktop apps can replace their own files and restart.", "No store policy applies to standalone distribution.", assumed)
                else -> PlatformUpdate(pl, "web", "Updates are new deployments of versioned static files; no separate OTA machinery.", CodeUpdates.DEPLOY,
                    "A web build is redeployed.", "No store policy.")
            }
        }
    }

    /** Requested content that a selected platform/channel cannot deliver as asked. Never downgraded silently: the spec and the review state each one. */
    fun unmet(p: Project): List<String> {
        if (!wanted(p) || !wantsCode(p)) return emptyList()
        val asked = content(p).filter { it == "gameplay_code" || it == "features" }.map { labels[it] ?: it }.joinToString(" and ")
        return perPlatform(p).filter { it.code == CodeUpdates.INTERPRETED_ONLY }.map {
            "${Platforms.labels[it.platform] ?: it.platform} (${it.channel}): updating $asked as downloaded native code cannot be satisfied there. ${it.policy} What can be delivered: data and interpreted scripts the app already knows how to run; anything needing new native code ships as a new build."
        }
    }

    /** Meaningful limits the owner should hear before the package is generated. */
    fun limitations(p: Project): List<String> = unmet(p) + perPlatform(p).filter { wanted(p) && it.channelAssumed }.map {
        "No distribution channel has been chosen for ${Platforms.labels[it.platform] ?: it.platform}; I assumed ${it.channel}. A different channel could change what can be updated."
    }

    /** Changes that always need a newly installed package. */
    fun requiresNewPackage(p: Project): List<String> = buildList {
        add("native, runtime or toolchain changes (engine upgrade, new native plugins, new permissions, target SDK or OS-requirement bumps)")
        add("anything that changes the runtime compatibility identity/fingerprint")
        perPlatform(p).filter { it.code == CodeUpdates.INTERPRETED_ONLY }.forEach { add("downloaded native/executable code on ${Platforms.labels[it.platform] ?: it.platform} (not allowed there)") }
    }
}

/** Platform facts the owner should know before a package is generated. */
object PlatformCompat {
    fun notes(p: Project): List<String> {
        val t = Traits(p)
        val out = mutableListOf<String>()
        val primary = t.primaryPlatform
        if (t.platforms.size > 1 && primary != null) out += "${Platforms.labels[primary] ?: primary} is built and tested first; the other selected platforms (${(t.platforms - primary).joinToString { Platforms.labels[it] ?: it }}) remain design targets but are not required to be finished in the prototype unless you ask."
        if (Platforms.IOS in t.platforms) out += "iPhone/iPad builds need a Mac with Xcode and Apple signing (a human step), so they cannot be produced or tested in a Claude cloud workspace alone."
        if (Platforms.WINDOWS in t.platforms && primary != Platforms.WINDOWS) out += "Windows builds are produced on a Windows machine or a Windows CI runner."
        out += UpdateStrategy.limitations(p)
        return out
    }
}

/** Requirements-driven technology reasoning, rendered into the spec: what was considered, what was recommended, what was runner-up. */
object StackAdvisor {
    fun considered(p: Project): List<String> {
        val t = Traits(p)
        val out = mutableListOf<String>()
        out += "Platforms: ${t.platforms.joinToString { Platforms.labels[it] ?: it }.ifEmpty { "not set" }}${t.primaryPlatform?.let { "; built and tested first: ${Platforms.labels[it] ?: it}" } ?: ""}."
        out += "Presentation: ${t.dimension ?: "n/a"}${p.value(Keys.ART_DIRECTION)?.let { ", art direction $it" } ?: ""}${if (t.engineDimension != t.dimension) " (3D assets, so a 3D-capable engine)" else ""}."
        out += "Multiplayer: ${t.multiplayer?.replace('_', ' ') ?: "not set"}${p.value(Keys.MULTIPLAYER_TIMING)?.let { ", $it" } ?: ""}. Network: ${p.value(Keys.NETWORK_POLICY)?.replace('_', ' ') ?: "n/a"}."
        if (UpdateStrategy.wanted(p)) out += "Updates after install: ${UpdateStrategy.contentLabels(p).ifBlank { "unspecified" }}; distribution ${p.value(Keys.STORE_PLAN)?.replace('_', ' ') ?: "n/a"}."
        return out
    }

    fun ranked(p: Project) = Traits(p).let { t -> EngineRecommender.rank(t.platforms, t.engineDimension, t.complexity, t.beginner, t.tags) }

    /** Concise comparison of the chosen engine and the closest alternative that also meets every platform and dimension requirement, with a concrete tradeoff. */
    fun comparison(p: Project): List<String> {
        val r = ranked(p)
        if (r.size < 2) return emptyList()
        val chosen = Traits(p).engine?.id
        val top = r.firstOrNull { it.engine.id == chosen } ?: r.first()
        val other = r.first { it.engine.id != top.engine.id }
        val why = (other.notes + listOfNotNull(if (Traits(p).engineDimension == "3D" && other.engine.capability < top.engine.capability) "Its 3D capability is lower than ${top.engine.name}'s." else null) + "Known caveats: ${other.engine.caveats}").joinToString(" ")
        return listOf("Chosen: ${top.engine.name} (${top.engine.strengths})",
            "Closest viable alternative: ${other.engine.name} (${other.engine.strengths}) It also reaches every selected platform. Not chosen because it fits this project's requirements less well: $why")
    }

    /** Genuine incompatibilities between the CHOSEN engine and the selected requirements. */
    fun incompatibilities(p: Project): List<String> {
        val t = Traits(p)
        val e = t.engine ?: return emptyList()
        val out = mutableListOf<String>()
        for (pl in t.platforms) if (!EngineCatalog.supportsWrapped(e, pl)) out += "${e.name} cannot target ${Platforms.labels[pl] ?: pl}."
        if (t.engineDimension == "3D" && !e.supports3D) out += "${e.name} does not support the required 3D presentation."
        return out
    }
}
