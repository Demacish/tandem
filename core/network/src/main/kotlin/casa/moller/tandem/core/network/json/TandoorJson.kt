package casa.moller.tandem.core.network.json

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * The one [Json] configuration for Tandoor (plan 3.6, "tolerance"):
 * - `ignoreUnknownKeys`: new server fields never break parsing.
 * - `explicitNulls = false`: a missing field and `null` both mean "not set"; nulls are not sent.
 * - `coerceInputValues`: `null` for a non-null field with a default falls back to the default.
 * - `encodeDefaults`: request bodies always contain every field the DTO declares.
 *
 * Deliberately not provided through Hilt, so it can never clash with another module's `Json`.
 */
@OptIn(ExperimentalSerializationApi::class)
val TandoorJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
}
