package casa.moller.tandem.feature.shell.push

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.language.AppLanguage
import casa.moller.tandem.feature.shell.CONNECTED_STATE
import casa.moller.tandem.feature.shell.PlaceholderShellFeatures
import casa.moller.tandem.feature.shell.R
import casa.moller.tandem.feature.shell.RecordingActions
import casa.moller.tandem.feature.shell.ShellScaffold
import casa.moller.tandem.feature.shell.navigation.FastPushSettingsKey
import casa.moller.tandem.feature.shell.navigation.ShellTab
import casa.moller.tandem.feature.shell.navigation.SyncSettingsKey
import casa.moller.tandem.feature.shell.navigationAt
import casa.moller.tandem.feature.shell.settings.FixedSettingsPages
import casa.moller.tandem.feature.shell.settings.PHONE_TALL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Fas 4 (push lane): Snabb push's controls send the right actions, the states show what they
 * should (and nothing else), the status is one TalkBack item, and the way there from Synk och lagring.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE_TALL)
class FastPushScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val actions = mutableListOf<FastPushAction>()
    private var permissionsOpened = 0

    private fun string(@StringRes id: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(id, *args)

    private fun setScreen(state: FastPushUiState) {
        composeRule.setContent {
            TandemTheme {
                FastPushScreen(state = state, onAction = { actions += it }, onNavigateBack = {}, onOpenPermissions = { permissionsOpened++ })
            }
        }
    }

    @Test
    fun typingPickingAndTurningOn() {
        setScreen(FAST_PUSH_OFF_FILLED)
        composeRule.onNodeWithTag(FAST_PUSH_URL_TAG).performScrollTo().performTextInput("x")
        composeRule.onNode(hasText("NextPush") and hasClickAction()).performScrollTo().performClick()
        composeRule.onNodeWithText(string(R.string.fast_push_enable)).performScrollTo().assertIsEnabled().performClick()

        assertTrue(actions.first() is FastPushAction.UrlChanged)
        assertEquals(FastPushAction.SelectDistributor("org.unifiedpush.distributor.nextpush"), actions[1])
        assertEquals(FastPushAction.Enable, actions.last())
        composeRule.onNodeWithText(string(R.string.fast_push_disable)).assertDoesNotExist()
    }

    @Test
    fun turningOnWaitsForAnAddress() {
        setScreen(FAST_PUSH_OFF)
        composeRule.onNodeWithText(string(R.string.fast_push_enable)).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun withoutADistributorTheScreenSaysWhatToInstall() {
        setScreen(FAST_PUSH_NO_DISTRIBUTOR)
        composeRule.onNodeWithText(string(R.string.fast_push_distributor_none_body), substring = true).assertExists()
        composeRule.onNodeWithText(string(R.string.fast_push_distributor_refresh)).performScrollTo().performClick()
        assertEquals(listOf<FastPushAction>(FastPushAction.Refresh), actions)
    }

    @Test
    fun activeOffersTheTestAndTurningOffAndLocksTheAddress() {
        setScreen(FAST_PUSH_ACTIVE)
        // The status is one item: "On" with how often the service checks and the last wake-up.
        composeRule.onNode(hasText(string(R.string.fast_push_status_active)) and hasText("45", substring = true)).assertExists()
        composeRule.onNodeWithText(string(R.string.fast_push_send_test)).performScrollTo().performClick()
        composeRule.onNodeWithText(string(R.string.fast_push_disable)).performScrollTo().performClick()
        composeRule.onNodeWithText(string(R.string.fast_push_enable)).assertDoesNotExist()
        composeRule.onNodeWithTag(FAST_PUSH_URL_TAG).assertIsNotEnabled()
        assertEquals(listOf(FastPushAction.SendTest, FastPushAction.Disable), actions)
    }

    @Test
    fun blockedNotificationsPointToThePermissions() {
        setScreen(FAST_PUSH_ACTIVE_BLOCKED)
        composeRule.onNodeWithText(string(R.string.fast_push_open_permissions)).performScrollTo().performClick()
        assertEquals(1, permissionsOpened)
    }

    @Test
    fun aFailureIsExplainedWithTryAgain() {
        setScreen(FAST_PUSH_FAILED)
        composeRule.onNodeWithText(string(R.string.fast_push_error_other_household), substring = true).assertExists()
        composeRule.onNodeWithText(string(R.string.fast_push_try_again)).performScrollTo().performClick()
        assertEquals(listOf<FastPushAction>(FastPushAction.Enable), actions)
    }

    @Test
    fun demoAndGuestsOnlyExplain() {
        setScreen(FAST_PUSH_DEMO)
        composeRule.onNodeWithText(string(R.string.fast_push_demo)).assertExists()
        composeRule.onNodeWithText(string(R.string.fast_push_enable)).assertDoesNotExist()
        composeRule.onNodeWithTag(FAST_PUSH_URL_TAG).assertDoesNotExist()
    }

    @Test
    fun theSyncPageOpensFastPush() {
        val navigation = navigationAt(ShellTab.MORE, SyncSettingsKey)
        val page = FixedFastPushPage(FAST_PUSH_ACTIVE)
        composeRule.setContent {
            TandemTheme {
                ShellScaffold(
                    state = CONNECTED_STATE,
                    navigation = navigation,
                    actions = RecordingActions(),
                    language = AppLanguage.SYSTEM,
                    onLanguageSelected = {},
                    onConnectTandoor = {},
                    features = PlaceholderShellFeatures(CONNECTED_STATE),
                    settingsPages = FixedSettingsPages(),
                    fastPushPage = page,
                )
            }
        }
        composeRule.onNodeWithText(string(R.string.fast_push_row)).performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(FastPushSettingsKey, navigation.stackOf(ShellTab.MORE).last())
        composeRule.onNodeWithText(string(R.string.fast_push_send_test)).assertExists()
    }
}
