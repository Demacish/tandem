package casa.moller.tandem.feature.cook

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.model.settings.AppSettings
import casa.moller.tandem.core.model.sync.SyncPause
import casa.moller.tandem.core.model.sync.SyncStatus
import casa.moller.tandem.core.model.timer.TimerCapabilities
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.feature.cook.navigation.CookKey
import casa.moller.tandem.feature.cook.timers.TimersScreen
import casa.moller.tandem.feature.cook.ui.CookLayout
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots of cook mode (plan 6.5/6.6 test matrix): phone steps light, dark, Swedish and font
 * scale 2, the tall phone, two panes on a tablet in both orientations and a phone in landscape,
 * the foldable on the table, "Klart!", ringing timers, the notification question and the timer
 * list. Recorded in CI and uploaded in the "screenshots" artifact (build/outputs/roborazzi/cook/).
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class CookScreenshotTest(private val device: String) {

    @get:Rule
    val composeRule = createComposeRule()

    protected val pasta = SampleRecipes.MushroomPasta

    protected fun capture(
        name: String,
        darkTheme: Boolean = false,
        fontScale: Float? = null,
        fakes: CookFakes = CookFakes(),
        content: @Composable (CookFakes) -> Unit,
    ) {
        composeRule.setContent { TestFrame(darkTheme = darkTheme, fontScale = fontScale, clock = fakes.clock) { content(fakes) } }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/cook/${device}_$name.png")
    }

    /** Two timers: the pasta counting down (7:39 left) and the mushrooms paused. */
    protected fun CookFakes.withTimers(): CookFakes = apply {
        startTimer(1, "Pastan · 8–10 minuter (10 min)", 10.minutes)
        startTimer(0, "Svampen · ca 5 minuter", 5.minutes)
        clock.advanceBy(2.minutes + 21.seconds)
        val mushrooms = timers.timers.value.last().id
        runBlocking { timers.pause(mushrooms) }
    }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE)
class CookPhoneScreenshotTest : CookScreenshotTest("phone") {

    @Test
    fun step() = capture("step_light") { CookWithFakes(fakes = it) }

    @Test
    fun stepDark() = capture("step_dark", darkTheme = true) { CookWithFakes(fakes = it) }

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun stepSwedish() = capture("step_light_sv") { CookWithFakes(fakes = it) }

    @Test
    fun stepFontScale2() = capture("step_font2_light", fontScale = 2f) { CookWithFakes(fakes = it) }

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun stepWithTimersSwedish() = capture("step_timers_light_sv", fakes = CookFakes().withTimers()) {
        CookWithFakes(key = CookKey(pasta.id, startStep = 1), fakes = it)
    }

    @Test
    fun stepWithTimersDark() = capture("step_timers_dark", darkTheme = true, fakes = CookFakes().withTimers()) {
        CookWithFakes(key = CookKey(pasta.id, startStep = 1), fakes = it)
    }

    @Test
    fun ringing() = capture("ringing_light", fakes = CookFakes().withRingingTimer()) { CookWithFakes(fakes = it) }

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun ringingDarkSwedish() = capture("ringing_dark_sv", darkTheme = true, fakes = CookFakes().withRingingTimer()) {
        CookWithFakes(fakes = it)
    }

    @Test
    fun ingredientsCollapsedLargerText() = capture(
        "step_collapsed_large_text_light",
        fakes = CookFakes().apply { settings.state.value = AppSettings.Default.copy(cookTextScale = 1.5f) },
    ) { CookWithFakes(fakes = it, prepare = { vm -> vm.onAction(CookAction.ToggleIngredients) }) }

    @Test
    fun done() = capture("done_light") { CookWithFakes(key = CookKey(pasta.id, startStep = 3), fakes = it) }

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun doneOfflineSwedish() = capture(
        "done_offline_light_sv",
        fakes = CookFakes().apply { sync.setStatus(SyncStatus.Idle.copy(pause = SyncPause.OFFLINE)) },
    ) {
        CookWithFakes(key = CookKey(pasta.id, startStep = 3), fakes = it, prepare = { vm -> vm.onAction(CookAction.SetRating(4)) })
    }

    @Test
    fun doneDark() = capture("done_dark", darkTheme = true) { CookWithFakes(key = CookKey(pasta.id, startStep = 3), fakes = it) }

    @Test
    fun doneFontScale2() = capture("done_font2_light", fontScale = 2f) {
        CookWithFakes(key = CookKey(pasta.id, startStep = 3), fakes = it)
    }

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun notificationQuestionSwedish() = capture(
        "notification_question_light_sv",
        fakes = CookFakes().apply {
            timers.setCapabilities(TimerCapabilities.FreshInstall)
            // What a tap on the first step's timer leaves behind before the question is answered.
            handle[CookViewModel.KEY_PENDING] = PendingTimer(0, "Svampen · ca 5 minuter", durationSeconds = 300).encode()
        },
    ) { CookWithFakes(fakes = it) }

    @Test
    fun lateAlarmWarning() = capture(
        "late_alarm_light",
        fakes = CookFakes().apply {
            timers.setCapabilities(TimerCapabilities.AllGranted.copy(exactAlarmsAllowed = false, exactAlarmsRequestable = true))
            startTimer(0, "Svampen · ca 5 minuter", 5.minutes)
        },
    ) { CookWithFakes(fakes = it) }

    @Test
    fun alwaysDark() = capture(
        "always_dark",
        darkTheme = true,
        fakes = CookFakes().apply { settings.state.value = AppSettings.Default.copy(cookModeAlwaysDark = true) },
    ) { CookWithFakes(fakes = it) }

    @Test
    fun timerList() = capture("timers_light", fakes = CookFakes().withRingingTimer()) { fakes ->
        val timers by fakes.timers.timers.collectAsState()
        TimersScreen(timers = timers, onAction = {}, onBack = {}, onOpenCook = { _, _ -> })
    }

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun timerListEmptySwedish() = capture("timers_empty_light_sv") {
        TimersScreen(timers = emptyList(), onAction = {}, onBack = {}, onOpenCook = { _, _ -> })
    }

    @Test
    fun foldableTabletop() = capture("tabletop_light", fakes = CookFakes().withTimers()) {
        CookWithFakes(key = CookKey(pasta.id, startStep = 1), fakes = it, layout = CookLayout.TABLETOP)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE_TALL)
class CookTallPhoneScreenshotTest : CookScreenshotTest("phone_tall") {

    @Test
    fun step() = capture("step_light", fakes = CookFakes().withTimers()) { CookWithFakes(fakes = it) }

    @Test
    @Config(qualifiers = "sv-$PHONE_TALL")
    fun doneSwedish() = capture("done_light_sv") { CookWithFakes(key = CookKey(pasta.id, startStep = 3), fakes = it) }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = TABLET)
class CookTabletScreenshotTest : CookScreenshotTest("tablet") {

    @Test
    fun twoPanes() = capture("step_light", fakes = CookFakes().withTimers()) {
        CookWithFakes(key = CookKey(pasta.id, startStep = 1), fakes = it)
    }

    @Test
    fun twoPanesDark() = capture("step_dark", darkTheme = true, fakes = CookFakes().withTimers()) {
        CookWithFakes(key = CookKey(pasta.id, startStep = 1), fakes = it)
    }

    @Test
    @Config(qualifiers = "sv-$TABLET")
    fun ringingSwedish() = capture("ringing_light_sv", fakes = CookFakes().withRingingTimer()) { CookWithFakes(fakes = it) }

    @Test
    fun done() = capture("done_light") { CookWithFakes(key = CookKey(pasta.id, startStep = 3), fakes = it) }

    @Test
    fun fontScale2() = capture("step_font2_light", fontScale = 2f, fakes = CookFakes().withTimers()) { CookWithFakes(fakes = it) }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = TABLET_PORTRAIT)
class CookTabletPortraitScreenshotTest : CookScreenshotTest("tablet_portrait") {

    @Test
    fun twoPanes() = capture("step_light", fakes = CookFakes().withTimers()) {
        CookWithFakes(key = CookKey(pasta.id, startStep = 1), fakes = it)
    }

    @Test
    @Config(qualifiers = "sv-$TABLET_PORTRAIT")
    fun twoPanesDarkSwedish() = capture("step_dark_sv", darkTheme = true, fakes = CookFakes().withTimers()) {
        CookWithFakes(fakes = it)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE_LANDSCAPE)
class CookPhoneLandscapeScreenshotTest : CookScreenshotTest("phone_landscape") {

    @Test
    fun twoPanes() = capture("step_light", fakes = CookFakes().withTimers()) { CookWithFakes(fakes = it) }
}
