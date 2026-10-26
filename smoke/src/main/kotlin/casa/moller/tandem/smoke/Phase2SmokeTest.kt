package casa.moller.tandem.smoke

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import java.io.File
import java.time.LocalDate
import java.util.regex.Pattern
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

/**
 * Release smoke test for Phase 2 (0.3.0 "handla & planera", plan §10 Fas 2 "Klart när"; docs/BYGGE.md
 * "Release-röktest") in the R8-minified sideload app, against [FakeTandoor]'s stateful shopping list
 * and meal plan:
 *
 * - [shoppingList_partnerCheckIn30s_twentyOfflineChanges_rowsDeletedElsewhereWithoutA404]:
 *   (c) "the partner" checks an item on the server while the list is open, and the app shows it
 *   within 30 s; (a) 20 changes offline (create + check of the same item among them, undo, buy
 *   later, remove, a changed amount, checks of server rows), then the network: the server's list
 *   is exactly what the user did; (b) offline edits of 15 rows (a whole aisle checked, 5 bought
 *   later, 5 removed) that the partner deletes meanwhile: the outbox drains with ZERO requests
 *   answered 404 and nothing is sent for the deleted rows.
 * - [mealPlan_webMealOnItsDay_appMealArrivesWithItsDate_shopForTheWeekWithoutDuplicates]:
 *   (d) a meal planned on the web at 00:30 server time (the previous day in UTC) shows on its own
 *   day, and a meal planned in the app reaches the server with the chosen date; (e) "Handla för
 *   veckan" with `mealplan_autoadd_shopping` off (the app adds a meal's ingredients once, asking
 *   again adds nothing) and on (the server added a web meal's ingredients, and `addshopping` an
 *   app meal's): one list recipe per meal and no duplicate rows.
 */
@RunWith(AndroidJUnit4::class)
class Phase2SmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = TandemApp(UiDevice.getInstance(instrumentation))
    private var networkTurnedOff = false
    private val density = instrumentation.context.resources.displayMetrics.density

    /** The network always comes back, also after a failure. The outermost rule (see Phase1SmokeTest). */
    @get:Rule(order = 0)
    val network = object : TestWatcher() {
        override fun finished(description: Description) {
            if (networkTurnedOff) app.setNetworkEnabled(true)
        }
    }

    @get:Rule(order = 1)
    val tandoor = FakeTandoor(instrumentation.context.assets)

    /** On failure: screenshot, window hierarchy, the server's requests, 404s and state. */
    @get:Rule(order = 2)
    val artifacts = object : TestWatcher() {
        override fun failed(e: Throwable, description: Description) {
            val name = "FAILED-${description.methodName}"
            app.capture(name)
            runCatching {
                File(TandemApp.outputDir(), "$name-requests.txt").writeText(
                    (
                        tandoor.requests + listOf("--- while offline:") + tandoor.requestsWhileOffline +
                            listOf("--- 404:") + tandoor.notFound +
                            listOf("--- shopping list:") + tandoor.shopping.all().map { it.toString() } +
                            listOf("--- list recipes:") + tandoor.shopping.listRecipes().map { it.toString() } +
                            listOf("--- meal plan:") + tandoor.mealPlan.all().map { it.toString() } +
                            listOf("--- meal plan creates:") + tandoor.mealPlan.creates + listOf(app.networkReport())
                        ).joinToString("\n"),
                )
            }
        }
    }

    @Before
    fun startFresh() {
        app.clearData()
    }

    @Test
    fun shoppingList_partnerCheckIn30s_twentyOfflineChanges_rowsDeletedElsewhereWithoutA404() {
        val list = tandoor.shopping
        // Grönsaker (an aisle of the recorded store): kakao for (c), five rows checked at once in (b).
        val kakao = list.seed("kakao", category = FakeShoppingList.CATEGORY_VEGETABLES)
        val vegetables = VEGETABLES.map { list.seed(it, category = FakeShoppingList.CATEGORY_VEGETABLES) }
        val later = LATER.map { list.seed(it) }
        val removed = REMOVED.map { list.seed(it) }
        // The rows (a) changes.
        list.seed("mjölk", 2.0, "l")
        list.seed("grädde", 3.0, "dl")
        listOf("smör", "yoghurt", "ost", "bröd", "russin").forEach { list.seed(it) }
        list.seed("ägg", 6.0)
        val total = 1 + VEGETABLES.size + LATER.size + REMOVED.size + 8

        logIn()
        app.tap("Shopping")
        app.waitFor(row("kakao"), FIRST_SYNC_TIMEOUT_MS)
        app.waitFor(By.text("0 of $total checked"))
        app.capture("phase2-shopping-list")

        // (c) The partner checks kakao on their phone; the open list shows it within 30 s (plan §10).
        val partnerCheckedAt = SystemClock.elapsedRealtime()
        list.partnerCheck(kakao)
        app.waitFor(By.text("1 of $total checked"), PARTNER_TIMEOUT_MS)
        val seenAfter = SystemClock.elapsedRealtime() - partnerCheckedAt
        assertTrue("The partner's check showed after $seenAfter ms (at most 30 s)", seenAfter <= PARTNER_TIMEOUT_MS)
        app.waitUntilGone(row("kakao"))
        app.capture("phase2-partner-checked")

        twentyChangesOffline()
        goOnline()
        waitForServer("the 20 offline changes", ::twentyChangesMismatch)
        waitUntilNothingWaits()
        val creates = tandoor.requests.count { it == "POST $ENTRIES" }
        assertEquals("5 creates (kaffe was removed before it was sent): ${tandoor.requests}", 5, creates)
        assertEquals("No request answered 404: ${tandoor.notFound}", emptyList<String>(), tandoor.notFound.toList())
        app.capture("phase2-after-twenty")

        // (b) Offline edits of 15 rows that the partner deletes meanwhile.
        goOffline()
        app.scrollBackTo(By.desc("Check all in Grönsaker")).click()
        VEGETABLES.forEach { app.waitUntilGone(row(it)) }
        LATER.forEach { editRow(it, "Buy later") }
        REMOVED.forEach { editRow(it, "Remove from the list") }
        app.waitFor(By.desc(WAITING))
        app.capture("phase2-fifteen-offline")
        val deleted = vegetables + later + removed
        list.partnerDelete(deleted)
        val before = tandoor.requests.size
        goOnline()
        waitUntilNothingWaits()
        waitForServer("the deleted rows gone from the app") { if (deletedStillShown()) "still shown" else null }
        // "Köp senare" holds te and yoghurt from (a) again, not the five deleted rows.
        app.findInList(By.text("Buy later (2)"))
        val sent = tandoor.requests.drop(before)
        val touched = sent.filter { call -> deleted.any { id -> call.endsWith("$ENTRIES$id/") } }
        assertEquals("Nothing is sent for a row deleted elsewhere: $sent", emptyList<String>(), touched)
        assertEquals("ZERO requests answered 404: ${tandoor.notFound}", emptyList<String>(), tandoor.notFound.toList())
        assertEquals("Only (a)'s changes are left", null, twentyChangesMismatch())
        app.capture("phase2-after-fifteen")
    }

    /**
     * (a) 20 changes without network, all on the device at once (plan §10: "inklusive skapa + bocka
     * av samma vara"): 1–5 five new items in one go, 6 check äpplen (created offline), 7–8 check
     * bananer and undo, 9 remove kaffe (never sent), 10 buy te later, 11–13 check mjölk, grädde and
     * smör, 14 buy yoghurt later, 15 remove ost, 16–17 add and check pasta, 18 bröd's amount to 2,
     * 19–20 check ägg and russin.
     */
    private fun twentyChangesOffline() {
        goOffline()
        addItems("äpplen, bananer, citroner, kaffe, te", count = 5)
        check("äpplen")
        check("bananer")
        // The snackbar of THIS check (each check replaces the last one's), then its "Undo".
        app.waitFor(By.text("bananer checked"))
        app.waitForClickable("Undo")
        app.tap("Undo")
        app.findInList(row("bananer"))
        editRow("kaffe", "Remove from the list")
        editRow("te", "Buy later")
        listOf("mjölk", "grädde", "smör").forEach(::check)
        editRow("yoghurt", "Buy later")
        editRow("ost", "Remove from the list")
        addItems("pasta", count = 1)
        check("pasta")
        rowOnScreen("bröd").longClick()
        app.waitFor(By.text(EDIT_TITLE))
        app.type(AMOUNT_FIELD, "2")
        app.tap("Save")
        app.waitUntilGone(By.text(EDIT_TITLE))
        check("ägg")
        check("russin")
        app.waitFor(By.desc(WAITING))
        app.capture("phase2-twenty-offline")
        val nothingSent = tandoor.shopping.all().none { it.food == "äpplen" }
        assertTrue("Nothing reached the server offline: ${tandoor.requestsWhileOffline}", nothingSent)
    }

    /** What still differs between the server's list and the 20 changes, null when nothing does. */
    private fun twentyChangesMismatch(): String? {
        val rows = tandoor.shopping.all().groupBy { it.food }
        val problems = mutableListOf<String>()
        fun expect(food: String, checked: Boolean, delayed: Boolean = false, amount: Double? = null) {
            val found = rows[food]
            when {
                found == null -> problems += "$food missing"
                found.size > 1 -> problems += "$food ${found.size} times"
                found.single().checked != checked -> problems += "$food checked=${found.single().checked}"
                (found.single().delayUntil != null) != delayed -> problems += "$food delay_until=${found.single().delayUntil}"
                amount != null && found.single().amount != amount -> problems += "$food amount=${found.single().amount}"
            }
        }
        expect("äpplen", checked = true)
        expect("bananer", checked = false)
        expect("citroner", checked = false)
        expect("te", checked = false, delayed = true)
        expect("pasta", checked = true)
        listOf("mjölk", "grädde", "smör", "ägg", "russin", "kakao").forEach { expect(it, checked = true) }
        expect("yoghurt", checked = false, delayed = true)
        expect("bröd", checked = false, amount = 2.0)
        listOf("kaffe", "ost").filter { it in rows }.forEach { problems += "$it still on the server" }
        return problems.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private fun deletedStillShown(): Boolean = (VEGETABLES + LATER + REMOVED).any { app.device.hasObject(row(it)) }

    @Test
    fun mealPlan_webMealOnItsDay_appMealArrivesWithItsDate_shopForTheWeekWithoutDuplicates() {
        val plan = tandoor.mealPlan
        val today = LocalDate.now()
        // (d) Planned on the web at 00:30 server time (Europe/Stockholm): the previous day in UTC.
        val pasta = plan.web("${today}T00:30:00", recipe = PASTA_ID, recipeName = PASTA)

        logIn()
        app.tap("Week")
        app.waitFor(By.res(dayTag(today)))
        waitForInList(By.res(dayTag(today)).hasDescendant(By.text(PASTA)), MEAL_PLAN_TIMEOUT_MS)
        app.capture("phase2-week-web-meal")

        // (e) Off: the app puts the meal's ingredients on the list once ...
        openShopForTheWeek()
        val add = app.waitFor(By.text(ADD_TO_LIST), WEEK_SHOPPING_TIMEOUT_MS)
        app.capture("phase2-shop-for-the-week")
        add.click()
        // "Added": the app switches to Handla, where the recipe's rows are.
        waitForInList(row("pasta"), WEEK_SHOPPING_TIMEOUT_MS)
        app.capture("phase2-week-shopping-added")
        waitUntilNothingWaits()
        waitForServer("the pasta's list recipe") { if (tandoor.shopping.hasListRecipeFor(pasta)) null else "none yet" }
        val pastaRows = rowsOf(pasta)
        assertTrue("The pasta's ingredients are on the list: ${tandoor.shopping.all()}", pastaRows > 0)

        // ... and asking again adds nothing.
        app.tap("Week")
        openShopForTheWeek()
        app.waitFor(By.text(ALL_ON_LIST), WEEK_SHOPPING_TIMEOUT_MS)
        app.capture("phase2-shop-for-the-week-again")
        app.device.pressBack()

        // On: the web planned a second meal and Tandoor put its ingredients on the list by itself.
        val meatballs = plan.web("${today}T12:00:00", recipe = MEATBALLS_ID, recipeName = MEATBALLS, type = FakeMealPlan.LUNCH)
        tandoor.shopping.serverAddMeal(meatballs, MEATBALLS_ID, servings = 4.0, foods = tandoor.foodsOf(MEATBALLS_ID))

        // (d) A meal planned in the app, with "Put the ingredients on the shopping list" (addshopping).
        app.waitFor(By.res(dayTag(today)))
        planInApp(today)
        waitForServer("the app's meal") { if (plan.all().any { it.recipe == SHAKSHUKA_ID }) null else "not created yet" }
        val created = plan.creates.map { Json.parseToJsonElement(it).jsonObject }
            .single { (it["recipe"] as? JsonObject)?.get("id")?.jsonPrimitive?.content == SHAKSHUKA_ID.toString() }
        val sentDay = created.getValue("from_date").jsonPrimitive.content
        assertTrue("The app's meal has the chosen day: $created", sentDay.startsWith(today.toString()))
        assertEquals("addshopping was sent: $created", "true", created["addshopping"]?.jsonPrimitive?.content)
        val shakshuka = plan.all().single { it.recipe == SHAKSHUKA_ID }
        assertEquals("On the server the meal is on $today: $shakshuka", today, shakshuka.fromDay)
        waitForInList(By.res(dayTag(today)).hasDescendant(By.text(MEATBALLS)), MEAL_PLAN_TIMEOUT_MS)
        waitForInList(By.res(dayTag(today)).hasDescendant(By.text(SHAKSHUKA)), MEAL_PLAN_TIMEOUT_MS)
        app.capture("phase2-week-three-meals")

        // Every planned meal is on the list now: nothing to add, and no duplicates on the server.
        openShopForTheWeek()
        app.waitFor(By.text(ALL_ON_LIST), WEEK_SHOPPING_TIMEOUT_MS)
        app.capture("phase2-all-on-the-list")
        val recipes = tandoor.shopping.listRecipes()
        for (meal in listOf(pasta, meatballs, shakshuka.id)) {
            assertEquals("One list recipe for meal $meal: $recipes", 1, recipes.count { it.mealplan == meal })
        }
        assertEquals("No other list recipes: $recipes", 3, recipes.size)
        assertEquals("The pasta's rows were not added again", pastaRows, rowsOf(pasta))
        val duplicates = tandoor.shopping.all().groupBy { Triple(it.listRecipe, it.food, it.unit) }.filterValues { it.size > 1 }
        assertEquals("No duplicate rows: $duplicates", emptyMap<Any, Any>(), duplicates)
        assertEquals("No request answered 404: ${tandoor.notFound}", emptyList<String>(), tandoor.notFound.toList())
    }

    /** "+" on [day] in Veckan, Shakshuka from the stored recipes, the shopping tick, Middag, save. */
    private fun planInApp(day: LocalDate) {
        val dayNode = app.findInList(By.res(dayTag(day)))
        val plus = dayNode.findObject(By.clickable(true).hasDescendant(By.descStartsWith("Plan a meal on")))
            ?: dayNode.findObject(By.descStartsWith("Plan a meal on"))
            ?: throw AssertionError("No \"+\" on $day")
        plus.click()
        app.waitFor(By.text("Plan a meal"))
        app.tap("Middag")
        app.type(0, SHAKSHUKA)
        app.waitFor(By.res("plan-meal:recipe:$SHAKSHUKA_ID")).click()
        app.waitFor(By.text("Chosen recipe"))
        val tick = app.scrollTo(By.checkable(true).hasDescendant(By.text(ADD_TO_SHOPPING)))
        if (!tick.isChecked) tick.click()
        app.waitFor(By.checkable(true).checked(true).hasDescendant(By.text(ADD_TO_SHOPPING)))
        app.capture("phase2-plan-meal")
        app.tapTextMatching(Pattern.compile("Add to the plan \\(.+\\)"))
        app.waitUntilGone(By.text("Plan a meal"))
    }

    /** The "Shop for the week" card below the days (Veckan scrolls; the card may be above or below). */
    private fun openShopForTheWeek() {
        app.findInList(By.text("Shop for the week")).click()
        app.waitUntilGone(By.res(dayTag(LocalDate.now())))
    }

    /** Rows on the server's list for [mealId]'s list recipe. */
    private fun rowsOf(mealId: Long): Int {
        val listRecipe = tandoor.shopping.listRecipes().single { it.mealplan == mealId }.id
        return tandoor.shopping.all().count { it.listRecipe == listRecipe }
    }

    // ------------------------------------------------------------------------------------ helpers

    /** Password login and the real first sync, like [Phase1SmokeTest]. */
    private fun logIn() {
        app.launch()
        app.waitFor(By.text(WELCOME_TEXT))
        app.tap("Get started")
        app.waitFor(By.text("Connect to your Tandoor"))
        app.type(0, tandoor.address)
        app.tap("Connect")
        app.waitFor(By.text("Use your Tandoor username and password."))
        app.type(0, "smoke-user")
        app.type(1, "smoke-password")
        app.tap("Log in")
        app.waitFor(By.text("You’re connected"))
        app.tap("Continue")
        app.waitForClickable("Recipes", FIRST_SYNC_TIMEOUT_MS)
    }

    /**
     * A list row by its food: one TalkBack item described "<amount> <food>, <aisle>[, ...]"
     * (feature:shopping ShoppingEntryRow). "mjöl" does not match "2 l mjölk, Mejeri".
     */
    private fun row(food: String): BySelector = By.desc(Pattern.compile("(?:[^,]* )?${Pattern.quote(food)}, .*"))

    /**
     * The row of [food], scrolled into the upper part of the screen: a snackbar ("Ångra", 10 s) and
     * the "+ Vara" button sit at the bottom, and a tap there would hit them instead of the row.
     */
    private fun rowOnScreen(food: String): UiObject2 {
        val limit = app.device.displayHeight * UPPER_PART
        repeat(MAX_NUDGES) {
            val found = app.findInList(row(food))
            val bounds = found.visibleBounds
            if (bounds.bottom <= limit && bounds.height() >= MIN_ROW_DP * density) return found
            val x = app.device.displayWidth / 2
            val upper = (app.device.displayHeight * UPPER_PART).toInt()
            val nudged = (app.device.displayHeight * NUDGE_TO).toInt()
            // Content up when the row is low, down when it is cut at the top.
            if (bounds.bottom > limit) {
                app.device.swipe(x, upper, x, nudged, SWIPE_STEPS)
            } else {
                app.device.swipe(x, nudged, x, upper, SWIPE_STEPS)
            }
            app.device.waitForIdle()
        }
        return app.findInList(row(food))
    }

    /** Taps [food]'s row (check); a checked row leaves the list for the folded "Checked". */
    private fun check(food: String) {
        rowOnScreen(food).click()
        app.waitUntilGone(row(food))
    }

    /** Long press on [food]'s row (the edit sheet) and [button] there ("Buy later", "Remove from the list"). */
    private fun editRow(food: String, button: String) {
        rowOnScreen(food).longClick()
        app.waitFor(By.text(EDIT_TITLE))
        app.tap(button)
        app.waitUntilGone(By.text(EDIT_TITLE))
    }

    /** "+ Item", the free text, "Add n items", and the sheet closed again. */
    private fun addItems(text: String, count: Int) {
        app.tap("Item")
        app.waitFor(By.text("Add to the list"))
        app.type(0, text)
        val button = if (count == 1) "Add 1 item" else "Add $count items"
        app.tap(button)
        // Added: the field is emptied and the button says "Add" again; the sheet stays open.
        app.waitUntilGone(By.text(button))
        repeat(CLOSE_ATTEMPTS) {
            if (!app.device.hasObject(By.text("Add to the list"))) return@repeat
            app.device.pressBack()
            app.device.waitForIdle()
        }
        app.waitUntilGone(By.text("Add to the list"))
    }

    private fun goOffline() {
        tandoor.offline = true
        networkTurnedOff = true
        app.setNetworkEnabled(false)
        assertTrue("The emulator must be offline: ${app.networkReport()}", !app.hasNetwork())
    }

    private fun goOnline() {
        tandoor.offline = false
        app.setNetworkEnabled(true)
        networkTurnedOff = false
    }

    /** Until the top bar's "n waiting" (and every row's "waiting to sync") is gone: the outbox is empty. */
    private fun waitUntilNothingWaits() {
        app.waitUntilGone(By.desc(WAITING), DRAIN_TIMEOUT_MS)
    }

    /** Polls [mismatch] (null = done) for up to [DRAIN_TIMEOUT_MS]. */
    private fun waitForServer(what: String, mismatch: () -> String?) {
        val deadline = SystemClock.uptimeMillis() + DRAIN_TIMEOUT_MS
        var last = mismatch()
        while (last != null) {
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError("Timed out waiting for $what: $last")
            SystemClock.sleep(POLL_MS)
            last = mismatch()
        }
    }

    private fun dayTag(day: LocalDate): String = "day:$day"

    /** [TandemApp.findInList] again and again until [selector] shows (data that arrives with a sync). */
    private fun waitForInList(selector: BySelector, timeoutMs: Long): UiObject2 {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            val found = runCatching { app.findInList(selector) }
            found.getOrNull()?.let { return it }
            if (SystemClock.uptimeMillis() > deadline) throw found.exceptionOrNull() ?: AssertionError("Not in the list: $selector")
            SystemClock.sleep(POLL_MS)
        }
    }

    private companion object {
        const val WELCOME_TEXT = "Welcome to Tandem"
        const val ENTRIES = "/api/shopping-list-entry/"
        const val EDIT_TITLE = "Edit item"

        /** The edit sheet's fields, top to bottom: Item, Amount, Unit. */
        const val AMOUNT_FIELD = 1

        /** The top bar's chip ("3 changes waiting to sync") and a pending row's "..., waiting to sync". */
        val WAITING: Pattern = Pattern.compile(".*waiting to sync")

        val VEGETABLES = listOf("morot", "gurka", "tomat", "paprika", "squash")
        val LATER = listOf("ris", "nudlar", "socker", "peppar", "olja")
        val REMOVED = listOf("senap", "honung", "sylt", "müsli", "flingor")

        /** fas1 recordings. */
        const val PASTA_ID = 2L
        const val PASTA = "Krämig svamppasta"
        const val MEATBALLS_ID = 5L
        const val MEATBALLS = "Köttbullar med potatismos"
        const val SHAKSHUKA_ID = 6L
        const val SHAKSHUKA = "Shakshuka"

        val ADD_TO_LIST: Pattern = Pattern.compile("Add \\d+ items? to the shopping list")
        const val ALL_ON_LIST = "Everything planned on these days is already on the list."
        const val ADD_TO_SHOPPING = "Put the ingredients on the shopping list"

        const val FIRST_SYNC_TIMEOUT_MS = 90_000L

        /** Plan §10: "ser varandras avbockningar inom 30 s när listan är öppen". */
        const val PARTNER_TIMEOUT_MS = 30_000L
        const val DRAIN_TIMEOUT_MS = 120_000L
        const val WEEK_SHOPPING_TIMEOUT_MS = 45_000L

        /** Veckan reads the meal plan when it opens and every 60 s while it is visible. */
        const val MEAL_PLAN_TIMEOUT_MS = 90_000L
        const val POLL_MS = 500L
        const val UPPER_PART = 0.6
        const val NUDGE_TO = 0.35
        const val MIN_ROW_DP = 40
        const val MAX_NUDGES = 4
        const val SWIPE_STEPS = 20
        const val CLOSE_ATTEMPTS = 3
    }
}
