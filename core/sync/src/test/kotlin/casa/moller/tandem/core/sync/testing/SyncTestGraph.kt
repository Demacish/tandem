package casa.moller.tandem.core.sync.testing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import casa.moller.tandem.core.data.demo.DemoContent
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.database.DatabaseKind
import casa.moller.tandem.core.database.TandemDatabase
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.datastore.StorageHealthMonitor
import casa.moller.tandem.core.model.account.ServerAccount
import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.network.api.TandoorApi
import casa.moller.tandem.core.network.api.TandoorApiFactory
import casa.moller.tandem.core.network.capabilities.CapabilityResolver
import casa.moller.tandem.core.sync.account.AccountMirror
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.book.BookNames
import casa.moller.tandem.core.sync.book.BookSync
import casa.moller.tandem.core.sync.book.BookSyncStep
import casa.moller.tandem.core.sync.book.OfflineFirstRecipeBookRepository
import casa.moller.tandem.core.sync.book.RecipeBookEntryOutboxHandler
import casa.moller.tandem.core.sync.book.RecipeBookOutboxHandler
import casa.moller.tandem.core.sync.book.RecipeKeywordsOutboxHandler
import casa.moller.tandem.core.sync.book.SmartBookResults
import casa.moller.tandem.core.sync.data.DefaultServerStatusRepository
import casa.moller.tandem.core.sync.data.DefaultSyncRepository
import casa.moller.tandem.core.sync.data.OfflineFirstCookLogRepository
import casa.moller.tandem.core.sync.data.OfflineFirstHomeRepository
import casa.moller.tandem.core.sync.data.OfflineFirstRecipeRepository
import casa.moller.tandem.core.sync.demo.DemoSeeder
import casa.moller.tandem.core.sync.engine.DetailFetcher
import casa.moller.tandem.core.sync.engine.OutboxSender
import casa.moller.tandem.core.sync.engine.SyncEngine
import casa.moller.tandem.core.sync.guard.SyncGuard
import casa.moller.tandem.core.sync.image.PrefetchedImages
import casa.moller.tandem.core.sync.permissions.DefaultSpacePermissionsRepository
import casa.moller.tandem.core.sync.permissions.SpaceRoleSync
import casa.moller.tandem.core.sync.remote.RemoteCaller
import casa.moller.tandem.core.sync.shopping.AisleOrderOutboxHandler
import casa.moller.tandem.core.sync.shopping.DefaultIngredientParser
import casa.moller.tandem.core.sync.shopping.DefaultSupermarketRepository
import casa.moller.tandem.core.sync.shopping.DroppedChanges
import casa.moller.tandem.core.sync.shopping.FoodOutboxHandler
import casa.moller.tandem.core.sync.shopping.OfflineFirstShoppingRepository
import casa.moller.tandem.core.sync.shopping.ShoppingChangeBus
import casa.moller.tandem.core.sync.shopping.ShoppingEntryOutboxHandler
import casa.moller.tandem.core.sync.shopping.ShoppingListRecipeOutboxHandler
import casa.moller.tandem.core.sync.shopping.ShoppingReader
import casa.moller.tandem.core.sync.shopping.ShoppingSync
import casa.moller.tandem.core.sync.shopping.ShoppingSyncStep
import casa.moller.tandem.core.sync.shopping.ShoppingTables
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
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
 * file databases, an HTTPS fake Tandoor with the recorded fixtures, and fake clock, network,
 * scheduler and image loader.
 */
internal class SyncTestGraph(
    val server: FakeTandoorServer = FakeTandoorServer().also { it.start() },
    // Phase 2 (inköp-data lane): a second "phone" needs its own database files and account id.
    private val databasePrefix: String = "",
    accountId: String = ACCOUNT_ID,
    serverVersion: String = "2.6.13",
    userId: Long = 1,
) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * File databases (fresh per graph), like the app. Not in-memory: when a flow that opens a
     * database for the first time is cancelled mid-open (the eager status flow when a test switches
     * to demo mode), Room closes that connection, and reopening ":memory:" gives an EMPTY database
     * that Room no longer creates tables in ("no such table"). A file keeps its tables.
     */
    val databases = TandemDatabaseFactory { kind ->
        context.deleteDatabase(databasePrefix + kind.fileName)
        TandemDatabase.build(context, databasePrefix + kind.fileName)
    }
    val clock = FakeClock()
    val network = FakeNetwork()
    val scheduler = FakeScheduler()
    val images = FakeImagePrefetcher()
    val prefetched = PrefetchedImages()
    val imageCache = FakeImageCache(prefetched)
    val notifier = FakeNotifier()
    val settings = FakeSettingsStore()
    val diagnosticsStore = FakeDiagnosticsStore()

    val account = ServerAccount(
        id = accountId,
        baseUrl = server.baseUrl.trimEnd('/'),
        username = "cook",
        userId = userId,
        spaceId = 1,
        serverVersion = serverVersion,
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
    val details = DetailFetcher(sources, databases, caller, clock)

    // Phase 2 (böcker lane): the books' reader and the role.
    val bookNames = TestBookNames()
    val smartBooks = SmartBookResults()
    val bookSync = BookSync(databases, caller, sources, smartBooks)
    val roleSync = SpaceRoleSync(databases, caller, sources)

    // Phase 2 (inköp-data lane): the shopping list's reader, small tables and outbox senders.
    val shoppingChanges = ShoppingChangeBus()
    val droppedChanges = DroppedChanges()
    val shoppingReader = ShoppingReader(databases, caller, sources, clock, shoppingChanges, droppedChanges)
    val shoppingTables = ShoppingTables(databases, caller, sources)
    val outbox = OutboxSender(
        databases,
        caller,
        network,
        clock,
        handlers = mapOf(
            OutboxEntities.SHOPPING_ENTRY to ShoppingEntryOutboxHandler(databases, caller, shoppingReader, droppedChanges, clock),
            OutboxEntities.SHOPPING_LIST_RECIPE to
                ShoppingListRecipeOutboxHandler(databases, caller, shoppingReader, droppedChanges, clock),
            OutboxEntities.FOOD to FoodOutboxHandler(databases, caller, droppedChanges, clock),
            OutboxEntities.SUPERMARKET_CATEGORY_RELATION to AisleOrderOutboxHandler(caller, droppedChanges, clock),
            // Phase 2 (böcker lane).
            OutboxEntities.RECIPE_BOOK to RecipeBookOutboxHandler(databases, caller),
            OutboxEntities.RECIPE_BOOK_ENTRY to RecipeBookEntryOutboxHandler(databases, caller),
            OutboxEntities.RECIPE to RecipeKeywordsOutboxHandler(databases, caller, details, bookNames),
        ),
    )
    val mirror = AccountMirror(sessions, accountStore, settings, health, databases, demo, imageCache, notifier, clock, scope)
    val serverStatus = DefaultServerStatusRepository(sources, sessions, mirror, settings, CapabilityResolver(), guard, clock)
    val engine = SyncEngine(
        sources,
        databases,
        caller,
        details,
        images,
        prefetched,
        outbox,
        serverStatus,
        scheduler,
        guard,
        network,
        clock,
        scope,
        settings,
        diagnosticsStore,
        ShoppingSyncStep(shoppingReader, shoppingTables, databases),
        BookSyncStep(roleSync, bookSync),
    )
    val recipes = OfflineFirstRecipeRepository(sources, databases, caller, details, images, scheduler, network, clock, scope)
    val home = OfflineFirstHomeRepository(sources, databases, engine, clock)
    val cookLogs = OfflineFirstCookLogRepository(sources, databases, scheduler, clock)
    val sync = DefaultSyncRepository(sources, databases, engine, scheduler, demo, guard, network, clock, scope)

    // Phase 2 (inköp-data lane): the shopping repositories, the parser and foreground polling.
    val permissions = FakeSpacePermissionsRepository()
    val shoppingSync = ShoppingSync(shoppingReader, outbox, scheduler, network)
    val shopping = OfflineFirstShoppingRepository(
        sources,
        databases,
        permissions,
        scheduler,
        clock,
        shoppingSync,
        shoppingChanges,
        details,
        network,
    )
    val supermarkets = DefaultSupermarketRepository(sources, databases, permissions, scheduler, clock)
    val parser = DefaultIngredientParser(sources, databases, caller, network, permissions)

    // Phase 2 (böcker lane): books, favourites, "Vill laga" and the real role detection.
    val books = OfflineFirstRecipeBookRepository(
        sources,
        databases,
        permissions,
        scheduler,
        clock,
        bookSync,
        smartBooks,
        bookNames,
        network,
    )
    val spacePermissions = DefaultSpacePermissionsRepository(sources, databases)

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

/** The names of the books Tandem creates, in Swedish (the app's resources are not needed). */
internal class TestBookNames : BookNames {
    override val favoritesWord: String = "Favoriter"
    override val wantToCook: String = "Vill laga"
}

/** Polls [condition] in real time (Room and the server run on their own threads). */
internal suspend fun waitUntil(timeoutMillis: Long = 15_000, condition: suspend () -> Boolean) {
    withTimeout(timeoutMillis) {
        while (!condition()) delay(20)
    }
}
