package casa.moller.tandem.core.sync.engine

import casa.moller.tandem.core.model.error.TandemError
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * A write refused because it would create an object the user's role may not create (Tandoor 2.7.3,
 * api-schema/fixtures/2.7.3/FACTS.md): from 2.7.3 on only the group `user` may create a food, a meal
 * type or a shopping list, also through a nested object such as a shopping entry's
 * `{"food": {"name": "godis"}}`. A guest (or a member whose role changed while the outbox waited)
 * gets **400**, not the 403 of other refusals, with the refusal as a plain message list on that
 * field: `{"food": ["You do not have the required permissions to create food."]}`.
 *
 * Told apart from an ordinary validation error by its SHAPE, never by the localized text: a field
 * error inside the nested object comes as an object (`{"food": {"name": ["…"]}}`, which
 * `ErrorClassifier.fieldErrors` keeps as its JSON text), the refusal as plain strings on a field
 * the app sent as an object. The outbox then treats the row like a 403 refusal (FAILED with
 * `forbidden`, the role read again, the drain stopped) instead of a rejected payload.
 */
internal object NestedCreateRefusal {
    /** True when [error] refuses a nested object of [payload] (the row's JSON body) as a whole. */
    fun isRefusal(payload: String, error: TandemError): Boolean {
        if (error !is TandemError.BadRequest) return false
        val body = parse(payload) ?: return false
        return error.fieldErrors.any { (field, messages) ->
            body[field] is JsonObject && messages.isNotEmpty() && messages.none { it.trimStart().startsWith("{") }
        }
    }

    private fun parse(payload: String): JsonObject? = try {
        Json.parseToJsonElement(payload) as? JsonObject
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}
