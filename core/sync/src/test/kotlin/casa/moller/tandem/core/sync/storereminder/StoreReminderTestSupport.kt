package casa.moller.tandem.core.sync.storereminder

import casa.moller.tandem.core.datastore.StoreFenceRegistration
import casa.moller.tandem.core.datastore.StoreReminderRecord
import casa.moller.tandem.core.datastore.StoreReminderStore
import casa.moller.tandem.core.datastore.StoreSpotRecord
import casa.moller.tandem.core.domain.storereminder.StoreFence
import casa.moller.tandem.core.model.account.ServerAccount
import casa.moller.tandem.core.model.shopping.GeoPoint
import casa.moller.tandem.core.model.shopping.ShoppingEntry
import casa.moller.tandem.core.model.shopping.ShoppingFood
import casa.moller.tandem.core.model.shopping.StoreReminderPermissions
import casa.moller.tandem.core.network.api.TandoorApiFactory
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.platform.StoreReminderNotifier
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient

// Fas 5 (butik lane): fakes for the reminder near the store. The places are made up, out at sea in
// the Atlantic (no real store, no real home).

internal object StoreReminderSamples {
    const val ACCOUNT = "account-1"
    const val OTHER_ACCOUNT = "account-2"
    const val STORE = 1L
    const val OTHER_STORE = 2L

    val point = GeoPoint(12.3456, -45.6789)

    val spot = StoreSpotRecord(storeId = STORE, latitude = point.latitude, longitude = point.longitude, radiusMeters = 200)

    fun account(id: String = ACCOUNT) = ServerAccount(
        id = id,
        baseUrl = "https://tandoor.example.com",
        username = "cook",
        userId = 1,
        spaceId = 1,
        serverVersion = "2.6.13",
        addedAt = Instant.fromEpochMilliseconds(0),
    )

    /** A logged-in source that never makes a request (no server in these tests). */
    fun remote(id: String = ACCOUNT): ActiveSource.Remote =
        ActiveSource.Remote(account(id), TandoorApiFactory(OkHttpClient()).create("https://tandoor.example.com/", { null }))

    fun entry(id: Long, food: String, checked: Boolean = false) = ShoppingEntry(id = id, food = ShoppingFood(id, food), checked = checked)
}

/** [StoreReminderStore] in memory. */
internal class FakeStoreReminderStore : StoreReminderStore {
    val records = MutableStateFlow<Map<String, StoreReminderRecord>>(emptyMap())
    override val registration = MutableStateFlow<StoreFenceRegistration?>(null)

    override fun observe(accountId: String): Flow<StoreReminderRecord> = records.map { it[accountId] ?: StoreReminderRecord() }

    override suspend fun update(accountId: String, transform: (StoreReminderRecord) -> StoreReminderRecord) {
        records.update { all ->
            val next = transform(all[accountId] ?: StoreReminderRecord())
            if (next == StoreReminderRecord()) all - accountId else all + (accountId to next)
        }
    }

    override suspend fun setRegistration(registration: StoreFenceRegistration?) {
        this.registration.value = registration
    }

    override suspend fun retainOnly(accountIds: Set<String>) {
        records.update { all -> all.filterKeys { it in accountIds } }
    }

    override suspend fun accounts(): Set<String> = records.value.keys

    fun record(accountId: String = StoreReminderSamples.ACCOUNT): StoreReminderRecord = records.value[accountId] ?: StoreReminderRecord()
}

/** Play services' geofences, recorded. */
internal class FakeGeofenceGateway : GeofenceGateway {
    @Volatile
    var result: GeofenceResult = GeofenceResult.OK

    @Volatile
    var registered: List<StoreFence> = emptyList()
    val calls: MutableList<String> = CopyOnWriteArrayList()

    override suspend fun replaceAll(fences: List<StoreFence>): GeofenceResult {
        calls += "replace ${fences.map { it.storeId }}"
        if (result == GeofenceResult.OK) registered = fences
        return result
    }

    override suspend fun removeAll(): GeofenceResult {
        calls += "remove"
        registered = emptyList()
        return GeofenceResult.OK
    }
}

internal class FakePermissionReader(var permissions: StoreReminderPermissions = ALL) : StoreReminderPermissionReader {
    override fun read(): StoreReminderPermissions = permissions

    companion object {
        val ALL = StoreReminderPermissions(precise = true, background = true, notifications = true, locationOn = true)
    }
}

internal class FakePlayServices(var available: Boolean = true) : PlayServicesCheck {
    override fun isAvailable(): Boolean = available
}

internal class FakeDeviceKey(var boot: Int = 1, var version: Long = 9) : DeviceRegistrationKey {
    override fun bootCount(): Int = boot

    override fun versionCode(): Long = version
}

internal class FakeSystemEventSwitch : SystemEventSwitch {
    @Volatile
    var enabled: Boolean? = null

    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }
}

internal class FakeLocation(var position: Pair<GeoPoint, Int?>? = StoreReminderSamples.point to 12) : CurrentLocationGateway {
    var calls = 0

    override suspend fun current(): Pair<GeoPoint, Int?>? {
        calls++
        return position
    }
}

/** The posted reminders: (store, name, count). */
internal class RecordingStoreNotifier : StoreReminderNotifier {
    @Volatile
    var allowed = true
    val posted: MutableList<Triple<Long, String?, Int>> = CopyOnWriteArrayList()

    override fun canPost(): Boolean = allowed

    override fun post(storeId: Long, storeName: String?, count: Int): Boolean = allowed && posted.add(Triple(storeId, storeName, count))
}
