package casa.moller.tandem.wear.tile

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.protolayout.LayoutElementBuilders
import casa.moller.tandem.core.watchlink.MirroredTimer
import casa.moller.tandem.core.watchlink.MirroredTimerState
import casa.moller.tandem.wear.data.WearAccount
import casa.moller.tandem.wear.data.WearState
import casa.moller.tandem.wear.entry
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Fas 5 (wear lane): what the shopping-list tile says, and that its layout builds. */
@RunWith(AndroidJUnit4::class)
class ShoppingTileTest {
    private val account = WearAccount("https://tandoor.example.com/", 42, Long.MAX_VALUE, "account-1")
    private val sv = Locale.forLanguageTag("sv-SE")
    private val running = MirroredTimer(1, "Pasta", null, MirroredTimerState.RUNNING, endsAtEpochMillis = 900_000, durationMillis = 1)
    private val later = MirroredTimer(2, "Ugnen", null, MirroredTimerState.RUNNING, endsAtEpochMillis = 2_000_000, durationMillis = 1)

    @Test
    fun theTileCountsTheRowsAndNamesTheFirstThree() {
        val state = WearState(
            account = account,
            entries = listOf(
                entry(1, "mjölk"),
                entry(2, "mjölk"),
                entry(3, "bröd"),
                entry(4, "ost"),
                entry(5, "kaffe"),
                entry(6, "te", checked = true),
            ),
            timers = listOf(later, running),
        )

        val content = ShoppingTile.content(state, 0L, sv)

        assertEquals(TileContent.Mode.LIST, content.mode)
        assertEquals(4, content.toBuy)
        assertEquals(listOf("mjölk", "bröd", "ost"), content.firstItems)
        assertEquals(TileTimer("Pasta", ringing = false, endsAtMillis = 900_000), content.timer)
    }

    @Test
    fun aRingingTimerComesFirstAndThePairingStatesAreShown() {
        val ringing = later.copy(state = MirroredTimerState.RINGING)

        assertEquals(TileTimer("Ugnen", true, null), ShoppingTile.content(WearState(timers = listOf(running, ringing)), 0L, sv).timer)
        assertEquals(TileContent.Mode.NOT_PAIRED, ShoppingTile.content(WearState(), 0L, sv).mode)
        assertEquals(TileContent.Mode.SIGNED_OUT, ShoppingTile.content(WearState(account = account, signedOut = true), 0L, sv).mode)
        assertNull(ShoppingTile.content(WearState(account = account), 0L, sv).timer)
    }

    @Test
    fun theLayoutOpensTheListAndSaysSo() {
        val timer = TileTimer("Pasta", false, 900_000)
        val content = TileContent(TileContent.Mode.LIST, toBuy = 2, firstItems = listOf("mjölk", "bröd"), timer = timer)

        val layout = TileLayout.layout(ApplicationProvider.getApplicationContext(), content) as LayoutElementBuilders.Box

        val modifiers = requireNotNull(layout.modifiers)
        assertEquals("open", modifiers.clickable?.id)
        assertTrue(modifiers.semantics?.contentDescription?.value.orEmpty().isNotBlank())
        assertEquals(1, layout.contents.size)
    }
}
