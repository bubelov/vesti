package org.vestifeed.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The shared JSON codec. Unknown fields are ignored so a server that starts
 * sending a new field cannot break parsing, matching the lenient behaviour the
 * Gson-based code had.
 */
val vestiJson: Json = Json {
    ignoreUnknownKeys = true
}

/** Parses [text] into a JSON tree, replacing `JsonParser.parseString`. */
fun parseJson(text: String): JsonElement = vestiJson.parseToJsonElement(text)

/** Parses [text] into a JSON object, replacing `JsonParser…asJsonObject`. */
fun parseJsonObject(text: String): JsonObject = parseJson(text).jsonObject
