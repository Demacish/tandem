package casa.moller.tandem.smoke

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
 */
@RunWith(AndroidJUnit4::class)
class PhaseSAiSmokeTest : Phase2SmokeBase() {
    override fun servers(): List<FakeTandoor> = listOf(tandoor)

    override fun extraReport(): List<String> = listOf("--- fake Anthropic:") + tandoor.anthropic.requests.map { it.toString() }

    @Test
    fun aiSettings_claudeKeyIsStoredAndTestedThroughTheSdk() {
        logIn()
        app.tap("More")
        app.tap("AI (experimental)")
        app.waitFor(By.text("Service"))
        assertTrue("No Claude choice", app.clickNodeWithText("Claude"))
        app.waitForNode("the key field") { it.contentDescription?.toString() == "No key" }

        // The own base URL (third field: key, model, base URL) = the fake Anthropic endpoint.
        val baseUrl = textFields()[BASE_URL_FIELD]
        assertTrue("Base URL not typed", app.setNodeText(baseUrl, tandoor.baseUrl))
        waitUntil("Save enabled") { app.clickNodeWithText("Save") }
        app.waitForNode("the snackbar \"Saved\"") { it.text?.toString() == "Saved" }

        val key = textFields()[KEY_FIELD]
        assertTrue("Key not typed", app.setNodeText(key, FakeAnthropic.KEY))
        waitUntil("Save key enabled") { app.clickNodeWithText("Save key") }
        app.waitForNode("the stored key") { it.contentDescription?.toString() == "Key saved" }
        // The field is cleared once the key is stored: the key is not on screen any more.
        assertFalse("The key is still in a field", textFields().any { it.text?.toString() == FakeAnthropic.KEY })

        waitUntil("Test the key enabled") { app.clickNodeWithText("Test the key") }
        app.waitForNode("the key test's answer", SDK_TIMEOUT_MS) { it.text?.toString() == "The key works" }
        app.capture("phaseS-ai-key-works")

        val seen = tandoor.anthropic.requests.single()
        assertEquals("GET", seen.method)
        assertEquals("/v1/models/claude-opus-5-5", seen.path)
        assertTrue("The SDK did not send the stored key", seen.keyMatched)
        assertTrue("Not the Anthropic SDK (no anthropic-version header)", seen.sdkVersionHeader)
        assertFalse("A Tandoor Authorization header reached the AI service", seen.hadAuthorization)
    }

    /** The page's text fields in screen order (also those scrolled out of view). */
    private fun textFields(): List<AccessibilityNodeInfo> {
        val fields = app.appNodes().filter { it.className?.toString() == "android.widget.EditText" }
        assertEquals("Expected the key, model and base URL fields", FIELDS, fields.size)
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
        const val KEY_FIELD = 0
        const val BASE_URL_FIELD = 2
        const val FIELDS = 3
        const val SDK_TIMEOUT_MS = 30_000L
        const val ACTION_TIMEOUT_MS = 10_000L
    }
}
