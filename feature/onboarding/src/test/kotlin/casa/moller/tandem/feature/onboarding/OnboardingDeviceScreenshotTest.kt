package casa.moller.tandem.feature.onboarding

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.ui.language.AppLanguage
import casa.moller.tandem.feature.onboarding.ui.OnboardingScreen
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Compact phone (Pixel 9 class, portrait) and expanded tablet (Galaxy Tab S10+ class, landscape). */
private const val PHONE = "w412dp-h915dp-port-xhdpi"
private const val TABLET = "w1280dp-h800dp-land-hdpi"

/**
 * Intro steps 1-3 and the connection error on the two reference devices of Fas 0 "Klart när" #2
 * (compact phone, expanded tablet), light and dark, and the welcome step in Swedish. Complements
 * [OnboardingScreenshotTest], which covers the other states, font scale 2 and medium width.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class OnboardingDeviceScreenshotTest(private val device: String) {

    @get:Rule
    val composeRule = createComposeRule()

    private val serverState = OnboardingUiState(step = OnboardingStep.SERVER)
    private val connectionErrorState = OnboardingUiState(
        step = OnboardingStep.SERVER,
        serverUrl = "recept.exempel.se",
        serverCheck = ServerCheck.Failed(TandemError.UnknownHost),
    )
    private val loginState = OnboardingUiState(
        step = OnboardingStep.LOGIN,
        serverUrl = "tandoor.example.com",
        server = SUPPORTED_SERVER,
        username = "anna",
    )

    @Test
    fun welcome() = capture("welcome_light", OnboardingUiState())

    @Test
    fun welcomeDark() = capture("welcome_dark", OnboardingUiState(), darkTheme = true)

    @Test
    fun server() = capture("server_light", serverState)

    @Test
    fun serverDark() = capture("server_dark", serverState, darkTheme = true)

    @Test
    fun login() = capture("login_light", loginState)

    @Test
    fun loginDark() = capture("login_dark", loginState, darkTheme = true)

    @Test
    fun connectionError() = capture("connection_error_light", connectionErrorState)

    @Test
    fun connectionErrorDark() = capture("connection_error_dark", connectionErrorState, darkTheme = true)

    protected fun capture(name: String, state: OnboardingUiState, darkTheme: Boolean = false) {
        composeRule.setContent {
            TandemTheme(darkTheme = darkTheme) {
                OnboardingScreen(
                    state = state,
                    actions = NoOpActions,
                    language = AppLanguage.SYSTEM,
                    onLanguageSelected = {},
                )
            }
        }
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/onboarding/${device}_$name.png")
    }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE)
class OnboardingPhoneScreenshotTest : OnboardingDeviceScreenshotTest("phone") {
    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun welcomeSwedish() = capture("welcome_light_sv", OnboardingUiState())

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun welcomeDarkSwedish() = capture("welcome_dark_sv", OnboardingUiState(), darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun connectionErrorSwedish() = capture(
        "connection_error_light_sv",
        OnboardingUiState(
            step = OnboardingStep.SERVER,
            serverUrl = "recept.exempel.se",
            serverCheck = ServerCheck.Failed(TandemError.UnknownHost),
        ),
    )
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = TABLET)
class OnboardingTabletScreenshotTest : OnboardingDeviceScreenshotTest("tablet") {
    @Test
    @Config(qualifiers = "sv-$TABLET")
    fun welcomeSwedish() = capture("welcome_light_sv", OnboardingUiState())

    @Test
    @Config(qualifiers = "sv-$TABLET")
    fun welcomeDarkSwedish() = capture("welcome_dark_sv", OnboardingUiState(), darkTheme = true)
}
