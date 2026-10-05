package com.hotattic.gamedesigner

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hotattic.gamedesigner.core.director.DirectorAction
import com.hotattic.gamedesigner.core.engine.AuditEngine
import com.hotattic.gamedesigner.core.engine.InterpreterKind
import com.hotattic.gamedesigner.core.engine.ProjectOps
import com.hotattic.gamedesigner.core.generate.ExportPackage
import com.hotattic.gamedesigner.core.generate.SpecVersioning
import com.hotattic.gamedesigner.core.github.RepoSummary
import com.hotattic.gamedesigner.core.llm.LlmMessage
import com.hotattic.gamedesigner.core.llm.LlmRequest
import com.hotattic.gamedesigner.core.llm.LlmResult
import com.hotattic.gamedesigner.core.model.AppSettings
import com.hotattic.gamedesigner.core.model.BrandingAsset
import com.hotattic.gamedesigner.core.model.BrandingMode
import com.hotattic.gamedesigner.core.model.BrandingSlot
import com.hotattic.gamedesigner.core.model.DecisionSource
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.ProjectMode
import com.hotattic.gamedesigner.core.model.ProjectPrefs
import com.hotattic.gamedesigner.core.model.ResearchKind
import com.hotattic.gamedesigner.core.model.Role
import com.hotattic.gamedesigner.core.persist.ProjectSummary
import com.hotattic.gamedesigner.core.persist.UnsupportedSchemaException
import com.hotattic.gamedesigner.core.research.ResearchOutcome
import com.hotattic.gamedesigner.core.schema.Keys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

sealed class UiEvent {
    data class Message(val text: String) : UiEvent()
    data class PickImage(val slot: String) : UiEvent()
    data class OpenSpec(val projectId: String) : UiEvent()
    data class OpenChat(val projectId: String) : UiEvent()
}

/** Single ViewModel for the whole app: owns settings, the project list, the open project and long-running work. */
class AppViewModel(app: Application, private val c: AppContainer) : AndroidViewModel(app) {
    private val lock = Mutex()

    val settings: StateFlow<AppSettings> = c.settings
    private val _projects = MutableStateFlow<List<ProjectSummary>>(emptyList())
    val projects = _projects.asStateFlow()
    private val _current = MutableStateFlow<Project?>(null)
    val current = _current.asStateFlow()
    private val _busy = MutableStateFlow<String?>(null)
    /** Non-null text while something is running ("Bob is thinking..."). */
    val busy = _busy.asStateFlow()
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private val _interpreter = MutableStateFlow(InterpreterKind.RULES)
    /** What currently interprets the owner's words; RULES means no AI model is configured. */
    val interpreter = _interpreter.asStateFlow()

    val container get() = c

    init { refresh(); refreshInterpreter() }

    fun refreshInterpreter() = viewModelScope.launch { _interpreter.value = try { c.director().interpreterKind() } catch (e: Exception) { InterpreterKind.RULES } }

    private fun toast(t: String) { _events.tryEmit(UiEvent.Message(t)) }

    fun refresh() = viewModelScope.launch(Dispatchers.IO) { _projects.value = c.store.list() }

    fun updateSettings(f: (AppSettings) -> AppSettings) { c.updateSettings(f); refreshInterpreter() }

    // ---- Projects ----

    private suspend fun commit(p: Project) {
        withContext(Dispatchers.IO) { c.store.save(p) }
        if (_current.value?.id == p.id) _current.value = p
        _projects.value = withContext(Dispatchers.IO) { c.store.list() }
    }

    fun createProject(mode: ProjectMode, onCreated: (String) -> Unit = {}) = viewModelScope.launch {
        val s = c.settings.value
        val now = System.currentTimeMillis()
        val prefs = ProjectPrefs(s.defaultExperience, s.defaultClaudePlan, s.defaultUsageStyle)
        var p = ProjectOps.newProject(UUID.randomUUID().toString(), "", mode, prefs, now)
        if (mode != ProjectMode.EXISTING_GAME) p = c.director().start(p, s)
        commit(p)
        _current.value = p
        onCreated(p.id)
    }

    fun open(id: String) = viewModelScope.launch {
        val p = try { withContext(Dispatchers.IO) { c.store.load(id) } } catch (e: UnsupportedSchemaException) { toast(e.message ?: "Newer project format"); null } catch (e: Exception) { toast("Could not open this project: ${e.message}"); null }
        _current.value = p
        refreshInterpreter()
    }

    fun deleteProject(id: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { c.store.delete(id) }
        if (_current.value?.id == id) _current.value = null
        refresh()
    }

    fun renameProject(name: String) = viewModelScope.launch { _current.value?.let { commit(it.copy(name = name.trim(), updatedAt = System.currentTimeMillis())) } }

    fun setProjectPrefs(prefs: ProjectPrefs) = viewModelScope.launch { _current.value?.let { commit(it.copy(prefs = prefs)) } }

    fun setMode(mode: ProjectMode) = viewModelScope.launch {
        val p = _current.value ?: return@launch
        commit(c.director().enterMode(p, c.settings.value, mode))
    }

    // ---- Conversation ----

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            lock.withLock {
                val p = _current.value ?: return@withLock
                _busy.value = "${c.settings.value.directorName} is thinking..."
                try {
                    val turn = c.director().handleUserMessage(p, c.settings.value, trimmed)
                    commit(turn.project)
                    when (val a = turn.action) {
                        DirectorAction.GenerateSpec -> generateLocked()
                        is DirectorAction.RequestUpload -> _events.tryEmit(UiEvent.PickImage(a.slot))
                        null -> Unit
                    }
                } catch (t: Throwable) {
                    toast("Something went wrong: ${t.message ?: t.javaClass.simpleName}")
                } finally { _busy.value = null }
            }
        }
    }

    /** Structured answer from the question card (single tap or multi-select Continue). */
    fun submitSelection(fieldKey: String, ids: List<String>) {
        viewModelScope.launch {
            lock.withLock {
                val p = _current.value ?: return@withLock
                _busy.value = "${c.settings.value.directorName} is thinking..."
                try {
                    val turn = c.director().submitSelection(p, c.settings.value, fieldKey, ids)
                    commit(turn.project)
                    when (val a = turn.action) {
                        DirectorAction.GenerateSpec -> generateLocked()
                        is DirectorAction.RequestUpload -> _events.tryEmit(UiEvent.PickImage(a.slot))
                        null -> Unit
                    }
                } catch (t: Throwable) {
                    toast("Something went wrong: ${t.message ?: t.javaClass.simpleName}")
                } finally { _busy.value = null }
            }
        }
    }

    // ---- Spec generation ----

    fun generate() = viewModelScope.launch { lock.withLock { _busy.value = "Generating spec..."; try { generateLocked() } finally { _busy.value = null } } }

    private suspend fun generateLocked() {
        var p = _current.value ?: return
        val s = c.settings.value
        // Capture current toolchain facts (with sources) so the spec can pin versions, when permitted.
        val engine = p.value(Keys.ENGINE)
        if (s.internetResearchAllowed && engine != null) {
            _busy.value = "Checking current toolchain versions..."
            when (val r = c.research.toolchainFacts(engine)) {
                is ResearchOutcome.Found -> p = p.copy(research = p.research.filter { n -> r.value.none { it.topic == n.topic } || n.kind != ResearchKind.TOOLCHAIN_VERSION } + r.value)
                else -> Unit
            }
            _busy.value = "Generating spec..."
        }
        val now = System.currentTimeMillis()
        val gen = SpecVersioning.generate(p, SpecVersioning.suggestedKind(p), now, LocalDate.now().toString())
        if (gen.blocked) {
            val msg = "I can't export yet - the consistency review found contradictions:\n" + gen.review.errors.take(6).joinToString("\n") { "- ${it.message}" } + "\n\nTell me which way each should go; your latest word wins."
            commit(ProjectOps.setPending(ProjectOps.addMessage(p, Role.DIRECTOR, msg, now), null))
            return
        }
        val next = gen.project
        val v = next.versions.last()
        val done = ProjectOps.addMessage(next, Role.DIRECTOR, "Generated spec v${v.number} (${v.kind.label}). ${v.auditSummary} Open the Spec screen to copy, share or export it.", now)
        commit(done)
        _events.tryEmit(UiEvent.OpenSpec(done.id))
    }

    /** Optional cloud review of a generated spec (needs a configured provider). */
    fun reviewWithCloud(versionNumber: Int, onResult: (String) -> Unit) = viewModelScope.launch {
        val p = _current.value ?: return@launch
        val v = p.versions.firstOrNull { it.number == versionNumber } ?: return@launch
        if (!c.cloudConfigured()) { onResult("No cloud provider is configured. Add an API key in Settings to use Claude for a deeper review."); return@launch }
        _busy.value = "Asking Claude to review the spec..."
        try {
            val r = c.cloud.complete(LlmRequest(
                "You are a meticulous reviewer of game build specifications meant for an autonomous coding agent. List contradictions, missing systems, vague requirements likely to cause wrong improvisation, and technical/build holes. Be concrete and brief; use bullet points; no preamble.",
                listOf(LlmMessage("user", v.claudeMd.take(60_000))), maxTokens = 1500,
            ))
            onResult(when (r) { is LlmResult.Ok -> r.text; is LlmResult.Failure -> "The review failed: ${r.reason}" })
        } finally { _busy.value = null }
    }

    // ---- Branding ----

    fun importBranding(slot: String, uri: Uri) = viewModelScope.launch {
        val p = _current.value ?: return@launch
        val ctx = getApplication<Application>()
        try {
            val asset = withContext(Dispatchers.IO) {
                val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "image"
                val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                require(bytes.size in 1..(25 * 1024 * 1024)) { "The image must be under 25 MB." }
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                require(opts.outWidth > 0 && opts.outHeight > 0) { "That file isn't a readable image (PNG, JPEG or WebP)." }
                val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                val ext = when (opts.outMimeType) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
                val dir = File(c.store.assetsDir(p.id), "branding").also { it.mkdirs() }
                p.branding[slot]?.localFile?.let { File(dir, File(it).name).delete() }
                val file = File(dir, "${slot}_${sha.take(8)}.$ext")
                file.writeBytes(bytes)
                BrandingAsset(slot, BrandingMode.UPLOADED, "branding/${file.name}", name, sha, opts.outWidth, opts.outHeight, System.currentTimeMillis())
            }
            val key = Keys.brandingKeyForSlot.getValue(slot)
            val now = System.currentTimeMillis()
            commit(ProjectOps.setDecision(p.copy(branding = p.branding + (slot to asset)), key, "upload", DecisionSource.USER, now))
            toast("Image added. The original is kept untouched as the master.")
        } catch (e: IllegalArgumentException) { toast(e.message ?: "Could not use that image.") }
        catch (e: Exception) { toast("Could not import the image: ${e.message}") }
    }

    fun setBrandingChoice(slot: String, choice: String) = viewModelScope.launch {
        val p = _current.value ?: return@launch
        val mode = when (choice) { "generate_original" -> BrandingMode.GENERATE_ORIGINAL; "generic_temporary" -> BrandingMode.GENERIC_TEMPORARY; "skip" -> BrandingMode.SKIP; else -> BrandingMode.UNSET }
        p.branding[slot]?.localFile?.let { rel -> withContext(Dispatchers.IO) { File(c.store.assetsDir(p.id), rel).delete() } }
        val b = BrandingAsset(slot, mode, addedAt = System.currentTimeMillis())
        commit(ProjectOps.setDecision(p.copy(branding = p.branding + (slot to b)), Keys.brandingKeyForSlot.getValue(slot), choice, DecisionSource.USER, System.currentTimeMillis()))
    }

    fun brandingFile(slot: String): File? {
        val p = _current.value ?: return null
        val rel = p.branding[slot]?.localFile ?: return null
        return File(c.store.assetsDir(p.id), rel).takeIf { it.exists() }
    }

    // ---- Export / backup ----

    fun exportPackage(versionNumber: Int, out: Uri) = viewModelScope.launch {
        val p = _current.value ?: return@launch
        val v = p.versions.firstOrNull { it.number == versionNumber } ?: return@launch
        withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openOutputStream(out)!!.use { it.write(ExportPackage.zip(ExportPackage.files(p, v))) }
        }
        toast("Exported spec package.")
    }

    fun saveText(out: Uri, text: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.openOutputStream(out)!!.use { it.write(text.toByteArray()) } }
        toast("Saved.")
    }

    fun backupAll(out: Uri) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openOutputStream(out)!!.use { os ->
                java.util.zip.ZipOutputStream(os).use { z ->
                    val root = File(getApplication<Application>().filesDir, "projects")
                    root.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") && !it.name.endsWith(".corrupt") }.forEach { f ->
                        z.putNextEntry(java.util.zip.ZipEntry("projects/" + f.relativeTo(root).path.replace('\\', '/'))); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
                    }
                }
            }
        }
        toast("Backup saved. It contains your projects and uploaded images, not your API keys.")
    }

    fun restoreBackup(uri: Uri) = viewModelScope.launch {
        try {
            val count = withContext(Dispatchers.IO) {
                val root = File(getApplication<Application>().filesDir, "projects").also { it.mkdirs() }
                var n = 0
                java.util.zip.ZipInputStream(getApplication<Application>().contentResolver.openInputStream(uri)!!).use { z ->
                    var e = z.nextEntry
                    while (e != null) {
                        val name = e.name
                        if (!e.isDirectory && name.startsWith("projects/")) {
                            val target = File(root, name.removePrefix("projects/"))
                            // Zip-slip guard: the resolved path must stay inside the projects directory.
                            if (target.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
                                target.parentFile?.mkdirs(); target.outputStream().use { z.copyTo(it) }
                                if (name.endsWith("/project.json")) n++
                            }
                        }
                        e = z.nextEntry
                    }
                }
                n
            }
            refresh(); toast("Restored $count project(s).")
        } catch (e: Exception) { toast("That doesn't look like a Game Designer backup: ${e.message}") }
    }

    // ---- GitHub ----

    private val _repos = MutableStateFlow<List<RepoSummary>?>(null)
    val repos = _repos.asStateFlow()

    fun loadRepos() = viewModelScope.launch {
        _busy.value = "Loading your repositories..."
        try { _repos.value = c.github().listRepos() } catch (e: Exception) { _repos.value = emptyList(); toast(e.message ?: "Could not load repositories") } finally { _busy.value = null }
    }

    fun startFromRepo(repo: RepoSummary, onCreated: (String) -> Unit) = viewModelScope.launch {
        val s = c.settings.value
        _busy.value = "Inspecting ${repo.owner}/${repo.name}..."
        try {
            val now = System.currentTimeMillis()
            val ins = c.github().inspect(repo.owner, repo.name, now)
            var p = ProjectOps.newProject(UUID.randomUUID().toString(), repo.name, ProjectMode.EXISTING_GAME, ProjectPrefs(s.defaultExperience, s.defaultClaudePlan, s.defaultUsageStyle), now)
            p = ProjectOps.applyInspection(p, repo.owner, repo.name, ins, now)
            val facts = (ins.verifiedFacts.take(6).map { "- $it" } + ins.assumptions.take(2).map { "- (unverified) $it" }).joinToString("\n")
            p = ProjectOps.addMessage(p, Role.SYSTEM, "Inspected ${repo.owner}/${repo.name} (branch ${ins.defaultBranch}):\n$facts", now)
            p = c.director().start(p, s)
            commit(p); _current.value = p; onCreated(p.id)
        } catch (e: Exception) { toast(e.message ?: "Inspection failed") } finally { _busy.value = null }
    }

    fun auditSummary(p: Project) = AuditEngine.audit(p)
}
