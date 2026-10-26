package casa.moller.tandem.feature.shell.accounts

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.account.SpaceRole
import casa.moller.tandem.core.ui.language.AppLanguage
import casa.moller.tandem.feature.shell.CONNECTED_STATE
import casa.moller.tandem.feature.shell.PHONE
import casa.moller.tandem.feature.shell.PHONE_LANDSCAPE
import casa.moller.tandem.feature.shell.PHONE_SV
import casa.moller.tandem.feature.shell.PlaceholderShellFeatures
import casa.moller.tandem.feature.shell.RecordingActions
import casa.moller.tandem.feature.shell.ShellScaffold
import casa.moller.tandem.feature.shell.ShellUiState
import casa.moller.tandem.feature.shell.TABLET
import casa.moller.tandem.feature.shell.TABLET_SV
import casa.moller.tandem.feature.shell.navigation.AccountsKey
import casa.moller.tandem.feature.shell.navigation.ShellTab
import casa.moller.tandem.feature.shell.navigationAt
import casa.moller.tandem.feature.shell.settings.FixedSettingsPages
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The account card in Mer with the household (an administrator by default) and three stored accounts. */
internal val HOUSEHOLD_STATE: ShellUiState =
    CONNECTED_STATE.copy(household = "Familjen Exempel", role = SpaceRole.ADMIN, storedAccounts = 3)

/** A tablet held upright. */
internal const val TABLET_PORTRAIT = "w800dp-h1280dp-port-hdpi"

/**
 * Screenshots of Mer → Konton och servrar (Phase 2, konto lane; Stitch konto_servrar): the page
 * with three accounts (a guest on a second server, a test server in safe mode), one account with
 * unsent changes, demo mode, the removal and logout dialogs, and the whole shell (Mer with the
 * household and the role, and on wide windows the page beside the More list). Phone portrait and
 * landscape, tablet in both orientations, dark, font scale 2 and Swedish.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class AccountsScreenshotTest(private val device: String) {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun accounts() = page("accounts_light", SAMPLE_ACCOUNTS)

    @Test
    fun accountsDark() = page("accounts_dark", SAMPLE_ACCOUNTS, darkTheme = true)

    @Test
    fun single() = page("accounts_single_light", SAMPLE_SINGLE)

    @Test
    fun demo() = page("accounts_demo_light", SAMPLE_DEMO)

    @Test
    fun removeWithUnsentChanges() = dialog("accounts_remove_unsent_light", SAMPLE_REMOVE_PENDING)

    @Test
    fun shellAccounts() = shell("shell_accounts_light", HOUSEHOLD_STATE, AccountsKey)

    protected fun page(name: String, state: AccountsUiState, darkTheme: Boolean = false, fontScale: Float? = null) {
        setPage(state, darkTheme, fontScale)
        composeRule.onRoot().captureRoboImage(path(name))
    }

    /** The page with its dialog open (the dialog is a window of its own). */
    @OptIn(ExperimentalRoborazziApi::class)
    protected fun dialog(name: String, state: AccountsUiState, darkTheme: Boolean = false) {
        setPage(state, darkTheme, fontScale = null)
        composeRule.waitForIdle()
        captureScreenRoboImage(path(name))
    }

    private fun setPage(state: AccountsUiState, darkTheme: Boolean, fontScale: Float?) {
        composeRule.setContent {
            TandemTheme(darkTheme = darkTheme) {
                val density = LocalDensity.current
                val scaled = if (fontScale == null) density else Density(density.density, fontScale)
                CompositionLocalProvider(LocalDensity provides scaled) {
                    FixedAccountsPage(state).Accounts({}, {}, {})
                }
            }
        }
    }

    /** The whole shell on the More tab with [pages] (list-detail on wide windows). */
    protected fun shell(name: String, state: ShellUiState, vararg pages: NavKey, darkTheme: Boolean = false) {
        val navigation = navigationAt(ShellTab.MORE, *pages)
        composeRule.setContent {
            TandemTheme(darkTheme = darkTheme) {
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
        composeRule.onRoot().captureRoboImage(path(name))
    }

    private fun path(name: String) = "$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/shell/accounts_${device}_$name.png"
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE)
class AccountsPhoneScreenshotTest : AccountsScreenshotTest("phone") {
    @Test
    @Config(qualifiers = PHONE_SV)
    fun accountsSwedish() = page("accounts_light_sv", SAMPLE_ACCOUNTS)

    @Test
    @Config(qualifiers = PHONE_SV)
    fun demoSwedish() = page("accounts_demo_light_sv", SAMPLE_DEMO)

    @Test
    fun accountsFontScale2() = page("accounts_font_scale_2_light", SAMPLE_ACCOUNTS, fontScale = 2f)

    @Test
    @Config(qualifiers = PHONE_SV)
    fun removeWithUnsentChangesSwedish() = dialog("accounts_remove_unsent_light_sv", SAMPLE_REMOVE_PENDING)

    @Test
    fun logoutWithUnsentChanges() = dialog("accounts_logout_unsent_light", SAMPLE_LOGOUT_PENDING)

    @Test
    @Config(qualifiers = PHONE_SV)
    fun logoutWithUnsentChangesDarkSwedish() = dialog("accounts_logout_unsent_dark_sv", SAMPLE_LOGOUT_PENDING, darkTheme = true)

    /** Mer's account card with the household and a guest's role (#42). */
    @Test
    fun moreGuest() = shell("shell_more_guest_light", HOUSEHOLD_STATE.copy(role = SpaceRole.GUEST, storedAccounts = 2))

    @Test
    @Config(qualifiers = PHONE_SV)
    fun moreMemberSwedish() = shell("shell_more_member_light_sv", HOUSEHOLD_STATE.copy(role = SpaceRole.USER, storedAccounts = 1))
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE_LANDSCAPE)
class AccountsPhoneLandscapeScreenshotTest : AccountsScreenshotTest("phone_landscape")

/** The tablet's list-detail layout: the page beside the More list. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = TABLET)
class AccountsTabletScreenshotTest : AccountsScreenshotTest("tablet") {
    @Test
    @Config(qualifiers = TABLET_SV)
    fun shellAccountsDarkSwedish() = shell("shell_accounts_dark_sv", HOUSEHOLD_STATE, AccountsKey, darkTheme = true)
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = TABLET_PORTRAIT)
class AccountsTabletPortraitScreenshotTest : AccountsScreenshotTest("tablet_portrait")
