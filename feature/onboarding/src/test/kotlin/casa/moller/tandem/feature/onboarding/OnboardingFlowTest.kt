package casa.moller.tandem.feature.onboarding

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.R as DesignR
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.TlsErrorKind
import casa.moller.tandem.core.ui.language.AppLanguage
import casa.moller.tandem.feature.onboarding.ui.PASSWORD_FIELD_TAG
import casa.moller.tandem.feature.onboarding.ui.SERVER_URL_FIELD_TAG
import casa.moller.tandem.feature.onboarding.ui.TOKEN_FIELD_TAG
import casa.moller.tandem.feature.onboarding.ui.USERNAME_FIELD_TAG
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The intro end to end on Robolectric, with the real ViewModel and fake repositories. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class OnboardingFlowTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val accounts = FakeAccountRepository()
    private val session = FakeSessionRepository()
    private val language = FakeLanguageController()
    private var finishedCalls = 0
    private var demoCalls = 0

    private fun setContent(startAtServerStep: Boolean = false) {
        val vm = viewModel(accounts, session)
        composeRule.setContent {
            TandemTheme {
                OnboardingFlow(
                    onFinished = { finishedCalls++ },
                    onStartDemo = { demoCalls++ },
                    startAtServerStep = startAtServerStep,
                    viewModel = vm,
                    languageController = language,
                )
            }
        }
    }

    private fun string(@StringRes id: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(id, *args)

    private fun button(@StringRes id: Int): SemanticsNodeInteraction = composeRule.onNode(hasText(string(id)) and hasClickAction())

    private fun SemanticsNodeInteraction.scrollAndClick() {
        performScrollTo()
        performClick()
    }

    private fun connectTo(url: String = "tandoor.example.com") {
        button(R.string.onboarding_get_started).scrollAndClick()
        composeRule.onNodeWithTag(SERVER_URL_FIELD_TAG).performTextInput(url)
        button(R.string.onboarding_connect).scrollAndClick()
    }

    private fun logIn(username: String = "anna", password: String = "secret") {
        composeRule.onNodeWithTag(USERNAME_FIELD_TAG).performScrollTo().performTextInput(username)
        composeRule.onNodeWithTag(PASSWORD_FIELD_TAG).performScrollTo().performTextInput(password)
        button(R.string.onboarding_log_in).scrollAndClick()
    }

    @Test
    fun welcomeShowsBothEntryPoints() {
        setContent()
        composeRule.onNodeWithText(string(R.string.onboarding_welcome_title)).assertIsDisplayed()
        button(R.string.onboarding_get_started).performScrollTo().assertIsDisplayed()
        button(R.string.onboarding_try_demo).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun demoButtonStartsDemoMode() {
        setContent()
        button(R.string.onboarding_try_demo).scrollAndClick()
        composeRule.waitForIdle()
        assertEquals(1, session.demoStarts)
        assertEquals(1, demoCalls)
        assertEquals(0, finishedCalls)
    }

    @Test
    fun fullPasswordLoginShowsTheServerVersionAndFinishes() {
        setContent()
        connectTo()
        composeRule.onNodeWithText(string(R.string.onboarding_server_connected, "tandoor.example.com"))
            .performScrollTo()
            .assertIsDisplayed()

        logIn()

        composeRule.onNodeWithText(string(R.string.onboarding_connected_summary, "tandoor.example.com", "2.6.13"))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.onboarding_connected_account, "Anna")).assertIsDisplayed()
        button(R.string.onboarding_continue).scrollAndClick()
        assertEquals(1, finishedCalls)
        assertEquals(listOf("tandoor.example.com"), accounts.probes)
        assertEquals(1, accounts.logins.size)
    }

    @Test
    fun wrongPasswordShowsAnErrorAndStaysOnLogin() {
        accounts.loginResult = Outcome.Failure(TandemError.BadRequest())
        setContent()
        connectTo()
        logIn(password = "wrong")

        composeRule.onNodeWithText(string(R.string.onboarding_error_wrong_credentials_title))
            .performScrollTo()
            .assertIsDisplayed()
        assertEquals(0, finishedCalls)
    }

    @Test
    fun pastedTokenLogin() {
        setContent()
        connectTo()
        button(R.string.onboarding_use_token).scrollAndClick()
        composeRule.onNodeWithText(string(R.string.onboarding_token_howto_title)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(TOKEN_FIELD_TAG).performScrollTo().performTextInput("tda_pasted")
        button(R.string.onboarding_log_in).scrollAndClick()

        composeRule.onNodeWithText(string(R.string.onboarding_connected_title)).performScrollTo().assertIsDisplayed()
        assertEquals("tda_pasted", accounts.tokenLogins.single().second)
    }

    @Test
    fun untestedVersionCanContinueInSafeMode() {
        accounts.probeResult = Outcome.Success(NEWER_SERVER)
        setContent()
        connectTo()

        composeRule.onNodeWithText(string(R.string.onboarding_untested_title, "2.7.1"))
            .performScrollTo()
            .assertIsDisplayed()
        button(R.string.onboarding_continue_safe_mode).scrollAndClick()

        composeRule.onNodeWithText(string(R.string.onboarding_login_body)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun tlsErrorExplainsHowToInstallACertificate() {
        accounts.probeResult = Outcome.Failure(TandemError.TlsError(TlsErrorKind.UNTRUSTED_CERTIFICATE))
        setContent()
        connectTo()

        composeRule.onNodeWithText(string(R.string.onboarding_hint_tls)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun backFromServerStepReturnsToWelcome() {
        setContent()
        button(R.string.onboarding_get_started).scrollAndClick()
        composeRule.onNodeWithContentDescription(string(DesignR.string.tandem_action_back)).performClick()
        composeRule.onNodeWithText(string(R.string.onboarding_welcome_title)).assertIsDisplayed()
    }

    @Test
    fun startAtServerStepSkipsWelcome() {
        setContent(startAtServerStep = true)
        composeRule.onNodeWithText(string(R.string.onboarding_server_title)).assertIsDisplayed()
    }

    @Test
    fun languagePickerSelectsSwedish() {
        setContent()
        composeRule.onNodeWithContentDescription(
            string(R.string.onboarding_language_button, string(R.string.onboarding_language_system)),
        ).performClick()
        composeRule.onNodeWithText(string(R.string.onboarding_language_swedish)).performClick()
        assertEquals(listOf(AppLanguage.SWEDISH), language.selected)
    }

    @Test
    @Config(qualifiers = "sv-w411dp-h891dp")
    fun swedishTexts() {
        setContent()
        composeRule.onNodeWithText("Välkommen till Tandem").assertIsDisplayed()
        composeRule.onNode(hasText("Kom igång") and hasClickAction()).performScrollTo().assertIsDisplayed()
        composeRule.onNode(hasText("Prova demoläget") and hasClickAction()).performScrollTo().assertIsDisplayed()
    }
}
