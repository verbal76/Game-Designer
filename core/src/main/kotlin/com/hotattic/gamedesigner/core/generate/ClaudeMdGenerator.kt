package com.hotattic.gamedesigner.core.generate

import com.hotattic.gamedesigner.core.engine.AssetPlan
import com.hotattic.gamedesigner.core.engine.AuditEngine
import com.hotattic.gamedesigner.core.engine.AuditReport
import com.hotattic.gamedesigner.core.engine.ConflictEngine
import com.hotattic.gamedesigner.core.engine.AssetStrategy
import com.hotattic.gamedesigner.core.engine.BuildObjective
import com.hotattic.gamedesigner.core.engine.PlatformPolicy
import com.hotattic.gamedesigner.core.engine.SystemState
import com.hotattic.gamedesigner.core.engine.ConsistencyReview
import com.hotattic.gamedesigner.core.engine.MetaConversation
import com.hotattic.gamedesigner.core.engine.ProjectObjective
import com.hotattic.gamedesigner.core.engine.ScopeEngine
import com.hotattic.gamedesigner.core.model.AssetResolution
import com.hotattic.gamedesigner.core.model.BrandingMode
import com.hotattic.gamedesigner.core.model.BrandingSlot
import com.hotattic.gamedesigner.core.model.DecisionSource
import com.hotattic.gamedesigner.core.model.DecisionStatus
import com.hotattic.gamedesigner.core.model.FactStatus
import com.hotattic.gamedesigner.core.model.Provenance
import com.hotattic.gamedesigner.core.model.FeedbackStatus
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.ProjectMode
import com.hotattic.gamedesigner.core.model.ResearchKind
import com.hotattic.gamedesigner.core.schema.Dependencies
import com.hotattic.gamedesigner.core.schema.EngineCatalog
import com.hotattic.gamedesigner.core.schema.Field
import com.hotattic.gamedesigner.core.schema.Fields
import com.hotattic.gamedesigner.core.schema.GenreKnowledge
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Platforms
import com.hotattic.gamedesigner.core.schema.Tag
import com.hotattic.gamedesigner.core.schema.Traits

/**
 * Deterministically composes the authoritative target-game CLAUDE.md from structured project state. It never emits
 * TBD/TODO placeholders: every field is either resolved, derived from a rule, or listed as an explicit exclusion.
 */
object ClaudeMdGenerator {

    fun generate(project0: Project, versionNumber: Int, versionLabel: String, nowIso: String, audit: AuditReport = AuditEngine.audit(project0)): String {
        val project = com.hotattic.gamedesigner.core.engine.DerivedDefaults.apply(project0)
        val t = Traits(project)
        val scope = ScopeEngine.recommend(project)
        val prototype = ProjectObjective.of(project) == BuildObjective.PROTOTYPE
        val title = project.value(Keys.DISPLAY_NAME) ?: project.name
        val sb = StringBuilder()
        fun h1(s: String) = sb.append("# ").append(s).append("\n\n")
        fun h2(s: String) = sb.append("## ").append(s).append("\n\n")
        fun h3(s: String) = sb.append("### ").append(s).append("\n\n")
        fun p(s: String) = sb.append(s).append("\n\n")
        fun bullets(items: Collection<String>) { if (items.isNotEmpty()) { items.forEach { sb.append("- ").append(it).append('\n') }; sb.append('\n') } }
        // Only confirmed, still-relevant decisions are rendered; proposals and stale values never reach the spec.
        fun active(key: String) = project.decision(key)?.takeIf { it.status == DecisionStatus.CONFIRMED && it.value.isNotBlank() && (Fields.get(key)?.isRelevant(t) != false) }
        fun v(key: String) = active(key)?.value
        /** A value the owner (or their accepted recommendation / a grounded inference) set, not one of Bob's routine defaults. */
        fun nd(key: String) = active(key)?.takeIf { it.prov != Provenance.DEFAULT }?.value
        fun label(key: String, value: String?): String = value?.let { raw -> raw.split("|").joinToString(", ") { optionLabel(t, key, it) } } ?: "(not specified)"

        h1("$title - AUTHORITATIVE BUILD SPECIFICATION")
        p("Spec version $versionNumber ($versionLabel) - generated $nowIso by Game Designer. This file supersedes earlier versions; earlier versions are preserved in the Game Designer project history and must not be re-derived from memory.")

        // 0. Authority
        h2("0. Authority and how to use this file")
        p("This file is the authoritative specification for **$title**. Read it completely before making decisions. Preserve the owner's intent over convenience. " +
            "If repository reality conflicts with this document, investigate, preserve recoverable working state, and take the safest path that satisfies this specification. " +
            (if (prototype) "The owner defined the first build as a FULLY FUNCTIONAL PROTOTYPE: the intended game and core loop genuinely playable, but not full production content. Never reduce it below that to a mockup, toy, or gray-box, and never inflate it into a full commercial game. " else "Never silently reduce scope to a prototype, toy, mockup or gray-box. ") + "Where this file is explicit, follow it; where it is silent on a non-creative engineering detail, make the best reversible decision and record it in `docs/DECISIONS.md`.")
        p("The owner is Kevin. Creative and product decisions belong to the owner; engineering decisions belong to you.")
        if (project.mode != ProjectMode.NEW_GAME) {
            h3("Project mode")
            p(if (project.mode == ProjectMode.EXISTING_GAME) "EXISTING GAME: this specification continues an existing repository. Inspect it first; see section 17." else "PLAYTEST CONTINUATION: this specification updates an existing build after human playtesting; see section 17.")
        }

        // PART A / B: who said what.
        sb.append("# PART A - OWNER REQUIREMENTS (authoritative: implement exactly; nothing in Part B or C may contradict this)\n\n")
        val platformPolicy = platformPolicyBlock(project)
        if (platformPolicy.isNotEmpty()) {
            h2("A0. Hot Attic Games platform policy (standing rules for every project; they apply here without further discussion)")
            platformPolicy.forEach { (title, lines) -> if (title.isNotBlank()) p(title); bullets(lines) }
        }
        h2("A1. Owner vision - the original concept, in their own words")
        val concept = project.originalConcept.ifBlank { project.value(Keys.CONCEPT).orEmpty() }
        sb.append(ConsistencyReview.VERBATIM_OPEN).append('\n')
        sb.append(concept.ifBlank { title }.lines().joinToString("\n") { "> $it" }).append('\n')
        sb.append(ConsistencyReview.VERBATIM_CLOSE).append("\n\n")
        val ownerFacts = project.activeFacts().filter { it.category != "correction" }
        val sectionKeys = setOf(Keys.CONCEPT, Keys.MUST_NOT_CHANGE, Keys.FIRST_SLICE, Keys.DONE, Keys.WIN_LOSS, Keys.ASSET_POLICY, Keys.FIVE_MINUTES) + Keys.brandingKeyForSlot.values
        fun ownerDecision(k: String, d: com.hotattic.gamedesigner.core.model.Decision) = d.ownerAuthored && d.status == DecisionStatus.CONFIRMED && d.value.isNotBlank() && active(k) != null
        val ownerDecisions = project.decisions.filter { (k, d) -> ownerDecision(k, d) && k !in sectionKeys && d.prov == Provenance.OWNER_EXPLICIT }
        h2("A2. Owner requirements")
        if (ownerFacts.isEmpty() && ownerDecisions.isEmpty()) p("The owner specified nothing beyond the concept above.")
        bullets(ownerFacts.map { it.text })
        bullets(ownerDecisions.map { (k, d) -> "**${Fields.get(k)?.title ?: k}:** ${label(k, d.value)}" + if (d.rawAnswer.isNotBlank() && d.rawAnswer.length < 160) " (owner said: \"${d.rawAnswer.trim()}\")" else "" })

        val retracted = project.facts.filter { it.status == FactStatus.RETRACTED }
        val corrections = project.decisions.filter { (k, d) -> ownerDecision(k, d) && k !in sectionKeys && d.prov == Provenance.OWNER_CORRECTION }
        val correctionFacts = project.activeFacts().filter { it.category == "correction" }
        val rejectedLines = project.rejected.flatMap { (k, ids) -> ids.map { id -> k to id } }.map { (k, id) ->
            when (k) {
                Dependencies.TAG -> "Rejected by the owner: ${id.lowercase().replace('_', '-')} design"
                Keys.GENRE, "genre" -> "Rejected by the owner: genre ${GenreKnowledge.resolve(id).label}"
                else -> "Rejected by the owner: ${Fields.get(k)?.title ?: k} = ${optionLabel(t, k, id)}"
            }
        }.distinct()
        h2("A3. Owner corrections (these supersede anything earlier; do NOT implement withdrawn items)")
        if (corrections.isEmpty() && correctionFacts.isEmpty() && retracted.isEmpty() && rejectedLines.isEmpty()) p("None.")
        bullets(correctionFacts.map { it.text })
        bullets(corrections.map { (k, d) -> "**${Fields.get(k)?.title ?: k}:** ${label(k, d.value)} [corrected by the owner]" })
        bullets(retracted.map { "WITHDRAWN (do not implement): ${it.text}" } + rejectedLines)

        val invariants = v(Keys.MUST_NOT_CHANGE)?.takeIf { it.trim().lowercase() != "none" }
        val derivedInvariants = com.hotattic.gamedesigner.core.schema.SliceSuggester.invariants(t).value.takeIf { it != "none" }
        h2("A4. Must-not-change constraints")
        p(if (invariants == null && derivedInvariants == null) "None declared. (See Part E.)" else "These are binding and are listed in full in Part E. Do not reinterpret, 'improve' or normalise them.")

        fun ownerV(k: String) = project.decision(k)?.takeIf { ownerDecision(k, it) }?.value
        h2("A5. First playable build scope")
        p(ownerV(Keys.FIRST_SLICE) ?: "The owner did not define the slice themselves; follow the recommended slice in Part B and the objective below.")
        p(if (prototype) "Objective: a FULLY FUNCTIONAL PROTOTYPE (the owner's own word) - real gameplay, representative presentation, a beginning-to-end slice, actual controls and core loop, representative content; not full commercial content volume." else "Objective: a complete, genuinely playable first version (the default; the owner did not ask for a prototype).")

        h2("A6. Completion criteria")
        val completion = listOfNotNull(ownerV(Keys.WIN_LOSS)?.let { "Win and loss: $it" }, ownerV(Keys.DONE)?.let { "The first build is done when: $it" })
        if (completion.isEmpty()) p("The owner delegated the completion criteria; see Part B.") else bullets(completion)

        h2("A7. Asset policy and owner-supplied assets")
        v(Keys.ASSET_POLICY)?.let { pol -> p(AssetStrategy.of(pol).describe()); p("Asset policy: **${label(Keys.ASSET_POLICY, pol)}**" + (project.decision(Keys.ASSET_POLICY)?.let { if (it.ownerAuthored) " (chosen by the owner)" else "" } ?: "") + ".") }
        if (v(Keys.ASSET_POLICY)?.let { AssetStrategy.of(it).usesSupplied } == true)
            p("**OWNER-SUPPLIED ASSET PACKS.** The owner will give you asset packs together with the master prompt. They are the FIRST choice for every asset need: inspect them before choosing anything else (files, formats, scale, rigs and animations, bundled licenses), use their real contents, and create missing animations for the supplied player character where technically reasonable. Do not replace them with external or generated assets. " + (project.activeFacts().map { it.text }.firstOrNull { Regex("(?i)\\b3d\\b").containsMatchIn(it) && Regex("(?i)asset").containsMatchIn(it) } ?.let { "The owner noted: \"$it\". " } ?: "") + "Only gaps the packs cannot cover follow the gap policy in section 8.")
        val slotLines = Keys.brandingKeyForSlot.map { (slot, key) ->
            val up = project.branding[slot]?.takeIf { it.mode == BrandingMode.UPLOADED }
            val nice = when (slot) { BrandingSlot.ICON -> "Game icon"; BrandingSlot.STUDIO_SPLASH -> "Studio logo / splash"; else -> "Game splash / title image" }
            when {
                up != null -> "**$nice:** OWNER-SUPPLIED file `${up.originalName}` (${up.width}x${up.height}, sha256 ${up.sha256.take(16)}...), kept untouched at `branding/master/${up.localFile?.substringAfterLast('/')}`. Use it; never generate a replacement."
                else -> when (v(key)) { "generate_original" -> "**$nice:** none supplied; create an original one."; "generic_temporary" -> "**$nice:** none supplied; use a clean generic replaceable one."; "skip" -> "**$nice:** intentionally omitted."; else -> null }
            }
        }.filterNotNull()
        bullets(slotLines)

        h2("A8. Informed overrides and acknowledged risks")
        val overrides = project.decisions.filter { it.value.source == DecisionSource.OVERRIDE }
        if (overrides.isEmpty()) p("None.")
        else bullets(overrides.map { (k, d) -> "**${Fields.get(k)?.title ?: k}** = ${label(k, d.value)} (the owner was informed; Director recommended: ${d.overrides ?: "an alternative"})." })
        val acknowledged = ConflictEngine.acknowledged(project)
        if (acknowledged.isNotEmpty()) bullets(acknowledged.map { "Acknowledged risk - ${it.title}: ${it.message}" })

        sb.append("# PART B - ACCEPTED RECOMMENDATIONS (Game Designer's suggestions the owner accepted or delegated; keep unless there is a strong engineering reason)\n\n")
        val accepted = project.decisions.filter { (k, d) -> d.prov == Provenance.OWNER_ACCEPTED_RECOMMENDATION && d.status == DecisionStatus.CONFIRMED && d.value.isNotBlank() && active(k) != null && k != Keys.CONCEPT && k !in Keys.brandingKeyForSlot.values && k != Keys.ASSET_POLICY }
        if (accepted.isEmpty()) p("None.")
        else bullets(accepted.map { (k, d) -> "**${Fields.get(k)?.title ?: k}:** ${label(k, d.value)}" + (d.note.takeIf { it.isNotBlank() }?.let { " - $it" } ?: "") })

        sb.append("# PART C - IMPLEMENTATION GUIDANCE (how to build it; derived from Parts A and B plus engineering practice)\n\n")
        p("Where anything in this part appears to conflict with Part A, Part A wins.")
        // A routine default never sits beside the owner's own description of the same thing (their camera idea beats "side view"; their stated slice beats "1-5 minute sessions").
        val ownerCamera = project.decision(Keys.MOVEMENT_CAMERA)?.ownerAuthored == true
        val ownerSlice = project.decision(Keys.FIRST_SLICE)?.ownerAuthored == true
        val derivedDefaults = project.decisions.filter { (k, d) -> d.prov == Provenance.DEFAULT && d.value.isNotBlank() && active(k) != null &&
            !(k == Keys.PERSPECTIVE && ownerCamera) && !(k == Keys.SESSION_STRUCTURE && ownerSlice) && k != Keys.ANDROID_TARGET_API }
        if (derivedDefaults.isNotEmpty()) {
            h3("Engineering decisions Bob made so the owner did not have to (change any of them for a good reason)")
            bullets(derivedDefaults.map { (k, d) -> "**${Fields.get(k)?.title ?: k}:** ${label(k, d.value)}" })
        }
        val slop = AntiSlop.derive(project)
        h3("Anti-slop acceptance criteria (what does NOT count as satisfying this design)")
        bullets(slop)

        // 2. Vision
        h2("2. Vision and non-negotiables")
        p("The owner's concept is quoted verbatim in section A1.")
        v(Keys.CORE_FANTASY)?.let { p("**Core fantasy:** $it") }
        v(Keys.PLAYER_FEELING)?.let { p("**Intended player feeling:** $it") }
        com.hotattic.gamedesigner.core.engine.DesignModel.of(project).experience.takeIf { it.dynamics.isNotEmpty() }?.let { e -> p("**Design reading of that feeling:** ${e.dynamics.joinToString("; ")}. Derive the mechanics from this experience; do not turn the word \"${e.ownerWords.take(40).trim()}\" into a feature.") }
        if (t.genresKnown) p("**Genre:** ${t.genres.joinToString(" + ") { it.label }}. **Dimension:** ${v(Keys.DIMENSION) ?: "n/a"}. ${nd(Keys.PERSPECTIVE)?.let { " **Perspective:** ${label(Keys.PERSPECTIVE, it)}." } ?: ""}")
        if (project.references.isNotEmpty()) {
            h3("Reference games")
            p("References inform design characteristics only. Never copy characters, art, maps, music, writing, names or any proprietary asset from them.")
            v(Keys.REFERENCE_ASPECTS)?.let { p("What to take from them: $it") }
            bullets(project.references.map { r ->
                buildString {
                    append("**${r.name}**")
                    if (r.aspects.isNotEmpty()) append(" - take: ${r.aspects.joinToString("; ")}")
                    if (r.summary.isNotBlank()) append(". Research summary: ${r.summary}")
                    if (r.sources.isNotEmpty()) append(" (sources: ${r.sources.joinToString(", ") { it.url }})")
                }
            })
        }
        h3("Non-negotiables")
        bullets(listOf(
            if (prototype) "The first build is a fully functional prototype, as the owner defined it: the intended core loop genuinely playable end to end with real input, feedback, win/failure and progression, proving the design. It is not required to contain full production content (every eventual level, enemy, boss or final art), and it is not a mockup or a block moving on a screen."
            else "The first build is a genuinely playable, complete game - the full intended core loop with real visuals, input, audio (if specified), menus, settings, saves, win/failure/progression - not a prototype.",
            "No unfinished-work markers, placeholder gameplay or stubbed systems ship in the first build. Anything deferred is listed in section 16 and nowhere else.",
            if (prototype) "Everything this file specifies for the prototype is implemented, validated by automation and repaired before asking the owner to playtest." else "Everything in this file is implemented, validated by automation, repaired and polished before asking the owner to playtest.",
            "No secrets (API keys, tokens, signing keys, passwords) are ever committed.",
            "All external assets satisfy the license policy in section 8 and are logged with provenance.",
        ))

        // 3. Player experience
        h2("3. Player experience")
        bullets(listOfNotNull(
            v(Keys.CORE_LOOP)?.let { "**Core loop:** $it" },
            nd(Keys.SESSION_STRUCTURE)?.let { "**Session structure:** ${label(Keys.SESSION_STRUCTURE, it)}" },
            v(Keys.WORLD_STRUCTURE)?.let { "**World/level structure:** ${label(Keys.WORLD_STRUCTURE, it)}" },
            v(Keys.WIN_LOSS)?.let { "**Win/loss:** $it" },
            v(Keys.DIFFICULTY_FAILURE)?.let { "**Difficulty and failure:** ${label(Keys.DIFFICULTY_FAILURE, it)}" },
            v(Keys.TUTORIAL)?.let { "**Tutorial/onboarding:** ${label(Keys.TUTORIAL, it)}" },
            v(Keys.STORY)?.let { "**Story:** ${label(Keys.STORY, it)}" },
            v(Keys.MOVEMENT_CAMERA)?.let { "**Movement and camera feel:** $it" },
        ))

        // 4. Systems
        h2("4. Gameplay and system requirements")
        p("Every system the owner specified or accepted below is required in the first build and must be reachable and exercised during normal play. Genre checklist items apply wherever they fit this game's actual design; if one does not fit, adapt or omit it and note why in `docs/DECISIONS.md` (Part A always wins).")
        val specifics = listOfNotNull(
            v(Keys.COMBAT_MODEL)?.let { "**Combat:** ${label(Keys.COMBAT_MODEL, it)}" },
            v(Keys.ENEMIES_BOSSES)?.let { "**Enemies and bosses:** $it" },
            v(Keys.CHARACTERS)?.let { "**Characters:** ${label(Keys.CHARACTERS, it)}" },
            if (v(Keys.HAS_PROGRESSION) == "no") "**Progression:** NONE by design (owner decision). The player never gets stronger - no XP, levels, upgrades, unlocks or power-ups. Skill and physical progress through the game are the only progression; do not add any."
            else v(Keys.PROGRESSION)?.let { "**Progression:** ${label(Keys.PROGRESSION, it)}" },
            if (v(Keys.HAS_COMBAT) == "no") "**Combat:** NONE by design (owner decision). No enemies, bosses, weapons, health or damage model; challenge comes from the environment." else null,
            v(Keys.ECONOMY)?.let { "**Economy:** $it" },
            v(Keys.SURVIVAL_CRAFTING)?.let { "**Survival and crafting:** $it" },
            v(Keys.AUTOMATION_SIM)?.let { "**Simulation/building:** $it" },
        )
        if (specifics.isNotEmpty()) { h3("Specified design (who decided each item is in Parts A and B)"); bullets(specifics) }
        val systems = t.systems()
        if (systems.isNotEmpty()) {
            h3("Required systems (genre completeness checklist - adapt each item to this game's real design)")
            bullets(systems.map { (g, s) -> "**${s.name}** (${g.label.substringBefore(" (").substringBefore(" /")}): ${s.detail}" })
        }
        val dm = com.hotattic.gamedesigner.core.engine.DesignModel.of(project)
        if (dm.procedural) {
            val (must, vary) = com.hotattic.gamedesigner.core.engine.ProceduralInvariants.derive(project)
            h3("Procedural generation constraints (what every generated result must preserve)")
            p("Procedural does not mean random placement. Derived by Game Designer from the owner's design; the owner can change any of it. Generation happens inside these bounds, and a generator is not successful merely because its outputs differ.")
            bullets(must)
            p("What may vary: ${vary.joinToString("; ")}. Validate functionally (automated solver/validator over many seeds) AND by playtest, because a stage can be valid without being good.")
        }
        if (dm.playerMasteryIsProgression) {
            h3("Progression is player mastery")
            p(dm.masteryNote)
        }
        h3("Cross-cutting systems that must exist")
        bullets(listOfNotNull(
            "Pause behavior: the game can be paused/backgrounded at any time without loss or exploit; resuming restores exact state.",
            "Death/failure/restart: defined flow from failure to retry or menu with no dead ends or soft-locks.",
            "Save/load: ${label(Keys.SAVE_SYSTEM, v(Keys.SAVE_SYSTEM))}. See section 11.",
            "Main loop must never reach an unwinnable or stuck state; automated tests assert this where feasible.",
            if (t.has(Tag.CRAFTING) || t.hasGenre("rpg")) "Inventory: stacking, capacity, drop/pickup, full-inventory handling, sorting/filtering; no item can be lost silently." else null,
            if (t.has(Tag.CRAFTING) || t.has(Tag.ECONOMY)) "Resource regeneration/respawn and sinks are defined in data and balanced by headless simulation." else null,
        ))

        // 5. Scope
        h2(if (prototype) "5. Content scope (prototype now; production sizing for later)" else "5. Content scope (recommended sizing)")
        p("Scope tier: **${scope.effectiveTier.label}** (recommended: ${scope.recommendedTier.label}). ${scope.rationale.joinToString(" ")}")
        if (prototype) p("PROTOTYPE SCOPE: build only enough content to prove each specified system and the core loop (for example one complete playable stretch of the world and a few representative enemies/obstacles), data-driven so production content can be added later without rework. The counts below are the eventual production heuristics and are NOT required for this build.")
        p("These counts are Game Designer's UPPER-BOUND EFFORT HEURISTICS, not owner requirements, unless the owner stated numbers in Part A. Do not pad content to reach them. Map each unit onto this game's real structure (for example depth zones instead of levels in a descent game) and size content so the intended loop is complete and replayable. Author content as data (tables/resources), validated by automated checks; no content slot may be an empty stub.")
        bullets(scope.targets.map { "${it.label}: **${it.count}**" })

        // 6. Controls
        h2("6. Controls, camera and orientation")
        bullets(listOfNotNull(
            "Input methods: ${v(Keys.INPUT_METHODS)?.split("|")?.joinToString { label(Keys.INPUT_METHODS, it) } ?: "per platform defaults"}",
            v(Keys.TOUCH_SCHEME)?.let { "Touch scheme: ${label(Keys.TOUCH_SCHEME, it)}. Touch targets at least 48dp; controls must not obscure critical play; layout adapts to both thumbs and to phone/tablet." },
            v(Keys.INPUT_REMAP)?.let { "Remapping: ${label(Keys.INPUT_REMAP, it)}" },
            v(Keys.ORIENTATION)?.let { "Orientation: ${label(Keys.ORIENTATION, it)}; layouts must handle notches, cutouts, safe areas and aspect ratios from 16:9 to 21:9 (and tablets)." },
            "Camera and movement feel follow section 3 and must be tunable from a single data file.",
        ))

        // 7. Art / audio / UI
        h2("7. Art, audio and interface direction")
        bullets(listOfNotNull(
            v(Keys.ART_DIRECTION)?.let { "Art direction: ${label(Keys.ART_DIRECTION, it)}" },
            v(Keys.COLOR_MOOD)?.let { "Color and mood: $it" },
            v(Keys.VFX)?.let { "Effects/game feel: ${label(Keys.VFX, it)} (all screen shake/flash effects respect the reduced-motion setting)" },
            v(Keys.AUDIO)?.let { "Audio: ${label(Keys.AUDIO, it)}" },
            v(Keys.HUD_UI)?.let { "HUD/UI: $it" },
            v(Keys.MENUS_SETTINGS)?.let { "Menus and settings: ${it.split("|").joinToString { m -> label(Keys.MENUS_SETTINGS, m) }}" },
        ))

        // 8. Assets
        h2("8. Assets, provenance and branding")
        val policy = v(Keys.ASSET_POLICY) ?: "cc0_default"
        if (AssetStrategy.of(policy).usesSupplied) p("Supplied material is used first for the needs it covers (see A7). The rules below apply to every GAP asset and to anything external that is added; the supplied packs themselves are the owner's responsibility, but still log them in `ASSETS.md`.")
        p("License policy: **${label(Keys.ASSET_POLICY, policy)}**. 'Free to download' is not a license. For every external asset: verify the license on the asset's own page, save a copy of the license text under `assets/licenses/`, and add an entry to `ASSETS.md` with file, source URL, creator, license, and download date. The in-game credits screen lists all of them. Do not scrape or redistribute assets against a site's terms.")
        p("If no coherent, appropriately licensed set exists for a need, build the original procedural replacement described below. Never leave an asset need unresolved or as a placeholder.")
        val needs = AssetPlan.needs(t)
        val recs = project.assets.associateBy { it.needId }
        for (n in needs) {
            val r = recs[n.id]
            h3(n.label)
            p(n.detail)
            if (r == null) p("Resolution: original procedural construction. ${AssetPlan.proceduralFallback(n)}")
            else bullets(listOfNotNull(
                "Resolution: ${resolutionText(r.resolution)}",
                r.source.takeIf { it.isNotBlank() }?.let { "Candidate sources: $it" },
                r.license.takeIf { it.isNotBlank() }?.let { "License: $it" },
                r.creator.takeIf { it.isNotBlank() }?.let { "Creator: $it" },
                r.url.takeIf { it.isNotBlank() }?.let { "URL: $it" },
                r.notes.takeIf { it.isNotBlank() }?.let { "Instructions: $it" },
            ))
        }
        h3("Game branding and identity")
        for (slot in BrandingSlot.CORE) {
            val b = project.branding[slot]
            val key = Keys.brandingKeyForSlot.getValue(slot)
            val nice = when (slot) { BrandingSlot.ICON -> "Game/app icon"; BrandingSlot.STUDIO_SPLASH -> "Studio/developer splash"; else -> "Game splash / title screen" }
            val choice = v(key)
            val line = when {
                b?.mode == BrandingMode.UPLOADED -> "uploaded master at `branding/master/${b.localFile?.substringAfterLast('/')}` (${b.originalName}, ${b.width}x${b.height}, sha256 ${b.sha256.take(12)}...). Never modify or overwrite the master; derive every size from it."
                choice == "generate_original" -> "create an original asset in keeping with the art direction and the game's identity."
                choice == "generic_temporary" -> "use a clean generic temporary asset; mark it as replaceable branding (not a gameplay placeholder) in `ASSETS.md`."
                choice == "skip" -> "intentionally omitted by the owner; do not create one."
                else -> "create an original asset."
            }
            sb.append("- **$nice:** $line\n")
        }
        sb.append('\n')
        p("Derive all required platform sizes, formats, adaptive-icon foreground/background and safe-zone variants automatically from the master artwork (for Android: mipmap densities, adaptive icon layers with the monochrome layer, Play Store 512px icon; splash via the platform splash API plus an in-game splash screen). Keep the master file untouched.")

        // 9. Architecture / toolchain
        h2("9. Architecture, toolchain and platforms")
        val e = t.engine
        if (e != null) {
            bullets(listOf(
                "Engine/framework: **${e.name}** (${e.language}). License: ${e.licenseNote}.",
                "Why: ${e.strengths}",
                "Known caveats to design around: ${e.caveats}",
                "Headless validation: ${e.headlessValidation}",
                "Build artifacts: ${e.buildArtifactNotes}",
            ))
        }
        val pins = project.research.filter { it.kind == ResearchKind.TOOLCHAIN_VERSION }
        if (pins.isNotEmpty()) {
            p("Verified toolchain facts (captured with sources by Game Designer; re-verify only if a build fails because of them):")
            bullets(pins.map { n -> "${n.topic}: ${n.summary} (${n.sources.joinToString { it.url }})" })
        } else {
            p("Toolchain versions have not been captured from live sources. Before writing code, verify the current stable versions of the engine, language runtime, build tools and every dependency you select (${e?.baselineVersionNote ?: "verify current stable"}), pin them exactly in the repository (lockfiles / version catalogs / export presets) and record the reasons and sources in `docs/DECISIONS.md`.")
        }
        bullets(listOfNotNull(
            "Target platforms: ${t.platforms.joinToString { Platforms.labels[it] ?: it }.ifEmpty { "not specified" }}",
            v(Keys.PERFORMANCE)?.let { "Performance target: ${label(Keys.PERFORMANCE, it)}" },
            v(Keys.MIN_HARDWARE)?.let { "Minimum hardware: $it" },
            v(Keys.NETWORK_POLICY)?.let { "Network policy: ${label(Keys.NETWORK_POLICY, it)}. ${if (it == "fully_offline" && PlatformPolicy.otaState(project) == SystemState.PRESENT) "Apart from the signed update check described below, the game makes no network calls and works fully offline." else if (it == "fully_offline") "The build must request no INTERNET permission and make no network calls." else "Network features degrade gracefully when offline."}" },
            v(Keys.TOOLCHAIN_PREFS)?.let { "Owner toolchain preferences: $it" },
        ))
        if (PlatformPolicy.otaState(project) == SystemState.PRESENT) {
            h3("Over-the-air updates (owner decision; the Hot Attic Games Mote OTA architecture, see A0)")
            p("The authoritative OTA requirements are in section A0. Hosting and cost: static files on free hosting such as GitHub Releases or Pages; no server of your own, no accounts, no analytics, and no device identifiers or personal data in update requests. Permissions: INTERNET only. Record the update-pipeline tests (bad signature rejected, corrupt artifact rejected, incompatible fingerprint rejected, interrupted download handled, rollback works, offline start works, no repeated update loop) in `docs/VERIFICATION.md`.")
        }
        h3("Repository layout and rules")
        bullets(listOf(
            "Keep game logic separate from rendering/engine glue so rules, simulation and data validation are unit-testable headlessly.",
            "All balance/content numbers live in data files, never scattered in code.",
            "Docs: `docs/DECISIONS.md` (engineering decisions), `docs/ARCHITECTURE.md`, `HANDOFF.md` (current state for the next context), `ASSETS.md` (provenance).",
            "Commit at every stable, tested checkpoint with a clear message; never leave `main` unbuildable. Never force-push or rewrite history on shared branches.",
        ))
        h3("Build pipeline")
        p(pipelineText(t, v(Keys.CI_BUILD)))

        // 10. Performance
        h2("10. Performance requirements")
        bullets(listOfNotNull(
            "Hold the target frame rate (${label(Keys.PERFORMANCE, v(Keys.PERFORMANCE))}) on the minimum hardware in normal play, with graceful quality degradation instead of stutter.",
            if (t.hasGenre("survivors_like") || t.has(Tag.COMBAT)) "Set and enforce a documented maximum on-screen entity count; use object pooling and avoid per-frame allocations." else null,
            if (t.has(Tag.SIMULATION) || t.has(Tag.BUILDING)) "Simulation runs at a fixed timestep with a documented per-tick budget and a stress test at the maximum supported scale." else null,
            "Cold start under 4 seconds on minimum hardware to the main menu; no ANRs; memory stays within a documented ceiling; battery-friendly background behavior.",
        ))

        // 11. Persistence
        h2("11. Persistence and save requirements")
        bullets(listOfNotNull(
            "Save model: ${label(Keys.SAVE_SYSTEM, v(Keys.SAVE_SYSTEM))}.",
            "Saves are versioned with explicit migrations and tested with fixture saves from every previous version.",
            "Writes are atomic (write temp then rename); corrupt saves are detected, backed up and recovered from without crashing.",
            "The app can be killed at any moment on a phone; autosave on pause/background and at safe points.",
            "Settings persist separately from game saves. A 'reset data' option exists if listed in the menus.",
        ))

        // 12. Accessibility
        h2("12. Accessibility")
        val acc = project.list(Keys.ACCESSIBILITY)
        if (acc.isEmpty()) p("No accessibility features beyond platform defaults were specified.") else {
            p("Implement and test each of the following; all must be reachable from the settings screen and apply immediately:")
            bullets(acc.map { accessibilityText(it) })
        }

        // 13. Testing / validation
        h2("13. Testing and validation")
        v(Keys.TESTING)?.let { p(it) }
        h3("Validation ladder (run in this order; repair failures before proceeding)")
        bullets(listOf(
            "Static checks: lint/format/type checks for the chosen toolchain.",
            "Unit tests for all rules, simulations, data validation (content tables complete, references resolve, no negative or infinite values).",
            "Persistence tests: save/load round trip, corrupt save recovery, migration from fixtures.",
            "Headless smoke playtest(s): " + (t.smokeChecks().joinToString(" ").ifBlank { "scripted playthrough of the core loop completes without crash or soft-lock." }),
            "Build the real artifact for every target platform in CI and confirm it installs/launches (emulator or headless where available). Report only what you actually ran.",
            "UI inspection: capture screenshots of every screen at phone and tablet aspect ratios and review them for clipping, overlap and unreadable text.",
        ))
        h3("Verify before finishing (game-specific - actually play these paths)")
        bullets(VerificationPlan.steps(project))
        h3("Definition of done for the first build")
        p(v(Keys.DONE) ?: "The complete core loop is playable start to finish; all systems in this file are implemented; automated validation is green; installable artifact produced.")
        if (prototype) p("Interpretation: this is a fully functional PROTOTYPE of the game described in Part A. Done means the intended game and core loop are really playable and the automated validation is green with an installable build; it does not mean full production content.")

        h3("Deliverable contract")
        bullets(listOfNotNull(
            "A working " + (if (prototype) "prototype" else "game") + " project with source, assets and any editable sources committed to the repository.",
            "The installable/runnable build for ${t.platforms.joinToString { Platforms.labels[it] ?: it }.ifEmpty { "the target platform" }}, with its location reported.",
            "A README with exact install/launch instructions and controls, and `docs/VERIFICATION.md` listing what was actually exercised, what was not, and known remaining issues.",
            if (Platforms.ANDROID in t.platforms) "Android: the studio splash (Hot Attic Games, or the owner-supplied logo above) then the game's own title screen, a current target SDK, 16 KB page-size compatibility where native code is used, GitHub Actions producing the artifact, coherent versioning, release artifact naming, and rollback-safe update behaviour where over-the-air updates are used." else null,
        ))

        // 14. Release
        h2("14. Packaging, identity and release")
        bullets(listOfNotNull(
            v(Keys.DISPLAY_NAME)?.let { "Display name: **$it**" },
            v(Keys.PACKAGE_ID)?.let { "Application/package ID: `$it` (never change after first release)" },
            v(Keys.VERSION_STRATEGY)?.let { "Versioning: ${label(Keys.VERSION_STRATEGY, it)}; the CI sets the build code from the run number." },
            v(Keys.STORE_PLAN)?.let { "Distribution: ${label(Keys.STORE_PLAN, it)}" },
            v(Keys.MONETIZATION)?.let { "Monetization: ${label(Keys.MONETIZATION, it)}" },
            v(Keys.PRIVACY)?.let { "Privacy: ${label(Keys.PRIVACY, it)}" },
            v(Keys.SIGNING)?.let { "Signing: ${label(Keys.SIGNING, it)}. Keys and keystores are never in the repository; debug-signed builds are acceptable for owner playtests." },
            "Request only the permissions the game actually needs and document each in `docs/DECISIONS.md`.",
        ))

        // 15. Execution & resources
        h2("15. Execution plan and resource rules")
        p("Resource estimate: **${scope.resources.level.label}**. ${scope.resources.explanation}")
        p("Claude environment expectation: ${project.prefs.claudePlan.label}; usage style: ${project.prefs.usageStyle.label}.")
        h3("Phases")
        bullets(scope.phases)
        h3("Resource discipline")
        bullets(scope.resources.strategy + listOf(
            "Do not research settled questions repeatedly; persist findings in `docs/DECISIONS.md`.",
            "Do not sacrifice product quality merely to save tokens; do shrink process overhead instead.",
            "Do not ask the owner anything already answered in this file. Escalate only genuine creative decisions not covered here, credentials/permissions only the owner can supply, or verified blockers with no engineering alternative.",
        ))

        // 16. Exclusions
        h2("16. Explicit exclusions and deferred features")
        val ex = v(Keys.EXCLUSIONS)
        if (ex != null) p(ex) else p("No exclusions were declared beyond: online multiplayer, accounts and cloud services (not part of this build).")

        // 17. Continuation
        if (project.mode != ProjectMode.NEW_GAME) {
            h2("17. Repository reality and continuation plan")
            val r = project.repo
            val ins = r?.inspection
            if (r != null) p("Repository: ${r.owner}/${r.repo}${if (r.branch.isNotBlank()) " (branch ${r.branch})" else ""}.")
            if (ins != null) {
                p("Inspected ${ins.inspectedAt}: engine **${ins.detectedEngine.ifBlank { "unknown" }}** ${ins.detectedEngineVersion}, build system **${ins.buildSystem.ifBlank { "unknown" }}**, head ${ins.headSha.take(10)}.")
                if (ins.verifiedFacts.isNotEmpty()) { p("Verified facts (observed in the repository):"); bullets(ins.verifiedFacts) }
                if (ins.assumptions.isNotEmpty()) { p("Assumptions (unverified - confirm by inspection before relying on them):"); bullets(ins.assumptions) }
            } else p("No repository inspection is attached. Inspect the repository yourself first and distinguish verified facts from assumptions.")
            bullets(listOfNotNull(
                v(Keys.CONTINUATION_GOAL)?.let { "Goal: ${label(Keys.CONTINUATION_GOAL, it)}" },
                v(Keys.PRESERVE_SYSTEMS)?.let { "Must preserve: $it" },
                v(Keys.KNOWN_ISSUES)?.let { "Known problems: $it" },
                "Create a rollback tag/branch before changing anything. Do not replace working systems merely because a rewrite is easier. Modernize without destabilizing the current playable line.",
            ))
            val open = project.feedback.filter { it.status == FeedbackStatus.OPEN }
            if (open.isNotEmpty()) {
                h3("Open playtest feedback (against spec v${open.maxOf { it.againstVersion }})")
                p("Reproduce each item first (write a failing test or scripted scenario where feasible), fix the root cause, then verify.")
                bullets(open.map { fb ->
                    "[${fb.severity.name}] ${fb.text}" + if (fb.attachments.isNotEmpty()) " (attachments: ${fb.attachments.joinToString { it.name }})" else ""
                })
            }
        }

        // PART D
        sb.append("# PART D - UNRESOLVED AND DELEGATED DECISIONS\n\n")
        val unresolved = Fields.all.filter { f -> f.isRelevant(t) && f.required && f.key != Keys.CONCEPT && project.decision(f.key)?.let { it.status == DecisionStatus.CONFIRMED && it.value.isNotBlank() } != true }
            .map { f -> if (project.decision(f.key)?.status == DecisionStatus.PROPOSED) "${f.title}: inferred but not confirmed by the owner (${label(f.key, project.value(f.key))}); treat as a recommendation and make the least surprising reversible choice." else "${f.title}: not yet decided (${f.prompt})" }
        val deferred = project.decisions.filter { it.value.status == DecisionStatus.DEFERRED && it.key != Keys.FIVE_MINUTES && Fields.get(it.key)?.required == true }.keys.map { "${Fields.get(it)?.title ?: it}: deliberately deferred by the owner; do not build it." }
        val delegated = project.decisions.filter { (_, d) -> d.prov == Provenance.OWNER_ACCEPTED_RECOMMENDATION }.keys.map { Fields.get(it)?.title ?: it }
        if (unresolved.isEmpty() && deferred.isEmpty()) p("None unresolved. Every build-critical decision is resolved by the owner, delegated, or left to implementation discretion above.") else bullets(unresolved + deferred)
        if (delegated.isNotEmpty()) p("Delegated to Bob's recommendation (recorded in Part B): ${delegated.joinToString(", ")}.")
        project.designApproval?.let { p("The owner's plain-English design review was ${if (it.by == "delegated") "delegated (approved on the owner's behalf)" else "approved"}.") }


        // PART E
        sb.append("\n# PART E - MUST NOT CHANGE (binding constraints; do not reinterpret, 'improve' or normalise any of them)\n\n")
        if (invariants == null && derivedInvariants == null) p("None declared by the owner.")
        else {
            invariants?.let { p("Stated by the owner:"); bullets(MetaConversation.designSentences(it.replace(" | ", ". "), 6)) }
            if (derivedInvariants != null && derivedInvariants != invariants) { p("Implied by the owner's own decisions:"); bullets(MetaConversation.designSentences(derivedInvariants, 3)) }
        }

        // 18. Human-only
        h2(if (project.mode == ProjectMode.NEW_GAME) "17. Human-only steps" else "18. Human-only steps")
        p("Only these steps genuinely need the owner. Do everything else autonomously.")
        bullets(audit.humanOnlyTasks)

        if (audit.findings.any { it.level != com.hotattic.gamedesigner.core.engine.AuditLevel.INFO }) {
            h2("Audit notes at generation time")
            p("The Game Designer audit reported the following when this version was generated. Treat blocking items as owner-visible risks and resolve or work around them.")
            bullets(audit.findings.filter { it.level != com.hotattic.gamedesigner.core.engine.AuditLevel.INFO }.map { "[${it.level.name}] ${it.message}" })
        }
        return sb.toString().trimEnd() + "\n"
    }

    /** (heading line, bullets) for each policy that applies to this project; empty when none does. */
    private fun platformPolicyBlock(project: Project): List<Pair<String, List<String>>> = buildList {
        if (PlatformPolicy.androidSelected(project)) add("**${PlatformPolicy.androidHeadline}**" to PlatformPolicy.androidRequirements)
        when (PlatformPolicy.otaState(project)) {
            SystemState.PRESENT -> add("**${PlatformPolicy.moteHeadline}**" to PlatformPolicy.moteRequirements)
            SystemState.ABSENT -> add("" to listOf(PlatformPolicy.otaAbsent))
            else -> Unit
        }
    }

    private fun resolutionText(r: AssetResolution) = when (r) {
        AssetResolution.EXTERNAL_CC0 -> "use a CC0/public-domain set from external sources"
        AssetResolution.EXTERNAL_OTHER_LICENSE -> "external asset under a non-CC0 license (attribution rules apply)"
        AssetResolution.PROCEDURAL -> "original, generated procedurally by project code"
        AssetResolution.GENERATED_ORIGINAL -> "original, generated"
        AssetResolution.USER_SUPPLIED -> "use the owner-supplied asset packs (inspect them first)"
        AssetResolution.DEFERRED_BY_OWNER -> "deferred by the owner"
    }

    private fun optionLabel(t: Traits, key: String, value: String): String {
        val f: Field = Fields.get(key) ?: return value
        if (f.kind == com.hotattic.gamedesigner.core.schema.FieldKind.TEXT) return value
        if (key == Keys.ASSET_POLICY) com.hotattic.gamedesigner.core.engine.AssetStrategy.label(value)?.let { return it }
        return f.options(t).firstOrNull { it.id == value }?.label ?: when (key) {
            Keys.GENRE -> GenreKnowledge.resolve(value).label
            Keys.PLATFORMS -> Platforms.labels[value] ?: value
            else -> value
        }
    }

    private fun accessibilityText(id: String): String = when (id) {
        "text_scaling" -> "Text size: at least 3 steps (100/130/160%) applied to all text, with layouts that reflow without clipping."
        "colorblind" -> "Colorblind safety: never convey information by color alone; provide shape/pattern/icon redundancy and a palette option verified against deuteranopia/protanopia/tritanopia simulations."
        "reduced_motion" -> "Reduced motion: disables screen shake, flashes, heavy parallax and camera sway."
        "subtitles" -> "Subtitles/captions for all speech and meaningful audio cues, with size and background options."
        "haptics_control" -> "Vibration/haptics: global on/off and intensity control."
        "large_touch_targets" -> "Large touch targets: minimum 48dp, 56dp for primary actions, adequate spacing."
        "difficulty_assists" -> "Difficulty assists: adjustable game speed, jump forgiveness and similar (and damage or aim assist where the game has combat), changeable at any time without penalty."
        "remappable_controls" -> "Fully remappable keyboard/gamepad controls with conflict detection and reset to defaults."
        else -> id
    }

    private fun pipelineText(t: Traits, ci: String?): String {
        val e = t.engine
        val base = when (ci) {
            "github_actions" -> "Use GitHub Actions to build the installable artifact on every push to the working branch and on tags, and upload it as a downloadable workflow artifact. Claude writes and maintains the workflow, reads the run results, and repairs failures until green. "
            "claude_environment" -> "Build in the Claude workspace. First check whether the full toolchain can actually be installed there (network allowlists can block SDK hosts); if not, fall back to GitHub Actions. "
            "local_machine" -> "The owner builds locally; provide an exact, tested `docs/BUILD.md` and a single-command build script. "
            else -> "Provide a reproducible command-line build that produces the installable artifact. "
        }
        val engineSpecific = when (e?.id) {
            "godot" -> "Godot: pin the engine version, check export templates in, commit export presets, and run headless imports/tests with the pinned binary; Android needs JDK, Android SDK and a debug keystore for installable test builds."
            "unity" -> "Unity: use the pinned editor in batchmode via GameCI; activation uses a license secret in CI."
            "libgdx", "android_native" -> "Gradle: wrapper pinned, Android Gradle Plugin and compile/target SDK pinned to a verified compatible set; `assembleDebug` for owner playtests and `bundleRelease` for stores."
            "web_phaser", "web_three" -> "Web: Vite production build deployed as static files; wrap with Capacitor for Android/iOS packages if those platforms are targeted."
            "flutter_flame" -> "Flutter: pin the stable channel version; `flutter build apk --debug/--release`."
            "bevy" -> "Rust: pin toolchain via rust-toolchain.toml; commit Cargo.lock; cross-compile per platform in CI."
            else -> "Pin all tool versions in the repository."
        }
        val ios = if (Platforms.IOS in t.platforms) " iOS builds run on a macOS runner and require the owner's Apple signing assets (human step)." else ""
        return base + engineSpecific + ios
    }
}
