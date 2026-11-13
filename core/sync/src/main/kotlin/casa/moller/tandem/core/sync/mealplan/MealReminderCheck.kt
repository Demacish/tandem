package casa.moller.tandem.core.sync.mealplan

import casa.moller.tandem.core.data.mealplan.MealPlanRepository
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.SyncStateEntity
import casa.moller.tandem.core.datastore.SettingsStore
import casa.moller.tandem.core.domain.mealplan.MealReminder
import casa.moller.tandem.core.domain.mealplan.MealReminders
import casa.moller.tandem.core.domain.reminder.WallClockSchedule
import casa.moller.tandem.core.model.mealplan.MealPlanWeek
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.permissions.isGuest
import casa.moller.tandem.core.sync.platform.MealReminderNotifier
import casa.moller.tandem.core.sync.platform.NetworkStatus
import casa.moller.tandem.core.sync.platform.WallClock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** What one run of [MealReminderCheck] did (the tests and the worker's log read it). */
internal enum class MealReminderOutcome {
    /** The setting is off (the default). */
    OFF,

    /** No account (logged out). */
    NO_ACCOUNT,

    /** Demo mode never posts (its plan is made up). */
    DEMO,

    /** A guest gets no reminders (Tandem never writes for a guest, and the reminder is a household's). */
    GUEST,

    /** Before the chosen time of the evening (a change seen by the in-app watcher before it). */
    EARLY,

    /** The evening is over (a run deferred past midnight): tomorrow's meal is today's now. */
    TOO_LATE,

    /** No notification permission, notifications or the channel switched off. */
    NOT_ALLOWED,

    /** Every reminder of tomorrow is posted already (or there is none); stale ones were removed. */
    NOTHING_NEW,

    POSTED,
}

/**
 * Fas 5 (planera lane), plan §8.3 "Påminnelse kvällen innan" ("Tina ut kycklingen") and §8.7
 * "Måltidspåminnelser": the evening check behind the reminder of tomorrow's meals.
 *
 * - Only with Settings → "Påminnelse kvällen innan" on (off by default), for a logged-in account;
 *   never in demo mode, never for a guest.
 * - Works offline from the local meal plan ([MealPlanRepository.observeWeek]); online it reads the
 *   plan first ([MealPlanRepository.refresh], which follows the sync's rules), so a meal planned
 *   on the web in the afternoon is in it.
 * - ONE notification per meal with a reminder (`MealReminderNote`), once per evening: the keys
 *   posted for a day are kept in `sync_state` [NOTIFIED_KEY] (`window_from` = the meals' day,
 *   `hash` = JSON keys). A reminder that is no longer due (the meal moved, was removed or lost its
 *   reminder, also through the sync) has its notification removed and is forgotten; a changed text
 *   is a new reminder.
 */
@Singleton
internal class MealReminderCheck @Inject constructor(
    private val settings: SettingsStore,
    private val sources: ActiveSources,
    private val databases: TandemDatabaseFactory,
    private val mealPlans: MealPlanRepository,
    private val network: NetworkStatus,
    private val notifier: MealReminderNotifier,
    private val clock: WallClock,
) {
    /** One run at a time (the worker and the in-app watcher may meet). */
    private val running = Mutex()

    /**
     * The reminders of the evening [evening] (a local date) for the meals of the day after. [onTime]
     * false (the in-app watcher) also checks that the chosen time has come; a run started by hand
     * passes today and [onTime] true. [refresh] reads the plan from the server first when online.
     */
    suspend fun run(evening: LocalDate, onTime: Boolean = true, refresh: Boolean = true): MealReminderOutcome = running.withLock {
        val current = settings.settings.first()
        if (!current.mealReminders) return@withLock MealReminderOutcome.OFF
        val remote = when (val source = sources.current()) {
            is ActiveSource.Remote -> source
            ActiveSource.Demo -> return@withLock MealReminderOutcome.DEMO
            ActiveSource.Loading, ActiveSource.None -> return@withLock MealReminderOutcome.NO_ACCOUNT
        }
        val database = databases.get(remote.kind)
        if (isGuest(database, remote.accountId)) return@withLock MealReminderOutcome.GUEST
        val now = clock.now()
        val zone = clock.zone()
        if (WallClockSchedule.today(now, zone) != evening) return@withLock MealReminderOutcome.TOO_LATE
        val start = WallClockSchedule.occurrence(evening, current.mealReminderTime, zone)
        if (!onTime && now < start - WallClockSchedule.EARLY_TOLERANCE) return@withLock MealReminderOutcome.EARLY
        if (!notifier.canPost()) return@withLock MealReminderOutcome.NOT_ALLOWED
        if (refresh && network.online.value) withTimeoutOrNull(REFRESH_TIMEOUT_MILLIS) { mealPlans.refresh() }
        val day = evening.plus(1, DateTimeUnit.DAY)
        val week = withTimeoutOrNull(READ_TIMEOUT_MILLIS) { mealPlans.observeWeek(MealPlanWeek.startOf(day)).first() }
        val due = MealReminders.due(week?.days?.flatMap { it.meals }.orEmpty(), day)
        val notified = notified(remote, day)
        val dueKeys = due.map { it.key }.toSet()
        notified.filterNot { it in dueKeys }.forEach { notifier.cancel(it) }
        val posted = due.filterNot { it.key in notified }.filter { post(it) }.map { it.key }
        remember(remote, day, notified.filter { it in dueKeys }.toSet() + posted)
        if (posted.isEmpty()) MealReminderOutcome.NOTHING_NEW else MealReminderOutcome.POSTED
    }

    private fun post(reminder: MealReminder): Boolean = notifier.post(reminder)

    /** Removes every posted reminder of the active account (the setting was switched off). */
    suspend fun withdrawAll() = running.withLock {
        val remote = sources.current() as? ActiveSource.Remote ?: return@withLock
        val row = databases.get(remote.kind).syncStateDao().get(remote.accountId, NOTIFIED_KEY) ?: return@withLock
        val day = row.windowFrom?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@withLock
        notified(remote, day).forEach { notifier.cancel(it) }
        remember(remote, day, emptySet())
    }

    private suspend fun notified(remote: ActiveSource.Remote, day: LocalDate): Set<String> {
        val row = databases.get(remote.kind).syncStateDao().get(remote.accountId, NOTIFIED_KEY) ?: return emptySet()
        if (row.windowFrom != day.toString()) return emptySet()
        val keys = row.hash ?: return emptySet()
        return runCatching { json.decodeFromString(KEYS, keys).toSet() }.getOrDefault(emptySet())
    }

    private suspend fun remember(remote: ActiveSource.Remote, day: LocalDate, keys: Set<String>) {
        databases.get(remote.kind).syncStateDao().upsert(
            SyncStateEntity(
                accountId = remote.accountId,
                entity = NOTIFIED_KEY,
                windowFrom = day.toString(),
                hash = json.encodeToString(KEYS, keys.sorted()),
            ),
        )
    }

    companion object {
        /** `sync_state`: the meals' day (`window_from`) and the reminder keys posted for it (`hash`, JSON). */
        const val NOTIFIED_KEY: String = "meal_reminders_notified"

        private const val READ_TIMEOUT_MILLIS = 10_000L
        private const val REFRESH_TIMEOUT_MILLIS = 30_000L
        private val KEYS = ListSerializer(String.serializer())
        private val json = Json
    }
}
