package casa.moller.tandem.smoke

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Release smoke test for Fas S (integration, plan §10 Fas S "Klart när"; docs/BYGGE.md
 * "Release-röktest"): scanning in the R8-minified app with the BUNDLED ML Kit models, from
 * synthetic images in the gallery ([Gallery], the system photo picker; no camera involved).
 *
 * (c) Receipt: the text is read on the device, the lines become a review list, and saving puts
 * them in the pantry. (d) Barcode: an unknown code is looked up in the fake Open Food Facts
 * ([FakeOpenFoodFacts], only in the `minified` build) with nothing but the code, the product name
 * suggests a food, and saving learns the code as a `FOOD_ALIAS` on the server (so the other phone
 * knows it) and puts the package in the fridge.
 */
@RunWith(AndroidJUnit4::class)
class PhaseSScanSmokeTest : Phase2SmokeBase() {
    @get:Rule(order = 3)
    val gallery = Gallery(instrumentation.context)

    @get:Rule(order = 4)
    val openFoodFacts = FakeOpenFoodFacts(instrumentation.context.assets)

    override fun servers(): List<FakeTandoor> = listOf(tandoor)

    override fun extraReport(): List<String> =
        PhaseSPantrySmokeTest.pantryReport(tandoor) + "--- Open Food Facts:" + openFoodFacts.requests.map { it.toString() }

    @Test
    fun receiptFromTheGallery_readOnTheDevice_reviewed_savedInThePantry() {
        logIn()
        PhaseSPantrySmokeTest.openHome(app)
        PhaseSPantrySmokeTest.waitForDefaultLocations(app, tandoor)
        gallery.add("receipt", Gallery.receipt())

        app.tap("Photograph receipt")
        app.waitFor(By.text("Scan a receipt"))
        app.tap(PICK_IMAGE)
        gallery.pickNewest(app)
        app.waitFor(By.text(Pattern.compile("Read the receipt \\(1 photo\\)")), ML_KIT_TIMEOUT_MS)
        app.capture("phaseS-receipt-read")
        app.tapTextMatching(Pattern.compile("Read the receipt \\(1 photo\\)"))

        app.waitFor(By.text("Check before saving"), ML_KIT_TIMEOUT_MS)
        app.capture("phaseS-receipt-review")
        saveTheReview()
        waitForServer("the receipt's items in the pantry") {
            val foods = tandoor.pantry.entries().map { it.foodName.lowercase() }
            if (foods.containsAll(listOf("pasta", "parmesan"))) null else "entries: $foods"
        }
        assertFalse("A card line became an item", tandoor.pantry.entries().any { it.foodName.contains("kort", ignoreCase = true) })
        assertTrue("Open Food Facts is not asked for a receipt", openFoodFacts.requests.isEmpty())
        assertTrue("A 404: ${tandoor.notFound}", tandoor.notFound.isEmpty())
    }

    @Test
    fun barcodeFromTheGallery_lookedUpWithOnlyTheCode_learnedAsAnAlias_inTheFridge() {
        logIn()
        PhaseSPantrySmokeTest.openHome(app)
        PhaseSPantrySmokeTest.waitForDefaultLocations(app, tandoor)
        gallery.add("barcode", Gallery.ean13(FakeOpenFoodFacts.CODE))

        app.tap("Scan barcode")
        app.waitFor(By.text("Scan a barcode"))
        app.tap(PICK_IMAGE)
        gallery.pickNewest(app)
        app.waitFor(By.res(BARCODE_CARD), ML_KIT_TIMEOUT_MS)
        app.waitForNode("the scanned code") { it.contentDescription?.toString()?.contains(FakeOpenFoodFacts.CODE) == true }
        app.capture("phaseS-barcode-found")

        // The product name suggests the recorded food "parmesan"; choose it unless it is chosen already.
        val chosenOrSuggested = app.waitForNode("parmesan chosen or suggested", ML_KIT_TIMEOUT_MS) { node ->
            isParmesanCard(node) || (node.text?.toString() == "parmesan" && app.clickableOf(node) != null)
        }
        if (!isParmesanCard(chosenOrSuggested)) app.clickableOf(chosenOrSuggested)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        app.waitForNode("the card with parmesan", predicate = ::isParmesanCard)
        app.waitForNode("an enabled \"Put in Fridge\"") { it.viewIdResourceName == BARCODE_PRIMARY && it.isEnabled }
            .performAction(AccessibilityNodeInfo.ACTION_CLICK)

        waitForServer("the learned code and the package") {
            val alias = tandoor.pantry.aliases().singleOrNull { it.param1 == FakeOpenFoodFacts.CODE }
            val entry = tandoor.pantry.entries().singleOrNull { it.foodName.equals("parmesan", ignoreCase = true) }
            when {
                alias == null -> "aliases: ${tandoor.pantry.aliases()}"
                !alias.param2.equals("parmesan", ignoreCase = true) -> "the code points at ${alias.param2}"
                entry == null -> "entries: ${tandoor.pantry.entries()}"
                tandoor.pantry.locations().firstOrNull { it.id == entry.locationId }?.name != "Fridge" -> "not in the fridge: $entry"
                else -> null
            }
        }
        val lookup = openFoodFacts.requests.single()
        assertEquals("/api/v2/product/${FakeOpenFoodFacts.CODE}", lookup.path)
        val query = lookup.query.orEmpty()
        assertTrue("More than the field list went out: $query", query.startsWith("fields=") && "&" !in query)
        val agent = lookup.userAgent.orEmpty()
        assertTrue("Not Tandem's User-Agent: $agent", agent.startsWith("Tandem/") && "(+https://github.com/Demacish/tandem)" in agent)
        assertFalse("A Tandoor token reached Open Food Facts", lookup.hadAuthorization)
        assertFalse("A cookie reached Open Food Facts", lookup.hadCookie)
        assertTrue("A 404: ${tandoor.notFound}", tandoor.notFound.isEmpty())
    }

    private fun isParmesanCard(node: AccessibilityNodeInfo): Boolean =
        node.contentDescription?.toString()?.let { it.startsWith("parmesan, ") && FakeOpenFoodFacts.CODE in it } == true

    /** The review list's "Save n items in the pantry" (through its accessibility action), until the list closes. */
    private fun saveTheReview() {
        app.waitForNode("an enabled \"Save n items in the pantry\"") { node ->
            node.text?.toString()?.let { SAVE_REVIEW.matcher(it).matches() } == true && app.clickableOf(node)?.isEnabled == true
        }
        for (attempt in 1..TAP_ATTEMPTS) {
            app.clickNodeWithText(SAVE_REVIEW)
            if (nodeGone(TAP_WAIT_MS) { it.text?.toString() == "Check before saving" }) break
        }
    }

    companion object {
        const val PICK_IMAGE = "Pick a photo from the gallery"
        const val BARCODE_CARD = "scan:barcode:card"
        const val BARCODE_PRIMARY = "scan:barcode:primary"
        val SAVE_REVIEW: Pattern = Pattern.compile("Save \\d+ items? in the pantry")

        /** Reading an image with the bundled models on the CI emulator (software rendering, 2 vCPU). */
        const val ML_KIT_TIMEOUT_MS = 60_000L
    }
}
