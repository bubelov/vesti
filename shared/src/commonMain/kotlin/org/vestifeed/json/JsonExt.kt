package org.vestifeed.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

// Gson's `JsonElement` accessors, re-expressed on kotlinx.serialization so the
// ported Miniflux client reads the same.
val JsonElement.asString: String get() = jsonPrimitive.content

val JsonElement.asLong: Long get() = jsonPrimitive.long

val JsonElement.asBoolean: Boolean get() = jsonPrimitive.boolean

val JsonElement.asJsonArray: JsonArray get() = jsonArray

val JsonElement.asJsonObject: JsonObject get() = jsonObject

val JsonElement.isJsonNull: Boolean get() = this is JsonNull

fun JsonObject.has(name: String): Boolean = containsKey(name)

fun JsonObject.getAsJsonArray(name: String): JsonArray? =
    this[name]?.takeUnless { it is JsonNull }?.jsonArray

fun JsonObject.stringOrNull(name: String): String? =
    this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

fun JsonObject.longOrNull(name: String): Long? =
    this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.long

fun JsonObject.booleanOrNull(name: String): Boolean? =
    this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.boolean

fun JsonObject.objectOrNull(name: String): JsonObject? =
    this[name]?.takeUnless { it is JsonNull }?.jsonObject

fun JsonPrimitive(content: String): JsonPrimitive = JsonPrimitive(content)
