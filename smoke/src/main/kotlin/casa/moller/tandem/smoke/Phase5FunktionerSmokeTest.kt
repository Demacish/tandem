package casa.moller.tandem.smoke

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Release smoke test of Fas 5, lane funktioner (0.9.0, plan §8.7; docs/BYGGE.md "App Functions")
 * in the R8-minified sideload app, through the system's own App Function service (`cmd
 * app_function`, what an assistant's request goes through):
 *
 * - The minified APK publishes its five functions (the generated service, the schema in assets/
 *   and the system's index survive R8).
 * - "lägg till mjölk på listan" with "Let the assistant use Tandem" off adds nothing.
 * - Turned on in Mer → Assistant, the same request puts "mjölk" on the list and the outbox sends it
 *   to the server.
 */
@RunWith(AndroidJUnit4::class)
class Phase5FunktionerSmokeTest : Phase2SmokeBase() {
    override fun servers(): List<FakeTandoor> = listOf(tandoor)

    private val outputs = mutableListOf<String>()

    override fun extraReport(): List<String> = listOf("--- app_function:") + outputs

    @Test
    fun milkFromTheAssistantReachesTheServerOnlyWhenAllowed() {
        logIn()

        val listed = shell("cmd app_function list-app-functions --package ${TandemApp.PACKAGE}")
        // Android 16+ always has the service; the smoke emulator runs API 37.
        assertFalse("The emulator has no App Function service: $listed", listed.contains("Can't find service"))
        FUNCTIONS.forEach { name -> assertTrue("$name is published: $listed", listed.contains("$SERVICE#$name")) }

        // Off until the user allows it: nothing reaches the list.
        addMilk()
        SystemClock.sleep(OFF_WAIT_MS)
        assertTrue("Nothing added while off: ${tandoor.shopping.all()}", tandoor.shopping.all().none { it.food == MILK })

        app.tap("More")
        app.tapInList("Assistant")
        switchOn(SETTING)
        app.capture("phase5-assistant-on")

        // The setting reaches the system's enabled state in the background; ask until it answers.
        val deadline = SystemClock.uptimeMillis() + ENABLE_TIMEOUT_MS
        while (tandoor.shopping.all().none { it.food == MILK }) {
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError("mjölk never reached the server: $outputs")
            addMilk()
            waitBriefly { tandoor.shopping.all().any { it.food == MILK } }
        }
        waitForServer("mjölk on the server") { if (tandoor.shopping.all().any { it.food == MILK }) null else "not yet" }
    }

    private fun addMilk() {
        // UiAutomation splits the command at spaces and runs no shell, so the JSON needs no quoting.
        val parameters = "{\"text\":\"$MILK\"}"
        shell("cmd app_function execute-app-function --package ${TandemApp.PACKAGE} --function $SERVICE#$ADD --parameters $parameters")
    }

    private fun shell(command: String): String = app.shell(command).also { outputs += "$ $command\n$it" }

    private fun waitBriefly(done: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + ATTEMPT_WAIT_MS
        while (!done() && SystemClock.uptimeMillis() < until) SystemClock.sleep(POLL_MS)
    }

    /** The switch row [title] on the page, on; again while a tap was lost. */
    private fun switchOn(title: String) {
        val row = By.checkable(true).hasDescendant(By.text(title))
        val on = By.checkable(true).checked(true).hasDescendant(By.text(title))
        app.findInList(row)
        for (attempt in 1..TAP_ATTEMPTS) {
            if (app.device.hasObject(on)) break
            clickAtRest(listOf(row))
            app.device.wait(Until.hasObject(on), TAP_WAIT_MS)
        }
        app.waitFor(on)
    }

    private companion object {
        /** A function's id is `<the @AppFunctionServiceEntryPoint class>#<function>` (R8 does not touch the string). */
        const val SERVICE = "casa.moller.tandem.assistant.BaseTandemAppFunctionService"
        val FUNCTIONS = listOf("addToShoppingList", "getShoppingList", "getMealPlan", "searchRecipes", "openRecipe")
        const val ADD = "addToShoppingList"
        const val MILK = "mjölk"
        const val SETTING = "Let the assistant use Tandem"
        const val OFF_WAIT_MS = 5_000L
        const val ATTEMPT_WAIT_MS = 15_000L
        const val ENABLE_TIMEOUT_MS = 90_000L
    }
}
