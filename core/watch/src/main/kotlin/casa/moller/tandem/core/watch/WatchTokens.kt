package casa.moller.tandem.core.watch

import casa.moller.tandem.core.data.sync.RequestGuard
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.UnauthorizedSource
import casa.moller.tandem.core.network.api.TandoorApiFactory
import casa.moller.tandem.core.network.dto.AccessTokenCreateRequestDto
import casa.moller.tandem.core.network.error.apiCall
import casa.moller.tandem.core.network.http.TokenProvider
import casa.moller.tandem.core.network.json.TandoorDates
import casa.moller.tandem.core.watchlink.WatchTokenPolicy
import javax.inject.Inject
import kotlin.time.Instant

/** A watch token as the server created it (plaintext only in this answer, FACTS c). */
class CreatedWatchToken(val baseUrl: String, val token: String, val id: Long, val expires: Instant) {
    override fun toString(): String = "CreatedWatchToken(baseUrl=$baseUrl, token=<redacted>, id=$id, expires=$expires)"
}

/**
 * The watch token on the server (plan §3.1 "Andra klienter får egna, smalare tokens"), made and
 * deleted with the phone's own token, which never leaves the phone.
 *
 * Every request passes the sync's guard for [accountId] (0.8.0 review, fix-08; plan §5.4): it is
 * not sent while the account's token is refused, the server blocks (403) or throttles (429), nor
 * for a [background] request (an automatic renewal) while the circuit breaker is open; its result
 * is recorded, so a 403 or 429 here stops the sync too.
 */
interface WatchTokens {
    /** `POST /api/access-token/` `{scope: "read write", expires}` with the phone's token of [accountId]. */
    suspend fun create(
        accountId: String,
        baseUrl: String,
        phoneToken: String,
        expires: Instant,
        background: Boolean = false,
    ): Outcome<CreatedWatchToken>

    /**
     * `DELETE /api/access-token/{id}/` with the phone's token of [accountId] (the owner may delete
     * any of its tokens). A token that is already gone (404) counts as deleted.
     */
    suspend fun delete(accountId: String, baseUrl: String, phoneToken: String, tokenId: Long, background: Boolean = false): Outcome<Unit>
}

/** [WatchTokens] on core:network (the phone's shared client: HTTPS only, the rate limit) and the sync's [RequestGuard]. */
internal class TandoorWatchTokens @Inject constructor(private val apiFactory: TandoorApiFactory, private val guard: RequestGuard) :
    WatchTokens {
    override suspend fun create(
        accountId: String,
        baseUrl: String,
        phoneToken: String,
        expires: Instant,
        background: Boolean,
    ): Outcome<CreatedWatchToken> {
        val api = apiFactory.create(baseUrl, TokenProvider { phoneToken })
        val wireExpiry = TandoorDates.format(java.time.Instant.ofEpochMilli(expires.toEpochMilliseconds()))
        val request = AccessTokenCreateRequestDto(scope = WatchTokenPolicy.SCOPE, expires = wireExpiry)
        return when (val created = guarded(accountId, background) { api.auth.createAccessToken(request) }) {
            is Outcome.Failure -> created

            is Outcome.Success -> {
                val token = created.value.token.trim()
                val id = created.value.id
                if (token.isEmpty() || token.contains('*') || id == null) {
                    Outcome.Failure(TandemError.Decode("access-token: plaintext token or id missing"))
                } else {
                    val serverExpiry = TandoorDates.parseInstantOrNull(created.value.expires)
                        ?.let { Instant.fromEpochMilliseconds(it.toEpochMilli()) }
                        ?: expires
                    Outcome.Success(CreatedWatchToken(api.baseUrl, token, id, serverExpiry))
                }
            }
        }
    }

    override suspend fun delete(accountId: String, baseUrl: String, phoneToken: String, tokenId: Long, background: Boolean): Outcome<Unit> {
        val api = apiFactory.create(baseUrl, TokenProvider { phoneToken })
        return when (val deleted = guarded(accountId, background) { api.auth.deleteAccessToken(tokenId) }) {
            is Outcome.Success -> deleted
            is Outcome.Failure -> if (deleted.error.isAlreadyGone()) Outcome.Success(Unit) else deleted
        }
    }

    /** [block] unless the guard holds it back; its result (also a 404) is recorded like `RemoteCaller` does. */
    private suspend fun <T> guarded(accountId: String, background: Boolean, block: suspend () -> T): Outcome<T> {
        guard.check(accountId, background)?.let { return Outcome.Failure(it) }
        val result = apiCall { block() }
        guard.record(accountId, (result as? Outcome.Failure)?.error)
        return result
    }

    // FACTS g: an unknown token id is a JSON 404 ("No AccessToken matches the given query").
    private fun TandemError.isAlreadyGone(): Boolean = this == TandemError.NotFound
}

/** Tandoor's own 403 (an invalid or expired PHONE token): the user has to log in again. */
internal fun TandemError.isPhoneLoggedOut(): Boolean = this is TandemError.Unauthorized && source == UnauthorizedSource.TANDOOR
