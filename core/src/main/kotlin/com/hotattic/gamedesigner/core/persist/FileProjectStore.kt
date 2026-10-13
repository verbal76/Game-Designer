package com.hotattic.gamedesigner.core.persist

import com.hotattic.gamedesigner.core.model.AppSettings
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.ProjectMode
import java.io.File

data class ProjectSummary(
    val id: String, val name: String, val mode: ProjectMode, val updatedAt: Long, val versionCount: Int, val corrupt: Boolean = false,
    /** What a project card shows. All optional and derived from the project when it is listed; never stored separately. */
    val genre: String? = null, val primaryPlatform: String? = null, val artStyle: String? = null, val stage: String? = null, val openStages: Int = 0, val stageCount: Int = 0, val openDecisions: Int = 0,
)

/**
 * Local-first, file-based project storage. One directory per project:
 *   projects/<id>/project.json (+ project.json.bak, previous good copy)
 *   projects/<id>/assets/...  (uploaded masters, never modified)
 * Writes are atomic (temp file + rename). A corrupt project.json is preserved as *.corrupt and the .bak is used.
 */
class FileProjectStore(private val root: File) {

    private val projectsDir get() = File(root, "projects").also { it.mkdirs() }
    private val settingsFile get() = File(root, "settings.json")

    fun projectDir(id: String): File = File(projectsDir, sanitize(id)).also { it.mkdirs() }
    fun assetsDir(id: String): File = File(projectDir(id), "assets").also { it.mkdirs() }
    private fun projectFile(id: String) = File(projectDir(id), "project.json")

    fun list(): List<ProjectSummary> =
        (projectsDir.listFiles { f -> f.isDirectory } ?: emptyArray()).mapNotNull { dir ->
            val id = dir.name
            try {
                val p = load(id) ?: return@mapNotNull null
                runCatching { card(p) }.getOrElse { ProjectSummary(p.id, p.name, p.mode, p.updatedAt, p.versions.size) }
            } catch (e: Exception) {
                ProjectSummary(id, id, ProjectMode.NEW_GAME, dir.lastModified(), 0, corrupt = true)
            }
        }.sortedByDescending { it.updatedAt }

    private fun card(p: com.hotattic.gamedesigner.core.model.Project): ProjectSummary {
        val t = com.hotattic.gamedesigner.core.schema.Traits(p)
        val progress = com.hotattic.gamedesigner.core.engine.DesignStages.of(p)
        val c = com.hotattic.gamedesigner.core.engine.CompletenessEngine.compute(p)
        val f = com.hotattic.gamedesigner.core.schema.Fields
        fun label(k: String) = p.value(k)?.let { v -> com.hotattic.gamedesigner.core.generate.PlainLabels.of(p, k) ?: v }
        return ProjectSummary(p.id, p.name, p.mode, p.updatedAt, p.versions.size, false,
            genre = t.genres.firstOrNull()?.label?.substringBefore(" (")?.substringBefore(" /"), primaryPlatform = t.primaryPlatform?.let { com.hotattic.gamedesigner.core.schema.Platforms.labels[it] },
            artStyle = label(com.hotattic.gamedesigner.core.schema.Keys.ART_DIRECTION), stage = progress.current?.title ?: progress.stages.lastOrNull()?.takeIf { p.versions.isNotEmpty() }?.let { "Handoff generated" },
            openStages = progress.stages.size - progress.completed, stageCount = progress.stages.size, openDecisions = c.missingRequired.size + c.proposed.count { it.required })
    }

    fun load(id: String): Project? {
        val f = projectFile(id)
        if (!f.exists()) return null
        return try {
            ProjectCodec.decode(f.readText())
        } catch (e: UnsupportedSchemaException) {
            throw e
        } catch (e: Exception) {
            val bak = File(f.path + ".bak")
            f.copyTo(File(f.path + ".corrupt"), overwrite = true)
            if (bak.exists()) ProjectCodec.decode(bak.readText()).also { save(it) } else throw e
        }
    }

    fun save(p: Project) {
        val f = projectFile(p.id)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(ProjectCodec.encode(p))
        // Keep the previous good copy before replacing.
        if (f.exists()) f.copyTo(File(f.path + ".bak"), overwrite = true)
        if (!tmp.renameTo(f)) {
            tmp.copyTo(f, overwrite = true)
            tmp.delete()
        }
    }

    fun delete(id: String): Boolean = projectDir(id).deleteRecursively()

    fun loadSettings(): AppSettings = try {
        if (settingsFile.exists()) ProjectCodec.decodeSettings(settingsFile.readText()) else AppSettings()
    } catch (e: Exception) { AppSettings() }

    fun saveSettings(s: AppSettings) {
        root.mkdirs()
        val tmp = File(settingsFile.path + ".tmp")
        tmp.writeText(ProjectCodec.encodeSettings(s))
        if (!tmp.renameTo(settingsFile)) { tmp.copyTo(settingsFile, overwrite = true); tmp.delete() }
    }

    private fun sanitize(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
}
