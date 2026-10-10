package casa.moller.tandem.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * `GET /api/server-settings/current/` (no login needed, FACTS a). Only the fields the app uses;
 * the server sends many more (theme, logos), which are ignored.
 */
@Serializable
data class ServerSettingsDto(
    val version: String? = null,
    val hosted: Boolean = false,
    val debug: Boolean = false,
    @SerialName("disable_external_connectors") val disableExternalConnectors: Boolean = false,
    /** A STRING on 2.6.13 (`"5"`), a number in the schema; kept raw and read by [shoppingMinAutosyncIntervalOrNull]. */
    @SerialName("shopping_min_autosync_interval") val shoppingMinAutosyncInterval: JsonElement? = null,
    @SerialName("terms_url") val termsUrl: String? = null,
    @SerialName("privacy_url") val privacyUrl: String? = null,
    @SerialName("imprint_url") val imprintUrl: String? = null,
) {
    /** The minimum shopping auto-sync interval, whether the server sent it as string or number. */
    fun shoppingMinAutosyncIntervalOrNull(): Int? = (shoppingMinAutosyncInterval as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()
}
