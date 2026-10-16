package casa.moller.tandem.feature.cook

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.model.sync.SyncPause
import casa.moller.tandem.core.model.sync.SyncStatus
import casa.moller.tandem.core.model.timer.TimerCapabilities
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.feature.cook.navigation.CookKey
import casa.moller.tandem.feature.cook.timers.TimersScreen
import casa.moller.tandem.feature.cook.ui.CookLayout
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ATF accessibility checks (core:testing `assertAccessible`, plan 5.6) of cook mode: steps with
 * ingredients and timer buttons, running, paused and ringing timers, "Klart!", the notification
 * question, the alarm warnings, font scale 2, two panes, the foldable layout and the timer list;
 * light and dark, because contrast is measured on the rendered pixels.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class CookAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val pasta = SampleRecipes.MushroomPasta

    private fun check(
        darkTheme: Boolean = false,
        fontScale: Float? = null,
        fakes: CookFakes = CookFakes(),
        content: @Composable (CookFakes) -> Unit,
    ) {
        composeRule.setContent { TestFrame(darkTheme = darkTheme, fontScale = fontScale, clock = fakes.clock) { content(fakes) } }
        composeRule.waitForIdle()
        composeRule.assertAccessible()
    }

    private fun timersRunning(): CookFakes = CookFakes().apply {
        startTimer(1, "Pastan · 8–10 minuter (10 min)", 10.minutes)
        startTimer(0, "Svampen · ca 5 minuter", 5.minutes)
        clock.advanceBy(1.minutes)
    }

    private fun timerRinging(): CookFakes = CookFakes().apply {
        startTimer(0, "Svampen · ca 5 minuter", 5.minutes)
        startTimer(1, "Pastan · 8–10 minuter (10 min)", 10.minutes)
        timers.advanceTimeBy(5.minutes)
    }

    @Test
    fun step() = check { CookWithFakes(fakes = it) }

    @Test
    fun stepDark() = check(darkTheme = true) { CookWithFakes(fakes = it) }

    @Test
    fun stepFontScale2() = check(fontScale = 2f) { CookWithFakes(fakes = it) }

    @Test
    fun stepWithTimers() = check(fakes = timersRunning()) { CookWithFakes(key = CookKey(pasta.id, startStep = 1), fakes = it) }

    @Test
    fun stepWithTimersDark() = check(darkTheme = true, fakes = timersRunning()) { CookWithFakes(fakes = it) }

    @Test
    fun ringing() = check(fakes = timerRinging()) { CookWithFakes(fakes = it) }

    @Test
    fun ringingDark() = check(darkTheme = true, fakes = timerRinging()) { CookWithFakes(fakes = it) }

    @Test
    fun pausedTimer() = check(
        fakes = timersRunning().apply { runBlocking { timers.pause(timers.timers.value.first().id) } },
    ) { CookWithFakes(fakes = it) }

    @Test
    fun done() = check { CookWithFakes(key = CookKey(pasta.id, startStep = 3), fakes = it) }

    @Test
    fun doneDarkOffline() = check(
        darkTheme = true,
        fakes = CookFakes().apply { sync.setStatus(SyncStatus.Idle.copy(pause = SyncPause.OFFLINE)) },
    ) { CookWithFakes(key = CookKey(pasta.id, startStep = 3), fakes = it, prepare = { vm -> vm.onAction(CookAction.SetRating(3)) }) }

    @Test
    fun doneFontScale2() = check(fontScale = 2f) { CookWithFakes(key = CookKey(pasta.id, startStep = 3), fakes = it) }

    @Test
    fun notificationQuestion() = check(
        fakes = CookFakes().apply {
            timers.setCapabilities(TimerCapabilities.FreshInstall)
            handle[CookViewModel.KEY_PENDING] = PendingTimer(0, "Svampen · ca 5 minuter", durationSeconds = 300).encode()
        },
    ) { CookWithFakes(fakes = it) }

    @Test
    fun alarmWarnings() = check(
        fakes = CookFakes().apply {
            timers.setCapabilities(TimerCapabilities.AllGranted.copy(exactAlarmsAllowed = false, exactAlarmsRequestable = true))
            startTimer(0, "Svampen · ca 5 minuter", 5.minutes)
        },
    ) { CookWithFakes(fakes = it) }

    @Test
    fun silentAlarmWarningDark() = check(
        darkTheme = true,
        fakes = CookFakes().apply {
            timers.setCapabilities(TimerCapabilities.FreshInstall.copy(notificationPermissionAsked = true))
            startTimer(0, "Svampen · ca 5 minuter", 5.minutes)
        },
    ) { CookWithFakes(fakes = it) }

    @Test
    fun tabletop() = check(fakes = timersRunning()) { CookWithFakes(fakes = it, layout = CookLayout.TABLETOP) }

    @Test
    @Config(qualifiers = TABLET)
    fun twoPanes() = check(fakes = timersRunning()) { CookWithFakes(key = CookKey(pasta.id, startStep = 1), fakes = it) }

    @Test
    @Config(qualifiers = TABLET)
    fun twoPanesRingingDark() = check(darkTheme = true, fakes = timerRinging()) { CookWithFakes(fakes = it) }

    @Test
    @Config(qualifiers = TABLET_PORTRAIT)
    fun twoPanesPortraitFontScale2() = check(fontScale = 2f, fakes = timersRunning()) { CookWithFakes(fakes = it) }

    @Test
    fun timerList() = check(fakes = timerRinging()) { fakes ->
        TimersScreen(timers = fakes.timers.timers.value, onAction = {}, onBack = {}, onOpenCook = { _, _ -> })
    }

    @Test
    fun timerListEmptyDark() = check(darkTheme = true) {
        TimersScreen(timers = emptyList(), onAction = {}, onBack = {}, onOpenCook = { _, _ -> })
    }
}
