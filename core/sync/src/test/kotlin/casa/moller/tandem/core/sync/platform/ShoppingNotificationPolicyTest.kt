package casa.moller.tandem.core.sync.platform

import casa.moller.tandem.core.model.shopping.ShoppingChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fas 4: the shopping notification only off screen, with the setting on and the permission; one summary per batch. */
class ShoppingNotificationPolicyTest {
    @Test
    fun postsOnlyOffScreenWhenEnabledAndAllowed() {
        assertTrue(ShoppingNotificationPolicy.shouldPost(enabled = true, appVisible = false))
        assertFalse("the list shows the changes itself", ShoppingNotificationPolicy.shouldPost(enabled = true, appVisible = true))
        assertFalse(ShoppingNotificationPolicy.shouldPost(enabled = false, appVisible = false))
        assertFalse(ShoppingNotificationPolicy.shouldPost(enabled = true, appVisible = false, permissionGranted = false))
        assertFalse(ShoppingNotificationPolicy.shouldPost(enabled = true, appVisible = false, notificationsEnabled = false))
        assertFalse(ShoppingNotificationPolicy.shouldPost(enabled = true, appVisible = false, channelEnabled = false))
    }

    @Test
    fun theSummaryMergesPerFoodAndCapsEachKind() {
        val changes = listOf(
            ShoppingChange.Added("mjölk", by = "Anna"),
            ShoppingChange.Checked("ägg"),
            ShoppingChange.Added("Mjölk", by = null), // The same food again: its last change counts.
            ShoppingChange.Removed("smör"),
            ShoppingChange.Added(" ", by = "Anna"), // Nothing to say.
        ) + (1..7).map { ShoppingChange.Checked("vara $it") }
        val summary = ShoppingNotificationPolicy.summarize(changes)
        assertEquals(listOf(ShoppingChange.Added("Mjölk", by = null)), summary.added)
        assertEquals(listOf("ägg", "vara 1", "vara 2", "vara 3", "vara 4"), summary.checked)
        assertEquals(listOf("smör"), summary.removed)
        assertEquals("three checked foods did not fit", 3, summary.more)
        assertEquals(10, summary.count)
        assertTrue(ShoppingNotificationPolicy.summarize(emptyList()).isEmpty)
    }
}
