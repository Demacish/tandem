package casa.moller.tandem.smoke

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Release smoke test for Phase 2 (0.3.0 komplettering, the konto and integration lanes' open
 * issue; docs/BYGGE.md "Release-röktest"), in the R8-minified sideload app:
 *
 * - (g) "Lägg på inköpslistan" in a recipe, driven through accessibility actions, puts one list
 *   recipe with its rows on the server. The sheet's add button stays pinned below the scrolling
 *   ingredients, so it is on screen (and in the accessibility tree) without scrolling.
 * - (h) A second Tandoor ([second], its own server and list) is added under Mer → Konton och
 *   servrar. Handla then shows ITS list; a change made offline there waits on that account while
 *   the first one is active again (its own list, nothing sent to either server), and goes to the
 *   second server only after switching back.
 */
@RunWith(AndroidJUnit4::class)
class Phase2AccountsSmokeTest : Phase2SmokeBase() {
    /** A second Tandoor on another port (another account) for (h). */
    @get:Rule(order = 3)
    val second = FakeTandoor(instrumentation.context.assets)

    override fun servers(): List<FakeTandoor> = listOf(tandoor, second)

    override fun extraReport(): List<String> =
        listOf("--- second server:") + second.requests + listOf("--- second list:") + second.shopping.all().map { it.toString() }

    @Test
    fun recipeSheet_addAccount_switchKeepsEachListAndItsUnsentChanges() {
        tandoor.shopping.seed("mjölk", 1.0, "l")
        second.shopping.seed("kaffe")

        logIn()
        val pastaRows = addThePastaThroughTheRecipeSheet()
        // Everything of the first account is sent before the second one comes.
        app.tap("Shopping")
        waitForInList(row("mjölk"), FIRST_SYNC_TIMEOUT_MS)
        waitUntilNothingWaits()

        // (h) A second Tandoor under Mer → Konton och servrar; Handla shows ITS list.
        addTheSecondAccount()
        app.tap("Shopping")
        waitForInList(row("kaffe"), FIRST_SYNC_TIMEOUT_MS)
        assertTrue("The first account's list is not shown", (listOf("mjölk") + pastaRows).none { app.device.hasObject(row(it)) })
        app.capture("phase2-second-account-list")

        // Offline on the second account: the change waits there.
        goOffline()
        addItems("bröd", count = 1)
        app.waitFor(By.desc(WAITING))

        // Back to the first account (offline too): its own list, nothing of the second's.
        switchAccount()
        waitForInList(row("mjölk"), FIRST_SYNC_TIMEOUT_MS)
        assertTrue("The second account's rows are not shown", listOf("kaffe", "bröd").none { app.device.hasObject(row(it)) })
        assertTrue("Nothing waits on the first account", !app.device.hasObject(By.desc(WAITING)))
        app.capture("phase2-first-account-again")
        app.tap("More")
        openAccounts()
        app.waitForNode("the second account's waiting change") { node ->
            listOfNotNull(node.text, node.contentDescription).any { ACCOUNT_PENDING.matcher(it).find() }
        }
        app.capture("phase2-accounts-pending")

        // Online with the first account active: the second account's change is NOT sent.
        goOnline()
        app.tap("Shopping")
        waitForInList(row("mjölk"), FIRST_SYNC_TIMEOUT_MS)
        SystemClock.sleep(INACTIVE_ACCOUNT_WAIT_MS)
        assertTrue("Nothing is sent for an inactive account: ${second.requests}", second.shopping.all().none { it.food == "bröd" })
        assertTrue("Nothing of it reaches the first server", tandoor.shopping.all().none { it.food == "bröd" })

        // Switching back sends it to the second server, and only there.
        switchAccount()
        waitForInList(row("bröd"), FIRST_SYNC_TIMEOUT_MS)
        waitUntilNothingWaits()
        waitForServer("bröd on the second server") { if (second.shopping.all().any { it.food == "bröd" }) null else "not yet" }
        assertTrue("Never on the first server", tandoor.shopping.all().none { it.food == "bröd" })
        assertEquals("No 404 on either server", emptyList<String>(), tandoor.notFound + second.notFound)
        app.capture("phase2-second-account-sent")
    }

    /**
     * (g) "Lägg på inköpslistan" under the pasta's ingredients, then the sheet's "Add n items to
     * the shopping list", each through accessibility actions and again while the sheet is still
     * closed (or still open). Returns the foods of the rows the list recipe put on the server.
     */
    private fun addThePastaThroughTheRecipeSheet(): List<String> {
        app.tap("Recipes")
        app.findInList(By.text(PASTA))
        app.tap(PASTA)
        app.scrollTo(By.text(RECIPE_SHOPPING_BUTTON))
        for (attempt in 1..TAP_ATTEMPTS) {
            // The sheet's title reads the same as the button: once the sheet shows, never wait for the button again.
            if (app.findNode(::isRecipeSheetAdd) != null) break
            app.waitForNode("\"$RECIPE_SHOPPING_BUTTON\"") { node ->
                node.text?.toString() == RECIPE_SHOPPING_BUTTON && app.clickableOf(node)?.isEnabled == true
            }
            app.clickNodeWithText(RECIPE_SHOPPING_BUTTON)
            if (nodeShows(TAP_WAIT_MS, ::isRecipeSheetAdd)) break
        }
        app.waitForNode("\"Add n items to the shopping list\"", predicate = ::isRecipeSheetAdd)
        app.capture("phase2-recipe-sheet")
        for (attempt in 1..TAP_ATTEMPTS) {
            app.waitForNode("an enabled \"Add n items to the shopping list\"") { node ->
                isRecipeSheetAdd(node) && app.clickableOf(node)?.isEnabled == true
            }
            app.clickNodeWithText(ADD_TO_LIST)
            if (nodeGone(TAP_WAIT_MS, ::isRecipeSheetAdd)) break
        }
        assertTrue("The recipe sheet closed after adding", app.findNode(::isRecipeSheetAdd) == null)
        waitForServer("the pasta's list recipe") {
            val recipe = tandoor.shopping.listRecipes().singleOrNull { it.recipe == PASTA_ID }
            when {
                recipe == null -> "no list recipe yet"
                tandoor.shopping.all().none { it.listRecipe == recipe.id } -> "no rows yet"
                else -> null
            }
        }
        app.capture("phase2-recipe-sheet-added")
        return tandoor.shopping.all().filter { it.listRecipe != null }.map { it.food }
    }

    /** Mer → Konton och servrar → "Add account": the intro's server step and password login for [second]. */
    private fun addTheSecondAccount() {
        app.tap("More")
        openAccounts()
        app.tap("Add account")
        app.waitFor(By.text("Connect to your Tandoor"))
        app.type(0, second.address)
        app.tap("Connect")
        app.waitFor(By.text("Use your Tandoor username and password."))
        app.type(0, "smoke-user")
        app.type(1, "smoke-password")
        app.tap("Log in")
        app.waitFor(By.text("You’re connected"))
        app.tap("Continue")
        app.waitForClickable("Recipes", FIRST_SYNC_TIMEOUT_MS)
    }

    /** Mer → Konton och servrar → "Switch to …" (the other account), then the Handla tab. */
    private fun switchAccount() {
        app.tap("More")
        openAccounts()
        app.tapTextMatching(SWITCH_TO)
        app.waitForClickable("Shopping")
        app.tap("Shopping")
    }

    /** Mer → "Konton och servrar" (on a phone a page of its own, which starts with its intro). */
    private fun openAccounts() {
        app.tapUntil(ACCOUNTS, By.textStartsWith(ACCOUNTS_INTRO))
    }

    /** The recipe sheet's "Add n items to the shopping list". */
    private fun isRecipeSheetAdd(node: AccessibilityNodeInfo): Boolean =
        node.text?.toString()?.let { ADD_TO_LIST.matcher(it).matches() } == true

    private companion object {
        /** "Lägg på inköpslistan" in the recipe, and the accounts page. */
        const val RECIPE_SHOPPING_BUTTON = "Add to shopping list"
        const val ACCOUNTS = "Accounts and servers"
        const val ACCOUNTS_INTRO = "Switch between your Tandoor accounts."
        val SWITCH_TO: Pattern = Pattern.compile("Switch to .+")
        val ACCOUNT_PENDING: Pattern = Pattern.compile("\\d+ changes? waiting to be sent")

        /** How long an inactive account's change is watched for NOT being sent. */
        const val INACTIVE_ACCOUNT_WAIT_MS = 15_000L
    }
}
