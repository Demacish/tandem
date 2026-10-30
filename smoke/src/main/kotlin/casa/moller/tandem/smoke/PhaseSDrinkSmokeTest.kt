package casa.moller.tandem.smoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Release smoke test for Fas S (integration, plan §8.8 "Passar med", B20; docs/BYGGE.md
 * "Release-röktest"): a drink pairing chosen offline on a dish while "the partner" adds another
 * keyword to the same dish on the web. Once online the app reads the dish fresh and PATCHes the
 * WHOLE keyword list (FACTS "Fas S c": a PATCH replaces it), so both keywords survive; the pairing
 * keyword is created under "Passar med" (`POST /api/keyword/` ignores `parent`, so it is moved)
 * and the drink carries it too, so the web shows the link from both sides.
 */
@RunWith(AndroidJUnit4::class)
class PhaseSDrinkSmokeTest : Phase2SmokeBase() {
    override fun servers(): List<FakeTandoor> = listOf(tandoor)

    override fun extraReport(): List<String> = PhaseSPantrySmokeTest.pantryReport(tandoor) +
        "--- pasta keywords: ${tandoor.keywordNamesOf(PASTA_ID)}" + "--- drink keywords: ${tandoor.keywordNamesOf(FakeDrinks.DRINK_ID)}"

    @Test
    fun drinkPairedOffline_partnerAddsAKeyword_bothSurviveAndTheWebSeesTheLink() {
        tandoor.drinks.enabled = true
        logIn()
        app.tap("Recipes")
        app.findInList(By.text(PASTA))
        app.tap(PASTA)
        app.scrollTo(By.text(CHOOSE_A_DRINK))
        app.capture("phaseS-pasta-pairings")
        goOffline()

        app.tap(CHOOSE_A_DRINK)
        app.waitFor(By.text(FakeDrinks.DRINK_NAME))
        app.tap(FakeDrinks.DRINK_NAME)
        app.waitFor(By.checked(true))
        app.capture("phaseS-pairing-picker")
        app.tap("Done")
        app.scrollTo(By.text(FakeDrinks.DRINK_NAME))
        val patches = tandoor.drinks.requests("PATCH", "/api/recipe/")
        assertTrue("Something reached the server offline: $patches", patches.isEmpty())

        // Meanwhile on the web: the partner tags the same dish.
        tandoor.partnerAddsKeyword(PASTA_ID, PARTNER_KEYWORD)
        goOnline()

        waitForServer("the pairing next to the partner's keyword") {
            val dish = tandoor.keywordNamesOf(PASTA_ID)
            val drink = tandoor.keywordNamesOf(FakeDrinks.DRINK_ID)
            when {
                !dish.containsAll(listOf("Pasta", "Vegetariskt", PARTNER_KEYWORD, FakeDrinks.DRINK_NAME)) -> "dish keywords: $dish"
                !drink.containsAll(listOf("Dryck", "Vin", FakeDrinks.DRINK_NAME)) -> "drink keywords: $drink"
                tandoor.drinks.parentOf(FakeDrinks.DRINK_NAME) != PAIRS_WITH -> "parent: ${tandoor.drinks.parentOf(FakeDrinks.DRINK_NAME)}"
                else -> null
            }
        }
        assertTrue("A 404: ${tandoor.notFound}", tandoor.notFound.isEmpty())
    }

    private companion object {
        const val CHOOSE_A_DRINK = "Choose a drink"
        const val PARTNER_KEYWORD = "Snabbt"
        const val PAIRS_WITH = "Passar med"
    }
}
