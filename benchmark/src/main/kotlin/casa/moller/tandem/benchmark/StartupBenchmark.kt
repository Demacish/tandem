package casa.moller.tandem.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import casa.moller.tandem.benchmark.DemoJourney.ensureDemoMode
import casa.moller.tandem.benchmark.DemoJourney.waitForTodayContent
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start into Today in demo mode (plan §10 "Klart när": cold start < 1.5 s on the phone).
 * StartupMode.COLD kills the process before each of the 10 iterations; the median of
 * timeToInitialDisplayMs and timeToFullDisplayMs goes to benchmark.yml's job summary.
 *
 * Two compilation modes: [coldStartWithBaselineProfile] is what a user gets after installing
 * (the shipped baseline profile), [coldStartWithoutCompilation] is the worst case (no AOT
 * compilation at all). The gap between them is what the profile buys.
 *
 * [BaselineProfileMode.Require]: the run fails when the APK carries no installable profile,
 * instead of quietly measuring without one (benchmark.yml also checks assets/dexopt/ in the APK).
 *
 * On an emulator these numbers are a trend only; the real number comes from a phone (Mer ->
 * Diagnostik shows the last cold start, measured by the app itself).
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    private var demoModeOn = false

    @Test
    fun coldStartWithBaselineProfile() = coldStart(CompilationMode.Partial(BaselineProfileMode.Require))

    @Test
    fun coldStartWithoutCompilation() = coldStart(CompilationMode.None())

    private fun coldStart(compilationMode: CompilationMode) = rule.measureRepeated(
        packageName = DemoJourney.PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilationMode,
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = {
            // Once per test: the first start finds the welcome screen and turns demo mode on,
            // which is stored. Then the process is gone before every measured start.
            if (!demoModeOn) {
                ensureDemoMode()
                demoModeOn = true
            }
            killProcess()
        },
    ) {
        startActivityAndWait()
        waitForTodayContent()
    }

    companion object {
        const val ITERATIONS = 10

        /** A fresh app: the first setup turns demo mode on. */
        @JvmStatic
        @BeforeClass
        fun clearApp() {
            DemoJourney.clearData(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()))
        }
    }
}
