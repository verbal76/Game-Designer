package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Platforms
import com.hotattic.gamedesigner.core.schema.Traits

/**
 * Permanent Hot Attic Games platform policies. They are not interview questions about the game: they are standing rules that every
 * generated package carries when they apply.
 *
 * - Android selected => Target API 36 is a required implementation constraint (stored as the derived `android_target_api` decision).
 * - OTA is an explicit decision (PRESENT / ABSENT / UNKNOWN). PRESENT selects the Hot Attic Games Mote OTA architecture as the reference.
 */
object PlatformPolicy {
    const val ANDROID_TARGET_API = 36
    const val MOTE = "Mote"

    fun androidSelected(p: Project) = Platforms.ANDROID in Traits(p).platforms

    fun otaState(p: Project): SystemState = DesignModel.of(p).state(SystemId.OTA_UPDATES)

    /** True when OTA applies to this project's platforms but nobody has decided yet: a gap that blocks completion. */
    fun otaUnresolved(p: Project): Boolean {
        val t = Traits(p)
        val relevant = com.hotattic.gamedesigner.core.schema.Fields.get(Keys.OTA_UPDATES)?.isRelevant(t) == true
        return relevant && p.decision(Keys.OTA_UPDATES)?.value.isNullOrBlank()
    }

    val androidHeadline = "Android Target API $ANDROID_TARGET_API is REQUIRED (Hot Attic Games standing policy; it is not negotiable per project)."

    val androidRequirements: List<String> = listOf(
        "Set targetSdk and compileSdk to $ANDROID_TARGET_API (or the engine/toolchain's equivalent settings) and install Android SDK Platform $ANDROID_TARGET_API.",
        "Select a mutually compatible set for API $ANDROID_TARGET_API: Android SDK and build-tools, Android Gradle Plugin, Gradle, JDK, AndroidX and other dependencies, and the engine/toolchain's Android components (export templates, Gradle integration, NDK where used). Research the current compatible versions instead of assuming them, and record the chosen set and its sources in `docs/DECISIONS.md`.",
        "Never keep an older Android target because a dependency, project template or default uses it. Never resolve a dependency conflict by pinning older dependencies solely to preserve an obsolete target; upgrade the toolchain and dependencies to a combination that supports API $ANDROID_TARGET_API.",
        "If a specific dependency genuinely cannot support API $ANDROID_TARGET_API, report that exact incompatibility and the nearest compatible alternative to the owner. Do not silently lower the target.",
        "Verify the built artifact (for example `aapt2 dump badging` shows targetSdkVersion '$ANDROID_TARGET_API') and record it in `docs/VERIFICATION.md`.",
    )

    val moteHeadline = "Over-the-air updates are PRESENT (owner decision). The reference architecture is the Hot Attic Games $MOTE OTA implementation."

    val moteRequirements: List<String> = listOf(
        "Inspect and follow the actual $MOTE OTA implementation (its repository or reference material) wherever you can access it, rather than substituting a generic framework OTA solution. Do not use Expo EAS Update, CodePush, credentials embedded in the application, or another hosted OTA product by default.",
        "Carry forward, wherever technically applicable: a native shell/runtime boundary; classification of every change as OTA-safe or native-change; a runtime compatibility identity/fingerprint against which incompatible updates are rejected; a channel/update pointer for discovery; downloadable update artifacts; integrity and signature verification before use; staging before application; safe application and restart behavior; rollback and recovery protections; protection against broken or repeated update loops; owner-controlled publication; exact-source release discipline (a published update names the exact source it was built from).",
        "OTA compatibility does NOT mean every future change can be delivered over the air. Native, runtime or toolchain changes still require a new native build whenever the compatibility check says so.",
        "Nothing is published without the owner's explicit authorization; no publication happens as a side effect of ordinary pushes or builds. No secrets or signing credentials are ever embedded in the application or committed.",
        "If $MOTE is genuinely incompatible with this project's technology stack: (1) identify the specific incompatibility, (2) preserve as many $MOTE safety and release properties as technically possible, (3) report the deviation, and (4) obtain the owner's approval before adopting a materially different OTA architecture. Never substitute another OTA provider silently.",
    )

    val otaAbsent = "Over-the-air updates are ABSENT (owner decision). Do not add OTA infrastructure; updates ship as normal new installs."

    /** One concise line per applicable policy, for the execution prompt. */
    fun promptLines(p: Project): List<String> = buildList {
        if (androidSelected(p)) add("Android: Target API $ANDROID_TARGET_API is REQUIRED (compileSdk/targetSdk $ANDROID_TARGET_API). Pick a toolchain and dependency set compatible with it (SDK, AGP, Gradle, JDK, AndroidX, engine Android components); never keep an older target or pin older dependencies to preserve one. Details in CLAUDE.md section A0.")
        when (otaState(p)) {
            SystemState.PRESENT -> add("OTA: YES. Use the Hot Attic Games $MOTE OTA architecture as the reference (inspect the actual $MOTE implementation; no EAS Update, CodePush or other hosted OTA by default). OTA does not cover native/runtime changes, which still need a new native build, and nothing is published without the owner's authorization. Details in CLAUDE.md section A0.")
            SystemState.ABSENT -> add("OTA: NO. Do not add OTA infrastructure.")
            else -> Unit
        }
    }
}
