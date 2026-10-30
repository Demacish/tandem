package casa.moller.tandem.smoke

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import java.time.LocalDate
import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Release smoke test for Fas S (integration, plan §10 Fas S "Klart när"; docs/BYGGE.md
 * "Release-röktest"): Hemma and Kan laga nu in the R8-minified app against [FakeTandoor]'s
 * stateful pantry ([FakePantry]), which computes `makenow` like Tandoor.
 *
 * (a) Kan laga nu: the three groups are the server's, a food that expires tomorrow lifts its
 * recipes to the top, the missing foods are shown, and "Add what is missing to the list" puts
 * EXACTLY the missing foods on the shopping list. (b) Hemma offline: an item added and then used up
 * without network reaches the server as one entry with amount 0 once the network is back.
 */
@RunWith(AndroidJUnit4::class)
class PhaseSPantrySmokeTest : Phase2SmokeBase() {
    override fun servers(): List<FakeTandoor> = listOf(tandoor)

    override fun extraReport(): List<String> = pantryReport(tandoor)

    @Test
    fun makeNow_threeGroupsAsTheServer_useUpFirst_exactlyTheMissingToTheList() {
        seedKitchen()
        logIn()
        openHome(app)
        app.tapTextMatching(COOK_NOW)
        app.waitFor(By.text(Pattern.compile("Have it all \\(\\d+\\)")))
        app.device.findObject(By.text("Not now"))?.click()

        // Har allt: what the server's makenow=0 has (Pannkakor; Pytt i panna has no foods).
        app.findInList(By.res(row(PANCAKES)))
        waitForRowText(PANCAKES, "Everything at home")
        assertTab("Missing 1", tandoor.pantry.makeNow(1) - tandoor.pantry.makeNow(0).toSet())
        assertTab("Missing 2", tandoor.pantry.makeNow(2) - tandoor.pantry.makeNow(1).toSet())

        // Saknar 1: Rårakor uses the potatoes that expire tomorrow, so it comes before Shakshuka.
        selectTab("Missing 1")
        waitForRowText(RARAKOR, "Missing: lingonsylt")
        waitForRowText(SHAKSHUKA, "Missing: egg")
        assertAbove(RARAKOR, SHAKSHUKA)

        // Saknar 2: Grönsakssoppa (potatoes again) before the pasta.
        selectTab("Missing 2")
        waitForRowText(VEGETABLE_SOUP, "ekologisk morot")
        waitForRowText(PASTA_ID, "vispgrädde")
        waitForRowText(PASTA_ID, "parmesan")
        assertAbove(VEGETABLE_SOUP, PASTA_ID)
        app.capture("phaseS-make-now-missing-2")

        addTheMissingToTheList(PASTA_ID)
        waitForServer("exactly the pasta's missing foods on the list") {
            val recipe = tandoor.shopping.listRecipes().singleOrNull { it.recipe == PASTA_ID }
                ?: return@waitForServer "no list recipe yet"
            val foods = tandoor.shopping.all().filter { it.listRecipe == recipe.id }.map { it.food }.sorted()
            if (foods == listOf("parmesan", "vispgrädde")) null else "rows on the list: $foods"
        }
        assertEquals("Nothing else went on the list", 2, tandoor.shopping.all().size)
        assertTrue("A 404: ${tandoor.notFound}", tandoor.notFound.isEmpty())
    }

    @Test
    fun pantryOffline_addAndUseUp_theServerGetsOneEntryWithAmountZero() {
        logIn()
        openHome(app)
        waitForDefaultLocations(app, tandoor)
        goOffline()

        app.tapDescription("Add")
        app.waitFor(By.res(ENTRY_FOOD))
        val food = app.waitForNode("the food field") { it.viewIdResourceName == ENTRY_FOOD }
        assertTrue("Food not typed", app.setNodeText(food, "mjölk"))
        app.findNode { it.contentDescription?.toString() == "Use mjölk" }
            ?.let { app.clickableOf(it)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        val amount = app.waitForNode("the amount field") { it.viewIdResourceName == ENTRY_AMOUNT }
        assertTrue("Amount not typed", app.setNodeText(amount, "2"))
        app.capture("phaseS-pantry-entry-offline")
        // UiAutomator's view of the button (the accessibility cache can report it disabled after it was enabled).
        app.waitFor(By.res(ENTRY_SAVE).enabled(true)).click()

        // The row says it waits to be sent (read through UiAutomator: the accessibility cache can lag
        // behind the row's first frame, seen once on main), then is used up through its action.
        app.waitFor(By.desc(Pattern.compile("Mjölk, 2 .*waiting to be sent.*")))
        val milk = app.waitForNode("the new milk row") { it.contentDescription?.toString()?.startsWith("Mjölk, 2") == true }
        val (target, action) = requireNotNull(customAction(milk, "Used up")) { "No \"Used up\" action on ${milk.contentDescription}" }
        assertTrue("\"Used up\" did nothing", target.performAction(action))
        app.waitFor(By.text("Mjölk is used up"))
        app.capture("phaseS-pantry-used-up-offline")
        assertTrue("Something reached the server offline: ${tandoor.pantry.entries()}", tandoor.pantry.entries().isEmpty())

        goOnline()
        waitForServer("the used-up milk on the server") {
            val entries = tandoor.pantry.entries()
            val fridge = tandoor.pantry.locations().firstOrNull { it.name == "Fridge" }?.id
            val milkEntry = entries.singleOrNull { it.foodName.equals("mjölk", ignoreCase = true) }
            when {
                milkEntry == null -> "entries: $entries"
                milkEntry.amount != 0.0 -> "amount ${milkEntry.amount}"
                milkEntry.locationId != fridge -> "in location ${milkEntry.locationId}, not the fridge $fridge"
                else -> null
            }
        }
        assertEquals("Created, edited and used up offline is ONE create", 1, tandoor.pantry.requests("POST", "/api/inventory-entry/").size)
        val changes = tandoor.pantry.calls.filter { (call, _) -> call.startsWith("PATCH /api/inventory-entry/") }
            .plus(tandoor.pantry.calls.filter { (call, _) -> call.startsWith("DELETE") })
        assertTrue("No PATCH or DELETE was needed: $changes", changes.isEmpty())
        assertTrue("A 404: ${tandoor.notFound}", tandoor.notFound.isEmpty())
    }

    /**
     * The household's kitchen on the server: what [makeNow] needs at home, and potatoes in the
     * fridge that expire tomorrow (Rårakor and Grönsakssoppa use them).
     */
    private fun seedKitchen() {
        tandoor.pantry.markOnHand(
            "ägg", "mjölk", "vetemjöl", "salt", "smör",
            "onion", "garlic", "crushed tomatoes", "water",
            "champinjon", "gul lök", "vitlöksklyfta", "pasta", "svartpeppar",
        )
        val fridge = tandoor.pantry.seedLocation("Fridge")
        tandoor.pantry.seedLocation("Freezer", isFreezer = true)
        tandoor.pantry.seedLocation("Pantry")
        tandoor.pantry.seedEntry(fridge, "potatis", amount = 1.0, unit = "kg", expires = LocalDate.now().plusDays(1).toString())
    }

    private fun row(recipe: Long): String = "make-now:row:$recipe"

    private fun selectTab(group: String) {
        app.scrollBackTo(By.text(Pattern.compile("${Pattern.quote(group)} \\(\\d+\\)")))
        app.tapTextMatching(Pattern.compile("${Pattern.quote(group)} \\(\\d+\\)"))
        app.waitFor(By.res("make-now:list:${if (group == "Missing 1") "MISSING_1" else "MISSING_2"}"))
    }

    /** The tab of [group] counts exactly the recipes the server's makenow adds for it. */
    private fun assertTab(group: String, recipes: List<Long>) {
        app.scrollBackTo(By.text(Pattern.compile("${Pattern.quote(group)} \\(\\d+\\)")))
        app.waitFor(By.text("$group (${recipes.size})"))
    }

    /** Waits until the row of [recipe] (scrolled into view) says [text] (missing foods load lazily). */
    private fun waitForRowText(recipe: Long, text: String) {
        waitForServer("\"$text\" on recipe $recipe") {
            val found = runCatching { app.findInList(By.res(row(recipe))) }.getOrNull() ?: return@waitForServer "no row"
            val texts = textsOf(found)
            if (texts.any { text in it }) null else "row says $texts"
        }
    }

    private fun textsOf(node: UiObject2): List<String> = runCatching {
        listOfNotNull(node.text, node.contentDescription) + node.children.flatMap { textsOf(it) }
    }.getOrDefault(emptyList())

    /** [first]'s row stands above [second]'s (the rows are neighbours; a short swipe brings both on screen). */
    private fun assertAbove(first: Long, second: Long) {
        app.findInList(By.res(row(first)))
        repeat(MAX_NUDGES) {
            val upper = app.device.findObject(By.res(row(first)))
            val lower = app.device.findObject(By.res(row(second)))
            if (upper != null && lower != null) {
                assertTrue("Recipe $first does not come before $second", lower.visibleBounds.top > upper.visibleBounds.top)
                return
            }
            val x = app.device.displayWidth / 2
            val from = app.device.displayHeight * 3 / 5
            app.device.swipe(x, from, x, from - app.device.displayHeight / 5, SWIPE_STEPS)
            app.device.waitForIdle()
        }
        throw AssertionError("Recipes $first and $second are never on screen together")
    }

    /** "Add what is missing to the list" on [recipe]'s row, then the sheet's "Add n items to the shopping list". */
    private fun addTheMissingToTheList(recipe: Long) {
        app.findInList(By.res("make-now:add-missing:$recipe")).click()
        app.waitForNode("\"Add n items to the shopping list\"", predicate = ::isSheetAdd)
        app.capture("phaseS-missing-sheet")
        val add = app.findNode(::isSheetAdd)
        assertEquals("The sheet must preselect exactly the two missing foods", "Add 2 items to the shopping list", add?.text?.toString())
        for (attempt in 1..TAP_ATTEMPTS) {
            app.clickNodeWithText(ADD_TO_LIST)
            if (nodeGone(TAP_WAIT_MS, ::isSheetAdd)) break
        }
    }

    private fun isSheetAdd(node: AccessibilityNodeInfo): Boolean = node.text?.toString()?.let { ADD_TO_LIST.matcher(it).matches() } == true

    companion object {
        /** Hemma's button: "What can I cook now?" or "Cook now – n items expire soon". */
        val COOK_NOW: Pattern = Pattern.compile("What can I cook now\\?|Cook now – \\d+ items? expires? soon")

        /** fas1 recordings. */
        const val PANCAKES = 3L
        const val SHAKSHUKA = 6L
        const val VEGETABLE_SOUP = 8L
        const val RARAKOR = 11L

        const val ENTRY_FOOD = "pantry-entry:food"
        const val ENTRY_AMOUNT = "pantry-entry:amount"
        const val ENTRY_SAVE = "pantry-entry:save"
        const val PANTRY_LIST = "pantry:list"

        /** Mer → Hemma ("At home"), at its list or its "no places yet" state. */
        fun openHome(app: TandemApp) {
            // From another tab; on Mer already (its tab is then not clickable) the list may be scrolled anywhere.
            if (app.clickableWithText("More").any { app.device.hasObject(it) }) app.tap("More")
            app.findInList(By.text("At home"))
            app.tap("At home")
            val deadline = SystemClock.uptimeMillis() + Phase2SmokeBase.FIRST_SYNC_TIMEOUT_MS
            while (!app.device.hasObject(By.res(PANTRY_LIST)) && !app.device.hasObject(By.text("No storage places yet"))) {
                if (SystemClock.uptimeMillis() > deadline) throw AssertionError("Hemma did not open")
                SystemClock.sleep(Phase2SmokeBase.POLL_MS)
            }
        }

        /**
         * Hemma's first use creates Fridge, Freezer and Pantry on the server by itself
         * (`PantryRepository.refresh()`); "Add Fridge, Freezer and Pantry" may show for a moment
         * before that and is NOT tapped (that would race the automatic creation). An empty pantry
         * shows no place names, so the server is checked, then that the page left "no places".
         */
        fun waitForDefaultLocations(app: TandemApp, tandoor: FakeTandoor) {
            val deadline = SystemClock.uptimeMillis() + Phase2SmokeBase.DRAIN_TIMEOUT_MS
            while (tandoor.pantry.locations().map { it.name }.sorted() != listOf("Freezer", "Fridge", "Pantry")) {
                if (SystemClock.uptimeMillis() > deadline) throw AssertionError("No default places: ${tandoor.pantry.locations()}")
                SystemClock.sleep(Phase2SmokeBase.POLL_MS)
            }
            app.waitUntilGone(By.text("No storage places yet"))
        }

        /** The pantry's state for the failure report. */
        fun pantryReport(tandoor: FakeTandoor): List<String> =
            listOf("--- pantry locations:") + tandoor.pantry.locations().map { it.toString() } +
                listOf("--- pantry entries:") + tandoor.pantry.entries().map { it.toString() } +
                listOf("--- aliases:") + tandoor.pantry.aliases().map { it.toString() } +
                listOf("--- pantry calls:") + tandoor.pantry.calls.map { (call, body) -> "$call $body" } +
                listOf("--- keyword calls:") + tandoor.drinks.calls.map { (call, body) -> "$call $body" }
    }
}
