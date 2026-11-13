package casa.moller.tandem.core.sync.calendar

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.SyncStateEntity
import casa.moller.tandem.core.model.account.SpaceRole
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.mealplan.MealCalendarLink
import casa.moller.tandem.core.model.mealplan.MealCalendarLinkBlock
import casa.moller.tandem.core.network.capabilities.MealPlanIcal
import casa.moller.tandem.core.network.capabilities.TandoorVersionTable
import casa.moller.tandem.core.network.dto.AccessTokenCreateRequestDto
import casa.moller.tandem.core.network.dto.AccessTokenDto
import casa.moller.tandem.core.network.dto.AccessTokenExpiryDto
import casa.moller.tandem.core.network.json.TandoorDates
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.platform.WallClock
import casa.moller.tandem.core.sync.remote.RemoteCaller
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Fas 5 (kalender lane, plan §3.1, §5.5, §7 #36): the subscription link of "Matsedeln i kalendern".
 *
 * The link carries a token of its OWN with the scope `mealplan` (FACTS "Fas 5: matsedeln i
 * kalendern": the only scope the feed takes, and it opens nothing else), created with the account's
 * token and never the account's token itself. It is kept per account in one `sync_state` row ([KEY],
 * JSON in `hash`: id, token, expiry and tokens still to revoke), so it goes with the account
 * (`deleteAccountData`); the database is never backed up. The token lives a year ([LIFETIME]) and
 * is renewed in place (`PATCH expires`, same token, the address keeps working) once less than
 * [RENEW_BEFORE] is left ([renewIfDue], from the daily calendar work).
 *
 * Every request goes through [RemoteCaller] (circuit breaker, rate limits). Never for guests (they
 * only read), never in demo mode, never where the server cannot take the token in the address.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
internal class MealCalendarLinks @Inject constructor(
    private val sources: ActiveSources,
    private val databases: TandemDatabaseFactory,
    private val remote: RemoteCaller,
    private val clock: WallClock,
) {
    private val mutex = Mutex()

    /** The link of the active account (empty in demo mode and without an account). */
    fun observe(): Flow<MealCalendarLink> = sources.source
        .flatMapLatest { source ->
            if (source !is ActiveSource.Remote) return@flatMapLatest flowOf(MealCalendarLink())
            val database = databases.get(source.kind)
            combine(
                database.accountDao().observeGroupLevel(source.accountId),
                database.syncStateDao().observeOne(source.accountId, KEY),
            ) { level, row ->
                val block = blockOf(source, SpaceRole.of(listOfNotNull(level)) == SpaceRole.GUEST)
                val stored = decode(row?.hash)?.takeIf { block == null }
                MealCalendarLink(
                    block = block,
                    url = stored?.let { MealPlanIcal.subscriptionUrl(source.baseUrl, it.token) },
                    expires = instantOf(stored?.expires),
                )
            }
        }
        .catch { emit(MealCalendarLink()) }
        .distinctUntilChanged()

    /** Creates the token unless the account already has one. */
    suspend fun enable(): Outcome<Unit> = mutex.withLock {
        val source = writable() ?: return@withLock blocked()
        if (read(source) != null) return@withLock Outcome.Success(Unit)
        when (val created = create(source)) {
            is Outcome.Failure -> created

            is Outcome.Success -> {
                write(source, created.value)
                Outcome.Success(Unit)
            }
        }
    }

    /** A new token first (the new address works at once), then the old one is revoked. */
    suspend fun replace(): Outcome<Unit> = mutex.withLock {
        val source = writable() ?: return@withLock blocked()
        val old = read(source)
        when (val created = create(source)) {
            is Outcome.Failure -> created

            is Outcome.Success -> {
                val pending = old?.let { it.revoke + it.id }.orEmpty()
                write(source, created.value.copy(revoke = pending))
                revokePending(source)
                Outcome.Success(Unit)
            }
        }
    }

    /** Revokes the token; forgets the link only when the server no longer accepts it. */
    suspend fun disable(): Outcome<Unit> = mutex.withLock {
        val source = sources.current() as? ActiveSource.Remote ?: return@withLock Outcome.Success(Unit)
        val stored = read(source) ?: return@withLock Outcome.Success(Unit)
        revokePending(source)
        // A failure keeps the link: its token would stay valid on the server otherwise.
        val result = revoke(source, stored.id)
        if (result is Outcome.Failure) return@withLock result
        databases.get(source.kind).syncStateDao().delete(source.accountId, KEY)
        Outcome.Success(Unit)
    }

    /**
     * From the daily work (online): moves the expiry a year on once less than [RENEW_BEFORE] is left
     * (same token, so calendars keep their subscription) and revokes tokens a "Byt länk" could not.
     */
    suspend fun renewIfDue(): Outcome<Unit> = mutex.withLock {
        val source = sources.current() as? ActiveSource.Remote ?: return@withLock Outcome.Success(Unit)
        val stored = read(source) ?: return@withLock Outcome.Success(Unit)
        revokePending(source)
        val current = read(source) ?: return@withLock Outcome.Success(Unit)
        val expires = instantOf(current.expires)
        val now = clock.now()
        if (expires != null && expires - now > RENEW_BEFORE) return@withLock Outcome.Success(Unit)
        val body = AccessTokenExpiryDto(format(now + LIFETIME))
        when (val renewed = remote.call(source, background = true) { auth.renewAccessToken(stored.id, body) }) {
            is Outcome.Failure -> renewed

            is Outcome.Success -> {
                write(source, current.copy(expires = renewed.value.expires ?: body.expires))
                Outcome.Success(Unit)
            }
        }
    }

    /** Revokes the tokens still waiting to be revoked; keeps those that failed. */
    private suspend fun revokePending(source: ActiveSource.Remote) {
        val stored = read(source) ?: return
        if (stored.revoke.isEmpty()) return
        val failed = stored.revoke.filter { revoke(source, it) is Outcome.Failure }
        write(source, stored.copy(revoke = failed))
    }

    /** DELETE; a token the server no longer knows (404) counts as revoked. */
    private suspend fun revoke(source: ActiveSource.Remote, id: Long): Outcome<Unit> =
        when (val result = remote.call(source, background = false) { auth.deleteAccessToken(id) }) {
            is Outcome.Success -> result
            is Outcome.Failure -> if (result.error == TandemError.NotFound) Outcome.Success(Unit) else result
        }

    private suspend fun create(source: ActiveSource.Remote): Outcome<StoredLink> {
        val expires = format(clock.now() + LIFETIME)
        val body = AccessTokenCreateRequestDto(scope = MealPlanIcal.SCOPE, expires = expires)
        return when (val created = remote.call(source, background = false) { auth.createAccessToken(body) }) {
            is Outcome.Failure -> created

            is Outcome.Success -> created.value.toStored(expires)?.let { Outcome.Success(it) }
                ?: Outcome.Failure(TandemError.Unknown(UNEXPECTED_ANSWER))
        }
    }

    private fun AccessTokenDto.toStored(sentExpiry: String): StoredLink? {
        val id = id ?: return null
        // The token is plaintext in the create answer (and always for the scope `mealplan`, FACTS).
        if (!token.startsWith(TOKEN_PREFIX) || '*' in token) return null
        return StoredLink(id = id, token = token, expires = expires ?: sentExpiry)
    }

    /** The active account when it may have a link, else null. */
    private suspend fun writable(): ActiveSource.Remote? {
        val source = sources.current() as? ActiveSource.Remote ?: return null
        val level = databases.get(source.kind).accountDao().get(source.accountId)?.groupLevel
        return source.takeIf { blockOf(it, SpaceRole.of(listOfNotNull(level)) == SpaceRole.GUEST) == null }
    }

    private suspend fun blocked(): Outcome.Failure {
        val source = sources.current()
        return Outcome.Failure(
            when {
                source !is ActiveSource.Remote -> DataProblem.NO_ACCOUNT.toError()
                blockOf(source, guest = false) != null -> DataProblem.SERVER_TOO_OLD.toError()
                else -> DataProblem.READ_ONLY.toError()
            },
        )
    }

    private suspend fun read(source: ActiveSource.Remote): StoredLink? =
        decode(databases.get(source.kind).syncStateDao().get(source.accountId, KEY)?.hash)

    private suspend fun write(source: ActiveSource.Remote, link: StoredLink) {
        val row = SyncStateEntity(accountId = source.accountId, entity = KEY, hash = JSON.encodeToString(link))
        databases.get(source.kind).syncStateDao().upsert(row)
    }

    /**
     * @property id the token's id on the server.
     * @property token the token (only in this row, never in logs or diagnostics).
     * @property expires its expiry as Tandoor sent it.
     * @property revoke ids of older tokens of this link still to be revoked (an offline "Byt länk").
     */
    @Serializable
    internal data class StoredLink(val id: Long, val token: String, val expires: String? = null, val revoke: List<Long> = emptyList()) {
        override fun toString(): String = "StoredLink(id=$id, token=<redacted>, expires=$expires, revoke=$revoke)"
    }

    companion object {
        /** The `sync_state.entity` of the link. */
        const val KEY: String = "meal_calendar_link"

        /** How long a new or renewed token lives. */
        val LIFETIME: Duration = 365.days

        /** Renewed once less than this is left. */
        val RENEW_BEFORE: Duration = 60.days

        private const val TOKEN_PREFIX = "tda_"
        private const val UNEXPECTED_ANSWER = "tandem.meal-calendar.unexpected-token-answer"
        private val JSON = Json { ignoreUnknownKeys = true }

        fun blockOf(source: ActiveSource.Remote, guest: Boolean): MealCalendarLinkBlock? = when {
            guest -> MealCalendarLinkBlock.GUEST
            !MealPlanIcal.tokenInUrl(TandoorVersionTable.assess(source.account.serverVersion)) -> MealCalendarLinkBlock.UNSUPPORTED
            else -> null
        }

        private fun instantOf(text: String?): Instant? =
            TandoorDates.parseInstantOrNull(text)?.toEpochMilli()?.let(Instant::fromEpochMilliseconds)

        private fun format(instant: Instant): String = TandoorDates.format(java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds()))

        internal fun decode(text: String?): StoredLink? {
            if (text.isNullOrBlank()) return null
            return try {
                JSON.decodeFromString<StoredLink>(text)
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}
