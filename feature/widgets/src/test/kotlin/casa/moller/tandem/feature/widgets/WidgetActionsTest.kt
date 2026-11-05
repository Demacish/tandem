package casa.moller.tandem.feature.widgets

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.testing.data.FakeShoppingRepository
import casa.moller.tandem.core.testing.data.SampleShopping
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan §10 Fas 3 "Klart när" (Fas 3's leftovers): a tap in the shopping widget checks the entry
 * off through the repository's `setChecked` (the outbox, offline too; the drain is core:sync's
 * `ShoppingSyncTest`), and the count the widget and the tile show follows at once.
 */
class WidgetActionsTest {
    @Test
    fun aTapChecksTheEntryOffAndTheCountFollows() = runBlocking {
        val shopping = FakeShoppingRepository()
        val before = ShoppingWidgetState.of(shopping.observeEntries().first(), SampleShopping.now)
        val first = before.rows.first()

        assertTrue(WidgetActions.check(shopping, first.id) is Outcome.Success)

        val after = ShoppingWidgetState.of(shopping.observeEntries().first(), SampleShopping.now)
        assertEquals(true, shopping.observeEntries().first().first { it.id == first.id }.checked)
        assertEquals(before.remaining - 1, after.remaining)
        assertTrue(after.rows.none { it.id == first.id })
    }
}
