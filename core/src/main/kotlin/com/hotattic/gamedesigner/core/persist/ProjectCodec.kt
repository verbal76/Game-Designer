package com.hotattic.gamedesigner.core.persist

import com.hotattic.gamedesigner.core.model.AppSettings
import com.hotattic.gamedesigner.core.model.PROJECT_SCHEMA_VERSION
import com.hotattic.gamedesigner.core.model.Project
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One migration step from schema version N to N+1, operating on raw JSON so it can handle shapes the current
 * classes no longer describe.
 */
fun interface Migration { fun migrate(obj: JsonObject): JsonObject }

class MigrationRegistry(private val steps: Map<Int, Migration> = emptyMap(), val currentVersion: Int = PROJECT_SCHEMA_VERSION) {
    fun upgrade(obj: JsonObject): JsonObject {
        var version = obj["schemaVersion"]?.jsonPrimitive?.int ?: 1
        var cur = obj
        if (version > currentVersion) throw UnsupportedSchemaException(version, currentVersion)
        while (version < currentVersion) {
            val step = steps[version] ?: throw IllegalStateException("No migration from schema $version to ${version + 1}")
            cur = step.migrate(cur)
            version += 1
            cur = JsonObject(cur + ("schemaVersion" to JsonPrimitive(version)))
        }
        return cur
    }
}

class UnsupportedSchemaException(val found: Int, val supported: Int) :
    RuntimeException("Project was saved by a newer Game Designer (schema $found; this build supports up to $supported).")

object ProjectCodec {
    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** Registered historical migrations. Add an entry here whenever PROJECT_SCHEMA_VERSION is bumped. */
    val migrations = MigrationRegistry()

    fun encode(p: Project): String = json.encodeToString(Project.serializer(), p)

    /** The encoded text, and the paths of unknown stored fields that could not be placed back (their parent no longer corresponds to anything). */
    data class Preserved(val text: String, val unplaced: List<String>)

    /**
     * Encodes [p], carrying over every field the stored copy has that this build does not know, at any depth. A newer application layer may have
     * added fields (additive, no schema bump); after an OTA rollback the older layer saving the project must not erase them. "Unknown" means present
     * in the stored JSON but absent from what this build re-encodes of that same JSON, so entries the owner deliberately removed are never revived.
     * Anything that cannot be matched back to its parent is reported in [Preserved.unplaced] so the caller can keep the original text.
     */
    fun encodePreservingDetailed(p: Project, previousText: String?): Preserved {
        val fresh = json.encodeToJsonElement(Project.serializer(), p)
        val old = previousText?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
        if (old == null) return Preserved(json.encodeToString(JsonElement.serializer(), fresh), emptyList())
        val known = runCatching { json.encodeToJsonElement(Project.serializer(), json.decodeFromJsonElement(Project.serializer(), old)) }.getOrNull()
            ?: return Preserved(json.encodeToString(JsonElement.serializer(), fresh), emptyList())
        val unplaced = mutableListOf<String>()
        val merged = place(fresh, old, known, "$", unplaced)
        return Preserved(json.encodeToString(JsonElement.serializer(), merged), unplaced)
    }

    fun encodePreserving(p: Project, previousText: String?): String = encodePreservingDetailed(p, previousText).text

    private val identityKeys = listOf("id", "key", "number", "entryId", "slot")
    private fun identity(e: JsonElement): String? = (e as? JsonObject)?.let { o -> identityKeys.firstNotNullOfOrNull { k -> o[k]?.let { "$k=$it" } } }

    /** Walks the stored [old] beside what this build knows of it ([known]); unknown entries are inserted into the same place in [fresh]. */
    private fun place(fresh: JsonElement, old: JsonElement, known: JsonElement, path: String, unplaced: MutableList<String>): JsonElement = when {
        fresh is JsonObject && old is JsonObject && known is JsonObject -> {
            val out = LinkedHashMap<String, JsonElement>(fresh)
            for ((k, ov) in old) {
                val kv = known[k]
                if (kv == null) out[k] = ov                                  // unknown field: carried over untouched
                else if (k in fresh) out[k] = place(fresh.getValue(k), ov, kv, "$path.$k", unplaced)
            }
            JsonObject(out)
        }
        fresh is JsonArray && old is JsonArray && known is JsonArray && old.size == known.size -> {
            val ids = fresh.map(::identity)
            JsonArray(fresh.mapIndexed { i, f -> f }.let { items ->
                val result = items.toMutableList()
                for (i in old.indices) {
                    val oid = identity(old[i]); val target = if (oid != null) ids.indexOf(oid).takeIf { it >= 0 } else if (fresh.size == old.size) i else null
                    if (target == null) { if (hasUnknown(old[i], known[i])) unplaced += "$path[$i]"; continue }
                    result[target] = place(result[target], old[i], known[i], "$path[$i]", unplaced)
                }
                result
            })
        }
        else -> { if (hasUnknown(old, known)) unplaced += path; fresh }
    }

    private fun hasUnknown(old: JsonElement, known: JsonElement): Boolean = when {
        old is JsonObject && known is JsonObject -> old.any { (k, v) -> known[k]?.let { hasUnknown(v, it) } ?: true }
        old is JsonArray && known is JsonArray -> old.size != known.size || old.indices.any { hasUnknown(old[it], known[it]) }
        else -> false
    }

    fun decode(text: String, registry: MigrationRegistry = migrations): Project {
        val obj = json.parseToJsonElement(text).jsonObject
        val upgraded = registry.upgrade(obj)
        return json.decodeFromJsonElement(Project.serializer(), upgraded)
    }

    fun encodeSettings(s: AppSettings): String = json.encodeToString(AppSettings.serializer(), s)
    fun decodeSettings(text: String): AppSettings = json.decodeFromString(AppSettings.serializer(), text)
}
