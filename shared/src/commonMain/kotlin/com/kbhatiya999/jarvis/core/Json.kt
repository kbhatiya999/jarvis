package com.kbhatiya999.jarvis.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal fun parseJson(text: String): JsonElement? = try { Json.parseToJsonElement(text) } catch (e: Exception) { null }
internal fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull ?: ""
internal fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0L
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
internal fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.contentOrNull == "true"

internal fun stringList(text: String?): List<String> = try {
    if (text == null) emptyList() else Json.parseToJsonElement(text).jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
} catch (e: Exception) {
    emptyList()
}

internal fun stringListJson(items: List<String>): String = JsonArray(items.map { JsonPrimitive(it) }).toString()
