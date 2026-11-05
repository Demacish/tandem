package casa.moller.tandem.core.sync.push

import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.SyncStateEntity
import casa.moller.tandem.core.model.push.FastPushError
import casa.moller.tandem.core.model.push.FastPushStatus
import casa.moller.tandem.core.sync.account.ActiveSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The fast push settings of one account (Fas 4, push lane). No Room schema change: one
 * `sync_state` row per account ([ENTITY], the JSON in `hash`, like the household members), so it
 * is deleted with the account. Holds no token: the endpoint, its public Web Push keys and the
 * service's opaque registration id are all the app needs.
 */
@Serializable
internal data class StoredFastPush(
    val serviceUrl: String = "",
    val distributor: String? = null,
    val status: FastPushStatus = FastPushStatus.OFF,
    val endpoint: String? = null,
    val p256dh: String? = null,
    val auth: String? = null,
    val registrationId: String? = null,
    val testSupported: Boolean = false,
    val pollSeconds: Int? = null,
    val error: FastPushError? = null,
    val lastWakeUpMillis: Long? = null,
) {
    /** Whether the phone should hold a UnifiedPush registration for the account. */
    val wanted: Boolean
        get() = status != FastPushStatus.OFF && distributor != null
}

/** Reads and writes [StoredFastPush] in the real database (demo mode has none). */
@Singleton
internal class FastPushStore @Inject constructor(private val databases: TandemDatabaseFactory) {
    fun observe(remote: ActiveSource.Remote): Flow<StoredFastPush?> =
        databases.get(remote.kind).syncStateDao().observeOne(remote.accountId, ENTITY)
            .map { row -> row?.hash?.let(::decode) }
            .distinctUntilChanged()

    suspend fun load(remote: ActiveSource.Remote): StoredFastPush? =
        databases.get(remote.kind).syncStateDao().get(remote.accountId, ENTITY)?.hash?.let(::decode)

    suspend fun save(remote: ActiveSource.Remote, value: StoredFastPush) {
        databases.get(remote.kind).syncStateDao().upsert(
            SyncStateEntity(accountId = remote.accountId, entity = ENTITY, hash = json.encodeToString(StoredFastPush.serializer(), value)),
        )
    }

    /** Changes the stored value (a missing row starts from the defaults). */
    suspend fun update(remote: ActiveSource.Remote, change: (StoredFastPush) -> StoredFastPush): StoredFastPush {
        val next = change(load(remote) ?: StoredFastPush())
        save(remote, next)
        return next
    }

    private fun decode(text: String): StoredFastPush? = try {
        json.decodeFromString(StoredFastPush.serializer(), text)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    companion object {
        /** The `sync_state.entity` of the row. */
        const val ENTITY: String = "fast_push"

        private val json = Json { ignoreUnknownKeys = true }
    }
}
