package casa.moller.tandem.core.sync.testing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import casa.moller.tandem.core.data.demo.DemoContent
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.database.DatabaseKind
import casa.moller.tandem.core.database.TandemDatabase
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.datastore.StorageHealthMonitor
import casa.moller.tandem.core.model.account.ServerAccount
import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.network.api.TandoorApi
import casa.moller.tandem.core.network.api.TandoorApiFactory
import casa.moller.tandem.core.network.capabilities.CapabilityResolver
import casa.moller.tandem.core.sync.account.AccountMirror
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.data.DefaultServerStatusRepository
import casa.moller.tandem.core.sync.data.DefaultSyncRepository
import casa.moller.tandem.core.sync.data.OfflineFirstHomeRepository
import casa.moller.tandem.core.sync.data.OfflineFirstRecipeRepository
import casa.moller.tandem.core.sync.demo.DemoSeeder
import casa.moller.tandem.core.sync.engine.DetailFetcher
import casa.moller.tandem.core.sync.engine.OutboxSender
import casa.moller.tandem.core.sync.engine.SyncEngine
import casa.moller.tandem.core.sync.guard.SyncGuard
import casa.moller.tandem.core.sync.remote.RemoteCaller
import java.io.File
import java.util.Locale
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * Everything of core:sync wired by hand for one test, the way Hilt wires it in the app, on
 * in-memory databases, an HTTPS fake Tandoor with the recorded fixtures, and fake clock, network,
 * scheduler and image loader.
 */
internal class SyncTestGraph(val server: FakeTandoorServer = FakeTandoorServer().also { it.start() }) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /**
     * File databases (fresh per graph), like the app. Not in-memory: when a flow that opens a
     * database for the first time is cancelled mid-open (the eager status flow when a test switches
     * to demo mode), Room closes that connection, and reopening ":memory:" gives an EMPTY database
     * that Room no longer creates tables in ("no such table"). A file keeps its tables.
     */
    val databases = TandemDatabaseFactory { kind ->
        context.deleteDatabase(kind.fileName)
        TandemDatabase.build(context, kind.fileName)
    }
    val clock = FakeClock()
    val network = FakeNetwork()
    val scheduler = FakeScheduler()
    val images = FakeImagePrefetcher()
    val notifier = FakeNotifier()

    val account = ServerAccount(
        id = ACCOUNT_ID,
        baseUrl = server.baseUrl.trimEnd('/'),
        username = "cook",
        userId = 1,
        spaceId = 1,
        serverVersion = "2.6.13",
        addedAt = Instant.fromEpochMilliseconds(0),
    )
    val api: TandoorApi = TandoorApiFactory(TestTls.httpClient()).create(server.baseUrl, { sources.token })
    val remote: ActiveSource.Remote = ActiveSource.Remote(account, api)
    val sources: FakeSources = FakeSources(remote)
    val sessions = FakeSessionRepository(Session.LoggedIn(account, account.serverVersion, ServerCompatibility.SUPPORTED))
    val accountStore = FakeAccountStore(listOf(account))
    val health = StorageHealthMonitor()

    var demoLocale: Locale = Locale.forLanguageTag("sv-SE")
    val demoContent = DemoContent { path -> File(demoAssetsDir(), path).readText() }
    val demo = DemoSeeder(databases, demoContent, clock, hasDemoFile = { true }, locale = { demoLocale })

    val guard = SyncGuard(clock)
    val caller = RemoteCaller(guard, sources)
    val details = DetailFetcher(databases, caller, clock)
    val outbox = OutboxSender(databases, caller, network)
    val mirror = AccountMirror(sessions, accountStore, health, databases, demo, notifier, clock, scope)
    val serverStatus = DefaultServerStatusRepository(sources, sessions, mirror, CapabilityResolver(), guard, clock)
    val engine = SyncEngine(sources, databases, caller, details, images, outbox, serverStatus, scheduler, guard, network, clock, scope)
    val recipes = OfflineFirstRecipeRepository(sources, databases, caller, details, images, scheduler, network, clock, scope)
    val home = OfflineFirstHomeRepository(sources, databases, engine, clock)
    val sync = DefaultSyncRepository(sources, databases, engine, scheduler, demo, guard, network, clock, scope)

    val real: TandemDatabase
        get() = databases.get(DatabaseKind.REAL)

    val demoDatabase: TandemDatabase
        get() = databases.get(DatabaseKind.DEMO)

    fun close() {
        scope.cancel()
        server.close()
        real.close()
        demoDatabase.close()
    }

    companion object {
        const val ACCOUNT_ID = "account-a"

        fun demoAssetsDir(): File {
            val path = System.getProperty("tandem.demoAssetsDir")
            require(!path.isNullOrBlank()) { "tandem.demoAssetsDir is not set (core/sync/build.gradle.kts)" }
            return File(path)
        }
    }
}

/** Polls [condition] in real time (Room and the server run on their own threads). */
internal suspend fun waitUntil(timeoutMillis: Long = 15_000, condition: suspend () -> Boolean) {
    withTimeout(timeoutMillis) {
        while (!condition()) delay(20)
    }
}
