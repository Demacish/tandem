package casa.moller.tandem.feature.shopping

import casa.moller.tandem.core.model.shopping.Supermarket
import casa.moller.tandem.feature.shopping.ShoppingSamples.butter
import casa.moller.tandem.feature.shopping.ShoppingSamples.coffee
import casa.moller.tandem.feature.shopping.ShoppingSamples.cream
import casa.moller.tandem.feature.shopping.ShoppingSamples.eggs
import casa.moller.tandem.feature.shopping.ShoppingSamples.milk
import casa.moller.tandem.feature.shopping.ShoppingSamples.onions
import casa.moller.tandem.feature.shopping.ShoppingSamples.potato
import casa.moller.tandem.feature.shopping.ShoppingSamples.tomatoes
import casa.moller.tandem.feature.shopping.ShoppingSamples.toothpaste
import java.util.Locale
import kotlin.time.Duration.Companion.days
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShoppingGroupingTest {
    private val now = ShoppingSamples.now

    private fun group(store: Supermarket? = ShoppingSamples.store) =
        ShoppingGrouping.group(ShoppingSamples.entries, ShoppingSamples.categories, store, now)

    @Test
    fun aislesFollowTheStoresWalkingOrderThenOtherCategoriesThenOther() {
        val grouped = group()
        // Store order fruit, pantry, dairy; "smör" has no category (offline food) and tandkräm none: "Övrigt" last.
        assertEquals(listOf("Frukt & grönt", "Skafferi", "Mejeri", null), grouped.sections.map { it.name })
        assertEquals(listOf(onions, potato), grouped.sections[0].entries)
        assertEquals(listOf(tomatoes), grouped.sections[1].entries)
        assertEquals(listOf(cream, milk), grouped.sections[2].entries)
        assertEquals(listOf(butter, toothpaste), grouped.sections[3].entries)
    }

    @Test
    fun withoutAStoreAislesAreSortedByName() {
        val grouped = group(store = null)
        assertEquals(listOf("Frukt & grönt", "Mejeri", "Skafferi", null), grouped.sections.map { it.name })
    }

    @Test
    fun categoriesTheStoreDoesNotHaveComeAfterItsAisles() {
        val soap = toothpaste.copy(id = 40, food = toothpaste.food.copy(categoryId = ShoppingSamples.hygiene.id))
        val grouped = ShoppingGrouping.group(listOf(milk, soap, onions), ShoppingSamples.categories, ShoppingSamples.otherStore, now)
        // The other store only has dairy: dairy first, then the rest by name.
        assertEquals(listOf("Mejeri", "Frukt & grönt", "Hygien"), grouped.sections.map { it.name })
    }

    @Test
    fun delayedEntriesAreBuyLaterUntilTheyFallDue() {
        assertEquals(listOf(coffee), group().later)
        val later = ShoppingGrouping.group(ShoppingSamples.entries, ShoppingSamples.categories, ShoppingSamples.store, now + 3.days)
        assertTrue(later.later.isEmpty())
        assertTrue(coffee in later.sections.first { it.name == "Skafferi" }.entries)
    }

    @Test
    fun checkedEntriesGoToCheckedNewestFirst() {
        val earlier = milk.copy(id = 50, checked = true, completedAt = now - 2.days)
        val grouped = ShoppingGrouping.group(listOf(earlier, eggs), ShoppingSamples.categories, ShoppingSamples.store, now)
        assertEquals(listOf(eggs, earlier), grouped.checked)
        assertTrue(grouped.sections.isEmpty())
        assertEquals(0, grouped.toBuyCount)
    }

    @Test
    fun anUnknownCategoryCountsAsOther() {
        val unknown = milk.copy(food = milk.food.copy(categoryId = 999))
        val grouped = ShoppingGrouping.group(listOf(unknown), ShoppingSamples.categories, null, now)
        assertNull(grouped.sections.single().categoryId)
    }

    @Test
    fun freeTextIsSplitAtLinesCommasAndSemicolonsButNotDecimalCommas() {
        assertEquals(listOf("mjölk", "bröd", "6 ägg"), ShoppingGrouping.splitItems("mjölk, bröd, 6 ägg"))
        assertEquals(listOf("1,5 kg potatis", "smör"), ShoppingGrouping.splitItems("1,5 kg potatis\nsmör"))
        assertEquals(listOf("a", "b"), ShoppingGrouping.splitItems(" a ;\r\n\n b ,"))
        assertTrue(ShoppingGrouping.splitItems("  \n , ").isEmpty())
    }

    @Test
    fun amountsAcceptACommaOrAPoint() {
        assertEquals(1.5, ShoppingGrouping.parseAmount("1,5"))
        assertEquals(0.25, ShoppingGrouping.parseAmount(" 0.25 "))
        assertEquals(0.0, ShoppingGrouping.parseAmount(""))
        assertNull(ShoppingGrouping.parseAmount("två"))
        assertNull(ShoppingGrouping.parseAmount("-1"))
    }

    @Test
    fun linesUseTheLanguagesDecimalSeparatorAndTandoorsPluralRule() {
        val swedish = ShoppingFormat(Locale.forLanguageTag("sv-SE"))
        assertEquals("2 l mjölk", swedish.line(milk))
        assertEquals("3 gula lökar", swedish.line(onions))
        assertEquals("1 kg potatis", swedish.line(potato))
        assertEquals("2 burkar krossade tomater", swedish.line(tomatoes))
        assertEquals("1,5 dl grädde", swedish.line(cream.copy(amount = 1.5)))
        assertEquals("grädde", swedish.line(cream.copy(amount = 0.0, unit = null)))
        assertEquals("1.5 dl grädde", ShoppingFormat(Locale.ENGLISH).line(cream.copy(amount = 1.5)))
        assertEquals("1\u00A0½ dl grädde", ShoppingFormat(Locale.ENGLISH, AmountStyle(useFractions = true)).line(cream.copy(amount = 1.5)))
    }

    @Test
    fun shareTextListsWhatIsLeftByAisleInWalkingOrder() {
        val format = ShoppingFormat(Locale.forLanguageTag("sv-SE"), plainFractions = true)
        val text = format.shareText("Handla", group().sections, "Övrigt")
        assertEquals(
            """
            Handla

            Frukt & grönt
            - 3 gula lökar
            - 1 kg potatis

            Skafferi
            - 2 burkar krossade tomater

            Mejeri
            - 3 dl grädde
            - 2 l mjölk

            Övrigt
            - 500 g smör
            - 1 tandkräm
            """.trimIndent(),
            text,
        )
    }
}
