package casa.moller.tandem.feature.shopping

import casa.moller.tandem.core.domain.shopping.UnitConverter
import casa.moller.tandem.feature.shopping.ShoppingSamples.categories
import casa.moller.tandem.feature.shopping.ShoppingSamples.cream
import casa.moller.tandem.feature.shopping.ShoppingSamples.creamCarton
import casa.moller.tandem.feature.shopping.ShoppingSamples.creamGrams
import casa.moller.tandem.feature.shopping.ShoppingSamples.now
import casa.moller.tandem.feature.shopping.ShoppingSamples.store
import java.util.Locale
import kotlin.time.Duration.Companion.days
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Fas 5, lane handla (plan §8.2): the grouping merges per section, the share text says what the screen says.
class MergedGroupingTest {
    private val converter = UnitConverter(ShoppingSamples.conversions)
    private val sv = ShoppingFormat(Locale.forLanguageTag("sv"))

    @Test
    fun eachSectionMergesOnItsOwn() {
        val delayedCream = creamCarton.copy(delayUntil = now + 1.days)
        val grouped = ShoppingGrouping.group(listOf(cream, creamGrams, delayedCream), categories, store, now, converter)
        assertEquals(listOf("5 dl grädde"), grouped.sections.single().rows.map(sv::line))
        // "Köp senare" is a section of its own: the delayed carton is not added to the aisle's row.
        assertEquals(listOf("1 st grädde"), grouped.laterRows.map(sv::line))
        assertEquals(2, grouped.allRows.size)
        assertEquals(grouped.sections.single().rows.single(), grouped.rowOf(creamGrams.id))
        assertNull(grouped.rowOf(999))
        assertEquals(1, grouped.toBuyCount)
    }

    @Test
    fun theShareTextUsesTheMergedRows() {
        val grouped = ShoppingGrouping.group(ShoppingSamples.mergedEntries, categories, store, now, converter)
        val text = sv.shareText("Handla", grouped.sections, "Övrigt")
        assertEquals(true, text.contains("\n- 5 dl + 1 st grädde\n- 3 l mjölk"))
        assertEquals(false, text.contains("200 g"))
    }

    @Test
    fun withoutAConverterEveryEntryIsARowUnlessTheUnitIsTheSame() {
        val grouped = ShoppingGrouping.group(listOf(cream, creamGrams, cream.copy(id = 99, amount = 1.0)), categories, store, now)
        assertEquals(listOf("4 dl + 200 g grädde"), grouped.sections.single().rows.map(sv::line))
    }
}
