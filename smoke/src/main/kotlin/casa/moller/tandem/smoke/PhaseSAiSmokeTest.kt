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
        val baseUrl = textFields().last()
        assertTrue("Base URL not typed", app.setNodeText(baseUrl, tandoor.baseUrl))
        waitUntil("Save enabled") { app.clickNodeWithText("Save") }
        app.waitForNode("the snackbar \"Saved\"") { it.text?.toString() == "Saved" }

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
