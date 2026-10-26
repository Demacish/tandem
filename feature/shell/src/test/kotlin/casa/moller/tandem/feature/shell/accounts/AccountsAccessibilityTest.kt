package casa.moller.tandem.feature.shell.accounts

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.account.SpaceRole
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.core.ui.language.AppLanguage
import casa.moller.tandem.feature.shell.PlaceholderShellFeatures
import casa.moller.tandem.feature.shell.RecordingActions
import casa.moller.tandem.feature.shell.ShellScaffold
import casa.moller.tandem.feature.shell.ShellUiState
import casa.moller.tandem.feature.shell.TABLET
import casa.moller.tandem.feature.shell.navigation.AccountsKey
import casa.moller.tandem.feature.shell.navigation.ShellTab
import casa.moller.tandem.feature.shell.navigationAt
import casa.moller.tandem.feature.shell.settings.FixedSettingsPages
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ATF accessibility checks (core:testing `assertAccessible`, plan 5.6) of Mer → Konton och servrar
 * (Phase 2, konto lane): every state of the page and its dialogs, dark, font scale 2, Mer with the
 * household and a guest, and the tablet's list-detail layout.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_TALL_ACCOUNTS)
class AccountsAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun accounts() = check(SAMPLE_ACCOUNTS)

    @Test
    fun accountsDark() = check(SAMPLE_ACCOUNTS, darkTheme = true)

    @Test
    fun accountsFontScale2() = check(SAMPLE_ACCOUNTS, fontScale = 2f)

    @Test
    fun single() = check(SAMPLE_SINGLE)

    @Test
    fun demo() = check(SAMPLE_DEMO)

    @Test
    fun demoDark() = check(SAMPLE_DEMO, darkTheme = true)

    @Test
    fun removeWithUnsentChanges() = check(SAMPLE_REMOVE_PENDING)

    @Test
    fun removeWithUnsentChangesDark() = check(SAMPLE_REMOVE_PENDING, darkTheme = true)

    @Test
    fun logoutWithUnsentChanges() = check(SAMPLE_LOGOUT_PENDING)

    @Test
    fun removeWithoutUnsentChanges() = check(SAMPLE_ACCOUNTS.copy(removal = RemovalPrompt(SAMPLE_TEST_SERVER)))

    @Test
    fun moreWithHouseholdAndGuest() = checkShell(HOUSEHOLD_STATE.copy(role = SpaceRole.GUEST))

    @Test
    fun moreWithHouseholdDarkFontScale2() = checkShell(HOUSEHOLD_STATE, darkTheme = true, fontScale = 2f)

    @Test
    @Config(qualifiers = TABLET)
    fun tabletBesideMore() = checkShell(HOUSEHOLD_STATE, AccountsKey)

    @Test
    @Config(qualifiers = TABLET_PORTRAIT)
    fun tabletPortraitBesideMoreDark() = checkShell(HOUSEHOLD_STATE, AccountsKey, darkTheme = true)

    private fun check(state: AccountsUiState, darkTheme: Boolean = false, fontScale: Float = 1f) {
        composeRule.setContent {
            TandemTheme(darkTheme = darkTheme) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    FixedAccountsPage(state).Accounts({}, {}, {})
                }
            }
        }
        composeRule.assertAccessible()
    }

    private fun checkShell(state: ShellUiState, vararg pages: NavKey, darkTheme: Boolean = false, fontScale: Float = 1f) {
        val navigation = navigationAt(ShellTab.MORE, *pages)
        composeRule.setContent {
            TandemTheme(darkTheme = darkTheme) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
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
        }
        composeRule.assertAccessible()
    }
}
