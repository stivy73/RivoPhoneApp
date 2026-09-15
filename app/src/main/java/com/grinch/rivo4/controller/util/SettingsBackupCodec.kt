package com.grinch.rivo4.controller.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class SettingsBackupDocument(
    val applicationId: String,
    val createdAtEpochMillis: Long,
    val settings: Map<String, Any>
)

object SettingsBackupCodec {
    private const val FORMAT = "rivo-settings"
    private const val VERSION = 1
    private const val MAX_DOCUMENT_CHARS = 2_000_000
    private const val MAX_SETTINGS = 2_000
    private const val MAX_KEY_LENGTH = 200
    private val json = Json { prettyPrint = true }

    fun encode(
        settings: Map<String, Any>,
        applicationId: String,
        createdAtEpochMillis: Long = System.currentTimeMillis()
    ): String {
        require(settings.size <= MAX_SETTINGS) { "Too many settings" }
        val encodedSettings = buildJsonObject {
            settings.toSortedMap().forEach { (key, value) ->
                require(key.isNotBlank() && key.length <= MAX_KEY_LENGTH) { "Invalid setting key" }
                put(key, encodeValue(value))
            }
        }
        val document = buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("applicationId", applicationId)
            put("createdAtEpochMillis", createdAtEpochMillis)
            put("settings", encodedSettings)
        }
        return json.encodeToString(JsonObject.serializer(), document)
    }

    fun decode(document: String): SettingsBackupDocument {
        require(document.length <= MAX_DOCUMENT_CHARS) { "Backup file is too large" }
        val root = json.parseToJsonElement(document).jsonObject
        require(root["format"]?.jsonPrimitive?.contentOrNull == FORMAT) { "Unsupported backup format" }
        require(root["version"]?.jsonPrimitive?.intOrNull == VERSION) { "Unsupported backup version" }
        val applicationId = root["applicationId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        require(applicationId.isNotBlank()) { "Missing application id" }
        val createdAt = root["createdAtEpochMillis"]?.jsonPrimitive?.longOrNull
            ?: throw IllegalArgumentException("Missing creation time")
        val encodedSettings = root["settings"]?.jsonObject
            ?: throw IllegalArgumentException("Missing settings")
        require(encodedSettings.size <= MAX_SETTINGS) { "Too many settings" }

        val decoded = linkedMapOf<String, Any>()
        encodedSettings.forEach { (key, value) ->
            require(key.isNotBlank() && key.length <= MAX_KEY_LENGTH) { "Invalid setting key" }
            decoded[key] = decodeValue(value.jsonObject)
        }
        return SettingsBackupDocument(applicationId, createdAt, decoded)
    }

    private fun encodeValue(value: Any): JsonObject = buildJsonObject {
        when (value) {
            is Boolean -> { put("type", "boolean"); put("value", value) }
            is Int -> { put("type", "int"); put("value", value) }
            is Long -> { put("type", "long"); put("value", value) }
            is Float -> { put("type", "float"); put("value", value) }
            is String -> { put("type", "string"); put("value", value) }
            is Set<*> -> {
                require(value.all { it is String }) { "Unsupported set value" }
                put("type", "string-set")
                put("value", JsonArray(value.filterIsInstance<String>().sorted().map(::JsonPrimitive)))
            }
            else -> throw IllegalArgumentException("Unsupported setting value")
        }
    }

    private fun decodeValue(encoded: JsonObject): Any {
        val type = encoded["type"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("Missing setting type")
        val value = encoded["value"] ?: throw IllegalArgumentException("Missing setting value")
        return when (type) {
            "boolean" -> value.jsonPrimitive.booleanOrNull
            "int" -> value.jsonPrimitive.intOrNull
            "long" -> value.jsonPrimitive.longOrNull
            "float" -> value.jsonPrimitive.floatOrNull
            "string" -> value.jsonPrimitive.contentOrNull
            "string-set" -> value.jsonArray.map { element ->
                element.jsonPrimitive.contentOrNull
                    ?: throw IllegalArgumentException("Invalid string set")
            }.toSet()
            else -> throw IllegalArgumentException("Unsupported setting type")
        } ?: throw IllegalArgumentException("Invalid setting value")
    }
}
