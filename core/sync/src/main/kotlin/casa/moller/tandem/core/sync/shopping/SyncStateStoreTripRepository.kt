package casa.moller.tandem.core.sync.shopping

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.shopping.StoreTripRepository
import casa.moller.tandem.core.data.shopping.StoreTripRepository.Companion.MAX_TRIPS_PER_STORE
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.SyncStateEntity
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.catchingUnexpected
import casa.moller.tandem.core.model.shopping.StoreTrip
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * [StoreTripRepository] (Fas 4 "Inlärd butiksordning", plan §8.2): the store-mode trips live in one
 * `sync_state` row per account ([KEY]; a JSON array in `hash`: `{"s": store, "t": start, "c":
 * [categories]}`), so no schema change is needed. At most [MAX_TRIPS_PER_STORE] per store and
 * [MAX_STORES] stores are kept, the newest. A device record: nothing goes to Tandoor (the order is
 * written with `SupermarketRepository.reorderAisles` when the user approves it). Guests may record
 * too (they cannot check entries, so nothing comes in); demo mode writes the demo database.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
internal class SyncStateStoreTripRepository @Inject constructor(
    private val sources: ActiveSources,
    private val databases: TandemDatabaseFactory,
) : StoreTripRepository {
    private val writes = Mutex()

    override fun observeTrips(supermarketId: Long): Flow<List<StoreTrip>> = sources.source
        .flatMapLatest { source ->
            if (source !is ActiveSource.Stored) return@flatMapLatest flowOf(emptyList<StoreTrip>())
            databases.get(source.kind).syncStateDao().observeOne(source.accountId, KEY)
                .map { row -> decode(row?.hash).filter { it.supermarketId == supermarketId } }
        }
        .catch { emit(emptyList()) }
        .distinctUntilChanged()

    override suspend fun record(trip: StoreTrip): Outcome<Unit> = catchingUnexpected {
        val source = sources.current() as? ActiveSource.Stored
            ?: return@catchingUnexpected Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
        val states = databases.get(source.kind).syncStateDao()
        writes.withLock {
            val before = decode(states.get(source.accountId, KEY)?.hash)
            val others = before.filterNot { it.supermarketId == trip.supermarketId && it.startedAt == trip.startedAt }
            val after = keepNewest(listOf(trip.copy(categoryIds = trip.categoryIds.distinct())) + others)
            states.upsert(SyncStateEntity(accountId = source.accountId, entity = KEY, hash = encode(after)))
        }
        Outcome.Success(Unit)
    }

    companion object {
        /** The `sync_state.entity` of the trips. */
        const val KEY: String = "store_trips"

        /** Stores whose trips are kept (the most recently visited). */
        const val MAX_STORES: Int = 20

        /** [trips] newest first, at most [MAX_TRIPS_PER_STORE] per store and [MAX_STORES] stores. */
        internal fun keepNewest(trips: List<StoreTrip>): List<StoreTrip> {
            val sorted = trips.sortedByDescending { it.startedAt }
            val stores = sorted.map { it.supermarketId }.distinct().take(MAX_STORES).toSet()
            return sorted.filter { it.supermarketId in stores }
                .groupBy { it.supermarketId }
                .values
                .flatMap { it.take(MAX_TRIPS_PER_STORE) }
                .sortedByDescending { it.startedAt }
        }

        internal fun encode(trips: List<StoreTrip>): String = JsonArray(
            trips.map { trip ->
                JsonObject(
                    mapOf(
                        "s" to JsonPrimitive(trip.supermarketId),
                        "t" to JsonPrimitive(trip.startedAt),
                        "c" to JsonArray(trip.categoryIds.map { JsonPrimitive(it) }),
                    ),
                )
            },
        ).toString()

        /** The trips in [text], newest first; a broken row reads as no trips (never a crash). */
        internal fun decode(text: String?): List<StoreTrip> {
            if (text.isNullOrBlank()) return emptyList()
            return runCatching {
                Json.parseToJsonElement(text).jsonArray.map { element ->
                    val json = element.jsonObject
                    StoreTrip(
                        supermarketId = json.getValue("s").jsonPrimitive.long,
                        startedAt = json.getValue("t").jsonPrimitive.long,
                        categoryIds = json.getValue("c").jsonArray.map { it.jsonPrimitive.long },
                    )
                }.sortedByDescending { it.startedAt }
            }.getOrDefault(emptyList())
        }
    }
}
