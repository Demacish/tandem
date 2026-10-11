package casa.moller.tandem.core.database.mapper

import casa.moller.tandem.core.model.recipe.UnitConversion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The JSON stored in `ingredient.conversions_json`: an array of
 * `{"amount": 250.0, "unit": "g", "unit_plural": "g"}` objects (`unit_plural` optional).
 * Invalid or unknown content decodes to an empty list, never an exception.
 */
object IngredientConversions {
    /** JSON for [conversions], or null when there are none. */
    fun encode(conversions: List<UnitConversion>): String? {
        if (conversions.isEmpty()) return null
        return buildJsonArray {
            conversions.forEach { conversion ->
                add(
                    buildJsonObject {
                        put("amount", conversion.amount)
                        put("unit", conversion.unitName)
                        conversion.unitPluralName?.let { put("unit_plural", it) }
                    },
                )
            }
        }.toString()
    }

    /** The conversions in [json]; empty for null, blank or unreadable JSON. */
    fun decode(json: String?): List<UnitConversion> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            Json.parseToJsonElement(json).jsonArray.mapNotNull { element ->
                val fields = element.jsonObject
                val amount = fields["amount"]?.jsonPrimitive?.doubleOrNull
                val unit = fields["unit"]?.jsonPrimitive?.contentOrNull
                if (amount == null || unit == null) {
                    null
                } else {
                    UnitConversion(amount = amount, unitName = unit, unitPluralName = fields["unit_plural"]?.jsonPrimitive?.contentOrNull)
                }
            }
        }.getOrDefault(emptyList())
    }
}
