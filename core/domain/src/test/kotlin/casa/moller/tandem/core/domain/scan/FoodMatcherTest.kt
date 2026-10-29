package casa.moller.tandem.core.domain.scan

import casa.moller.tandem.core.model.recipe.Food
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Receipt words (the bundled data file) and food suggestions for scanned text (#27, #44). */
class FoodMatcherTest {
    private val foods = listOf(
        Food(1, "grädde"),
        Food(2, "vispgrädde"),
        Food(3, "banan", pluralName = "bananer"),
        Food(4, "kycklingfilé"),
        Food(5, "mjölk"),
        Food(6, "mellanmjölk"),
        Food(7, "potatis"),
        Food(8, "lök"),
        Food(9, "havregryn"),
        Food(10, "smör"),
        Food(11, "krossade tomater"),
    )

    @Test
    fun theBundledWordListIsRead() {
        val normalizer = ReceiptNormalizer.bundled
        assertEquals("grädde", normalizer.searchText("GRÄDDE 36% 5DL"))
        assertEquals("banan", normalizer.searchText("BANAN EKO 1,23 kg"))
        assertEquals("kyckling filé", normalizer.searchText("KYCKL FILÉ 900G"))
        assertEquals("vispgrädde", normalizer.searchText("VISPGR 36%"))
        assertEquals("mellanmjölk", normalizer.searchText("ICA Mellanmjölk 1,5L"))
        assertEquals("lök gul", normalizer.searchText("Lösvikt Lök gul"))
        assertEquals("", normalizer.searchText("EKO KRAV 500G"))
    }

    @Test
    fun theWordListFormat() {
        val normalizer = ReceiptNormalizer.fromText(
            """
            # a comment
            grdd = grädde   # trailing comment
            - eko
            broken line
            """.trimIndent(),
        )
        assertEquals("grädde", normalizer.searchText("GRDD EKO"))
        assertEquals("broken", normalizer.searchText("broken"))
    }

    @Test
    fun receiptLinesFindTheirFood() {
        assertEquals("grädde", FoodMatcher.best("GRÄDDE 36% 5DL", foods)?.name)
        assertEquals("banan", FoodMatcher.best("Bananer Eko", foods)?.name)
        assertEquals("kycklingfilé", FoodMatcher.best("KYCKL FILÉ 900G", foods)?.name)
        assertEquals("vispgrädde", FoodMatcher.best("Vispgrädde 36%", foods)?.name)
        assertEquals("lök", FoodMatcher.best("Lösvikt Lök gul", foods)?.name)
        assertEquals("krossade tomater", FoodMatcher.best("TOMATER KROSSADE 400G", foods)?.name)
        // The last part of a compound: "matlagningsgrädde" is a kind of "grädde".
        assertEquals("grädde", FoodMatcher.best("Matlagningsgrädde 15%", foods)?.name)
    }

    @Test
    fun nothingFitsWellEnough() {
        assertNull(FoodMatcher.best("Tvättmedel 1,5L", foods))
        assertNull(FoodMatcher.best("", foods))
        assertEquals(emptyList<FoodMatch>(), FoodMatcher.rank("Diskborste", foods))
    }

    @Test
    fun suggestionsAreRankedBestFirst() {
        assertEquals(listOf("mjölk", "mellanmjölk"), FoodMatcher.rank("Mjölk 3%", foods).map { it.food.name })
        assertEquals(listOf("grädde", "vispgrädde"), FoodMatcher.rank("grädde", foods).map { it.food.name })
    }

    @Test
    fun thePickerSearchFindsWhatWasTyped() {
        assertEquals(listOf("grädde", "havregryn", "vispgrädde"), FoodMatcher.search("gr", foods).map { it.name })
        assertEquals(listOf("banan"), FoodMatcher.search("BANANER", foods).map { it.name })
        assertEquals(emptyList<Food>(), FoodMatcher.search("  ", foods))
    }
}
