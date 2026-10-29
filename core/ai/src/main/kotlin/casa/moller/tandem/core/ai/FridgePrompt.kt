package casa.moller.tandem.core.ai

import casa.moller.tandem.core.model.ai.FridgeVisionError
import casa.moller.tandem.core.model.ai.FridgeVisionResult
import casa.moller.tandem.core.model.ai.RecognizedItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * The fridge photo's prompt and the parsing of the answer, the same for every provider (ai-foto
 * lane tunes the wording). The model answers with a JSON array only; anything else is
 * [FridgeVisionError.Unparseable]. Pure: no Android, no network.
 */
internal object FridgePrompt {
    fun system(languageTag: String): String =
        "You list the food items visible in a photo of a fridge, freezer or pantry, for a household's pantry app. " +
            "Name each item as a shopper would (\"mjölk\", \"cheddar\", \"ägg\"), in the language with the BCP 47 tag " +
            "\"$languageTag\". List only what you can actually see; when unsure, include the item with a low confidence " +
            "instead of guessing a brand or an amount. Answer with a JSON array only, no prose: " +
            "[{\"name\": string, \"amount\": number or null, \"unit\": string or null, \"confidence\": number 0..1, " +
            "\"note\": string or null}]. An empty array when no food is visible."

    const val USER: String = "List the food items in this photo."

    private val json = Json { ignoreUnknownKeys = true }

    /** The items in [text] (a JSON array, possibly inside a ```json fence), or Unparseable. */
    fun parse(text: String): FridgeVisionResult {
        val body = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val array = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonArray
            ?: return FridgeVisionResult.Failed(FridgeVisionError.Unparseable("not a JSON array"))
        val items = array.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val name = item.string("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            RecognizedItem(
                name = name,
                amount = item.number("amount")?.takeIf { it > 0 },
                unit = item.string("unit")?.trim()?.takeIf { it.isNotEmpty() },
                confidence = item.number("confidence")?.coerceIn(0.0, 1.0),
                note = item.string("note")?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
        return FridgeVisionResult.Items(items)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
}
