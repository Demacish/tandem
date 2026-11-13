package casa.moller.tandem.wear

import android.content.Context
import android.os.Build
import androidx.wear.tiles.TileService
import casa.moller.tandem.core.network.api.TandoorApiFactory
import casa.moller.tandem.core.network.http.TandemHttpClients
import casa.moller.tandem.wear.data.CredentialsStore
import casa.moller.tandem.wear.data.KeystoreCredentialsStore
import casa.moller.tandem.wear.data.WearStateStore
import casa.moller.tandem.wear.link.PhoneLink
import casa.moller.tandem.wear.link.PlayPhoneLink
import casa.moller.tandem.wear.link.WatchPairing
import casa.moller.tandem.wear.sync.ShoppingRemote
import casa.moller.tandem.wear.sync.SyncScheduler
import casa.moller.tandem.wear.sync.TandoorShoppingRemote
import casa.moller.tandem.wear.sync.WearSync
import casa.moller.tandem.wear.sync.WorkManagerSyncScheduler
import casa.moller.tandem.wear.tile.ShoppingTileService
import casa.moller.tandem.wear.timer.AndroidTimerNotifier
import casa.moller.tandem.wear.timer.WearTimers
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The watch app's objects, made by hand (no Hilt on the watch, docs/BYGGE.md "Klockan"): one per
 * process, owned by [TandemWearApplication]. Tests build the parts they need with fakes.
 */
class WearGraph(context: Context) {
    private val app: Context = context.applicationContext

    /** Work that outlives a screen or a service call (a notification action, the listener). */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val clock: () -> Long = System::currentTimeMillis

    val store: WearStateStore = WearStateStore(File(app.noBackupFilesDir, WearStateStore.FILE_NAME), Dispatchers.IO)

    val credentials: CredentialsStore =
        KeystoreCredentialsStore(File(app.noBackupFilesDir, KeystoreCredentialsStore.FILE_NAME), Dispatchers.IO)

    /** The shared OkHttp client: HTTPS only, the User-Agent, about 5 requests per second, 2 at a time. Never logs. */
    private val remotes: ShoppingRemote.Factory by lazy {
        val userAgent = "Tandem/${BuildConfig.VERSION_NAME} (Wear OS ${Build.VERSION.SDK_INT})"
        val client = TandemHttpClients.create(userAgent = userAgent, debugLogging = false)
        TandoorShoppingRemote.factory(TandoorApiFactory(client))
    }

    val scheduler: SyncScheduler = WorkManagerSyncScheduler(app)

    val sync: WearSync = WearSync(credentials, store, { remotes.create(it) }, clock)

    val phone: PhoneLink = PlayPhoneLink(app)

    val notifier: AndroidTimerNotifier = AndroidTimerNotifier(app, clock)

    val timers: WearTimers = WearTimers(store, phone, notifier, onChanged = ::refreshTile)

    val pairing: WatchPairing = WatchPairing(
        credentials = credentials,
        store = store,
        remotes = { remotes.create(it) },
        clock = clock,
        onPaired = { scheduler.syncNow(force = true) },
        onChanged = ::refreshTile,
    )

    /** Asks the system to draw the tile again (the list or the timers changed). */
    fun refreshTile() {
        TileService.getUpdater(app).requestUpdate(ShoppingTileService::class.java)
    }
}
