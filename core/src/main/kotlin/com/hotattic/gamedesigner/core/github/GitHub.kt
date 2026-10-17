package com.hotattic.gamedesigner.core.github

import com.hotattic.gamedesigner.core.model.RepoInspection
import com.hotattic.gamedesigner.core.net.HttpTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

data class RepoSummary(val owner: String, val name: String, val description: String, val isPrivate: Boolean, val defaultBranch: String, val pushedAt: String)

class GitHubException(val code: Int, message: String) : RuntimeException(message)

/**
 * GitHub REST client. Reads are free to call; the two write methods are only ever invoked after explicit owner
 * confirmation in the UI. The token comes from secure storage per call and is never logged or persisted here.
 */
class GitHubClient(private val http: HttpTransport, private val token: () -> String?) {
    private val json = Json { ignoreUnknownKeys = true }
    private val base = "https://api.github.com"

    private fun headers(): Map<String, String> {
        val t = token()?.takeIf { it.isNotBlank() } ?: throw GitHubException(401, "No GitHub token configured")
        return mapOf("Authorization" to "Bearer $t", "Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28")
    }

    private fun check(code: Int, body: String) {
        if (code !in 200..299) throw GitHubException(code, when (code) {
            401 -> "GitHub rejected the token (401)."
            403 -> "GitHub refused (403): the token may lack permission or hit a rate limit."
            404 -> "Not found (404): check the name and that the token can see this repository."
            else -> "GitHub error $code: ${runCatching { json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.content }.getOrNull().orEmpty()}"
        })
    }

    suspend fun whoAmI(): String { val r = http.get("$base/user", headers()); check(r.code, r.body); return json.parseToJsonElement(r.body).jsonObject["login"]!!.jsonPrimitive.content }

    suspend fun listRepos(): List<RepoSummary> {
        val r = http.get("$base/user/repos?per_page=100&sort=pushed&affiliation=owner,collaborator", headers()); check(r.code, r.body)
        return parseRepos(r.body)
    }

    suspend fun inspect(owner: String, repo: String, now: Long): RepoInspection {
        val meta = http.get("$base/repos/$owner/$repo", headers()); check(meta.code, meta.body)
        val m = json.parseToJsonElement(meta.body).jsonObject
        val branch = m["default_branch"]?.jsonPrimitive?.content ?: "main"
        val tree = http.get("$base/repos/$owner/$repo/git/trees/$branch?recursive=1", headers()); check(tree.code, tree.body)
        val t = json.parseToJsonElement(tree.body).jsonObject
        val paths = t["tree"]!!.jsonArray.mapNotNull { it.jsonObject.takeIf { o -> o["type"]?.jsonPrimitive?.content == "blob" }?.get("path")?.jsonPrimitive?.content }
        val truncated = t["truncated"]?.jsonPrimitive?.content == "true"
        var head = ""
        val commits = runCatching {
            val c = http.get("$base/repos/$owner/$repo/commits?per_page=8", headers())
            if (c.ok) {
                val arr = json.parseToJsonElement(c.body).jsonArray
                head = arr.firstOrNull()?.jsonObject?.get("sha")?.jsonPrimitive?.content.orEmpty()
                arr.map { it.jsonObject["commit"]!!.jsonObject["message"]!!.jsonPrimitive.content.lineSequence().first().take(100) }
            } else emptyList()
        }.getOrDefault(emptyList())
        val godotText = if ("project.godot" in paths) fileText(owner, repo, "project.godot") else null
        val unityText = if ("ProjectSettings/ProjectVersion.txt" in paths) fileText(owner, repo, "ProjectSettings/ProjectVersion.txt") else null
        val gradleText = paths.firstOrNull { it == "build.gradle" || it == "build.gradle.kts" || it == "app/build.gradle" || it == "app/build.gradle.kts" || it == "core/build.gradle" || it == "core/build.gradle.kts" }?.let { fileText(owner, repo, it) }
        val packageJson = if ("package.json" in paths) fileText(owner, repo, "package.json") else null
        val cargo = if ("Cargo.toml" in paths) fileText(owner, repo, "Cargo.toml") else null
        val pubspec = if ("pubspec.yaml" in paths) fileText(owner, repo, "pubspec.yaml") else null
        return RepoInspector.inspect(now, branch, head, paths, truncated, commits, godotText, unityText, gradleText, packageJson, cargo, pubspec)
    }

    private suspend fun fileText(owner: String, repo: String, path: String): String? = runCatching {
        val r = http.get("$base/repos/$owner/$repo/contents/$path", headers())
        if (!r.ok) return null
        val o = json.parseToJsonElement(r.body).jsonObject
        String(Base64.getMimeDecoder().decode(o["content"]!!.jsonPrimitive.content), Charsets.UTF_8).take(60_000)
    }.getOrNull()

    /** WRITE: creates a new private repository. Call only after explicit owner confirmation. */
    suspend fun createRepository(name: String, description: String, isPrivate: Boolean = true): RepoSummary {
        val body = buildJsonObject { put("name", name); put("description", description); put("private", isPrivate); put("auto_init", true) }.toString()
        val r = http.post("$base/user/repos", body, headers()); check(r.code, r.body)
        return parseRepos("[${r.body}]").first()
    }

    /** WRITE: creates or replaces one file on a branch. Call only after explicit owner confirmation. */
    suspend fun putFile(owner: String, repo: String, path: String, branch: String, message: String, content: String) {
        val existing = http.get("$base/repos/$owner/$repo/contents/$path?ref=$branch", headers())
        val sha = if (existing.ok) json.parseToJsonElement(existing.body).jsonObject["sha"]?.jsonPrimitive?.content else null
        val body = buildJsonObject {
            put("message", message); put("branch", branch)
            put("content", Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8)))
            if (sha != null) put("sha", sha)
        }.toString()
        val r = http.put("$base/repos/$owner/$repo/contents/$path", body, headers()); check(r.code, r.body)
    }

    fun parseRepos(body: String): List<RepoSummary> = json.parseToJsonElement(body).jsonArray.map { e ->
        val o: JsonObject = e.jsonObject
        RepoSummary(
            owner = o["owner"]!!.jsonObject["login"]!!.jsonPrimitive.content, name = o["name"]!!.jsonPrimitive.content,
            description = o["description"]?.jsonPrimitive?.content.orEmpty().takeIf { it != "null" }.orEmpty(),
            isPrivate = o["private"]?.jsonPrimitive?.content == "true", defaultBranch = o["default_branch"]?.jsonPrimitive?.content ?: "main",
            pushedAt = o["pushed_at"]?.jsonPrimitive?.content.orEmpty(),
        )
    }
}

/** Pure repository fact extraction. Separates what was observed from what is merely assumed. */
object RepoInspector {
    fun inspect(
        now: Long, branch: String, headSha: String, paths: List<String>, truncated: Boolean, commits: List<String>,
        godotProject: String?, unityVersion: String?, gradleText: String?, packageJson: String?, cargoToml: String?, pubspec: String?,
    ): RepoInspection {
        val facts = mutableListOf<String>()
        val assumptions = mutableListOf<String>()
        var engine = ""; var version = ""; var build = ""
        val langs = linkedSetOf<String>()
        fun has(suffix: String) = paths.any { it.endsWith(suffix) }
        when {
            "project.godot" in paths -> {
                engine = "godot"
                version = godotProject?.let { Regex("config/features=PackedStringArray\\(\"([0-9]+\\.[0-9]+)").find(it)?.groupValues?.get(1) }.orEmpty()
                build = "Godot export presets"; facts += "project.godot present${if (version.isNotBlank()) " (engine feature version $version)" else ""}."
                langs += "GDScript"; if (has(".cs")) langs += "C#"
            }
            "ProjectSettings/ProjectVersion.txt" in paths -> {
                engine = "unity"; version = unityVersion?.let { Regex("m_EditorVersion:\\s*(\\S+)").find(it)?.groupValues?.get(1) }.orEmpty()
                build = "Unity"; langs += "C#"; facts += "ProjectSettings/ProjectVersion.txt present${if (version.isNotBlank()) " (editor $version)" else ""}."
            }
            paths.any { it.endsWith(".uproject") } -> { engine = "unreal"; build = "Unreal UBT"; langs += "C++"; facts += "A .uproject file is present." }
            cargoToml?.contains("bevy") == true -> { engine = "bevy"; build = "cargo"; langs += "Rust"; facts += "Cargo.toml depends on bevy." }
            pubspec?.contains("flame") == true -> { engine = "flutter_flame"; build = "flutter"; langs += "Dart"; facts += "pubspec.yaml depends on flame." }
            packageJson != null && packageJson.contains("phaser") -> { engine = "web_phaser"; build = "npm"; langs += "TypeScript/JavaScript"; facts += "package.json depends on phaser." }
            packageJson != null && (packageJson.contains("\"three\"") || packageJson.contains("babylonjs")) -> { engine = "web_three"; build = "npm"; langs += "TypeScript/JavaScript"; facts += "package.json depends on three.js/Babylon.js." }
            gradleText != null && gradleText.contains("gdx") -> { engine = "libgdx"; build = "Gradle"; langs += "Kotlin/Java"; facts += "Gradle build references libGDX." }
            has("AndroidManifest.xml") -> { engine = "android_native"; build = "Gradle"; langs += "Kotlin/Java"; facts += "AndroidManifest.xml present." }
        }
        if (engine.isBlank()) assumptions += "No recognizable engine files were found; the engine must be established by reading the code."
        if (has("build.gradle") || has("build.gradle.kts")) { if (build.isBlank()) build = "Gradle"; facts += "Gradle build files present." }
        val ci = paths.any { it.startsWith(".github/workflows/") }
        val claudeMd = "CLAUDE.md" in paths
        facts += if (ci) "GitHub Actions workflows exist." else "No GitHub Actions workflows found."
        facts += if (claudeMd) "A CLAUDE.md already exists at the repository root." else "No CLAUDE.md at the repository root."
        if (truncated) assumptions += "The file tree was truncated by GitHub; some files were not seen."
        assumptions += "Whether the current build compiles and the game runs has NOT been verified - it must be tested before anything is changed."
        val top = paths.map { it.substringBefore('/') }.distinct().take(30)
        return RepoInspection(now, branch, headSha, engine, version, build, langs.toList(), top, facts, assumptions, commits, claudeMd, ci)
    }
}
