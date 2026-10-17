package com.hotattic.gamedesigner.core.persist

import com.hotattic.gamedesigner.core.model.Project
import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class BackupFile(val path: String, val sha256: String, val size: Long)

/** OK, or why the stored copy was not a clean one: RECOVERED_FROM_BAK (the last good copy was saved instead), NEWER_FORMAT (kept raw; a newer Game Designer can read it). */
@Serializable
data class BackupProject(val id: String, val name: String, val schemaVersion: Int, val files: List<BackupFile>, val status: String = "OK")

/** A project that could not be saved as a working project. Its raw files are kept under `unreadable/<folder>/` so nothing is lost. */
@Serializable
data class BackupNote(val id: String, val status: String, val detail: String)

/** Lists what a backup should contain so damage or tampering is detectable before anything is restored. */
@Serializable
data class BackupManifest(val format: String = "game-designer-backup", val formatVersion: Int = 1, val createdAt: Long, val appVersion: String, val projects: List<BackupProject>, val notes: List<BackupNote> = emptyList())

/** SPEC_ONLY: generated documents (CLAUDE.md and friends) with no project data in them. They cannot be turned back into a project. */
enum class PackageKind { BACKUP, SPEC_PACKAGE, BARE_PROJECT, SPEC_ONLY, UNKNOWN }

data class PackageProject(
    val sourceId: String, val name: String, val project: Project?, val rawJson: ByteArray?, val assets: Map<String, ByteArray>,
    /** True/false when a manifest vouched for (or contradicted) this project's files; null when the package carries no manifest. */
    val verified: Boolean?, val problems: List<String>,
) { val ok get() = project != null && problems.none { it.startsWith("ERROR") } }

data class PackageInspection(val kind: PackageKind, val projects: List<PackageProject>, val problems: List<String>, val hasManifest: Boolean, val notes: List<BackupNote> = emptyList())

data class ImportedProject(val sourceId: String, val id: String, val name: String, val renamed: Boolean, val verbatim: Boolean, val warnings: List<String>)

data class ImportResult(val imported: List<ImportedProject>, val skipped: List<String>, val errors: List<String>) {
    val success get() = imported.isNotEmpty() && errors.isEmpty()
    val summary get() = when {
        imported.isEmpty() -> "Nothing was imported. " + (errors + skipped).joinToString(" ")
        else -> "Imported ${imported.size} project(s): " + imported.joinToString("; ") { it.name + (if (it.renamed) " (saved as a copy; nothing existing was touched)" else "") } +
            (if (errors.isNotEmpty()) ". Problems: " + errors.joinToString(" ") else "") + (if (skipped.isNotEmpty()) ". Skipped: " + skipped.joinToString(" ") else "")
    }
}

/**
 * Moving whole projects in and out of the app: user-controlled backups with an integrity manifest, and a defensive importer for backups, spec
 * packages and a bare project.json. Importing NEVER overwrites: a project whose id already exists is saved as a separate copy. Nothing is written
 * until the package has been fully read, size-limited, zip-slip checked, decoded (with migrations) and checksummed.
 */
object ProjectTransfer {
    const val MANIFEST = "backup-manifest.json"
    const val MAX_ENTRIES = 5000
    /** The largest file the importer will read at all. Phones have a small heap; a real backup is a few MB. */
    const val MAX_INPUT_BYTES = 64L * 1024 * 1024
    /** The most it will hold after unpacking, across every kept entry. */
    const val MAX_TOTAL_BYTES = 128L * 1024 * 1024
    const val MAX_ENTRY_BYTES = 48L * 1024 * 1024
    const val MAX_PROJECT_JSON = 16L * 1024 * 1024
    /** A package that unpacks to far more than it weighs is a decompression bomb. */
    const val MAX_EXPANSION = 100L
    private const val BOMB_FLOOR = 8L * 1024 * 1024

    class TooLargeException(val limit: Long) : RuntimeException("larger than the allowed ${limit / 1048576} MB")

    /** Reads [input] fully but never more than [limit] bytes: a larger stream is refused after at most limit+1 bytes, not buffered. */
    fun readLimited(input: java.io.InputStream, limit: Long = MAX_INPUT_BYTES): ByteArray {
        val out = ByteArrayOutputStream(); val buf = ByteArray(64 * 1024); var total = 0L; var n: Int
        while (input.read(buf).also { n = it } > 0) { total += n; if (total > limit) throw TooLargeException(limit); out.write(buf, 0, n) }
        return out.toByteArray()
    }

    /** Package paths worth keeping in memory; everything else is counted against the limits and dropped. */
    private fun wanted(name: String) = name == MANIFEST || name == "game-designer/project.json" || name.startsWith("projects/") || name.startsWith("branding/master/") || name == "CLAUDE.md" || name.endsWith("/CLAUDE.md") || name == "MASTER_PROMPT.md"

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun safeId(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
    private fun safeRelative(name: String): Boolean {
        if (name.isBlank() || name.startsWith("/") || name.startsWith("\\") || name.contains(':')) return false
        return name.replace('\\', '/').split('/').none { it == ".." }
    }

    // ---- backup ---------------------------------------------------------------------------------------------------------------------------

    enum class BackupStatus { INCLUDED, RECOVERED_FROM_BAK, NEWER_FORMAT_RAW, UNREADABLE_RAW, TOO_LARGE }

    /** What happened to one project folder. [status] other than INCLUDED means the backup is not fully clean for it. */
    data class BackupItem(val folder: String, val name: String, val status: BackupStatus, val detail: String)

    data class BackupResult(val items: List<BackupItem>, val bytesWritten: Long, val sha256: String) {
        /** True only when every project folder went into the backup as a normal, readable project. */
        val complete get() = items.isNotEmpty() && items.all { it.status == BackupStatus.INCLUDED }
        val included get() = items.count { it.status == BackupStatus.INCLUDED || it.status == BackupStatus.RECOVERED_FROM_BAK }
        val attention get() = items.filter { it.status != BackupStatus.INCLUDED }
    }

    private fun tryDecode(text: String): Project? = try { ProjectCodec.decode(text) } catch (e: Exception) { null }

    /**
     * Writes a backup of every project folder to [out]. READ-ONLY on the store: it never loads through [FileProjectStore] (whose loading may rewrite
     * recovery copies) and never changes a file. No project is dropped silently: a damaged project.json is replaced in the backup by its last good
     * `.bak` (the damaged file is kept beside it under `recovery/`), a project from a newer Game Designer is kept raw, and a project with no readable
     * copy has its raw files saved under `unreadable/` and is reported. Each file is read once and hashed into the manifest; memory is one file at a time.
     */
    fun createBackup(store: FileProjectStore, appVersion: String, now: Long, out: java.io.OutputStream): BackupResult {
        val counting = object : java.io.FilterOutputStream(out) {
            val digest: MessageDigest = MessageDigest.getInstance("SHA-256"); var count = 0L
            override fun write(b: Int) { out.write(b); digest.update(b.toByte()); count++ }
            override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); digest.update(b, off, len); count += len }
            override fun close() { flush() }
        }
        val projects = mutableListOf<BackupProject>(); val notes = mutableListOf<BackupNote>(); val items = mutableListOf<BackupItem>()
        val root = File(store.root, "projects")
        ZipOutputStream(counting).use { z ->
            fun put(path: String, bytes: ByteArray) { z.putNextEntry(ZipEntry(path)); z.write(bytes); z.closeEntry() }
            for (dir in (root.listFiles { f -> f.isDirectory } ?: emptyArray()).sortedBy { it.name }) {
                val pj = File(dir, "project.json"); val bak = File(dir, "project.json.bak")
                if (!pj.exists() && !bak.exists() && dir.listFiles().isNullOrEmpty()) continue   // an empty folder is not a project
                val files = mutableListOf<BackupFile>()
                fun assets() = dir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.filter { f ->
                    val rel = f.relativeTo(dir).path.replace('\\', '/'); rel.startsWith("assets/") && !rel.endsWith(".tmp")
                }
                val big = dir.walkTopDown().filter { it.isFile }.firstOrNull { it.length() > MAX_ENTRY_BYTES }
                if (big != null) { items += BackupItem(dir.name, dir.name, BackupStatus.TOO_LARGE, "${big.name} is larger than the ${MAX_ENTRY_BYTES / 1048576} MB limit and could not be backed up"); notes += BackupNote(dir.name, "TOO_LARGE", big.name); continue }
                val text = runCatching { pj.readText() }.getOrNull()
                val main = text?.let(::tryDecode)
                var status = "OK"; var name: String; var itemStatus = BackupStatus.INCLUDED; var detail = ""
                val projectBytes: ByteArray
                if (main != null) { projectBytes = pj.readBytes(); name = main.name.ifBlank { dir.name } }
                else {
                    val newer = text != null && runCatching { ProjectCodec.decode(text) }.exceptionOrNull() is UnsupportedSchemaException
                    val bakText = if (bak.exists()) runCatching { bak.readText() }.getOrNull() else null
                    val recovered = bakText?.let(::tryDecode)
                    when {
                        newer -> { projectBytes = pj.readBytes(); name = dir.name; status = "NEWER_FORMAT"; itemStatus = BackupStatus.NEWER_FORMAT_RAW; detail = "saved by a newer Game Designer; kept as it is and readable only by that version" }
                        recovered != null -> {
                            projectBytes = bak.readBytes(); name = recovered.name.ifBlank { dir.name }; status = "RECOVERED_FROM_BAK"; itemStatus = BackupStatus.RECOVERED_FROM_BAK
                            detail = "its project file is damaged, so its last good copy was saved instead (the damaged file is kept under recovery/ in the backup; nothing on the phone was changed)"
                            if (pj.exists()) put("recovery/${dir.name}/project.json.damaged", pj.readBytes())
                        }
                        else -> {
                            // no readable copy at all: keep every raw file so nothing is lost, and say so
                            dir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f -> put("unreadable/${dir.name}/${f.relativeTo(dir).path.replace('\\', '/')}", f.readBytes()) }
                            items += BackupItem(dir.name, dir.name, BackupStatus.UNREADABLE_RAW, "no readable copy of this project exists; its raw files were saved under unreadable/ in the backup")
                            notes += BackupNote(dir.name, "UNREADABLE", "no readable project.json or .bak"); continue
                        }
                    }
                }
                put("projects/${dir.name}/project.json", projectBytes); files += BackupFile("projects/${dir.name}/project.json", sha(projectBytes), projectBytes.size.toLong())
                for (f in assets()) { val rel = f.relativeTo(dir).path.replace('\\', '/'); val b = f.readBytes(); put("projects/${dir.name}/$rel", b); files += BackupFile("projects/${dir.name}/$rel", sha(b), b.size.toLong()) }
                projects += BackupProject(main?.id ?: tryDecode(String(projectBytes))?.id ?: dir.name, name, com.hotattic.gamedesigner.core.model.PROJECT_SCHEMA_VERSION, files, status)
                if (itemStatus != BackupStatus.INCLUDED) notes += BackupNote(dir.name, status, detail)
                items += BackupItem(dir.name, name, itemStatus, detail)
            }
            put(MANIFEST, ProjectCodec.json.encodeToString(BackupManifest.serializer(), BackupManifest(createdAt = now, appVersion = appVersion, projects = projects, notes = notes)).toByteArray())
        }
        return BackupResult(items, counting.count, counting.digest.digest().joinToString("") { "%02x".format(it) })
    }

    /** Convenience for tests and small callers: the backup as bytes plus its report. */
    fun createBackupBytes(store: FileProjectStore, appVersion: String, now: Long): Pair<ByteArray, BackupResult> {
        val bos = ByteArrayOutputStream(); val r = createBackup(store, appVersion, now, bos); return bos.toByteArray() to r
    }

    fun createBackup(store: FileProjectStore, appVersion: String, now: Long): ByteArray = createBackupBytes(store, appVersion, now).first

    // ---- reading --------------------------------------------------------------------------------------------------------------------------

    private class Raw(val files: LinkedHashMap<String, ByteArray>, val problems: List<String>)

    private fun readZip(bytes: ByteArray): Raw {
        val files = linkedMapOf<String, ByteArray>(); val problems = mutableListOf<String>()
        var total = 0L; var kept = 0L; var count = 0
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                var e: ZipEntry? = z.nextEntry
                while (e != null) {
                    val name = e.name.replace('\\', '/')
                    if (++count > MAX_ENTRIES) { problems += "ERROR: the package has too many files"; break }
                    if (!e.isDirectory) {
                        if (!safeRelative(name)) problems += "ERROR: unsafe path in package: $name"
                        else {
                            val keep = wanted(name)
                            val out = if (keep) ByteArrayOutputStream() else null
                            val buf = ByteArray(64 * 1024); var n: Int; var entry = 0L
                            while (z.read(buf).also { n = it } > 0) {
                                entry += n; total += n
                                if (entry > MAX_ENTRY_BYTES) { problems += "ERROR: $name unpacks to more than ${MAX_ENTRY_BYTES / 1048576} MB"; return Raw(files, problems) }
                                if (total > MAX_TOTAL_BYTES) { problems += "ERROR: the package unpacks to more than the allowed ${MAX_TOTAL_BYTES / 1048576} MB"; return Raw(files, problems) }
                                if (total > BOMB_FLOOR && total > MAX_EXPANSION * maxOf(bytes.size, 1)) { problems += "ERROR: the package expands far beyond its size and was refused"; return Raw(files, problems) }
                                out?.write(buf, 0, n)
                            }
                            if (out != null) { files[name] = out.toByteArray(); kept += entry }
                        }
                    }
                    e = z.nextEntry
                }
            }
        } catch (ex: Exception) { problems += "ERROR: this is not a readable ZIP file (${ex.message})" }
        return Raw(files, problems)
    }

    private fun decode(raw: ByteArray, label: String): Pair<Project?, String?> {
        if (raw.size > MAX_PROJECT_JSON) return null to "ERROR: $label is unreasonably large"
        return try { ProjectCodec.decode(String(raw, Charsets.UTF_8)) to null }
        catch (e: UnsupportedSchemaException) { null to "ERROR: $label was saved by a newer Game Designer (${e.message})" }
        catch (e: Exception) { null to "ERROR: $label is damaged or is not a Game Designer project (${e.message?.take(120)})" }
    }

    /** Reads and checks a package without writing anything. */
    fun inspect(bytes: ByteArray): PackageInspection {
        if (bytes.size > MAX_INPUT_BYTES) return PackageInspection(PackageKind.UNKNOWN, emptyList(), listOf("ERROR: this file is larger than the allowed ${MAX_INPUT_BYTES / 1048576} MB"), false)
        val first = bytes.firstOrNull { it != ' '.code.toByte() && it != '\n'.code.toByte() && it != '\r'.code.toByte() && it != '\t'.code.toByte() }
        if (first == '{'.code.toByte()) {
            val (p, err) = decode(bytes, "this file")
            return PackageInspection(PackageKind.BARE_PROJECT, listOf(PackageProject(p?.id ?: "", p?.name ?: "", p, bytes, emptyMap(), null, listOfNotNull(err))), emptyList(), false)
        }
        val isZip = bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        if (!isZip) {
            val head = String(bytes.copyOf(minOf(bytes.size, 2000)), Charsets.UTF_8)
            return if (head.trimStart().startsWith("#") || head.contains("CLAUDE.md"))
                PackageInspection(PackageKind.SPEC_ONLY, emptyList(), listOf("ERROR: this is a generated specification document only; it contains no project data to import"), false)
            else PackageInspection(PackageKind.UNKNOWN, emptyList(), listOf("ERROR: this is not a ZIP backup, a spec package or a project file"), false)
        }
        val zip = readZip(bytes)
        if (zip.problems.any { it.startsWith("ERROR") } && zip.files.isEmpty()) return PackageInspection(PackageKind.UNKNOWN, emptyList(), zip.problems, false)
        val manifest = zip.files[MANIFEST]?.let { runCatching { ProjectCodec.json.decodeFromString(BackupManifest.serializer(), String(it)) }.getOrNull() }
        val hasManifestFile = zip.files.containsKey(MANIFEST)
        val problems = zip.problems.toMutableList()
        if (hasManifestFile && manifest == null) problems += "ERROR: the backup manifest is damaged, so this backup cannot be verified"

        val backupDirs = zip.files.keys.filter { it.startsWith("projects/") && it.endsWith("/project.json") }
        if (backupDirs.isNotEmpty()) {
            val projects = backupDirs.map { path ->
                val dir = path.removeSuffix("/project.json")
                val raw = zip.files.getValue(path)
                val (p, err) = decode(raw, "the project in $dir")
                val probs = mutableListOf<String>(); err?.let { probs += it }
                val assets = zip.files.filterKeys { it.startsWith("$dir/assets/") }.mapKeys { it.key.removePrefix("$dir/assets/") }
                var verified: Boolean? = null
                val m = manifest?.projects?.firstOrNull { "projects/${safeId(it.id)}" == dir || "projects/${it.id}" == dir }
                if (manifest != null) {
                    if (m == null) { verified = false; probs += "ERROR: ${dir} is not listed in the backup manifest" }
                    else {
                        val bad = m.files.filter { f -> zip.files[f.path]?.let { sha(it) != f.sha256 } != false }
                        verified = bad.isEmpty()
                        bad.forEach { probs += "ERROR: ${it.path} is missing or does not match its checksum" }
                    }
                }
                if (m != null && m.status == "RECOVERED_FROM_BAK") probs += "WARNING: when this backup was made the project file was damaged; this is its last good copy"
                PackageProject(p?.id ?: dir.removePrefix("projects/"), p?.name ?: dir, p, raw, assets, verified, probs)
            }
            return PackageInspection(PackageKind.BACKUP, projects, problems, manifest != null, manifest?.notes.orEmpty())
        }
        if (manifest != null && (manifest.notes.isNotEmpty() || manifest.projects.isEmpty())) return PackageInspection(PackageKind.BACKUP, emptyList(), problems, true, manifest.notes)

        val spec = zip.files["game-designer/project.json"]
        if (spec != null) {
            val (p, err) = decode(spec, "the project in this spec package")
            val assets = linkedMapOf<String, ByteArray>(); val probs = mutableListOf<String>(); err?.let { probs += it }
            p?.branding?.values?.forEach { b ->
                val local = b.localFile ?: return@forEach
                val bytes = zip.files["branding/master/${local.substringAfterLast('/')}"]
                if (bytes == null) probs += "WARNING: the uploaded ${b.slot.replace('_', ' ')} image is not in the package"
                else if (b.sha256.isNotBlank() && sha(bytes) != b.sha256) probs += "WARNING: the ${b.slot.replace('_', ' ')} image does not match its recorded checksum; it was not imported"
                else if (safeRelative(local)) assets[local] = bytes
            }
            return PackageInspection(PackageKind.SPEC_PACKAGE, listOf(PackageProject(p?.id ?: "", p?.name ?: "", p, spec, assets, null, probs)), problems, false)
        }
        if (zip.files.keys.any { it == "CLAUDE.md" || it.endsWith("/CLAUDE.md") || it == "MASTER_PROMPT.md" })
            return PackageInspection(PackageKind.SPEC_ONLY, emptyList(), problems + "ERROR: this package holds a generated specification only (CLAUDE.md and prompts); it has no project data, so it cannot be imported as a project", false)
        return PackageInspection(PackageKind.UNKNOWN, emptyList(), problems + "ERROR: this does not look like a Game Designer backup or spec package", false)
    }

    // ---- importing ------------------------------------------------------------------------------------------------------------------------

    /**
     * Imports every valid project in [bytes]. A project is written byte-for-byte as found when its id is free; when the id is taken, it is saved as
     * a separate copy under a fresh id (and a clashing name gets a numbered suffix) so nothing existing is overwritten. Each write is re-read and
     * compared before it counts as imported; a failed check removes only the folder this call just created.
     */
    fun import(store: FileProjectStore, bytes: ByteArray, newId: () -> String = { UUID.randomUUID().toString() }): ImportResult {
        val ins = inspect(bytes)
        val imported = mutableListOf<ImportedProject>(); val skipped = mutableListOf<String>(); val errors = mutableListOf<String>()
        errors += ins.problems.filter { it.startsWith("ERROR") }.map { it.removePrefix("ERROR: ") }
        if (ins.projects.isEmpty()) return ImportResult(emptyList(), skipped, errors)
        val taken = store.list().associate { it.id to it.name }.toMutableMap()
        for (pp in ins.projects) {
            val label = pp.name.ifBlank { pp.sourceId }
            if (!pp.ok || pp.verified == false) { skipped += "$label: " + pp.problems.filter { it.startsWith("ERROR") }.joinToString(" ") { it.removePrefix("ERROR: ") }.ifBlank { "not valid" }; continue }
            val src = pp.project!!
            if (src.id.isBlank() || src.name.isBlank()) { skipped += "$label: it has no id or name"; continue }
            val idTaken = safeId(src.id) in taken.keys.map(::safeId) || File(store.root, "projects/${safeId(src.id)}").exists()
            var id = src.id
            if (idTaken) { do { id = newId() } while (safeId(id) in taken.keys.map(::safeId) || File(store.root, "projects/${safeId(id)}").exists()) }
            var name = src.name
            fun nameTaken(n: String) = taken.values.any { it.equals(n, true) }
            if (idTaken || nameTaken(name)) {
                var k = if (idTaken) 1 else 2
                fun cand(k: Int) = if (idTaken) (if (k == 1) "${src.name} (imported)" else "${src.name} (imported $k)") else "${src.name} ($k)"
                while (nameTaken(cand(k))) k++
                name = cand(k)
            }
            val renamed = id != src.id || name != src.name
            val dir = File(store.root, "projects/${safeId(id)}")
            if (!dir.mkdirs()) { errors += "$label: could not create its folder"; continue }
            try {
                val data = if (!renamed) pp.rawJson!! else ProjectCodec.encode(src.copy(id = id, name = name)).toByteArray(Charsets.UTF_8)
                val tmp = File(dir, "project.json.tmp"); tmp.writeBytes(data)
                if (!tmp.renameTo(File(dir, "project.json"))) { tmp.copyTo(File(dir, "project.json")); tmp.delete() }
                val assetWarn = mutableListOf<String>()
                for ((rel, b) in pp.assets) {
                    if (!safeRelative(rel)) { assetWarn += "skipped an unsafe asset path"; continue }
                    val target = File(dir, "assets/$rel")
                    if (!target.canonicalPath.startsWith(File(dir, "assets").canonicalPath + File.separator)) { assetWarn += "skipped an asset outside the project"; continue }
                    target.parentFile.mkdirs(); target.writeBytes(b)
                }
                val back = store.load(safeId(id))
                val expected = if (renamed) src.copy(id = id, name = name) else src
                if (back == null || back != expected) { dir.deleteRecursively(); errors += "$label: the saved copy did not match the source, so it was removed"; continue }
                taken[id] = name
                imported += ImportedProject(src.id, id, name, renamed, !renamed, pp.problems.filter { it.startsWith("WARNING") }.map { it.removePrefix("WARNING: ") } + assetWarn)
            } catch (e: Exception) {
                dir.deleteRecursively(); errors += "$label: ${e.message ?: "could not be saved"}"
            }
        }
        return ImportResult(imported, skipped, errors)
    }

    // ---- previewing -----------------------------------------------------------------------------------------------------------------------

    /** What one selected file is, in plain words, before anything is imported. */
    data class FilePreview(val fileName: String, val kind: PackageKind, val headline: String, val projects: List<String>, val importable: Int, val details: List<String>)

    fun preview(fileName: String, bytes: ByteArray): FilePreview {
        val ins = inspect(bytes)
        val names = ins.projects.map { it.name.ifBlank { it.sourceId.ifBlank { "unnamed" } } }
        val usable = ins.projects.count { it.ok && it.verified != false }
        val details = (ins.problems + ins.notes.map { "WARNING: ${it.id}: ${it.status} - ${it.detail}" } + ins.projects.flatMap { p -> p.problems.map { "${p.name.ifBlank { p.sourceId }}: $it" } }).map { it.replace("ERROR: ", "Problem: ").replace("WARNING: ", "Note: ") }.distinct()
        val head = when (ins.kind) {
            PackageKind.BACKUP -> (if (ins.notes.isEmpty()) "Complete backup" else "INCOMPLETE backup (${ins.notes.size} project(s) could not be saved completely)") + " with ${ins.projects.size} readable project(s)" + when { !ins.hasManifest -> " (older backup without checksums; each project was read and decoded successfully)"; ins.projects.all { it.verified == true } -> " (checksums verified)"; else -> " (checksum problems found)" }
            PackageKind.SPEC_PACKAGE -> "Spec package that includes the complete project: its conversation, decisions, exclusions, approvals and spec versions can be imported"
            PackageKind.BARE_PROJECT -> "A single project file"
            PackageKind.SPEC_ONLY -> "Generated specification only. It has no project data, so it cannot be imported as a project"
            PackageKind.UNKNOWN -> "Not recognised as a Game Designer file"
        }
        return FilePreview(fileName, ins.kind, head, names, usable, details)
    }

    // ---- verifying ------------------------------------------------------------------------------------------------------------------------

    data class Verification(val ok: Boolean, val kind: PackageKind, val hasManifest: Boolean, val projects: Int, val problems: List<String>)

    /** Integrity check only: can every project be read, and do the checksums match? Nothing is written. */
    fun verify(bytes: ByteArray): Verification {
        val ins = inspect(bytes)
        val probs = (ins.problems + ins.projects.flatMap { p -> p.problems.map { "${p.name.ifBlank { p.sourceId }}: $it" } }).filter { it.contains("ERROR") }.map { it.replace("ERROR: ", "") }
        val noted = ins.notes.map { "${it.id}: ${it.status} - ${it.detail}" }
        val ok = ins.projects.isNotEmpty() && probs.isEmpty() && noted.isEmpty() && ins.projects.all { it.ok && it.verified != false }
        return Verification(ok, ins.kind, ins.hasManifest, ins.projects.size, probs + noted)
    }
}
