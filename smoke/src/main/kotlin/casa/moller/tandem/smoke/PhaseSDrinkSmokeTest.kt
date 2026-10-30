package casa.moller.tandem.smoke

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Release smoke test for Fas S (integration, plan §8.8 "Passar med", B20; docs/BYGGE.md
 * "Release-röktest"): a drink pairing chosen offline on a dish while "the partner" adds another
 * keyword to the same dish on the web. Once online the app reads the dish fresh and PATCHes the
 * WHOLE keyword list (FACTS "Fas S c": a PATCH replaces it), so both keywords survive; the pairing
 * keyword is created under "Passar med" (`POST /api/keyword/` ignores `parent`, so it is moved)
 * and the drink carries it too, so the web shows the link from both sides.
 *
 * Fas S komplettering: "Ny dryck" in the minified app. A drink with a type and a synthetic photo
 * from the gallery reaches Tandoor as `POST /api/recipe/` with `steps: []`, `internal: true` and
 * the keyword "Dryck" (FACTS "Fas S f"), and then its scaled photo as a multipart
 * `PUT /api/recipe/{id}/image/` (the outbox's `RECIPE_IMAGE`, R8 keeps `RecipeImageDto`).
 */
@RunWith(AndroidJUnit4::class)
class PhaseSDrinkSmokeTest : Phase2SmokeBase() {
    @get:Rule(order = 3)
    val gallery = Gallery(instrumentation.context)

    override fun servers(): List<FakeTandoor> = listOf(tandoor)

    override fun extraReport(): List<String> = PhaseSPantrySmokeTest.pantryReport(tandoor) +
        "--- pasta keywords: ${tandoor.keywordNamesOf(PASTA_ID)}" + "--- drink keywords: ${tandoor.keywordNamesOf(FakeDrinks.DRINK_ID)}" +
        "--- created drinks: ${tandoor.drinks.created()}" + tandoor.drinks.calls.map { (call, _) -> "  $call" }

    @Test
    fun newDrinkWithAGalleryPhoto_isARecipeWithDryckAndItsPhotoIsUploaded() {
        tandoor.drinks.enabled = true
        logIn()
        gallery.add("drink", Gallery.fridge())
        app.tap("Recipes")
        // The collection chips scroll sideways: "Drinks" is clicked through its accessibility action.
        waitForAction("the Drinks collection") { app.clickNodeWithText(DRINKS) }
        app.tap(NEW_DRINK)
        app.waitFor(By.text(SAVE_DRINK))
        val name = app.waitForNode("the name field") { it.className?.toString() == EDIT_TEXT }
        assertTrue("Name not typed", app.setNodeText(name, CREATED_NAME))
        waitForAction("the Wine chip") { app.clickNodeWithText(WINE) }
        app.tap(CHOOSE_PICTURE)
        gallery.pickNewest(app)
        app.waitForNode("the chosen photo") { it.text?.toString() == PHOTO_CHOSEN || it.contentDescription?.toString() == PHOTO_CHOSEN }
        app.capture("phaseS-new-drink")
        for (attempt in 1..TAP_ATTEMPTS) {
            app.clickNodeWithText(SAVE_DRINK)
            if (nodeGone(TAP_WAIT_MS) { it.text?.toString() == SAVE_DRINK }) break
        }

        waitForServer("the new drink and its photo") {
            val drink = tandoor.drinks.created().singleOrNull { it.name == CREATED_NAME }
            when {
                drink == null -> "no POST /api/recipe/ yet"
                drink.body["steps"] != JsonArray(emptyList()) -> "steps: ${drink.body["steps"]}"
                (drink.body["internal"] as? JsonPrimitive)?.booleanOrNull != true -> "internal: ${drink.body["internal"]}"
                "Dryck" !in tandoor.drinks.requestedKeywordNames(drink) -> "keywords: ${drink.body["keywords"]}"
                drink.photoBytes == 0L -> "no photo yet"
                else -> null
            }
        }
        val drink = tandoor.drinks.created().single { it.name == CREATED_NAME }
        assertEquals("multipart/form-data", drink.photoContentType)
        assertTrue("No JPEG part named image", drink.photoPart)
        val calls = tandoor.drinks.calls.map { it.first }
        val created = calls.indexOfFirst { it == "POST /api/recipe/" }
        val photo = calls.indexOfFirst { it.startsWith("PUT /api/recipe/${drink.id}/image/") }
        assertTrue("The photo before the recipe: $calls", created in 0 until photo)
        assertTrue("Vin" in tandoor.drinks.requestedKeywordNames(drink))
        assertTrue("A 404: ${tandoor.notFound}", tandoor.notFound.isEmpty())
    }

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

    /** Repeats [action] until it succeeds (a node that is not there yet), at most [ACTION_TIMEOUT_MS]. */
    private fun waitForAction(what: String, action: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + ACTION_TIMEOUT_MS
        while (!action()) {
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError("Not possible within $ACTION_TIMEOUT_MS ms: $what")
            SystemClock.sleep(POLL_MS)
        }
    }

    private companion object {
        const val DRINKS = "Drinks"
        const val NEW_DRINK = "New drink"
        const val SAVE_DRINK = "Save drink"
        const val WINE = "Wine"
        const val CHOOSE_PICTURE = "Choose picture"
        const val PHOTO_CHOSEN = "Photo chosen"
        const val CREATED_NAME = "Cava Exempel"
        const val ACTION_TIMEOUT_MS = 10_000L
        const val CHOOSE_A_DRINK = "Choose a drink"
        const val PARTNER_KEYWORD = "Snabbt"
        const val PAIRS_WITH = "Passar med"
    }
}
