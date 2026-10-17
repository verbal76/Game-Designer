package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.model.AssetResolution
import com.hotattic.gamedesigner.core.model.Category
import com.hotattic.gamedesigner.core.model.DecisionStatus
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.schema.FieldKind
import com.hotattic.gamedesigner.core.schema.Fields
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Platforms
import com.hotattic.gamedesigner.core.schema.Tag
import com.hotattic.gamedesigner.core.schema.Traits

enum class AuditLevel { ERROR, WARNING, INFO }

enum class AuditArea(val label: String) {
    CONTRADICTION("Contradictions"),
    UNRESOLVED("Unresolved decisions"),
    MISSING_SYSTEM("Missing gameplay systems"),
    PLATFORM("Platform compatibility"),
    LICENSING("Licensing"),
    BUILD("Technical / build"),
    ASSETS("Assets"),
    VAGUE("Vague requirements"),
}

data class AuditFinding(val level: AuditLevel, val area: AuditArea, val message: String, val keys: List<String> = emptyList())

data class AuditReport(val findings: List<AuditFinding>, val humanOnlyTasks: List<String>) {
    val errors get() = findings.filter { it.level == AuditLevel.ERROR }
    val warnings get() = findings.filter { it.level == AuditLevel.WARNING }
    val passes: Boolean get() = errors.isEmpty()
    fun summary(): String =
        if (findings.isEmpty()) "Audit clean."
        else "${errors.size} blocking, ${warnings.size} warnings, ${findings.count { it.level == AuditLevel.INFO }} notes."
}

/** The final pre-generation audit required by the spec: contradictions, holes, licensing, build and vagueness. */
object AuditEngine {

    private val vaguePhrases = listOf("tbd", "to be determined", "to be decided", "todo", "figure out later", "decide later", "somehow", "something like", "placeholder", "add later", "we'll see", "whatever", "etc.", " etc ")

    fun audit(project: Project): AuditReport {
        val t = Traits(project)
        val f = mutableListOf<AuditFinding>()
        val c = CompletenessEngine.compute(project)

        // 1. Contradictions / conflicts
        ConflictEngine.open(project).forEach {
            val level = if (it.severity == Severity.BLOCKER) AuditLevel.ERROR else AuditLevel.WARNING
            f += AuditFinding(level, if (it.severity == Severity.BLOCKER) AuditArea.PLATFORM else AuditArea.CONTRADICTION, "${it.title}: ${it.message} Recommendation: ${it.recommendation}", it.affectedKeys)
        }

        // 2. Unresolved decisions
        c.missingRequired.forEach { f += AuditFinding(AuditLevel.ERROR, AuditArea.UNRESOLVED, "Required decision missing: ${it.title}.", listOf(it.key)) }
        c.proposed.filter { it.required }.forEach { f += AuditFinding(AuditLevel.ERROR, AuditArea.UNRESOLVED, "Unconfirmed inferred decision: ${it.title} = ${project.value(it.key)}.", listOf(it.key)) }
        c.pendingUploads.forEach { f += AuditFinding(AuditLevel.ERROR, AuditArea.ASSETS, "${it.title}: you chose to upload but no file has been added.", listOf(it.key)) }

        // 3. Missing systems (cross-cutting checklist from the spec, only where relevant)
        fun has(k: String) = project.value(k) != null || project.decision(k)?.status == DecisionStatus.DEFERRED
        val checks = listOf(
            Triple("Save/load behavior", Keys.SAVE_SYSTEM, true),
            Triple("Pause and menu behavior", Keys.MENUS_SETTINGS, true),
            Triple("Death/failure and restart behavior", Keys.DIFFICULTY_FAILURE, true),
            Triple("Win/loss conditions", Keys.WIN_LOSS, project.value(Keys.SESSION_STRUCTURE) != "endless"),
            Triple("Input scheme", Keys.INPUT_METHODS, true),
            Triple("Touch control scheme", Keys.TOUCH_SCHEME, t.usesTouch),
            Triple("Tutorial / onboarding", Keys.TUTORIAL, true),
            Triple("HUD / UI definition", Keys.HUD_UI, true),
            Triple("Audio plan", Keys.AUDIO, true),
            Triple("Accessibility plan", Keys.ACCESSIBILITY, true),
            Triple("Performance target", Keys.PERFORMANCE, true),
            Triple("Crafting / resource systems", Keys.SURVIVAL_CRAFTING, t.has(Tag.CRAFTING)),
            Triple("Economy / resource flow", Keys.ECONOMY, t.has(Tag.ECONOMY)),
            Triple("Combat model", Keys.COMBAT_MODEL, t.has(Tag.COMBAT)),
            Triple("Build identity (name and package ID)", Keys.PACKAGE_ID, true),
        )
        checks.filter { it.third && !has(it.second) }.forEach {
            f += AuditFinding(AuditLevel.ERROR, AuditArea.MISSING_SYSTEM, "${it.first} is not defined.", listOf(it.second))
        }

        // 4. Platform / engine
        if (t.engine == null) f += AuditFinding(AuditLevel.ERROR, AuditArea.BUILD, "No engine/framework selected.", listOf(Keys.ENGINE))
        t.platforms.filter { it == Platforms.IOS }.forEach { _ ->
            f += AuditFinding(AuditLevel.INFO, AuditArea.PLATFORM, "iOS builds require macOS/Xcode; the generated pipeline uses a macOS CI runner.", listOf(Keys.PLATFORMS))
        }

        // 5. Licensing
        val policy = t.value(Keys.ASSET_POLICY) ?: "cc0_default"
        project.assets.filter { it.resolution == AssetResolution.EXTERNAL_OTHER_LICENSE && policy != "cc0_or_cc_by" }.forEach {
            f += AuditFinding(AuditLevel.ERROR, AuditArea.LICENSING, "Asset '${it.needId}' uses a non-CC0 license (${it.license}) but the policy is CC0/public domain only.", listOf(Keys.ASSET_POLICY))
        }
        project.assets.filter { it.resolution == AssetResolution.DEFERRED_BY_OWNER }.forEach {
            f += AuditFinding(AuditLevel.WARNING, AuditArea.ASSETS, "Asset '${it.needId}' was deferred by the owner.", emptyList())
        }
        project.assets.filter { it.resolution == AssetResolution.EXTERNAL_CC0 && it.verifiedAt == null }.takeIf { it.isNotEmpty() }?.let {
            f += AuditFinding(AuditLevel.INFO, AuditArea.LICENSING, "${it.size} asset sets are planned from CC0 sources; the build must verify each file's license and log provenance in ASSETS.md.", emptyList())
        }

        // 6. Build holes
        if (t.platformsKnown && !has(Keys.CI_BUILD)) f += AuditFinding(AuditLevel.ERROR, AuditArea.BUILD, "Build pipeline not chosen.", listOf(Keys.CI_BUILD))
        if (!has(Keys.TESTING)) f += AuditFinding(AuditLevel.ERROR, AuditArea.BUILD, "Testing/validation strategy not defined.", listOf(Keys.TESTING))

        // 7. Assets
        c.unresolvedAssets.forEach { f += AuditFinding(AuditLevel.ERROR, AuditArea.ASSETS, "No resolution for required asset: ${it.label}.", emptyList()) }

        // 8. Vagueness
        for ((key, d) in project.decisions) {
            val field = Fields.get(key) ?: continue
            if (field.kind != FieldKind.TEXT || d.status == DecisionStatus.DEFERRED || d.value.isBlank()) continue
            val lower = " ${d.value.lowercase()} "
            vaguePhrases.firstOrNull { lower.contains(it) }?.let {
                f += AuditFinding(AuditLevel.WARNING, AuditArea.VAGUE, "${field.title} contains vague wording (\"${it.trim()}\") that may cause incorrect improvisation.", listOf(key))
            }
            val minLen = if (key in setOf(Keys.DISPLAY_NAME, Keys.PACKAGE_ID, Keys.REFERENCES, Keys.COLOR_MOOD, Keys.EXCLUSIONS, Keys.PLAYER_FEELING, Keys.TOOLCHAIN_PREFS, Keys.MIN_HARDWARE)) 0 else 25
            if (field.required && d.value.trim().length < minLen && key != Keys.CONCEPT) {
                f += AuditFinding(AuditLevel.WARNING, AuditArea.VAGUE, "${field.title} is very short; the spec will be more reliable with a fuller description.", listOf(key))
            }
        }

        return AuditReport(f, humanOnlyTasks(project, t))
    }

    fun humanOnlyTasks(project: Project, t: Traits = Traits(project)): List<String> = buildList {
        add("Install and playtest the first build on a real device and report back what feels wrong (text, voice, screenshots or video).")
        if (t.storePlan in setOf("play_internal_testing", "play_store")) {
            add("Create and pay for a Google Play Console developer account if you don't have one.")
            add("Generate the upload key / keystore yourself and add it to the CI as encrypted secrets (never commit it).")
        }
        if (Platforms.IOS in t.platforms || t.storePlan == "app_store") add("Enroll in the Apple Developer Program and create signing certificates/profiles.")
        if (project.list(Keys.BRAND_ICON).any { it == "upload" } || project.value(Keys.BRAND_ICON) == "upload") add("Provide your master icon artwork (already requested in the app).")
        if (project.assets.any { it.resolution == AssetResolution.EXTERNAL_OTHER_LICENSE }) add("Review the attribution requirements of non-CC0 assets before release.")
        if (t.storePlan in setOf("play_store", "app_store", "steam_or_pc_store", "itch_or_web")) add("Write or approve the store description, screenshots and privacy policy URL.")
    }
}
