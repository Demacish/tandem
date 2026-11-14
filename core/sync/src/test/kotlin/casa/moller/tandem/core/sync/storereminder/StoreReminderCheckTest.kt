package casa.moller.tandem.core.sync.storereminder

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.datastore.StoreReminderRecord
import casa.moller.tandem.core.domain.storereminder.StoreTransition
import casa.moller.tandem.core.domain.storereminder.StoreVisits
import casa.moller.tandem.core.model.shopping.Supermarket
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.storereminder.StoreReminderSamples.OTHER_STORE
import casa.moller.tandem.core.sync.storereminder.StoreReminderSamples.STORE
import casa.moller.tandem.core.sync.storereminder.StoreReminderSamples.entry
import casa.moller.tandem.core.sync.testing.FakeNetwork
import casa.moller.tandem.core.sync.testing.FakeSources
import casa.moller.tandem.core.testing.data.FakeShoppingRepository
import casa.moller.tandem.core.testing.data.FakeSupermarketRepository
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fas 5 (butik lane, plan §10 Fas 5 "högst en gång per besök"): a geofence event for the active
 * account reminds once per visit, only with something on the list, in the store's name and with the
 * right count; EXIT re-arms; never in demo mode, without an account, with the reminder off or for a
 * store without a place; a guest gets it (it only reads).
 */
@RunWith(AndroidJUnit4::class)
class StoreReminderCheckTest {
    private val sources = FakeSources(StoreReminderSamples.remote())
    private val store = FakeStoreReminderStore()
    private val shopping = FakeShoppingRepository(
        entries = listOf(entry(1, "mjölk"), entry(2, "bröd"), entry(3, "smör", checked = true), entry(4, "ägg")),
    )
    private val supermarkets = FakeSupermarketRepository(
        supermarkets = listOf(Supermarket(STORE, "Testbutiken"), Supermarket(OTHER_STORE, "Hörnan")),
    )
    private val notifier = RecordingStoreNotifier()
    private val network = FakeNetwork(online = false)
    private val check = StoreReminderCheck(sources, store, shopping, supermarkets, notifier, network)
    private val start = 1_800_000_000_000L

    private fun setUp(enabled: Boolean = true, storeEnabled: Boolean = true) = runBlocking {
        store.update(StoreReminderSamples.ACCOUNT) {
            StoreReminderRecord(enabled = enabled, spots = listOf(StoreReminderSamples.spot.copy(enabled = storeEnabled)))
        }
    }

    private fun event(transition: StoreTransition, after: Duration, stores: List<Long> = listOf(STORE)) =
        runBlocking { check.onTransition(stores, transition, start + after.inWholeMilliseconds) }

    private fun enter(after: Duration) = event(StoreTransition.ENTER, after)[STORE]

    private fun exit(after: Duration) = event(StoreTransition.EXIT, after)[STORE]

    @Test
    fun anEnterRemindsWithTheStoresNameAndWhatIsLeft() {
        setUp()
        assertEquals(StoreReminderOutcome.POSTED, enter(Duration.ZERO))
        assertEquals(listOf(Triple(STORE, "Testbutiken", 3)), notifier.posted)
        val visit = store.record().visits.single()
        assertTrue(visit.inside)
        assertEquals(start, visit.lastRemindedMillis)
    }

    @Test
    fun oncePerVisitAndReArmedOnlyAfterAnExitAndTheGap() {
        setUp()
        assertEquals(StoreReminderOutcome.POSTED, enter(Duration.ZERO))
        assertEquals(StoreReminderOutcome.SAME_VISIT, enter(10.minutes))
        assertEquals(StoreReminderOutcome.LEFT, exit(30.minutes))
        assertEquals(StoreReminderOutcome.TOO_SOON, enter(40.minutes))
        assertEquals(StoreReminderOutcome.LEFT, exit(1.hours))
        assertEquals(StoreReminderOutcome.POSTED, enter(StoreVisits.MIN_GAP + 1.minutes))
        assertEquals(2, notifier.posted.size)
    }

    @Test
    fun anEmptyListPostsNothingAndDoesNotUseUpTheVisitGap() {
        setUp()
        shopping.entries.value = listOf(entry(3, "smör", checked = true))
        assertEquals(StoreReminderOutcome.EMPTY_LIST, enter(Duration.ZERO))
        assertTrue(notifier.posted.isEmpty())
        exit(10.minutes)
        shopping.entries.value = listOf(entry(5, "kaffe"))
        assertEquals(StoreReminderOutcome.POSTED, enter(20.minutes))
        assertEquals(1, notifier.posted.single().third)
    }

    @Test
    fun withNotificationsOffNothingIsPosted() {
        setUp()
        notifier.allowed = false
        assertEquals(StoreReminderOutcome.NOT_ALLOWED, enter(Duration.ZERO))
        assertTrue(notifier.posted.isEmpty())
    }

    @Test
    fun neverInDemoModeWithoutAnAccountOrWithTheReminderOff() {
        setUp()
        sources.source.value = ActiveSource.Demo
        assertEquals(StoreReminderOutcome.NO_ACCOUNT, enter(Duration.ZERO))
        sources.source.value = ActiveSource.None
        assertEquals(StoreReminderOutcome.NO_ACCOUNT, enter(1.minutes))
        sources.source.value = StoreReminderSamples.remote()
        setUp(enabled = false)
        assertEquals(StoreReminderOutcome.OFF, enter(2.minutes))
        assertTrue(notifier.posted.isEmpty())
    }

    @Test
    fun aStoreWithoutAPlaceOrTurnedOffIsNotWatched() {
        setUp(storeEnabled = false)
        assertEquals(StoreReminderOutcome.NOT_WATCHED, enter(Duration.ZERO))
        assertEquals(mapOf(OTHER_STORE to StoreReminderOutcome.NOT_WATCHED), event(StoreTransition.ENTER, 1.minutes, listOf(OTHER_STORE)))
        assertTrue(notifier.posted.isEmpty())
    }

    @Test
    fun aGuestGetsTheReminder() {
        setUp()
        shopping.readOnly = true
        assertEquals(StoreReminderOutcome.POSTED, enter(Duration.ZERO))
    }

    @Test
    fun anotherAccountsVisitsAreItsOwn() {
        setUp()
        enter(Duration.ZERO)
        sources.source.value = StoreReminderSamples.remote(StoreReminderSamples.OTHER_ACCOUNT)
        assertEquals(StoreReminderOutcome.OFF, enter(1.minutes))
        assertEquals(1, store.record().visits.size)
    }

    @Test
    fun onlineTheListIsReadFirst() {
        setUp()
        network.online.value = true
        assertEquals(StoreReminderOutcome.POSTED, enter(Duration.ZERO))
        assertEquals(1, shopping.refreshes.size)
    }
}
