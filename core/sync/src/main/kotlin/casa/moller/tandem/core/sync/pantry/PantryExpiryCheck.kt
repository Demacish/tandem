package casa.moller.tandem.core.sync.pantry

import casa.moller.tandem.core.data.pantry.MakeNowRepository
import casa.moller.tandem.core.data.pantry.PantryRepository
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.SyncStateEntity
import casa.moller.tandem.core.datastore.SettingsStore
import casa.moller.tandem.core.domain.pantry.ExpiryReminder
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.makenow.MakeNowFilter
import casa.moller.tandem.core.model.makenow.MakeNowGroup
import casa.moller.tandem.core.model.makenow.MakeNowRecipe
import casa.moller.tandem.core.model.makenow.MakeNowState
import casa.moller.tandem.core.model.pantry.PantryGroup
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.permissions.isGuest
import casa.moller.tandem.core.sync.platform.AppVisibility
import casa.moller.tandem.core.sync.platform.NetworkStatus
import casa.moller.tandem.core.sync.platform.PantryExpiryNotifier
import casa.moller.tandem.core.sync.platform.WallClock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDate
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** What one run of [PantryExpiryCheck] did (the tests and the worker's log read it). */
internal enum class PantryExpiryOutcome {
    /** The setting is off (the default). */
    OFF,

    /** No account (logged out). */
    NO_ACCOUNT,

    /** Demo mode never posts (its pantry is made up). */
    DEMO,

    /** A guest has no pantry (Tandoor answers 403 on its lists). */
    GUEST,

    /** Between 21 and 7 o'clock (or the chosen morning time): the coming morning's run takes it (every run sets the next one). */
    QUIET,

    /** Tandem is on screen (Idag's "Går ut snart" and Hemma show it): the next run comes a little later the same day. */
    VISIBLE,

    /** No notification permission, notifications or the channel switched off: the next morning's run asks again. */
    NOT_ALLOWED,

    /** Nothing expires today or tomorrow. */
    NOTHING_DUE,

    /** Everything due was in a notification earlier today. */
    ALREADY_NOTIFIED,

    POSTED,

    /** The system refused at the last moment (permission revoked). */
    NOT_POSTED,
}

/**
 * Fas 4 (skafferinotis lane), plan §8.8 "Använd upp" and §8.7 "Måltidspåminnelser": the once-a-day
 * check behind "Grädde går ut i morgon – 3 recept" (`PantryExpiryWorker`, around 08:00).
 *
 * - Only with Settings → "Notis när något går ut" on (off by default), for a logged-in account of a
 *   household; never in demo mode, never for a guest (no pantry), never at night ([ExpiryReminder.isQuiet])
 *   and never while Tandem is on screen (Idag's "Går ut snart" says the same; the item stays new, and
 *   the worker runs again later the same day, `ExpiryReminder.retryRun`).
 * - Works offline from the local pantry; online it reads the pantry first when the last complete
 *   read is older than the sync's own interval ([PantrySyncStep.PANTRY_INTERVAL_MILLIS]) and lets
 *   "Kan laga nu" refresh by its own once-a-minute rule, so the recipe count is current.
 * - ONE grouped notification for every entry that expires today or tomorrow; an item is never in
 *   two notifications the same day (`sync_state` [NOTIFIED_KEY]: the day and the item keys).
 */
@Singleton
internal class PantryExpiryCheck @Inject constructor(
    private val settings: SettingsStore,
    private val sources: ActiveSources,
    private val databases: TandemDatabaseFactory,
    private val pantry: PantryRepository,
    private val makeNow: MakeNowRepository,
    private val reader: PantryReader,
    private val network: NetworkStatus,
    private val visibility: AppVisibility,
    private val notifier: PantryExpiryNotifier,
    private val clock: WallClock,
) {
    /** One run at a time (WorkManager never overlaps unique work, a test or a second process might). */
    private val running = Mutex()

    suspend fun run(): PantryExpiryOutcome = running.withLock {
        val current = settings.settings.first()
        if (!current.pantryExpiryNotifications) return@withLock PantryExpiryOutcome.OFF
        val remote = when (val source = sources.current()) {
            is ActiveSource.Remote -> source
            ActiveSource.Demo -> return@withLock PantryExpiryOutcome.DEMO
            ActiveSource.Loading, ActiveSource.None -> return@withLock PantryExpiryOutcome.NO_ACCOUNT
        }
        val database = databases.get(remote.kind)
        if (isGuest(database, remote.accountId)) return@withLock PantryExpiryOutcome.GUEST
        val now = clock.now()
        val zone = clock.zone()
        when {
            // Fas 5 (planera lane): the quiet hours end at the chosen morning time when it is earlier than 07:00.
            ExpiryReminder.isQuiet(now, zone, current.pantryExpiryTime) -> return@withLock PantryExpiryOutcome.QUIET
            visibility.isVisible() -> return@withLock PantryExpiryOutcome.VISIBLE
            !notifier.canPost() -> return@withLock PantryExpiryOutcome.NOT_ALLOWED
        }
        if (network.online.value) refresh(remote, now.toEpochMilliseconds())
        val today = ExpiryReminder.today(now, zone)
        val groups: List<PantryGroup> = pantry.observePantry().first()
        val due = ExpiryReminder.due(groups.flatMap { it.entries }, today)
        if (due.isEmpty()) return@withLock PantryExpiryOutcome.NOTHING_DUE
        val notified = notifiedToday(remote, today)
        val foodIds = due.mapNotNull { it.foodId }.toSet()
        val count = if (foodIds.isEmpty()) 0 else ExpiryReminder.recipeCount(makeNowRecipes(today), foodIds)
        val summary = ExpiryReminder.summary(due, notified, today, count) ?: return@withLock PantryExpiryOutcome.ALREADY_NOTIFIED
        val names = groups.associate { it.location.id to it.location.name }
        if (!notifier.post(summary, today, names)) return@withLock PantryExpiryOutcome.NOT_POSTED
        remember(remote, today, notified + due.map { it.key })
        PantryExpiryOutcome.POSTED
    }

    /** Online: the pantry when it was not read completely lately, then "Kan laga nu" (its own throttle). Failures keep the stored data. */
    private suspend fun refresh(remote: ActiveSource.Remote, nowMillis: Long) {
        val states = databases.get(remote.kind).syncStateDao()
        val key = PantrySyncStep.PANTRY_KEY
        val read = states.get(remote.accountId, key)?.lastFullRelistEpochMillis
        if (read == null || nowMillis - read !in 0 until PantrySyncStep.PANTRY_INTERVAL_MILLIS) {
            if (reader.sync(remote, background = true) is Outcome.Success) {
                states.upsert(SyncStateEntity(accountId = remote.accountId, entity = key, lastFullRelistEpochMillis = nowMillis))
            }
        }
        makeNow.refresh(force = false)
    }

    /** Every recipe of "Kan laga nu" as stored (the first answer of the flow; empty after [READ_TIMEOUT_MILLIS]). */
    private suspend fun makeNowRecipes(today: LocalDate): List<MakeNowRecipe> {
        val state: MakeNowState = withTimeoutOrNull(READ_TIMEOUT_MILLIS) {
            makeNow.observe(MakeNowFilter(), today).first()
        } ?: return emptyList()
        return MakeNowGroup.entries.flatMap { state.recipes(it) }
    }

    private suspend fun notifiedToday(remote: ActiveSource.Remote, today: LocalDate): Set<String> {
        val row = databases.get(remote.kind).syncStateDao().get(remote.accountId, NOTIFIED_KEY) ?: return emptySet()
        if (row.windowFrom != today.toString()) return emptySet()
        val keys = row.hash ?: return emptySet()
        return runCatching { json.decodeFromString(KEYS, keys).toSet() }.getOrDefault(emptySet())
    }

    private suspend fun remember(remote: ActiveSource.Remote, today: LocalDate, keys: Set<String>) {
        databases.get(remote.kind).syncStateDao().upsert(
            SyncStateEntity(
                accountId = remote.accountId,
                entity = NOTIFIED_KEY,
                windowFrom = today.toString(),
                hash = json.encodeToString(KEYS, keys.sorted()),
            ),
        )
    }

    companion object {
        /** `sync_state`: the day (`window_from`) and the item keys (`hash`, JSON) of the last notification. */
        const val NOTIFIED_KEY: String = "pantry_expiry_notified"

        private const val READ_TIMEOUT_MILLIS = 10_000L
        private val KEYS = ListSerializer(String.serializer())
        private val json = Json
    }
}
