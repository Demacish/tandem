package casa.moller.tandem.core.model.drink

import casa.moller.tandem.core.model.settings.AppSettings
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Fas S (drycker lane): the stable server names and the Systembolaget default (plan §8.8). */
class DrinkKeywordsTest {
    @Test
    fun theRootsAreFoundInSwedishAndEnglishButDrinkIsAType() {
        assertTrue(DrinkKeywords.isDrinkRoot(" dryck "))
        assertTrue(DrinkKeywords.isDrinkRoot("Beverages"))
        assertFalse(DrinkKeywords.isDrinkRoot("Drink"))
        assertTrue(DrinkKeywords.isSuggestedType("drink"))
        assertTrue(DrinkKeywords.isPairsWithRoot("PASSAR MED"))
        assertTrue(DrinkKeywords.isPairsWithRoot("Goes with"))
        assertFalse(DrinkKeywords.isPairsWithRoot("Passar till"))
    }

    @Test
    fun systembolagetFollowsTheLanguageUntilChosen() {
        assertTrue(AppSettings.Default.systembolagetLinkShown("sv"))
        assertTrue(AppSettings.Default.systembolagetLinkShown("sv-SE"))
        assertFalse(AppSettings.Default.systembolagetLinkShown("en-GB"))
        assertFalse(AppSettings(systembolagetLink = false).systembolagetLinkShown("sv"))
        assertTrue(AppSettings(systembolagetLink = true).systembolagetLinkShown("en"))
    }
}
