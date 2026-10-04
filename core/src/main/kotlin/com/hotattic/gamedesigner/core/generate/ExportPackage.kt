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
        appendLine("| Need | Resolution | License | Source / instructions |")
        appendLine("|---|---|---|---|")
        p.assets.forEach { a ->
            appendLine("| ${a.needId} | ${a.resolution.name.lowercase().replace('_', ' ')} | ${a.license.ifBlank { "-" }} | ${(a.source + " " + a.notes).trim().replace("|", "/").replace("\n", " ")} |")
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

    fun zip(files: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            files.forEach { (name, text) ->
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }
}
