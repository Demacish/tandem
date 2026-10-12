package casa.moller.tandem.smoke

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

/**
 * Release smoke test for Phase 1 (0.1.0, docs/BYGGE.md "Release-röktest"): reading recipes with
 * the R8-minified app, online and offline, and demo mode. Like [ReleaseSmokeTest] it only sees
 * what a user sees, so it covers the keep rules of everything Phase 1 added (the Phase 1 Retrofit
 * services and DTOs, Room, WorkManager's Hilt workers, Coil, Navigation 3 keys) in the code that
 * ships.
 *
 * - [passwordLogin_firstSync_searchScaleDownloadAndReadOffline]: login, the real first sync
 *   against the recorded Tandoor 2.6.13 fixtures, the recipe list, a search with å/ä/ö (local FTS
 *   and the server search), the detail with web-parity scaling (scaling-golden.json), "Download
 *   for offline use", then a cold start without any network: search and open the same recipe
 *   with its photo and the chosen servings.
 * - [demoMode_showsTonightAndARecipeWithItsPhoto]: demo mode's Today (Tonight) and a demo recipe
 *   with its bundled photo and ingredients, without a single request to a server.
 */
@RunWith(AndroidJUnit4::class)
class Phase1SmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = TandemApp(UiDevice.getInstance(instrumentation))
    private var networkTurnedOff = false

    /**
     * The network always comes back, also after a failure, so later tests can log in. The
     * outermost rule, so a failure is captured (below) while the network is still off.
     */
    @get:Rule(order = 0)
    val network = object : TestWatcher() {
        override fun finished(description: Description) {
            if (networkTurnedOff) app.setNetworkEnabled(true)
        }
    }

    @get:Rule(order = 1)
    val tandoor = FakeTandoor(instrumentation.context.assets)

    /** On failure: screenshot, window hierarchy, the server's request log and the network state. */
    @get:Rule(order = 2)
    val artifacts = object : TestWatcher() {
        override fun failed(e: Throwable, description: Description) {
            val name = "FAILED-${description.methodName}"
            app.capture(name)
            runCatching {
                File(TandemApp.outputDir(), "$name-requests.txt").writeText(
                    (tandoor.requests + listOf("--- while offline:") + tandoor.requestsWhileOffline + listOf(app.networkReport()))
                        .joinToString("\n"),
                )
            }
        }
    }

    @Before
    fun startFresh() {
        app.clearData()
    }

    @Test
    fun passwordLogin_firstSync_searchScaleDownloadAndReadOffline() {
        val golden = Golden.load(instrumentation.context.assets.open(Golden.ASSET).bufferedReader().use { it.readText() })
        val baseAmount = golden.amount(RECIPE_ID, servings = BASE_SERVINGS, food = GOLDEN_FOOD)
        val scaledAmount = golden.amount(RECIPE_ID, servings = BASE_SERVINGS + 1, food = GOLDEN_FOOD)

        // Login and the real first sync (onboarding's first-sync step), then the shell.
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
        for (call in listOf("GET /api/recipe/flat/", "GET /api/recipe/", "GET /api/keyword/", "GET /api/meal-plan/")) {
            assertTrue("The first sync must call $call: ${tandoor.requests}", call in tandoor.requests)
        }
        app.capture("phase1-today-after-first-sync")

        // The Recipes tab lists the synced fixture recipes (read from Room).
        app.tap("Recipes")
        for (name in listOf("Krämig svamppasta", "Smörgåstårta", RECIPE_NAME)) {
            app.findInList(By.text(name))
        }
        app.capture("phase1-recipes")

        // Search with a Swedish word with å/ä/ö: "äppel" finds Äppelkaka (FTS prefix, folded) and
        // nothing else; online the server is searched as well. The field scrolls with the list.
        app.scrollBackTo(By.clazz(EDIT_TEXT))
        app.type(0, SEARCH)
        app.waitFor(By.text(ONE_RESULT))
        app.waitFor(By.text(RECIPE_NAME))
        app.waitUntilGone(By.text("Krämig svamppasta"))
        app.waitUntil("the server search for \"$SEARCH\" (${tandoor.searches})") { SEARCH in tandoor.searches }
        app.waitUntilGone(By.text("Searching Tandoor…"))
        assertFalse("The server search must work", app.device.hasObject(By.text("Couldn't search Tandoor")))
        app.waitFor(By.text(ONE_RESULT))
        app.capture("phase1-search")

        // The detail: photo, base amount, one serving more gives the web's scaled amount.
        app.tap(RECIPE_NAME)
        app.waitFor(By.desc("Download for offline use"))
        app.waitFor(By.res(RECIPE_IMAGE_LOADED))
        app.scrollTo(By.desc("More servings"))
        app.waitFor(By.text("$BASE_SERVINGS $SERVINGS_TEXT"))
        app.scrollTo(By.text(baseAmount))
        app.tapDescription("More servings")
        app.waitFor(By.text("${BASE_SERVINGS + 1} $SERVINGS_TEXT"))
        app.scrollTo(By.text(scaledAmount))
        app.capture("phase1-detail-scaled")

        // Download for offline use (plan feature #5): the detail and its photo are kept.
        app.tapDescription("Download for offline use")
        app.waitFor(By.desc("Remove offline copy"))
        assertTrue("The detail was read from the server: ${tandoor.requests}", "GET /api/recipe/$RECIPE_ID/" in tandoor.requests)
        assertTrue("The photo was loaded: ${tandoor.requests}", tandoor.requests.any { it.startsWith("GET /media/") })

        // No network at all, and the fake server refuses every connection (loopback is not
        // affected by airplane mode). A cold start, so nothing comes from memory.
        tandoor.offline = true
        networkTurnedOff = true
        app.setNetworkEnabled(false)
        assertTrue("The emulator must be offline: ${app.networkReport()}", !app.hasNetwork())
        app.forceStop()
        app.launch()
        app.waitForClickable("Recipes")
        app.tap("Recipes")
        app.waitFor(By.desc(OFFLINE_CHIP))
        app.type(0, SEARCH)
        // IconLabel: one element whose description is the text (TalkBack reads it once).
        app.waitFor(By.desc("Offline: searching recipes on this device"))
        app.waitFor(By.text(ONE_RESULT))
        app.waitFor(By.text(RECIPE_NAME))
        app.capture("phase1-offline-search")
        app.tap(RECIPE_NAME)
        // The photo from the disk cache, the stored detail and the remembered servings.
        app.waitFor(By.desc("Remove offline copy"))
        app.waitFor(By.res(RECIPE_IMAGE_LOADED))
        app.scrollTo(By.text("${BASE_SERVINGS + 1} $SERVINGS_TEXT"))
        app.scrollTo(By.text(scaledAmount))
        app.capture("phase1-offline-detail")
        val recipeCalls = tandoor.requestsWhileOffline.filter { it.startsWith("GET /api/recipe") }
        assertEquals("Offline, recipes come from the device only: ${tandoor.requestsWhileOffline}", emptyList<String>(), recipeCalls)
    }

    @Test
    fun demoMode_showsTonightAndARecipeWithItsPhoto() {
        app.launch()
        app.waitFor(By.text(WELCOME_TEXT))
        app.tap("Try demo mode")

        // Today: the demo meal plan's dinner as "Tonight", with its bundled photo.
        app.waitFor(By.textStartsWith("Tonight"))
        app.waitFor(By.text(DEMO_TONIGHT))
        app.waitFor(By.res(RECIPE_IMAGE_LOADED))
        app.capture("phase1-demo-today")

        // The recipe: photo, scaled-as-written amounts and ingredient names.
        app.tap(DEMO_TONIGHT)
        app.waitFor(By.desc("More servings"))
        app.waitFor(By.res(RECIPE_IMAGE_LOADED))
        app.scrollTo(By.text(DEMO_AMOUNT))
        app.waitFor(By.textStartsWith(DEMO_FOOD))
        app.capture("phase1-demo-recipe")

        assertEquals("Demo mode never talks to a server", emptyList<String>(), tandoor.requests.toList())
    }

    /** Polls [condition] for up to [TandemApp.UI_TIMEOUT_MS]. */
    private fun TandemApp.waitUntil(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + TandemApp.UI_TIMEOUT_MS
        while (!condition()) {
            if (SystemClock.uptimeMillis() > deadline) {
                throw AssertionError("Timed out waiting for $what (app running: ${isRunning()})")
            }
            SystemClock.sleep(POLL_MS)
        }
    }

    /**
     * `api-schema/fixtures/2.6.13/fas1/scaling-golden.json`: what Tandoor's web shows for each
     * recorded recipe at several servings (packaged as an asset of this test APK). The app's user
     * preference (fixture 12) has `use_fractions` off, so the `fractionsOff` rows apply; the
     * emulator's English locale writes the decimal point like the web.
     */
    private class Golden(private val root: JsonObject) {
        fun amount(recipeId: Int, servings: Int, food: String): String {
            val recipe = root.getValue("recipes").jsonArray.map { it.jsonObject }
                .first { it.getValue("id").jsonPrimitive.content.toInt() == recipeId }
            val case = recipe.getValue("cases").jsonArray.map { it.jsonObject }
                .first { it.getValue("servings").jsonPrimitive.content.toDouble() == servings.toDouble() }
            val row = case.getValue("fractionsOff").jsonObject.getValue("steps").jsonArray
                .flatMap { it.jsonObject.getValue("ingredients").jsonArray.map { row -> row.jsonObject } }
                .first { it.getValue("food").jsonPrimitive.content == food }
            val amount = row.getValue("amount").jsonPrimitive.content
            val unit = row.getValue("unit").jsonPrimitive.content
            return if (unit.isEmpty()) amount else "$amount $unit"
        }

        companion object {
            const val ASSET = "fas1/scaling-golden.json"

            fun load(text: String): Golden = Golden(Json.parseToJsonElement(text).jsonObject)
        }
    }

    private companion object {
        const val WELCOME_TEXT = "Welcome to Tandem"
        const val EDIT_TEXT = "android.widget.EditText"

        /** fas1/recipe-appelkaka: 8 servings called "bitar"; smör 150 g. */
        const val RECIPE_ID = 10
        const val RECIPE_NAME = "Äppelkaka"
        const val BASE_SERVINGS = 8
        const val SERVINGS_TEXT = "bitar"
        const val GOLDEN_FOOD = "smör"
        const val SEARCH = "äppel"

        /** recipes_result_count for one match. */
        const val ONE_RESULT = "1 recipe"

        /** core:ui RecipeImageTags.LOADED, a resource id through testTagsAsResourceId. */
        const val RECIPE_IMAGE_LOADED = "recipe_image_loaded"

        /** ui_status_offline_description (core:ui, English). */
        const val OFFLINE_CHIP = "Offline: showing saved data"

        /** The demo meal plan's dinner on the reference day, which demo mode moves to today. */
        const val DEMO_TONIGHT = "Baked salmon with dill potatoes"
        const val DEMO_AMOUNT = "600 g"
        const val DEMO_FOOD = "salmon fillet"

        /** First sync against the fake on a slow CI emulator. */
        const val FIRST_SYNC_TIMEOUT_MS = 90_000L
        const val POLL_MS = 250L
    }
}
