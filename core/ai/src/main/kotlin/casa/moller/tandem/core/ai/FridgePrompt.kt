package casa.moller.tandem.core.ai

import casa.moller.tandem.core.model.ai.FridgeVisionError
import casa.moller.tandem.core.model.ai.FridgeVisionResult
import casa.moller.tandem.core.model.ai.RecognizedItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * The fridge photo's prompt, the ONE result shape every provider is asked for, and the parsing of
 * the answer (plan §8.8, B22). The structured result is
 * `{"items": [{"name", "amount"?, "unit"?, "confidence" 0..1, "note"?}]}`; each provider gets it as
 * its own schema dialect ([claudeSchema], [openAiSchema], [geminiSchema]) and the answer is read
 * here leniently (also a bare array, a ```json fence, or a cut-off answer). Pure: no Android, no
 * network.
 */
internal object FridgePrompt {
    /** The system prompt: visible food only, in the UI language, no guessing (the review checks it all). */
    fun system(languageTag: String): String {
        val language = languageTag.substringBefore('-').ifBlank { "en" }
        return "You list the food items that are VISIBLE in a photo of a fridge, freezer or pantry, for a household's pantry " +
            "app. Name each item as a shopper would (for example \"milk\", \"cheddar\", \"eggs\"), written in the language " +
            "with the ISO 639 code \"$language\". List only what you can actually see. Do not guess items that are hidden, " +
            "inside closed or opaque containers or behind other items, and do not guess brands. Give an amount and a unit " +
            "only when you can count or read them (for example 6 eggs, 1 l milk); otherwise leave them null. Set confidence " +
            "between 0 and 1: how sure you are that the item is really there and correctly named. Use note for a short " +
            "remark in the same language, such as \"half full\" or \"partly hidden\", or null. Every item once; an empty " +
            "list when no food is visible. The household checks every row before anything is saved."
    }

    /** The user turn next to the photo. */
    const val USER: String = "List the food items you can see in this photo."

    /** The output limit of every provider (non-streaming; the answer is a short list). */
    const val MAX_OUTPUT_TOKENS: Long = 16_000L

    private const val NAME = "name"
    private const val AMOUNT = "amount"
    private const val UNIT = "unit"
    private const val CONFIDENCE = "confidence"
    private const val NOTE = "note"
    private const val ITEMS = "items"

    /** Anthropic structured outputs (`output_config.format`): `anyOf` with null, `additionalProperties: false`. */
    fun claudeSchema(): Map<String, Any> {
        fun nullable(type: String) = mapOf("anyOf" to listOf(mapOf("type" to type), mapOf("type" to "null")))
        val item = mapOf(
            "type" to "object",
            "properties" to mapOf(
                NAME to mapOf("type" to "string"),
                AMOUNT to nullable("number"),
                UNIT to nullable("string"),
                CONFIDENCE to mapOf("type" to "number"),
                NOTE to nullable("string"),
            ),
            "required" to listOf(NAME, AMOUNT, UNIT, CONFIDENCE, NOTE),
            "additionalProperties" to false,
        )
        return mapOf(
            "type" to "object",
            "properties" to mapOf(ITEMS to mapOf("type" to "array", "items" to item)),
            "required" to listOf(ITEMS),
            "additionalProperties" to false,
        )
    }

    /** OpenAI strict JSON schema (Responses API `text.format`): every field required, null as a type array. */
    fun openAiSchema(): Map<String, Any> {
        val item = mapOf(
            "type" to "object",
            "properties" to mapOf(
                NAME to mapOf("type" to "string"),
                AMOUNT to mapOf("type" to listOf("number", "null")),
                UNIT to mapOf("type" to listOf("string", "null")),
                CONFIDENCE to mapOf("type" to "number"),
                NOTE to mapOf("type" to listOf("string", "null")),
            ),
            "required" to listOf(NAME, AMOUNT, UNIT, CONFIDENCE, NOTE),
            "additionalProperties" to false,
        )
        return mapOf(
            "type" to "object",
            "properties" to mapOf(ITEMS to mapOf("type" to "array", "items" to item)),
            "required" to listOf(ITEMS),
            "additionalProperties" to false,
        )
    }

    /** Gemini `generationConfig.responseSchema` (the OpenAPI 3.0 subset: upper-case types, `nullable`). */
    fun geminiSchema(): Map<String, Any> {
        val item = mapOf(
            "type" to "OBJECT",
            "properties" to mapOf(
                NAME to mapOf("type" to "STRING"),
                AMOUNT to mapOf("type" to "NUMBER", "nullable" to true),
                UNIT to mapOf("type" to "STRING", "nullable" to true),
                CONFIDENCE to mapOf("type" to "NUMBER"),
                NOTE to mapOf("type" to "STRING", "nullable" to true),
            ),
            "required" to listOf(NAME, CONFIDENCE),
            "propertyOrdering" to listOf(NAME, AMOUNT, UNIT, CONFIDENCE, NOTE),
        )
        return mapOf(
            "type" to "OBJECT",
            "properties" to mapOf(ITEMS to mapOf("type" to "ARRAY", "items" to item)),
            "required" to listOf(ITEMS),
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The items in [text]: `{"items": [...]}` or a bare array, possibly inside a ```json fence. When
     * the answer was cut off at the output limit ([truncated]) the complete items before the cut are
     * kept and the result says so; otherwise anything else is [FridgeVisionError.Unparseable].
     */
    fun parse(text: String, truncated: Boolean = false): FridgeVisionResult {
        val body = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> root[ITEMS] as? JsonArray
            else -> null
        }
        if (array != null) return FridgeVisionResult.Items(array.mapNotNull(::item), truncated)
        if (truncated) return FridgeVisionResult.Items(salvage(body), truncated = true)
        return FridgeVisionResult.Failed(FridgeVisionError.Unparseable("not the expected JSON"))
    }

    private fun item(element: JsonElement): RecognizedItem? {
        val item = element as? JsonObject ?: return null
        val name = item.string(NAME)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return RecognizedItem(
            name = name,
            amount = item.number(AMOUNT)?.takeIf { it > 0 },
            unit = item.string(UNIT)?.trim()?.takeIf { it.isNotEmpty() },
            confidence = item.number(CONFIDENCE)?.coerceIn(0.0, 1.0),
            note = item.string(NOTE)?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /** The complete `{...}` objects inside the first `[` of a cut-off answer (strings and escapes respected). */
    // A small JSON scanner: one branch per token kind reads better than helpers that share the state.
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    private fun salvage(body: String): List<RecognizedItem> {
        val start = body.indexOf('[').takeIf { it >= 0 } ?: return emptyList()
        val items = mutableListOf<RecognizedItem>()
        var depth = 0
        var objectStart = -1
        var inString = false
        var escaped = false
        for (index in start + 1 until body.length) {
            val char = body[index]
            when {
                escaped -> escaped = false
                inString && char == '\\' -> escaped = true
                char == '"' -> inString = !inString
                inString -> Unit
                char == '{' -> {
                    if (depth == 0) objectStart = index
                    depth++
                }
                char == '}' && depth > 0 -> {
                    depth--
                    if (depth == 0 && objectStart >= 0) {
                        runCatching { json.parseToJsonElement(body.substring(objectStart, index + 1)) }.getOrNull()
                            ?.let(::item)
                            ?.let(items::add)
                        objectStart = -1
                    }
                }
                char == ']' && depth == 0 -> return items
            }
        }
        return items
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
}
