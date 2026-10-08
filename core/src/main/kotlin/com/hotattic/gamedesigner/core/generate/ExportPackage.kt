package com.hotattic.gamedesigner.core.generate

import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.SpecVersion
import com.hotattic.gamedesigner.core.persist.ProjectCodec
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Everything the owner needs to hand to Claude Code (or back up), assembled as plain files. */
object ExportPackage {

    fun files(project: Project, version: SpecVersion): LinkedHashMap<String, String> {
        val out = linkedMapOf<String, String>()
        out["CLAUDE.md"] = version.claudeMd
        out["MASTER_PROMPT.md"] = version.masterPrompt
        out["ASSETS.md"] = assetsMarkdown(project)
        out["docs/RESEARCH.md"] = researchMarkdown(project)
        out["docs/SPEC_HISTORY.md"] = historyMarkdown(project)
        out["game-designer/project.json"] = ProjectCodec.encode(project)
        return out
    }

    fun assetsMarkdown(p: Project): String = buildString {
        appendLine("# Assets and provenance")
        appendLine()
        appendLine("Every external asset used by the game must have a row here with its exact source URL, creator, license and download date.")
        appendLine("Planned resolutions from Game Designer are listed first; the build appends one row per actual file.")
        appendLine()
        if (p.value(com.hotattic.gamedesigner.core.schema.Keys.ASSET_POLICY)?.startsWith("supplied") == true) {
            appendLine("## Owner-supplied asset packs (FIRST CHOICE for every need below)")
            appendLine()
            appendLine("The owner supplies asset packs together with the master prompt. Before choosing anything else: inspect every supplied pack (file list, formats, scale, rigs and animations, bundled license or readme), record each pack here with its name, creator and license as stated by the owner, and use its real contents. Create missing animations for the supplied player character where technically reasonable. Only the gaps the packs genuinely cannot cover follow the policy in the table.")
            appendLine()
            appendLine("| Pack (fill in after inspection) | Creator | License / terms | Used for |")
            appendLine("|---|---|---|---|")
            appendLine()
        }
        appendLine("| Need | Resolution | License | Source / instructions |")
        appendLine("|---|---|---|---|")
        p.assets.forEach { a ->
            appendLine("| ${a.needId} | ${a.resolution.name.lowercase().replace('_', ' ')} | ${a.license.ifBlank { "-" }} | ${(a.source + " " + a.notes).trim().replace("|", "/").replace("\n", " ")} |")
        }
        appendLine()
        val uploaded = p.branding.values.filter { it.mode == com.hotattic.gamedesigner.core.model.BrandingMode.UPLOADED }
        if (uploaded.isNotEmpty()) {
            appendLine()
            appendLine("## Owner-supplied branding (use as-is; never replace or regenerate)")
            appendLine()
            appendLine("| Slot | File in this package | Original name | Size | SHA-256 | License |")
            appendLine("|---|---|---|---|---|---|")
            uploaded.forEach { b -> appendLine("| ${b.slot} | `${masterPath(b)}` | ${b.originalName} | ${b.width}x${b.height} | ${b.sha256} | owner-supplied |") }
        }
        appendLine()
        appendLine("## Actual files")
        appendLine()
        appendLine("| File | Source URL | Creator | License | Downloaded |")
        appendLine("|---|---|---|---|---|")
    }

    fun researchMarkdown(p: Project): String = buildString {
        appendLine("# Research notes")
        appendLine()
        if (p.research.isEmpty() && p.references.isEmpty()) appendLine("No research was captured for this project.")
        p.references.forEach { r ->
            appendLine("## Reference: ${r.name}")
            if (r.aspects.isNotEmpty()) appendLine("Aspects taken: ${r.aspects.joinToString("; ")}")
            if (r.summary.isNotBlank()) appendLine(r.summary)
            r.sources.forEach { appendLine("- Source: ${it.title} <${it.url}> ${it.license}".trimEnd()) }
            appendLine()
        }
        p.research.forEach { n ->
            appendLine("## ${n.topic} (${n.kind.name.lowercase().replace('_', ' ')})")
            appendLine(n.summary)
            n.sources.forEach { appendLine("- Source: ${it.title} <${it.url}> ${it.license}".trimEnd()) }
            appendLine()
        }
    }

    fun historyMarkdown(p: Project): String = buildString {
        appendLine("# Spec version history")
        appendLine()
        p.versions.forEach { v ->
            appendLine("- v${v.number} - ${v.label} (${v.kind.label}), readiness ${v.readinessPercent}%. ${v.auditSummary}")
        }
    }

    fun masterPath(b: com.hotattic.gamedesigner.core.model.BrandingAsset) = "branding/master/${b.localFile?.substringAfterLast('/') ?: b.slot}"

    /** The owner's untouched master images, read through [read] (which returns null if a file is missing). */
    fun binaryFiles(project: Project, read: (com.hotattic.gamedesigner.core.model.BrandingAsset) -> ByteArray?): LinkedHashMap<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        project.branding.values.filter { it.mode == com.hotattic.gamedesigner.core.model.BrandingMode.UPLOADED }.forEach { b -> read(b)?.let { out[masterPath(b)] = it } }
        return out
    }

    fun zip(files: Map<String, String>, binaries: Map<String, ByteArray> = emptyMap()): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            files.forEach { (name, text) ->
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
            binaries.forEach { (name, bytes) ->
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }
}
