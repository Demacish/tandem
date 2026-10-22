package casa.moller.tandem.smoke

import android.view.accessibility.AccessibilityEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeoutException
import java.util.regex.Pattern
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
 * Release smoke test (docs/BYGGE.md "Release-röktest"): the R8-minified app on a real Android
 * runtime, against [FakeTandoor]. Covers what JVM tests cannot: keep rules for Retrofit,
 * kotlinx.serialization and Hilt, the Tink/Keystore-encrypted token, and Navigation 3 saving
 * and restoring its back stack.
 *
 * v0.0.1 crashed on "Log in" because R8 had turned every Retrofit call into dead code; the
 * password test below is the regression test for that.
 */
@RunWith(AndroidJUnit4::class)
class ReleaseSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = TandemApp(UiDevice.getInstance(instrumentation))

    @get:Rule(order = 0)
    val tandoor = FakeTandoor(instrumentation.context.assets)

    /** On failure: screenshot, window hierarchy and the server's request log. */
    @get:Rule(order = 1)
    val artifacts = object : TestWatcher() {
        override fun failed(e: Throwable, description: Description) {
            val name = "FAILED-${description.methodName}"
            app.capture(name)
            runCatching {
                File(TandemApp.outputDir(), "$name-requests.txt").writeText(tandoor.requests.joinToString("\n"))
            }
        }
    }

    @Before
    fun startFresh() {
        app.clearData()
    }

    @Test
    fun passwordLogin_restoresSessionAfterProcessDeathAndColdStart_thenLogsOut() {
        app.launch()
        startIntro()

        // Step 3: username and password.
        app.waitFor(By.text("Use your Tandoor username and password."))
        app.type(0, "smoke-user")
        app.type(1, "smoke-password")
        app.tap("Log in")

        finishIntroAndOpenMore()
        assertEquals(
            listOf("POST /api-token-auth/", "POST /api/access-token/"),
            tandoor.requests.filter { it.startsWith("POST") },
        )
        // The display name comes from GET /api/user-preference/ (fixture 12) and differs from the
        // typed user name; a failed user read would silently fall back to "smoke-user". The space
        // name comes from GET /api/space/current/ (fixture 13). Both prove response decoding of
        // the password path (List<UserPreferenceDto>, UserDto, SpaceDto) under R8.
        app.waitFor(By.text(DISPLAY_NAME))
        app.waitFor(By.text(SPACE_NAME))
        assertFalse("The typed user name must be replaced by the server's", app.device.hasObject(By.text("smoke-user")))
        app.capture("password-more")

        // Navigation 3 back stack across process death: More -> About is saved when the app goes
        // to the background and restored (deserialized) when it comes back.
        app.tap("About Tandem")
        app.waitFor(By.text(ABOUT_TEXT))
        app.killInBackground()
        app.launch()
        app.waitFor(By.text(ABOUT_TEXT))

        // A Navigation 3 key with an argument: LicenseTextKey(BundledLicense) is restored through
        // its Companion, $$serializer and the enum serializer (data objects use INSTANCE). Also
        // proves that assets/licenses is in the release APK (no "could not be loaded").
        app.tap("Licenses")
        app.tap("DM Sans")
        app.waitFor(By.textStartsWith(DM_SANS_LICENSE_TEXT))
        app.killInBackground()
        app.launch()
        app.waitFor(By.textStartsWith(DM_SANS_LICENSE_TEXT))
        app.capture("license-restored-after-process-death")
        app.device.pressBack()
        app.waitFor(By.text("Fonts and icons"))
        app.device.pressBack()
        app.waitFor(By.text(ABOUT_TEXT))

        app.device.pressBack()
        // More keeps its scroll position, and About sits below the settings rows (0.2.0): the
        // account card with the version may be above or below.
        app.findInList(By.text(VERSION_TEXT))
        app.capture("password-restored-after-process-death")

        // Cold start: the account and the Tink-encrypted device token are read back from storage.
        app.forceStop()
        app.launch()
        app.waitForClickable("More")
        assertFalse("A logged-in cold start must not show the intro", app.device.hasObject(By.text(WELCOME_TEXT)))
        app.tap("More")
        app.scrollTo(By.text(VERSION_TEXT))
        app.capture("password-after-cold-start")

        // Logout revokes this device's token (the dialog's default for a token Tandem created).
        // "Logged out." needs the DELETE's 204 to be handled (suspend fun returning Unit); if
        // that path broke under R8 the app would say the token could not be revoked.
        logOut(expectedToast = LOGGED_OUT_TOAST)
        assertTrue(
            "Logout must revoke the device token: ${tandoor.requests}",
            "DELETE /api/access-token/2/" in tandoor.requests,
        )
    }

    @Test
    fun pastedToken_logsIn_thenLogsOut() {
        app.launch()
        startIntro()

        app.tap("Paste a token instead")
        app.waitFor(By.text("Paste an API token from your Tandoor account."))
        app.type(0, FakeTandoor.DEVICE_TOKEN)
        app.tap("Log in")

        finishIntroAndOpenMore()
        assertFalse(
            "A pasted token never uses the password login: ${tandoor.requests}",
            tandoor.requests.any { it.startsWith("POST /api-token-auth/") },
        )
        // The pasted token is checked by reading the space and the user, and matched in the token
        // list (its id and expiry). A failed list read would not fail the login, so check that
        // every call reached the server, and that the account shows the decoded user and space.
        for (call in listOf("GET /api/space/current/", "GET /api/user-preference/", "GET /api/access-token/")) {
            assertTrue("$call missing: ${tandoor.requests}", call in tandoor.requests)
        }
        app.waitFor(By.text(DISPLAY_NAME))
        app.waitFor(By.text(SPACE_NAME))
        app.capture("token-more")

        // A pasted token belongs to the user: logout keeps it on the server.
        logOut(expectedToast = LOGGED_OUT_PASTED_TOKEN_TOAST)
        assertFalse(
            "A pasted token must not be revoked: ${tandoor.requests}",
            tandoor.requests.any { it.startsWith("DELETE") },
        )
    }

    /** Welcome -> server address -> Connect, up to the login step. */
    private fun startIntro() {
        app.waitFor(By.text(WELCOME_TEXT))
        app.tap("Get started")
        app.waitFor(By.text("Connect to your Tandoor"))
        app.type(0, tandoor.address)
        app.tap("Connect")
        // Step 3 shows the probed server and its version. If the probe failed instead, say why.
        app.waitFor(By.text(Pattern.compile("Paste a token instead|Show details")))
        if (app.device.hasObject(By.text("Show details"))) {
            app.tap("Show details")
            app.capture("probe-failed")
            val texts = app.device.findObjects(By.clazz("android.widget.TextView")).joinToString(" | ") { it.text.orEmpty() }
            throw AssertionError("The probe of ${tandoor.address} failed: $texts; server saw ${tandoor.requests}")
        }
        app.waitFor(By.text(VERSION_TEXT))
    }

    /** "You're connected" -> Continue -> the shell's More tab shows the server version. */
    private fun finishIntroAndOpenMore() {
        app.waitFor(By.text("You’re connected"))
        app.tap("Continue")
        app.waitForClickable("More")
        app.tap("More")
        app.scrollTo(By.text(VERSION_TEXT))
    }

    /**
     * Logs out from More and waits for the result toast. App toasts are announced to
     * accessibility (and so to UiAutomation) as notification state events carrying their text.
     * The pasted-token toast is how the release smoke test found that a pasted-token logout only
     * said "Logged out." (0.0.2).
     */
    private fun logOut(expectedToast: String) {
        app.scrollTo(By.textStartsWith("Log out of")).click()
        app.waitFor(By.text("Log out?"))
        val toasts = CopyOnWriteArrayList<String>()
        try {
            instrumentation.uiAutomation.executeAndWaitForEvent(
                { app.tap("Log out") },
                { event ->
                    // Android 16 announces a toast as [message, app name].
                    val texts = event.text.map { it.toString() }
                    if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) toasts += texts.joinToString(" | ")
                    event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED && expectedToast in texts
                },
                TOAST_TIMEOUT_MS,
            )
        } catch (e: TimeoutException) {
            throw AssertionError("No toast \"$expectedToast\" after logout; toasts seen: $toasts; server saw ${tandoor.requests}", e)
        }
        app.waitFor(By.text(Pattern.compile("$WELCOME_TEXT|Connect to your Tandoor")))
        app.capture("logged-out")
    }

    private companion object {
        const val WELCOME_TEXT = "Welcome to Tandem"
        const val VERSION_TEXT = "Tandoor 2.6.13"
        const val ABOUT_TEXT = "Not affiliated with the Tandoor project"

        /** display_name in fixture 12 (the typed user name is "smoke-user"). */
        const val DISPLAY_NAME = "contract-admin"

        /** name in fixture 13. */
        const val SPACE_NAME = "Contract Space"

        /** First line of core:designsystem's assets/licenses/OFL-DMSans.txt. */
        const val DM_SANS_LICENSE_TEXT = "Copyright 2014 The DM Sans Project Authors"

        /** shell_logged_out and shell_logged_out_pasted_token (feature/shell, English). */
        const val LOGGED_OUT_TOAST = "Logged out."
        const val LOGGED_OUT_PASTED_TOKEN_TOAST = "Logged out on this device. The pasted token is still valid; " +
            "delete it in Tandoor under Settings → API if you no longer need it."

        const val TOAST_TIMEOUT_MS = 10_000L
    }
}
