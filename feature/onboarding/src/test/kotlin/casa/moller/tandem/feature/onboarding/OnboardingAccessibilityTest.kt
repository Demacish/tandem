package casa.moller.tandem.feature.onboarding

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.TlsErrorKind
import casa.moller.tandem.core.model.sync.SyncPhase
import casa.moller.tandem.core.model.sync.SyncProgress
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.core.ui.language.AppLanguage
import casa.moller.tandem.feature.onboarding.ui.OnboardingScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val PHONE = "w412dp-h915dp-port-xhdpi"
private const val TABLET = "w1280dp-h800dp-land-mdpi"

/**
 * ATF accessibility checks (core:testing `assertAccessible`, plan 5.6 and Fas 1 "Klart när") of
 * the intro (Phase 0 screens, added by the 0.2.0 integration lane, which closes the settings
 * lane's open point): welcome, server step with errors, login (password, token, wrong password),
 * connected in safe mode, the first sync running and failed, the battery tip; light and dark,
 * font scale 2 and the tablet.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class OnboardingAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val login = OnboardingUiState(
        step = OnboardingStep.LOGIN,
        serverUrl = "tandoor.example.com",
        server = SUPPORTED_SERVER,
        username = "anna",
        password = "secret",
    )
    private val connected = ConnectedSummary("tandoor.example.com", "2.6.13", "Anna", safeMode = false)
    private val firstSync = OnboardingUiState(
        step = OnboardingStep.FIRST_SYNC,
        connected = connected,
        firstSync = FirstSyncState(progress = SyncProgress(SyncPhase.RECIPE_INDEX, done = 214, total = 312), recipeCount = 312),
    )

    @Test
    fun welcome() = check(OnboardingUiState())

    @Test
    fun welcomeDark() = check(OnboardingUiState(), darkTheme = true)

    @Test
    fun serverCertificateError() = check(
        OnboardingUiState(
            step = OnboardingStep.SERVER,
            serverUrl = "tandoor.home.arpa",
            serverCheck = ServerCheck.Failed(TandemError.TlsError(TlsErrorKind.UNTRUSTED_CERTIFICATE)),
        ),
    )

    @Test
    fun serverUntestedVersionDark() = check(
        OnboardingUiState(
            step = OnboardingStep.SERVER,
            serverUrl = "tandoor.example.com",
            serverCheck = ServerCheck.NeedsConfirmation(NEWER_SERVER),
        ),
        darkTheme = true,
    )

    @Test
    fun serverTooOld() = check(
        OnboardingUiState(
            step = OnboardingStep.SERVER,
            serverUrl = "old.example.com",
            serverCheck = ServerCheck.TooOld(OLD_SERVER),
        ),
    )

    @Test
    fun login() = check(login)

    @Test
    fun loginWrongPasswordDark() = check(login.copy(loginError = LoginError.WrongCredentials), darkTheme = true)

    @Test
    fun loginPasswordUnavailable() = check(login.copy(loginError = LoginError.PasswordLoginUnavailable))

    @Test
    fun loginToken() = check(login.copy(loginMethod = LoginMethod.TOKEN, token = "tda_0123"))

    @Test
    fun loginFontScale2() = check(login, fontScale = 2f)

    @Test
    fun connectedSafeMode() = check(
        OnboardingUiState(step = OnboardingStep.CONNECTED, connected = connected.copy(serverVersion = "2.7.1", safeMode = true)),
    )

    @Test
    fun firstSyncRunning() = check(firstSync)

    @Test
    fun firstSyncFailedDark() = check(
        firstSync.copy(firstSync = firstSync.firstSync.copy(error = TandemError.Timeout)),
        darkTheme = true,
    )

    @Test
    fun batteryTip() = check(OnboardingUiState(step = OnboardingStep.BATTERY_TIP, connected = connected))

    @Test
    @Config(qualifiers = TABLET)
    fun loginTablet() = check(login)

    private fun check(state: OnboardingUiState, darkTheme: Boolean = false, fontScale: Float = 1f) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale * density.fontScale)) {
                TandemTheme(darkTheme = darkTheme) {
                    OnboardingScreen(state = state, actions = NoOpActions, language = AppLanguage.SYSTEM, onLanguageSelected = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.assertAccessible()
    }
}
