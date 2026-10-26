package casa.moller.tandem.feature.shell.accounts

import androidx.annotation.StringRes
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.language.AppLanguage
import casa.moller.tandem.feature.shell.CONNECTED_STATE
import casa.moller.tandem.feature.shell.PlaceholderShellFeatures
import casa.moller.tandem.feature.shell.R
import casa.moller.tandem.feature.shell.RecordingActions
import casa.moller.tandem.feature.shell.ShellScaffold
import casa.moller.tandem.feature.shell.navigation.AccountsKey
import casa.moller.tandem.feature.shell.navigation.MoreKey
import casa.moller.tandem.feature.shell.navigation.ShellTab
import casa.moller.tandem.feature.shell.navigationAt
import casa.moller.tandem.feature.shell.settings.FixedSettingsPages
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Phase 2 (konto lane): Mer → Konton och servrar. One TalkBack item per account, its buttons, the
 * removal dialog with the unsent-changes warning and export, demo mode, and the way there from Mer.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE_TALL_ACCOUNTS)
class AccountsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val actions = mutableListOf<AccountsAction>()
    private var addClicks = 0
    private var connectClicks = 0

    private fun string(@StringRes id: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(id, *args)

    private fun setScreen(state: AccountsUiState) {
        composeRule.setContent {
            TandemTheme {
                AccountsScreen(
                    state = state,
                    onAction = { actions += it },
                    onAddAccount = { addClicks++ },
                    onConnectTandoor = { connectClicks++ },
                    onNavigateBack = {},
                )
            }
        }
    }

    @Test
    fun everyAccountIsOneItemWithItsFacts() {
        setScreen(SAMPLE_ACCOUNTS)

        // The guest's server, space, role and unsent changes are read together, once.
        val guest = hasText("recept.exempel.se/tandoor", substring = true) and hasText("Receptklubben", substring = true)
        composeRule.onAllNodes(guest).assertCountEquals(1)
        composeRule.onAllNodes(hasText("Receptklubben", substring = true)).assertCountEquals(1)
        composeRule.onAllNodes(hasText(string(R.string.accounts_role_guest_body), substring = true)).assertCountEquals(1)
        // The active account shows the household and the role.
        val active = hasText("Familjen Exempel", substring = true) and hasText(string(R.string.accounts_role_admin), substring = true)
        composeRule.onAllNodes(active).assertCountEquals(1)
    }

    @Test
    fun switchAndRemoveAreButtonsOfTheirAccount() {
        setScreen(SAMPLE_ACCOUNTS)

        composeRule.onNode(hasText(string(R.string.accounts_switch_to, "Anna")) and hasClickAction()).performScrollTo().performClick()
        composeRule.onNode(hasText(string(R.string.accounts_remove_named, "Bo")) and hasClickAction()).performScrollTo().performClick()

        assertEquals(listOf(AccountsAction.SwitchTo("account-2"), AccountsAction.RequestRemoval("account-3")), actions)
    }

    @Test
    fun addAccountAndLogOut() {
        setScreen(SAMPLE_ACCOUNTS)

        composeRule.onNodeWithText(string(R.string.accounts_add)).performScrollTo().performClick()
        composeRule.onNodeWithText(string(R.string.shell_log_out_of, "tandoor.example.com")).performScrollTo().performClick()

        assertEquals(1, addClicks)
        assertEquals(listOf(AccountsAction.RequestRemoval("account-1")), actions)
    }

    @Test
    fun removingAnAccountWithUnsentChangesWarnsAndOffersTheExport() {
        setScreen(SAMPLE_REMOVE_PENDING)

        composeRule.onNodeWithTag(UNSENT_WARNING_TAG).assertExists()
        composeRule.onNodeWithText(string(R.string.accounts_export)).performClick()
        // A pasted token is never revoked: no checkbox, and the removal says so.
        composeRule.onNodeWithText(string(R.string.shell_logout_pasted_token_note)).assertExists()
        composeRule.onNodeWithText(string(R.string.accounts_remove_confirm)).performClick()

        assertEquals(listOf(AccountsAction.ExportPending, AccountsAction.ConfirmRemoval(revokeToken = false)), actions)
    }

    @Test
    fun loggingOutWithUnsentChangesOffersSendFirstAndRevokesByDefault() {
        setScreen(SAMPLE_LOGOUT_PENDING)

        composeRule.onNodeWithTag(UNSENT_WARNING_TAG).assertExists()
        composeRule.onNodeWithText(string(R.string.shell_logout_send_first)).performClick()
        composeRule.onNodeWithText(string(R.string.shell_logout_confirm)).performClick()

        assertEquals(listOf(AccountsAction.SyncNow, AccountsAction.ConfirmRemoval(revokeToken = true)), actions)
    }

    @Test
    fun demoModeIsNotAnAccountAndConnectsATandoor() {
        setScreen(SAMPLE_DEMO)

        composeRule.onNodeWithText(string(R.string.accounts_demo_title)).assertExists()
        composeRule.onAllNodes(hasText(string(R.string.accounts_section_active))).assertCountEquals(0)
        composeRule.onNodeWithText(string(R.string.accounts_connect)).performClick()
        composeRule.onNodeWithText(string(R.string.accounts_add)).performScrollTo().performClick()
        val switchToAnna = hasText(string(R.string.accounts_switch_to, "Anna Andersson")) and hasClickAction()
        composeRule.onNode(switchToAnna).performScrollTo().performClick()

        assertEquals(2, connectClicks)
        assertEquals(0, addClicks)
        assertEquals(listOf(AccountsAction.SwitchTo("account-1")), actions)
    }

    @Test
    fun moreOpensAccountsAndServers() {
        val navigation = navigationAt(ShellTab.MORE)
        val state = CONNECTED_STATE.copy(storedAccounts = 3, household = "Familjen Exempel")
        composeRule.setContent {
            TandemTheme {
                ShellScaffold(
                    state = state,
                    navigation = navigation,
                    actions = RecordingActions(),
                    language = AppLanguage.SYSTEM,
                    onLanguageSelected = {},
                    onConnectTandoor = {},
                    features = PlaceholderShellFeatures(state),
                    settingsPages = FixedSettingsPages(),
                    accountsPage = FixedAccountsPage(),
                )
            }
        }

        composeRule.onNodeWithText("Familjen Exempel").assertExists()
        composeRule.onNodeWithText(string(R.string.accounts_more_row)).performScrollTo().performClick()

        assertEquals(listOf(MoreKey, AccountsKey), navigation.stackOf(ShellTab.MORE))
        composeRule.onNodeWithTag(ACCOUNTS_SCREEN_TAG).assertExists()
    }
}

/** A tall phone, so the whole accounts page fits without scrolling in most tests. */
internal const val PHONE_TALL_ACCOUNTS = "w412dp-h1800dp-port-xhdpi"
