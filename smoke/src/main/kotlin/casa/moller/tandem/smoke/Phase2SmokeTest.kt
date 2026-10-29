package casa.moller.tandem.smoke

import android.os.SystemClock
import android.text.format.DateFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.regex.Pattern
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
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
 *   app meal's): one list recipe per meal and no duplicate rows; (f, 0.3.0 komplettering) Veckan's
 *   "Flytta" sheet moves a meal to tomorrow through its TalkBack action, and the server gets both dates.
 *
 * The accounts scenario and the recipe's sheet are in [Phase2AccountsSmokeTest]; the shared rules
 * and steps in [Phase2SmokeBase].
 */
@RunWith(AndroidJUnit4::class)
class Phase2SmokeTest : Phase2SmokeBase() {
    override fun servers(): List<FakeTandoor> = listOf(tandoor)

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
        checkWholeAisle("Grönsaker", VEGETABLES)
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
        openEdit("bröd")
        app.type(AMOUNT_FIELD, "2")
        for (attempt in 1..TAP_ATTEMPTS) {
            app.waitForNode("\"Save\"") { it.text?.toString() == "Save" && app.clickableOf(it)?.isEnabled == true }
            app.clickNodeWithText("Save")
            if (app.device.wait(Until.gone(By.text(EDIT_TITLE)), TAP_WAIT_MS) == true) break
        }
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
        app.waitFor(By.res(WEEK_SHOPPING_ADD).enabled(true).hasDescendant(By.text(ADD_TO_LIST)), WEEK_SHOPPING_TIMEOUT_MS)
        app.capture("phase2-shop-for-the-week")
        // Clicked again only while the enabled button is still there (a click that took effect
        // disables it while adding, then leaves for Handla).
        for (attempt in 1..TAP_ATTEMPTS) {
            clickAtRest(listOf(By.res(WEEK_SHOPPING_ADD).enabled(true)))
            if (app.device.wait(Until.gone(By.res(WEEK_SHOPPING_ADD).enabled(true)), TAP_WAIT_MS) == true) break
        }
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

        // (f) 0.3.0 komplettering: "Flytta" moves the web's lunch to tomorrow (the sheet, through accessibility actions).
        app.device.pressBack()
        app.waitFor(By.res(WEEK_AGENDA))
        moveMeal(meatballs, today.plusDays(1))
        waitForServer("the moved meal") {
            val moved = plan.all().single { it.id == meatballs }
            if (moved.fromDay == today.plusDays(1)) null else "still on ${moved.fromDay}"
        }
        val patch = tandoor.requests.count { it == "PATCH /api/meal-plan/$meatballs/" }
        assertEquals("One PATCH for the move: ${tandoor.requests}", 1, patch)
        assertEquals("No request answered 404: ${tandoor.notFound}", emptyList<String>(), tandoor.notFound.toList())
        app.capture("phase2-meal-moved")
    }

    /**
     * (f) The meal card's TalkBack action "Move" (the same as a long press), tomorrow's day chip and
     * "Move" in the sheet, each through accessibility actions and again while the sheet is still open.
     */
    private fun moveMeal(mealId: Long, day: LocalDate) {
        waitForInList(By.res("meal:$mealId"), MEAL_PLAN_TIMEOUT_MS)
        val moveTitle = By.textStartsWith("Move ")
        for (attempt in 1..TAP_ATTEMPTS) {
            val card = app.waitForNode("meal $mealId") { it.viewIdResourceName == "meal:$mealId" }
            val action = customAction(card, MOVE)
            assertTrue("The meal card offers \"$MOVE\"", action != null)
            action?.let { (node, id) -> node.performAction(id) }
            if (app.device.wait(Until.hasObject(moveTitle), TAP_WAIT_MS) == true) break
        }
        app.waitFor(moveTitle)
        val chip = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEdMMM"), Locale.getDefault())
            .format(day)
        app.waitForNode("the day chip \"$chip\"") { it.text?.toString() == chip }
        assertTrue("The day chip \"$chip\" is clicked", app.clickNodeWithText(chip))
        app.capture("phase2-move-sheet")
        for (attempt in 1..TAP_ATTEMPTS) {
            app.waitForNode("\"$MOVE\"") { it.text?.toString() == MOVE && app.clickableOf(it)?.isEnabled == true }
            app.clickNodeWithText(MOVE)
            if (app.device.wait(Until.gone(moveTitle), TAP_WAIT_MS) == true) break
        }
        app.waitUntilGone(moveTitle)
    }

    /** "+" on [day] in Veckan, Shakshuka from the stored recipes, the shopping tick, Middag, save. */
    private fun planInApp(day: LocalDate) {
        // Today comes first in the agenda: back to the top until its heading and "+" show.
        val withPlus = By.res(dayTag(day)).hasDescendant(By.descStartsWith("Plan a meal on"))
        val dayNode = settled(withPlus, app.scrollBackTo(withPlus))
        val plus = dayNode.findObject(By.clickable(true).hasDescendant(By.descStartsWith("Plan a meal on")))
            ?: dayNode.findObject(By.descStartsWith("Plan a meal on"))
            ?: throw AssertionError("No \"+\" on $day")
        plus.click()
        app.waitFor(By.text("Plan a meal"))
        app.tap("Middag")
        app.type(0, SHAKSHUKA)
        app.waitFor(By.res("plan-meal:recipe:$SHAKSHUKA_ID")).click()
        app.waitFor(By.text("Chosen recipe"))
        val tick = By.checkable(true).hasDescendant(By.text(ADD_TO_SHOPPING))
        val ticked = By.checkable(true).checked(true).hasDescendant(By.text(ADD_TO_SHOPPING))
        app.scrollTo(tick)
        for (attempt in 1..TAP_ATTEMPTS) {
            if (app.device.hasObject(ticked)) break
            clickAtRest(listOf(tick))
            app.device.wait(Until.hasObject(ticked), TAP_WAIT_MS)
        }
        app.waitFor(ticked)
        app.capture("phase2-plan-meal")
        // Through the button's accessibility action, again while the form is still open.
        for (attempt in 1..TAP_ATTEMPTS) {
            app.waitForNode("\"Add to the plan\"") { node ->
                node.text?.toString()?.let { SAVE_PLAN.matcher(it).matches() } == true && app.clickableOf(node)?.isEnabled == true
            }
            app.clickNodeWithText(SAVE_PLAN)
            if (app.device.wait(Until.gone(By.text("Plan a meal")), TAP_WAIT_MS) == true) break
        }
        app.waitUntilGone(By.text("Plan a meal"))
    }

    /** The "Shop for the week" card below the days (Veckan scrolls; the card may be above or below). */
    private fun openShopForTheWeek() {
        // The card is found while Veckan may still fling after findInList's swipes, so it is clicked
        // at rest and again while the agenda still shows (CI: a click that landed where the card WAS,
        // and the old check "today's heading gone" passed only because the heading had scrolled off).
        val card = By.clickable(true).hasDescendant(By.text("Shop for the week"))
        for (attempt in 1..TAP_ATTEMPTS) {
            app.findInList(card)
            clickAtRest(listOf(card))
            if (app.device.wait(Until.gone(By.res(WEEK_AGENDA)), TAP_WAIT_MS) == true) break
        }
        app.waitUntilGone(By.res(WEEK_AGENDA))
        app.waitFor(By.res(WEEK_SHOPPING_LIST), WEEK_SHOPPING_TIMEOUT_MS)
    }

    /** Rows on the server's list for [mealId]'s list recipe. */
    private fun rowsOf(mealId: Long): Int {
        val listRecipe = tandoor.shopping.listRecipes().single { it.mealplan == mealId }.id
        return tandoor.shopping.all().count { it.listRecipe == listRecipe }
    }

    private fun dayTag(day: LocalDate): String = "day:$day"

    private companion object {
        const val ENTRIES = "/api/shopping-list-entry/"

        /** feature:mealplan WEEK_AGENDA_TAG, WEEK_SHOPPING_LIST_TAG and WEEK_SHOPPING_ADD_TAG (resource ids). */
        const val WEEK_AGENDA = "week:agenda"
        const val WEEK_SHOPPING_LIST = "week-shopping:list"
        const val WEEK_SHOPPING_ADD = "week-shopping:add"

        /** The edit sheet's fields, top to bottom: Item, Amount, Unit. */
        const val AMOUNT_FIELD = 1

        val VEGETABLES = listOf("morot", "gurka", "tomat", "paprika", "squash")
        val LATER = listOf("ris", "nudlar", "socker", "peppar", "olja")
        val REMOVED = listOf("senap", "honung", "sylt", "müsli", "flingor")

        /** fas1 recordings. */
        const val MEATBALLS_ID = 5L
        const val MEATBALLS = "Köttbullar med potatismos"
        const val SHAKSHUKA_ID = 6L
        const val SHAKSHUKA = "Shakshuka"

        const val ALL_ON_LIST = "Everything planned on these days is already on the list."
        const val ADD_TO_SHOPPING = "Put the ingredients on the shopping list"
        val SAVE_PLAN: Pattern = Pattern.compile("Add to the plan \\(.+\\)")

        /** 0.3.0 komplettering: Veckan's "Flytta" (the card's TalkBack action and the sheet's button). */
        const val MOVE = "Move"

        /** Plan §10: "ser varandras avbockningar inom 30 s när listan är öppen". */
        const val PARTNER_TIMEOUT_MS = 30_000L
        const val WEEK_SHOPPING_TIMEOUT_MS = 45_000L

        /** Veckan reads the meal plan when it opens and every 60 s while it is visible. */
        const val MEAL_PLAN_TIMEOUT_MS = 90_000L
    }
}
