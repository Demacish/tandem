package casa.moller.tandem.core.sync.pantry

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.domain.pantry.ExpiryDay
import casa.moller.tandem.core.domain.pantry.ExpiryReminderSummary
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.platform.AppVisibility
import casa.moller.tandem.core.sync.platform.PantryExpiryNotifier
import casa.moller.tandem.core.sync.platform.WallClock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode

/**
 * Fas 4 (skafferinotis lane): the morning check behind "Grädde går ut i morgon – 3 recept" against
 * the stateful pantry fake (both recorded versions): due items, "never twice the same day", a new
 * day, time zones and local midnight, the setting, the permission, the app on screen, offline,
 * guests and demo mode.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PantryExpiryCheckTest {
    private class Posted(val summary: ExpiryReminderSummary, val today: LocalDate, val places: Map<Long, String>)

    private class TestNotifier : PantryExpiryNotifier {
        var allowed = true
        val posted = mutableListOf<Posted>()

        override fun canPost(): Boolean = allowed

        override fun post(summary: ExpiryReminderSummary, today: LocalDate, locationNames: Map<Long, String>): Boolean {
            posted += Posted(summary, today, locationNames)
            return true
        }
    }

    private class TestWallClock(var instant: Instant, var timeZone: TimeZone) : WallClock {
        override fun now(): Instant = instant

        override fun zone(): TimeZone = timeZone

        fun advance(duration: Duration) {
            instant += duration
        }
    }

    private class Check(val env: PantrySyncEnv) {
        val notifier = TestNotifier()
        var visible = false
        val clock = TestWallClock(LocalDateTime.parse("2026-10-06T08:00").toInstant(STOCKHOLM), STOCKHOLM)
        val check = PantryExpiryCheck(
            settings = env.graph.settings,
            sources = env.graph.sources,
            databases = env.graph.databases,
            pantry = env.graph.pantry,
            makeNow = env.graph.makeNow,
            reader = env.graph.pantryReader,
            network = env.graph.network,
            visibility = AppVisibility { visible },
            notifier = notifier,
            clock = clock,
        )

        fun enable(on: Boolean = true) = env.graph.settings.state.update { it.copy(pantryExpiryNotifications = on) }

        suspend fun run() = check.run()
    }

    private fun forEachVersion(block: suspend Check.() -> Unit) = PantrySyncEnv.forEachVersion { Check(this).block() }

    @Test
    fun offByDefault() = forEachVersion {
        env.tandoor.seedEntry(env.tandoor.seedLocation("Kyl"), "grädde", expires = "2026-10-07")
        assertEquals(PantryExpiryOutcome.OFF, run())
        assertTrue(notifier.posted.isEmpty())
    }

    @Test
    fun dueItemsArePostedOnceADayAndANewItemBringsTheListAgain() = forEachVersion {
        enable()
        val fridge = env.tandoor.seedLocation("Kyl")
        env.tandoor.seedEntry(fridge, "grädde", expires = "2026-10-07")
        env.tandoor.seedEntry(fridge, "mjölk", expires = "2026-10-11")
        env.tandoor.seedEntry(fridge, "smör", expires = "2026-10-07", amount = 0.0)

        assertEquals(PantryExpiryOutcome.POSTED, run())
        val first = notifier.posted.single()
        assertEquals(listOf("grädde"), first.summary.items.map { it.name })
        assertEquals(ExpiryDay.TOMORROW, first.summary.day)
        assertEquals(LocalDate(2026, 10, 6), first.today)
        assertEquals("Kyl", first.places[first.summary.first.locationId])

        // Again the same morning (a second run, a retry): nothing new.
        clock.advance(10.minutes)
        assertEquals(PantryExpiryOutcome.ALREADY_NOTIFIED, run())

        // A partner puts yoghurt that expires today in the fridge: one notification with both.
        env.tandoor.seedEntry(fridge, "yoghurt", expires = "2026-10-06")
        clock.advance(10.minutes)
        assertEquals(PantryExpiryOutcome.POSTED, run())
        assertEquals(listOf("yoghurt", "grädde"), notifier.posted.last().summary.items.map { it.name })
        assertEquals(ExpiryDay.MIXED, notifier.posted.last().summary.day)

        // The next morning the cream expires today: a new day, a new notification (yoghurt is past).
        clock.advance(1.days - 20.minutes)
        assertEquals(PantryExpiryOutcome.POSTED, run())
        assertEquals(listOf("grädde"), notifier.posted.last().summary.items.map { it.name })
        assertEquals(ExpiryDay.TODAY, notifier.posted.last().summary.day)
        assertEquals(3, notifier.posted.size)
    }

    @Test
    fun theDayIsTheDevicesAndNothingIsPostedAtNight() = forEachVersion {
        enable()
        env.tandoor.seedEntry(env.tandoor.seedLocation("Kyl"), "grädde", expires = "2026-10-07")
        // 05:30 UTC on the 7th: 22:30 on the 6th in Los Angeles (night), 07:30 on the 7th in Stockholm.
        clock.instant = LocalDateTime.parse("2026-10-07T05:30").toInstant(TimeZone.UTC)
        clock.timeZone = TimeZone.of("America/Los_Angeles")
        assertEquals(PantryExpiryOutcome.QUIET, run())
        clock.timeZone = STOCKHOLM
        assertEquals(PantryExpiryOutcome.POSTED, run())
        assertEquals(ExpiryDay.TODAY, notifier.posted.single().summary.day)
        assertEquals(LocalDate(2026, 10, 7), notifier.posted.single().today)
        // Just before local midnight the same day: already told.
        clock.instant = LocalDateTime.parse("2026-10-07T20:59").toInstant(STOCKHOLM)
        assertEquals(PantryExpiryOutcome.ALREADY_NOTIFIED, run())
        // Just after it the cream has expired: nothing due.
        clock.instant = LocalDateTime.parse("2026-10-08T07:00").toInstant(STOCKHOLM)
        assertEquals(PantryExpiryOutcome.NOTHING_DUE, run())
    }

    @Test
    fun theSettingThePermissionAndTheScreenStopIt() = forEachVersion {
        env.tandoor.seedEntry(env.tandoor.seedLocation("Kyl"), "grädde", expires = "2026-10-07")
        enable()
        notifier.allowed = false
        assertEquals(PantryExpiryOutcome.NOT_ALLOWED, run())
        notifier.allowed = true
        visible = true
        assertEquals(PantryExpiryOutcome.VISIBLE, run())
        visible = false
        enable(false)
        assertEquals(PantryExpiryOutcome.OFF, run())
        assertTrue(notifier.posted.isEmpty())
        // Nothing was remembered: switched on again, it is posted.
        enable()
        assertEquals(PantryExpiryOutcome.POSTED, run())
    }

    @Test
    fun offlineTheLocalPantryIsUsedWithoutARequest() = forEachVersion {
        enable()
        env.tandoor.seedEntry(env.tandoor.seedLocation("Kyl"), "grädde", expires = "2026-10-07")
        env.read()
        env.offline()
        val before = env.tandoor.calls.size
        assertEquals(PantryExpiryOutcome.POSTED, run())
        assertEquals(before, env.tandoor.calls.size)
        assertEquals(listOf("grädde"), notifier.posted.single().summary.items.map { it.name })
    }

    @Test
    fun guestsAndDemoModeGetNothing() = forEachVersion {
        enable()
        env.tandoor.seedEntry(env.tandoor.seedLocation("Kyl"), "grädde", expires = "2026-10-07")
        PantrySyncEnv.mirror(env.graph, groupLevel = "guest")
        assertEquals(PantryExpiryOutcome.GUEST, run())
        PantrySyncEnv.mirror(env.graph)
        env.graph.sources.source.value = ActiveSource.Demo
        assertEquals(PantryExpiryOutcome.DEMO, run())
        env.graph.sources.source.value = ActiveSource.None
        assertEquals(PantryExpiryOutcome.NO_ACCOUNT, run())
        assertTrue(notifier.posted.isEmpty())
    }

    private companion object {
        val STOCKHOLM: TimeZone = TimeZone.of("Europe/Stockholm")
    }
}
