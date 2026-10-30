package casa.moller.tandem.smoke

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Release smoke test for Fas S, the ai-foto lane (docs/BYGGE.md "Foto av kylen"): Settings → AI
 * in the R8-minified app. Claude is chosen, the fake Anthropic endpoint of [FakeTandoor] becomes
 * the own base URL, the fake key is pasted and stored (the Keystore-encrypted AI key store), and
 * "Test the key" goes through the official Anthropic SDK (`GET /v1/models/claude-opus-5-5`): the
 * answer must be read ("The key works") and the request must carry the key in `x-api-key` and
 * nothing of Tandoor's (no `Authorization`). The minified build keeps the SDK's OkHttp client,
 * Jackson and model types working, which unit tests cannot show.
 *
 * Fas S (integration): the fridge photo itself (#45, B22). The first use shows the experimental
 * warning, a synthetic photo from the gallery goes to the fake Claude through the SDK's beta
 * Messages call (`messages().create`: request serialization and the structured answer under R8),
 * and the answer becomes a review list in which nothing is saved until the user saves; the unsure
 * row starts skipped.
 *
 * Fas S komplettering: Gemini and OpenAI the same way, against [FakeAiServices] on the same
 * server: "Test the key" and a fridge photo through core:ai's own REST clients in the minified app.
 */
@RunWith(AndroidJUnit4::class)
class PhaseSAiSmokeTest : Phase2SmokeBase() {
    @get:Rule(order = 3)
    val gallery = Gallery(instrumentation.context)

    override fun servers(): List<FakeTandoor> = listOf(tandoor)

    override fun extraReport(): List<String> = listOf("--- fake Anthropic:") + tandoor.anthropic.requests.map { it.toString() } +
        tandoor.anthropic.messages.map { it.toString() } + "--- fake Gemini/OpenAI:" +
        tandoor.aiServices.requests.map { it.toString() } + tandoor.aiServices.photos.map { it.toString() } +
        PhaseSPantrySmokeTest.pantryReport(tandoor)

    @Test
    fun aiSettings_claudeKeyIsStoredAndTestedThroughTheSdk() {
        logIn()
        setUpProvider("Claude", tandoor.baseUrl)

        app.scrollBackTo(By.text("Test the key"))
        waitUntil("Test the key enabled") { app.clickNodeWithText("Test the key") }
        // The answer card sits below the buttons (maybe just below the fold): scroll to it.
        app.scrollTo(By.text("The key works"))
        app.capture("phaseS-ai-key-works")

        val seen = tandoor.anthropic.requests.single()
        assertEquals("GET", seen.method)
        assertEquals("/v1/models/claude-opus-5-5", seen.path)
        assertTrue("The SDK did not send the stored key", seen.keyMatched)
        assertTrue("Not the Anthropic SDK (no anthropic-version header)", seen.sdkVersionHeader)
        assertFalse("A Tandoor Authorization header reached the AI service", seen.hadAuthorization)
    }

    @Test
    fun fridgePhoto_warningFirst_claudeThroughTheSdk_nothingSavedBeforeTheReview() {
        logIn()
        setUpProvider("Claude", tandoor.baseUrl)
        app.device.pressBack()
        fridgePhotoToTheReview("claude")
        val message = tandoor.anthropic.messages.single()
        assertEquals("claude-opus-5-5", message.model)
        assertTrue("No JPEG in the request", message.hadJpeg)
        assertTrue("No structured output in the request", message.structuredOutput)
        assertTrue("No refusal fallback beta: ${message.betaHeader}", message.betaHeader.orEmpty().contains("server-side-fallback"))
        val call = tandoor.anthropic.requests.last()
        assertEquals("/v1/messages", call.path)
        assertTrue("The SDK did not send the stored key", call.keyMatched)
        assertFalse("A Tandoor Authorization header reached the AI service", call.hadAuthorization)
        saveTheReviewedRows()
    }

    /**
     * Fas S komplettering: Gemini through core:ai's own REST client in the minified app ("Test the
     * key" = `models.get`, the photo = `generateContent` with `inlineData` and a response schema).
     */
    @Test
    fun gemini_keyTestAndFridgePhotoThroughTheRestClient() {
        logIn()
        setUpProvider("Gemini", tandoor.baseUrl + FakeAiServices.GEMINI_PATH)
        testTheKey()
        val check = tandoor.aiServices.requests.single()
        assertEquals("GET", check.method)
        assertEquals("${FakeAiServices.GEMINI_PATH}/models/gemini-2.5-flash-lite", check.path)

        app.device.pressBack()
        fridgePhotoToTheReview("gemini")
        val photo = tandoor.aiServices.photos.single()
        assertEquals(FakeAiServices.Provider.GEMINI, photo.provider)
        assertEquals("gemini-2.5-flash-lite", photo.model)
        assertTrue("No JPEG in the request", photo.hadJpeg)
        assertTrue("No response schema in the request", photo.structuredOutput)
        assertOnlyTheAiKey()
        saveTheReviewedRows()
    }

    /**
     * Fas S komplettering: OpenAI through core:ai's own REST client in the minified app ("Test the
     * key" = `GET /models/{model}`, the photo = `POST /responses` with an `input_image` and a strict
     * JSON schema).
     */
    @Test
    fun openAi_keyTestAndFridgePhotoThroughTheRestClient() {
        logIn()
        setUpProvider("OpenAI", tandoor.baseUrl + FakeAiServices.OPENAI_PATH)
        testTheKey()
        val check = tandoor.aiServices.requests.single()
        assertEquals("GET", check.method)
        assertEquals("${FakeAiServices.OPENAI_PATH}/models/gpt-5-nano", check.path)

        app.device.pressBack()
        fridgePhotoToTheReview("openai")
        val photo = tandoor.aiServices.photos.single()
        assertEquals(FakeAiServices.Provider.OPENAI, photo.provider)
        assertEquals("gpt-5-nano", photo.model)
        assertTrue("No JPEG in the request", photo.hadJpeg)
        assertTrue("No strict JSON schema in the request", photo.structuredOutput)
        assertOnlyTheAiKey()
        saveTheReviewedRows()
    }

    /** "Test the key" on Settings → AI until "The key works" shows. */
    private fun testTheKey() {
        app.scrollBackTo(By.text("Test the key"))
        waitUntil("Test the key enabled") { app.clickNodeWithText("Test the key") }
        app.scrollTo(By.text("The key works"))
    }

    /** Every request to the fake Gemini/OpenAI carried the stored key and nothing of Tandoor's. */
    private fun assertOnlyTheAiKey() {
        val requests = tandoor.aiServices.requests
        assertTrue("The REST client did not send the stored key: $requests", requests.all { it.keyMatched })
        assertTrue("A Tandoor header reached the AI service: $requests", requests.none { it.tandoorHeader })
        assertTrue("A cookie reached the AI service: $requests", requests.none { it.cookie })
        assertTrue("The Anthropic endpoint was called: ${tandoor.anthropic.requests}", tandoor.anthropic.requests.isEmpty())
    }

    /**
     * Hemma → "Fridge photo (experimental)": the experimental warning first, then a synthetic
     * photo from the gallery, until the review list shows what "the AI" found. Nothing is saved yet.
     */
    private fun fridgePhotoToTheReview(label: String) {
        PhaseSPantrySmokeTest.openHome(app)
        PhaseSPantrySmokeTest.waitForDefaultLocations(app, tandoor)
        gallery.add("fridge", Gallery.fridge())

        app.tap("Fridge photo (experimental)")
        app.waitFor(By.text("The fridge photo is experimental"))
        app.capture("phaseS-fridge-warning-$label")
        app.tap("I understand")
        app.waitUntilGone(By.text("The fridge photo is experimental"))
        app.tap(PhaseSScanSmokeTest.PICK_IMAGE)
        gallery.pickNewest(app)

        app.waitFor(By.text("Check what the AI found"), PhaseSScanSmokeTest.ML_KIT_TIMEOUT_MS)
        app.capture("phaseS-fridge-review-$label")
        assertTrue("Saved before the review: ${tandoor.pantry.entries()}", tandoor.pantry.entries().isEmpty())
    }

    /** Saves the two sure rows (the unsure "kaviar", confidence 0.3, starts skipped); no alias is learned. */
    private fun saveTheReviewedRows() {
        app.waitForNode("\"Save 2 items in the pantry\"") { it.text?.toString() == "Save 2 items in the pantry" }
        for (attempt in 1..TAP_ATTEMPTS) {
            app.clickNodeWithText("Save 2 items in the pantry")
            if (nodeGone(TAP_WAIT_MS) { it.text?.toString() == "Check what the AI found" }) break
        }
        waitForServer("the two reviewed rows in the pantry") {
            val foods = tandoor.pantry.entries().map { it.foodName.lowercase() }.sorted()
            if (foods == listOf("mjölk", "smör")) null else "entries: $foods"
        }
        assertTrue("A Tandoor alias was learned from the AI", tandoor.pantry.aliases().isEmpty())
    }

    /** Settings → AI: [provider], the fake key (stored), and [baseUrl] (a fake endpoint) as the own base URL. */
    private fun setUpProvider(provider: String, baseUrl: String) {
        app.tap("More")
        app.tap("AI (experimental)")
        app.waitFor(By.text("Service"))
        assertTrue("No $provider choice", app.clickNodeWithText(provider))
        app.waitForNode("the key status") { it.contentDescription?.toString() == "No key" }

        // Compose leaves nodes scrolled out of view out of the tree: bring the key group (field,
        // "Save key", "Paste") on screen first; the key field is then the first field.
        app.scrollTo(By.text("Paste"))
        val key = textFields().first()
        assertTrue("Key not typed", app.setNodeText(key, FakeAnthropic.KEY))
        waitUntil("Save key enabled") { app.clickNodeWithText("Save key") }
        app.waitForNode("the stored key") { it.contentDescription?.toString() == "Key saved" }
        // The field is cleared once the key is stored: the key is not on screen any more.
        assertFalse("The key is still in a field", textFields().any { it.text?.toString() == FakeAnthropic.KEY })

        // The own base URL (the last field, right above "Use the defaults") = the fake Anthropic endpoint.
        app.scrollTo(By.text("Use the defaults"))
        val baseUrlField = textFields().last()
        assertTrue("Base URL not typed", app.setNodeText(baseUrlField, baseUrl))
        waitUntil("Save enabled") { app.clickNodeWithText("Save") }
        app.waitForNode("the snackbar \"Saved\"") { it.text?.toString() == "Saved" }
    }

    /** The page's text fields on screen, in order (at least one). */
    private fun textFields(): List<AccessibilityNodeInfo> {
        val fields = app.appNodes().filter { it.className?.toString() == EDIT_TEXT }
        assertTrue("No text field on screen", fields.isNotEmpty())
        return fields
    }

    private fun waitUntil(what: String, action: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + ACTION_TIMEOUT_MS
        while (!action()) {
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError("Not possible within $ACTION_TIMEOUT_MS ms: $what")
            SystemClock.sleep(POLL_MS)
        }
    }

    private companion object {
        const val ACTION_TIMEOUT_MS = 10_000L
    }
}
