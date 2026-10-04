package com.hotattic.gamedesigner.core.model

import kotlinx.serialization.Serializable

/** Current persisted project schema version. Bump together with a migration in persist/Migrations.kt. */
const val PROJECT_SCHEMA_VERSION = 1

@Serializable
enum class ProjectMode { NEW_GAME, EXISTING_GAME, PLAYTEST_CONTINUE }

@Serializable
enum class Experience { BEGINNER, INTERMEDIATE, EXPERT }

@Serializable
enum class ClaudePlan(val label: String) {
    PRO("Claude Pro"),
    MAX_5X("Claude Max 5x"),
    MAX_20X("Claude Max 20x"),
    TEAM_OR_API("Team / Enterprise / API billing"),
    UNSURE("Not sure"),
}

@Serializable
enum class UsageStyle(val label: String) {
    CONSERVATIVE("Conservative - share my allowance with other projects"),
    BALANCED("Balanced"),
    AGGRESSIVE("Aggressive - use what it takes"),
}

@Serializable
enum class Category(val label: String) {
    GAMEPLAY("Gameplay"),
    CONTENT("Content"),
    ART("Art & Audio"),
    TECHNICAL("Technical"),
    PLATFORM("Platform"),
    ASSETS("Assets"),
    RELEASE("Release"),
}

@Serializable
enum class DecisionSource {
    /** Said directly by the owner. */
    USER,
    /** Owner delegated ("choose for me"); Director picked. */
    DIRECTOR_CHOICE,
    /** Inferred from something the owner said or from research; still awaiting confirmation unless status says otherwise. */
    INFERRED,
    /** Owner knowingly chose against a Director recommendation. */
    OVERRIDE,
    /** Applied by the Director from the schema default without being asked. */
    DEFAULT,
}

@Serializable
enum class DecisionStatus { CONFIRMED, PROPOSED, DEFERRED }

/**
 * One resolved (or proposed) design decision. Multi-valued answers are stored joined by [LIST_SEPARATOR]
 * so the schema stays flat and forward compatible.
 */
@Serializable
data class Decision(
    val value: String,
    val source: DecisionSource,
    val status: DecisionStatus = DecisionStatus.CONFIRMED,
    val note: String = "",
    /** If this is an informed override, what the Director had recommended. */
    val overrides: String? = null,
    val updatedAt: Long = 0L,
) {
    fun list(): List<String> = value.split(LIST_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        const val LIST_SEPARATOR = "|"
        fun joinList(values: Collection<String>) = values.joinToString(LIST_SEPARATOR)
    }
}

@Serializable
enum class Role { USER, DIRECTOR, SYSTEM }

@Serializable
data class QuickReply(val label: String, val send: String)

@Serializable
data class ChatMessage(
    val id: String,
    val role: Role,
    val text: String,
    val at: Long,
    /** Schema field this message is asking about, if any. */
    val fieldKey: String? = null,
    val quickReplies: List<QuickReply> = emptyList(),
)

@Serializable
data class SourceRef(
    val title: String,
    val url: String,
    val retrievedAt: Long,
    val license: String = "",
)

@Serializable
data class ResearchNote(
    val id: String,
    val topic: String,
    val summary: String,
    val sources: List<SourceRef>,
    val kind: ResearchKind = ResearchKind.GENERAL,
    val createdAt: Long,
)

@Serializable
enum class ResearchKind { GENERAL, REFERENCE_GAME, TOOLCHAIN_VERSION, ASSET_LICENSE, REPOSITORY }

@Serializable
data class ReferenceGame(
    val name: String,
    /** Which aspects of the reference the owner wants ("the roguelite loop", "the moving castle"). */
    val aspects: List<String> = emptyList(),
    val summary: String = "",
    val traits: List<String> = emptyList(),
    val sources: List<SourceRef> = emptyList(),
)

@Serializable
enum class AssetResolution {
    EXTERNAL_CC0,
    EXTERNAL_OTHER_LICENSE,
    PROCEDURAL,
    GENERATED_ORIGINAL,
    USER_SUPPLIED,
    DEFERRED_BY_OWNER,
}

@Serializable
data class AssetRecord(
    /** Stable id of the need this record satisfies (see engine/AssetPlan). */
    val needId: String,
    val resolution: AssetResolution,
    val description: String = "",
    val source: String = "",
    val creator: String = "",
    val license: String = "",
    val url: String = "",
    val verifiedAt: Long? = null,
    val notes: String = "",
)

object BrandingSlot {
    const val ICON = "icon"
    const val STUDIO_SPLASH = "studio_splash"
    const val GAME_SPLASH = "game_splash"
    // Future slots (logo, store feature graphic, loading artwork...) are just new string keys; no migration needed.
    val CORE = listOf(ICON, STUDIO_SPLASH, GAME_SPLASH)
}

@Serializable
enum class BrandingMode { UNSET, UPLOADED, GENERATE_ORIGINAL, GENERIC_TEMPORARY, SKIP }

@Serializable
data class BrandingAsset(
    val slot: String,
    val mode: BrandingMode = BrandingMode.UNSET,
    /** Path relative to the project's asset directory; the master file is never modified after import. */
    val localFile: String? = null,
    val originalName: String = "",
    val sha256: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val addedAt: Long = 0L,
)

@Serializable
data class RepoLink(
    val owner: String,
    val repo: String,
    val branch: String = "",
    val inspection: RepoInspection? = null,
)

@Serializable
data class RepoInspection(
    val inspectedAt: Long,
    val defaultBranch: String,
    val headSha: String = "",
    val detectedEngine: String = "",
    val detectedEngineVersion: String = "",
    val buildSystem: String = "",
    val languages: List<String> = emptyList(),
    val topLevelEntries: List<String> = emptyList(),
    /** Facts directly observed in the repository. */
    val verifiedFacts: List<String> = emptyList(),
    /** Statements that are assumptions or guesses and must not be treated as fact. */
    val assumptions: List<String> = emptyList(),
    val lastCommitSummaries: List<String> = emptyList(),
    val hasClaudeMd: Boolean = false,
    val hasCi: Boolean = false,
)

@Serializable
enum class VersionKind(val label: String) {
    INITIAL("Initial Build"),
    EXPANSION("Expansion"),
    PLAYTEST_REPAIR("Playtest Repair"),
    RELEASE_POLISH("Release Polish"),
    REVISION("Revision"),
}

@Serializable
data class SpecVersion(
    val number: Int,
    val kind: VersionKind,
    val label: String,
    val createdAt: Long,
    val claudeMd: String,
    val masterPrompt: String,
    /** Decisions as they stood at generation time, so history can be reconstructed. */
    val decisions: Map<String, Decision>,
    val readinessPercent: Int,
    val auditSummary: String = "",
)

@Serializable
enum class AttachmentKind { IMAGE, VIDEO, AUDIO }

@Serializable
data class Attachment(val kind: AttachmentKind, val localFile: String, val name: String)

@Serializable
enum class FeedbackStatus { OPEN, INCORPORATED, DISMISSED }

@Serializable
enum class FeedbackSeverity { BLOCKER, MAJOR, MINOR, SUGGESTION }

@Serializable
data class PlaytestFeedback(
    val id: String,
    val createdAt: Long,
    /** The spec version the owner was playing. */
    val againstVersion: Int,
    val text: String,
    val severity: FeedbackSeverity = FeedbackSeverity.MAJOR,
    val attachments: List<Attachment> = emptyList(),
    val status: FeedbackStatus = FeedbackStatus.OPEN,
)

@Serializable
enum class AckChoice { ACCEPTED_RECOMMENDATION, OVERRIDDEN }

@Serializable
data class ConflictAck(val conflictId: String, val choice: AckChoice, val at: Long)

@Serializable
data class ProjectPrefs(
    val experience: Experience = Experience.BEGINNER,
    val claudePlan: ClaudePlan = ClaudePlan.UNSURE,
    val usageStyle: UsageStyle = UsageStyle.BALANCED,
)

@Serializable
data class Project(
    val id: String,
    val schemaVersion: Int = PROJECT_SCHEMA_VERSION,
    val name: String,
    val mode: ProjectMode = ProjectMode.NEW_GAME,
    val createdAt: Long,
    val updatedAt: Long,
    val prefs: ProjectPrefs = ProjectPrefs(),
    val decisions: Map<String, Decision> = emptyMap(),
    val messages: List<ChatMessage> = emptyList(),
    val references: List<ReferenceGame> = emptyList(),
    val research: List<ResearchNote> = emptyList(),
    val assets: List<AssetRecord> = emptyList(),
    val branding: Map<String, BrandingAsset> = emptyMap(),
    val repo: RepoLink? = null,
    val versions: List<SpecVersion> = emptyList(),
    val feedback: List<PlaytestFeedback> = emptyList(),
    val conflictAcks: List<ConflictAck> = emptyList(),
    /** Field the Director most recently asked about and is waiting on. */
    val pendingFieldKey: String? = null,
    /** Keys of conflicts already announced to the owner, to avoid repeating. */
    val announcedConflicts: List<String> = emptyList(),
    /** Fields the owner explicitly postponed this session ("ask me later"). */
    val postponed: List<String> = emptyList(),
) {
    fun decision(key: String): Decision? = decisions[key]
    fun value(key: String): String? = decisions[key]?.value?.takeIf { it.isNotBlank() }
    fun list(key: String): List<String> = decisions[key]?.list().orEmpty()
}

/** App-level (not per-project) settings. Secrets are never stored here; see the Android SecretStore. */
@Serializable
data class AppSettings(
    val directorName: String = "Bob",
    val onboardingComplete: Boolean = false,
    val defaultExperience: Experience = Experience.BEGINNER,
    val internetResearchAllowed: Boolean = false,
    val githubEnabled: Boolean = false,
    val defaultClaudePlan: ClaudePlan = ClaudePlan.UNSURE,
    val defaultUsageStyle: UsageStyle = UsageStyle.BALANCED,
    val cloudProvider: CloudProviderId = CloudProviderId.NONE,
    val cloudModel: String = "",
    val localModelId: String = "",
    val localModelEnabled: Boolean = true,
    val studioName: String = "Hot Attic Games",
)

@Serializable
enum class CloudProviderId(val label: String) {
    NONE("None"),
    ANTHROPIC("Anthropic Claude"),
}
