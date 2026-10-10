package casa.moller.tandem.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.TlsErrorKind
import casa.moller.tandem.core.ui.language.AppLanguage
import casa.moller.tandem.feature.onboarding.ui.OnboardingScreen
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import kotlin.time.Duration.Companion.hours
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Records the intro steps (plan 6.5: phone and tablet, light and dark, Swedish, font scale 2).
 * Phase 0 only records; CI uploads build/outputs/roborazzi as the "screenshots" artifact.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class OnboardingScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val loginState = OnboardingUiState(
        step = OnboardingStep.LOGIN,
        serverUrl = "tandoor.example.com",
        server = SUPPORTED_SERVER,
        username = "anna",
        password = "secret",
    )

    @Test
    fun welcome() = capture("welcome_light", OnboardingUiState())

    @Test
    fun welcomeDark() = capture("welcome_dark", OnboardingUiState(), darkTheme = true)

    @Test
    fun serverEmpty() = capture("server_light", OnboardingUiState(step = OnboardingStep.SERVER))

    @Test
    fun serverCertificateError() = capture(
        "server_tls_error_light",
        OnboardingUiState(
            step = OnboardingStep.SERVER,
            serverUrl = "tandoor.home.arpa",
            serverCheck = ServerCheck.Failed(TandemError.TlsError(TlsErrorKind.UNTRUSTED_CERTIFICATE)),
        ),
    )

    @Test
    fun serverBlockedDark() = capture(
        "server_blocked_dark",
        OnboardingUiState(
            step = OnboardingStep.SERVER,
            serverUrl = "tandoor.example.com",
            serverCheck = ServerCheck.Failed(TandemError.Blocked),
        ),
        darkTheme = true,
    )

    @Test
    fun serverUntestedVersion() = capture(
        "server_untested_version_light",
        OnboardingUiState(
            step = OnboardingStep.SERVER,
            serverUrl = "tandoor.example.com",
            serverCheck = ServerCheck.NeedsConfirmation(NEWER_SERVER),
        ),
    )

    @Test
    fun serverTooOld() = capture(
        "server_too_old_light",
        OnboardingUiState(
            step = OnboardingStep.SERVER,
            serverUrl = "old.example.com",
            serverCheck = ServerCheck.TooOld(OLD_SERVER),
        ),
    )

    @Test
    fun login() = capture("login_light", loginState)

    @Test
    fun loginThrottled() = capture(
        "login_throttled_light",
        loginState.copy(loginError = LoginError.Throttled(retryAfter = 7.hours)),
    )

    @Test
    fun loginToken() = capture(
        "login_token_light",
        loginState.copy(loginMethod = LoginMethod.TOKEN, token = "tda_0123"),
    )

    @Test
    fun connectedSafeMode() = capture(
        "connected_safe_mode_light",
        OnboardingUiState(
            step = OnboardingStep.CONNECTED,
            connected = ConnectedSummary("tandoor.example.com", "2.7.1", "Anna", safeMode = true),
        ),
    )

    @Test
    fun loginFontScale2() = capture("login_font_scale_2_light", loginState, fontScale = 2f)

    @Test
    @Config(qualifiers = "sv-w411dp-h891dp-xhdpi")
    fun welcomeSwedish() = capture("welcome_light_sv", OnboardingUiState())

    @Test
    @Config(qualifiers = "sv-w411dp-h891dp-xhdpi")
    fun loginWrongPasswordSwedish() = capture(
        "login_wrong_password_light_sv",
        loginState.copy(loginError = LoginError.WrongCredentials),
    )

    @Test
    @Config(qualifiers = "w1280dp-h800dp-mdpi")
    fun welcomeTabletLandscape() = capture("welcome_tablet_landscape_light", OnboardingUiState())

    @Test
    @Config(qualifiers = "w1280dp-h800dp-mdpi")
    fun loginTabletLandscapeDark() = capture("login_tablet_landscape_dark", loginState, darkTheme = true)

    @Test
    @Config(qualifiers = "w700dp-h1000dp-mdpi")
    fun serverMediumWidth() = capture(
        "server_medium_light",
        OnboardingUiState(step = OnboardingStep.SERVER, serverCheck = ServerCheck.Failed(TandemError.Timeout)),
    )

    private fun capture(name: String, state: OnboardingUiState, darkTheme: Boolean = false, fontScale: Float? = null) {
        composeRule.setContent {
            WithFontScale(fontScale) {
                TandemTheme(darkTheme = darkTheme) {
                    OnboardingScreen(
                        state = state,
                        actions = NoOpActions,
                        language = AppLanguage.SYSTEM,
                        onLanguageSelected = {},
                        onFinished = {},
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/onboarding/$name.png")
    }

    @Composable
    private fun WithFontScale(fontScale: Float?, content: @Composable () -> Unit) {
        if (fontScale == null) {
            content()
        } else {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), content = content)
        }
    }
}
