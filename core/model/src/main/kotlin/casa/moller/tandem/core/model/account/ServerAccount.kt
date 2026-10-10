package casa.moller.tandem.core.model.account

import kotlin.time.Instant

/**
 * A Tandoor account that this device is logged in to (plan 3.1: one device token per account).
 *
 * The device token is deliberately **not** part of this model. It lives only in the encrypted
 * account store (`:core:datastore`, `SecureAccountStore.token`), so a [ServerAccount] can be
 * logged, shown in the UI or kept in a ViewModel without leaking a credential.
 *
 * @property id local, random identifier (UUID) created on this device. Never sent to the server.
 * @property baseUrl normalized server base URL as entered by the user, for example
 *   `https://recipes.example.org` or `https://example.org/tandoor` (no trailing slash).
 * @property username the Tandoor username used for the login.
 * @property displayName `display_name` from `GET /api/user/{id}/`, if the server returned one.
 * @property userId Tandoor user id (`user_id` from the login response).
 * @property spaceId id of the active space (`GET /api/space/current/`).
 * @property spaceName name of the active space, for the account switcher.
 * @property tokenId server id of the device token (`/api/access-token/{id}/`), used to revoke it
 *   on logout. `null` for a pasted token whose id is unknown.
 * @property tokenExpires when the device token expires, if known.
 * @property serverVersion Tandoor version from `GET /api/server-settings/current/` at the last
 *   check, for example `2.6.13`.
 * @property addedAt when the account was added on this device.
 * @property tokenOrigin how the token was obtained. Only a token Tandem created itself
 *   ([TokenOrigin.CREATED]) is ever deleted on the server by the app; a pasted token belongs to
 *   the user and may be in use elsewhere. Defaults to [TokenOrigin.PASTED], the safe value.
 */
data class ServerAccount(
    val id: String,
    val baseUrl: String,
    val username: String,
    val displayName: String? = null,
    val userId: Long,
    val spaceId: Long,
    val spaceName: String? = null,
    val tokenId: Long? = null,
    val tokenExpires: Instant? = null,
    val serverVersion: String? = null,
    val addedAt: Instant,
    val tokenOrigin: TokenOrigin = TokenOrigin.PASTED,
) {
    /** Name to show for the account: the display name if set, otherwise the username. */
    val label: String
        get() = displayName?.takeIf { it.isNotBlank() } ?: username
}

/** Where the device token of a [ServerAccount] came from (plan 4.2 step 4). */
enum class TokenOrigin {
    /** Created by Tandem for this device at a password login. Tandem may revoke it at logout. */
    CREATED,

    /**
     * Created by the user in Tandoor (Settings -> API) and pasted into Tandem. It may be shared
     * with other apps or devices, so Tandem never deletes it on the server.
     */
    PASTED,
}
