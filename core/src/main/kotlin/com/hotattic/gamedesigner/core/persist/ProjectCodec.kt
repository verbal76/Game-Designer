package com.hotattic.gamedesigner.core.persist

import com.hotattic.gamedesigner.core.model.AppSettings
import com.hotattic.gamedesigner.core.model.PROJECT_SCHEMA_VERSION
import com.hotattic.gamedesigner.core.model.Project
import kotlinx.serialization.json.Json
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

    fun decode(text: String, registry: MigrationRegistry = migrations): Project {
        val obj = json.parseToJsonElement(text).jsonObject
        val upgraded = registry.upgrade(obj)
        return json.decodeFromJsonElement(Project.serializer(), upgraded)
    }

    fun encodeSettings(s: AppSettings): String = json.encodeToString(AppSettings.serializer(), s)
    fun decodeSettings(text: String): AppSettings = json.decodeFromString(AppSettings.serializer(), text)
}
