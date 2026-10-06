package com.hotattic.gamedesigner

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hotattic.gamedesigner.core.director.DirectorAction
import com.hotattic.gamedesigner.core.attach.AttachmentIngest
import com.hotattic.gamedesigner.core.attach.IngestFailure
import com.hotattic.gamedesigner.core.attach.IngestResult
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
import com.hotattic.gamedesigner.core.model.Provenance
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.ProjectMode
import com.hotattic.gamedesigner.core.model.ProjectPrefs
import com.hotattic.gamedesigner.core.model.ResearchKind
import com.hotattic.gamedesigner.core.model.Role
import com.hotattic.gamedesigner.core.persist.ProjectSummary
import com.hotattic.gamedesigner.core.session.ProjectSession
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
    private val session = ProjectSession { p ->
        withContext(Dispatchers.IO) { c.store.save(p) }
        _projects.value = withContext(Dispatchers.IO) { c.store.list() }
    }
    private val inFlightPicks = HashSet<String>()
    /** Which branding slot the open picker is for. Lives in the ViewModel, so it survives configuration changes. */
    private var pickSlot: String? = null

    val settings: StateFlow<AppSettings> = c.settings
    private val _projects = MutableStateFlow<List<ProjectSummary>>(emptyList())
    val projects = _projects.asStateFlow()
    val current = session.current
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

    /** Saves a brand-new project and makes it the open one. */
    private suspend fun adopt(p: Project) {
        withContext(Dispatchers.IO) { c.store.save(p) }
        _projects.value = withContext(Dispatchers.IO) { c.store.list() }
        session.open(p)
    }

    fun createProject(mode: ProjectMode, onCreated: (String) -> Unit = {}) = viewModelScope.launch {
        val s = c.settings.value
        val now = System.currentTimeMillis()
        val prefs = ProjectPrefs(s.defaultExperience, s.defaultClaudePlan, s.defaultUsageStyle)
        var p = ProjectOps.newProject(UUID.randomUUID().toString(), "", mode, prefs, now)
        if (mode != ProjectMode.EXISTING_GAME) p = c.director().start(p, s)
        adopt(p)
        onCreated(p.id)
    }

    fun open(id: String) = viewModelScope.launch {
        val p = try { withContext(Dispatchers.IO) { c.store.load(id) } } catch (e: UnsupportedSchemaException) { toast(e.message ?: "Newer project format"); null } catch (e: Exception) { toast("Could not open this project: ${e.message}"); null }
        session.open(p)
        refreshInterpreter()
        p?.pendingTurn?.let { runTurn(it.text, it.id) }
    }

    fun deleteProject(id: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { c.store.delete(id) }
        if (current.value?.id == id) session.open(null)
        refresh()
    }

    fun renameProject(name: String) = viewModelScope.launch { session.update { it.copy(name = name.trim(), updatedAt = System.currentTimeMillis()) } }

    fun setProjectPrefs(prefs: ProjectPrefs) = viewModelScope.launch { session.update { it.copy(prefs = prefs) } }

    fun setMode(mode: ProjectMode) = viewModelScope.launch {
        session.update { p -> c.director().enterMode(p, c.settings.value, mode) }
    }

    // ---- Conversation ----

    /**
     * One owner action = one transaction on the freshest project, under the session lock. [turnId] identifies the action, so a
     * repeated callback or replay is ignored by the Director instead of being applied twice.
     */
    fun send(text: String, turnId: String = UUID.randomUUID().toString()) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val queued = session.mutate { p -> val q = c.director().queueTurn(p, trimmed, turnId); (q ?: p) to (q != null) }
            if (queued == false) { toast("I'm still working on your last message."); return@launch }
            runTurn(trimmed, turnId)
        }
    }

    /** Runs a queued owner message under the session lock. Safe to call again: the turn id makes a second run a no-op. */
    private suspend fun runTurn(text: String, turnId: String) {
        _busy.value = "${c.settings.value.directorName} is thinking..."
        try {
            val action = session.mutate { p ->
                val turn = c.director().handleUserMessage(p, c.settings.value, text, turnId)
                turn.project to turn.action
            }
            dispatch(action)
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t   // the message stays queued on disk; it is resumed on the next open or Retry
        } catch (t: Throwable) {
            toast("Something went wrong: ${t.message ?: t.javaClass.simpleName}. Your message is saved - tap Retry.")
        } finally { _busy.value = null }
    }

    /** Back: revisit the owner's previous answer and ask it again. */
    fun goBack() {
        viewModelScope.launch {
            val ok = session.mutate { p -> val n = c.director().goBack(p); (n ?: p) to (n != null) }
            if (ok == false) toast("There's nothing to go back to yet.")
        }
    }

    /** Resumes an owner message that was saved but never answered (crash, backgrounding, timeout). */
    fun retryPending() {
        val pt = current.value?.pendingTurn ?: return
        viewModelScope.launch { runTurn(pt.text, pt.id) }
    }

    /** Structured answer from the question card (single tap or multi-select Continue). */
    fun submitSelection(fieldKey: String, ids: List<String>) {
        viewModelScope.launch {
            _busy.value = "${c.settings.value.directorName} is thinking..."
            try {
                val action = session.mutate { p ->
                    val turn = c.director().submitSelection(p, c.settings.value, fieldKey, ids)
                    turn.project to turn.action
                }
                dispatch(action)
            } catch (t: Throwable) {
                toast("Something went wrong: ${t.message ?: t.javaClass.simpleName}")
            } finally { _busy.value = null }
        }
    }

    private suspend fun dispatch(a: DirectorAction?) {
        when (a) {
            DirectorAction.GenerateSpec -> generateNow()
            is DirectorAction.RequestUpload -> { pickSlot = a.slot; _events.tryEmit(UiEvent.PickImage(a.slot)) }
            null -> Unit
        }
    }

    // ---- Spec generation ----

    fun generate() = viewModelScope.launch { _busy.value = "Generating spec..."; try { generateNow() } finally { _busy.value = null } }

    private suspend fun generateNow() {
        val opened = session.mutate { p0 ->
            var p = p0
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
                ProjectOps.setPending(ProjectOps.addMessage(p, Role.DIRECTOR, msg, now), null) to false
            } else {
                val next = gen.project
                val v = next.versions.last()
                ProjectOps.addMessage(next, Role.DIRECTOR, "Generated spec v${v.number} (${v.kind.label}). ${v.auditSummary} Open the Spec screen to copy, share or export it.", now) to true
            }
        }
        if (opened == true) current.value?.id?.let { _events.tryEmit(UiEvent.OpenSpec(it)) }
    }

    /** Optional cloud review of a generated spec (needs a configured provider). */
    fun reviewWithCloud(versionNumber: Int, onResult: (String) -> Unit) = viewModelScope.launch {
        val p = current.value ?: return@launch
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

    private fun slotFromPending(): String? {
        val k = current.value?.pendingFieldKey ?: return null
        return Keys.brandingKeyForSlot.entries.firstOrNull { it.value == k }?.key
    }

    /**
     * The picker returned (or was cancelled). The slot comes from the Branding screen if it launched the picker, else from the
     * ViewModel, else from the open question itself - so an activity recreated while Files was open still knows what this is for.
     */
    fun onImagePicked(uri: Uri?, slotHint: String? = null) = viewModelScope.launch {
        val slot = slotHint ?: pickSlot ?: slotFromPending()
        pickSlot = null
        if (uri == null) { toast("No image was chosen. The question is still open - tap Upload to try again."); return@launch }
        if (slot == null) { toast("I lost track of which image this was for. Please choose it again."); return@launch }
        ingestImage(slot, uri)
    }

    /** Success is reported only after the bytes are read, validated, saved in app storage and the conversation has advanced. */
    private suspend fun ingestImage(slot: String, uri: Uri) {
        val key = "$slot|$uri"
        if (!inFlightPicks.add(key)) return // the same result delivered twice while the first is still being saved
        _busy.value = "Saving your image..."
        try {
            val p0 = current.value ?: return
            val ctx = getApplication<Application>()
            val resolver = ctx.contentResolver
            val name = try { resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } } catch (e: Exception) { null } ?: "image"
            try { resolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { /* not every provider offers a persistable grant; we copy the bytes now instead */ }
            val dir = File(c.store.assetsDir(p0.id), "branding")
            val result = withContext(Dispatchers.IO) { AttachmentIngest.ingest(slot, name, { resolver.openInputStream(uri) }, dir, System.currentTimeMillis()) }
            when (result) {
                is IngestResult.Failed -> reportIngestFailure(result.message)
                is IngestResult.Ok -> {
                    val decodable = withContext(Dispatchers.IO) { val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(result.file.path, o); o.outWidth > 0 && o.outHeight > 0 }
                    if (!decodable) { withContext(Dispatchers.IO) { result.file.delete() }; reportIngestFailure(IngestResult.Failed(IngestFailure.CORRUPT).message); return }
                    val turn = session.mutate { p ->
                        val old = p.branding[slot]?.localFile
                        if (old != null && old != result.asset.localFile) withContext(Dispatchers.IO) { File(c.store.assetsDir(p.id), old).delete() }
                        val t = c.director().attachmentReceived(p, c.settings.value, slot, result.asset)
                        t.project to t.duplicate
                    }
                    toast(if (turn == true) "That image is already attached." else "Saved. The original is kept untouched as the master.")
                }
            }
        } catch (e: Exception) {
            reportIngestFailure("Something went wrong saving the image (${e.message ?: e.javaClass.simpleName}).")
        } finally { inFlightPicks.remove(key); _busy.value = null }
    }

    private suspend fun reportIngestFailure(message: String) {
        toast(message)
        session.update { p -> ProjectOps.addMessage(p, Role.SYSTEM, "I couldn't use that file. $message Try another image, or say \"create one for me\".", System.currentTimeMillis()) }
    }

    fun setBrandingChoice(slot: String, choice: String) = viewModelScope.launch {
        session.update { p ->
            p.branding[slot]?.localFile?.let { rel -> withContext(Dispatchers.IO) { File(c.store.assetsDir(p.id), rel).delete() } }
            val mode = when (choice) { "generate_original" -> BrandingMode.GENERATE_ORIGINAL; "generic_temporary" -> BrandingMode.GENERIC_TEMPORARY; "skip" -> BrandingMode.SKIP; else -> BrandingMode.UNSET }
            val b = BrandingAsset(slot, mode, addedAt = System.currentTimeMillis())
            ProjectOps.setDecision(p.copy(branding = p.branding + (slot to b)), Keys.brandingKeyForSlot.getValue(slot), choice, Provenance.OWNER_EXPLICIT, System.currentTimeMillis())
        }
    }

    fun brandingFile(slot: String): File? {
        val p = current.value ?: return null
        val rel = p.branding[slot]?.localFile ?: return null
        return File(c.store.assetsDir(p.id), rel).takeIf { it.exists() }
    }

    // ---- Export / backup ----

    fun exportPackage(versionNumber: Int, out: Uri) = viewModelScope.launch {
        val p = current.value ?: return@launch
        val v = p.versions.firstOrNull { it.number == versionNumber } ?: return@launch
        withContext(Dispatchers.IO) {
            val masters = ExportPackage.binaryFiles(p) { b -> b.localFile?.let { File(c.store.assetsDir(p.id), it).takeIf { f -> f.exists() }?.readBytes() } }
            getApplication<Application>().contentResolver.openOutputStream(out)!!.use { it.write(ExportPackage.zip(ExportPackage.files(p, v), masters)) }
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
            adopt(p); onCreated(p.id)
        } catch (e: Exception) { toast(e.message ?: "Inspection failed") } finally { _busy.value = null }
    }

    fun auditSummary(p: Project) = AuditEngine.audit(p)
}
