package casa.moller.tandem.wear.shopping

import casa.moller.tandem.wear.data.WearAccount
import casa.moller.tandem.wear.data.WearEntry
import casa.moller.tandem.wear.data.WearState
import casa.moller.tandem.wear.data.WearSupermarket
import casa.moller.tandem.wear.entry
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fas 5 (wear lane): the list on the watch, in the store's walking order, merged like Handla (plan §8.2). */
class WearShoppingListTest {
    private val dairy = 1L to "Mejeri"
    private val bread = 2L to "Bröd"
    private val fruit = 3L to "Frukt"
    private val sv = Locale.forLanguageTag("sv-SE")
    private val account = WearAccount("https://tandoor.example.com/", 42, Long.MAX_VALUE, "account-1")

    private fun state(vararg entries: WearEntry) = WearState(
        account = account,
        entries = entries.toList(),
        supermarkets = listOf(WearSupermarket(7, "Ica", listOf(fruit.first, bread.first, dairy.first))),
    )

    @Test
    fun aislesFollowTheStoreThenTheRestByNameThenNoAisle() {
        val list = WearShoppingList.of(
            state(
                entry(1, "mjölk", category = dairy),
                entry(2, "limpa", category = bread),
                entry(3, "tvål", category = 9L to "Hushåll"),
                entry(4, "äpplen", category = fruit),
                entry(5, "batterier"),
                entry(6, "kaffe", category = 8L to "Dryck"),
            ),
            0L,
            sv,
        )

        assertEquals(listOf("Frukt", "Bröd", "Mejeri", "Dryck", "Hushåll", null), list.aisles.map { it.name })
        assertEquals(6, list.toBuy)
        assertEquals(listOf("äpplen", "limpa", "mjölk"), list.firstRows(3).map { it.food })
    }

    @Test
    fun theSameFoodIsOneRowAndCheckingItChecksEveryEntry() {
        val list = WearShoppingList.of(
            state(entry(1, "grädde", 3.0, "dl", dairy), entry(2, "Grädde", 2.5, "dl", dairy), entry(3, "smör", 1.0, null, dairy)),
            0L,
            sv,
        )

        val cream = list.aisles.single().rows.first()
        assertEquals(listOf(1L, 2L), cream.ids)
        assertEquals("5,5 dl", cream.quantity)
        assertEquals(2, cream.entryCount)
        assertEquals("1", list.aisles.single().rows[1].quantity)
    }

    @Test
    fun checkedRowsComeLastAndTheOutboxShowsAtOnce() {
        val stored = state(entry(1, "mjölk", category = dairy), entry(2, "limpa", category = bread, checked = true))
            .copy(pending = mapOf(1L to true))

        val list = WearShoppingList.of(stored, 0L, sv)

        assertTrue(list.aisles.isEmpty())
        assertEquals(listOf("limpa", "mjölk"), list.checked.map { it.food })
        assertTrue(list.checked.first { it.food == "mjölk" }.pending)
        assertFalse(list.checked.first { it.food == "limpa" }.pending)
    }

    @Test
    fun laterIsHiddenUntilItsTime() {
        val stored = state(entry(1, "glass", category = dairy).copy(delayedUntilMillis = 1_000L), entry(2, "mjölk", category = dairy))

        assertEquals(listOf("mjölk"), WearShoppingList.of(stored, 999L, sv).firstRows(5).map { it.food })
        assertEquals(listOf("glass", "mjölk"), WearShoppingList.of(stored, 1_000L, sv).firstRows(5).map { it.food })
    }

    @Test
    fun withoutAStoreTheAislesGoByName() {
        val stored = state(entry(1, "mjölk", category = dairy), entry(2, "limpa", category = bread)).copy(supermarkets = emptyList())

        assertEquals(listOf("Bröd", "Mejeri"), WearShoppingList.of(stored, 0L, sv).aisles.map { it.name })
    }
}
