package casa.moller.tandem.smoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Release smoke test of Fas 5, lane handla (0.7.0, plan §8.2; docs/BYGGE.md "Sammanslagning och
 * rutnät") in the R8-minified sideload app, against [FakeTandoor]'s stateful shopping list:
 *
 * - Two "mjölk" entries in litres are ONE row on Handla ("3 l mjölk, …, 2 rows on the list");
 *   checking it reaches the server as checks of BOTH entries, in ONE bulk call, and no entry is
 *   patched one by one. The server keeps two entries.
 * - "Show as grid" in the list's menu shows tiles; the merged "grädde" tile (3 + 2 dl) is checked
 *   with a tap, and both its entries are checked on the server, again in one bulk call.
 */
@RunWith(AndroidJUnit4::class)
class Phase5ShoppingSmokeTest : Phase2SmokeBase() {
    override fun servers(): List<FakeTandoor> = listOf(tandoor)

    @Test
    fun aMergedRowAndAMergedTileCheckEveryEntryOnTheServer() {
        val list = tandoor.shopping
        val milk = listOf(list.seed("mjölk", 2.0, "l"), list.seed("mjölk", 1.0, "l"))
        val cream = listOf(list.seed("grädde", 3.0, "dl"), list.seed("grädde", 2.0, "dl"))
        list.seed("bröd")

        logIn()
        app.tap("Shopping")
        app.waitFor(By.desc(MERGED_MILK), FIRST_SYNC_TIMEOUT_MS)
        app.waitFor(By.text("0 of 3 checked"))
        app.capture("phase5-merged-rows")

        check("mjölk")
        waitForServer("both milk entries checked") { unchecked(milk) }
        waitUntilNothingWaits()
        assertEquals("One bulk call for the merged row: ${tandoor.requests}", 1, bulks())
        assertEquals("No entry patched one by one: ${tandoor.requests}", emptyList<String>(), patches(milk + cream))
        assertEquals("The server keeps both entries", 2, list.all().count { it.food == "mjölk" })

        app.device.findObject(By.desc(MORE_OPTIONS)).click()
        app.tap(SHOW_AS_GRID)
        app.waitFor(By.res("shopping:tile:${cream.first()}"))
        app.capture("phase5-grid")
        check("grädde")
        waitForServer("both cream entries checked") { unchecked(cream) }
        waitUntilNothingWaits()
        assertEquals("One more bulk call for the merged tile: ${tandoor.requests}", 2, bulks())
        assertEquals("No entry patched one by one: ${tandoor.requests}", emptyList<String>(), patches(milk + cream))
        app.capture("phase5-grid-checked")
    }

    private fun unchecked(ids: List<Long>): String? =
        tandoor.shopping.all().filter { it.id in ids && !it.checked }.map { it.id }.takeIf { it.isNotEmpty() }?.let { "unchecked: $it" }

    private fun bulks(): Int = tandoor.requests.count { it == "POST ${ENTRIES}bulk/" }

    private fun patches(ids: List<Long>): List<String> = tandoor.requests.filter { call -> ids.any { call == "PATCH $ENTRIES$it/" } }

    private companion object {
        const val ENTRIES = "/api/shopping-list-entry/"

        /** feature:shopping's row description: "3 l mjölk, Other, 2 rows on the list". */
        val MERGED_MILK: Pattern = Pattern.compile("3 l mjölk, .*2 rows on the list.*")

        /** feature:shopping `shopping_more` and `shopping_view_grid`. */
        const val MORE_OPTIONS = "More options"
        const val SHOW_AS_GRID = "Show as grid"
    }
}
