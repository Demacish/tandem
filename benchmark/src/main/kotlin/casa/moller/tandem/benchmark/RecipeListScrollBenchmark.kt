package casa.moller.tandem.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import casa.moller.tandem.benchmark.DemoJourney.ensureDemoMode
import casa.moller.tandem.benchmark.DemoJourney.flingDownAndUp
import casa.moller.tandem.benchmark.DemoJourney.openRecipeList
import casa.moller.tandem.benchmark.DemoJourney.recipeList
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Flinging the demo recipe list (14 recipes with photos) down and up again: frame durations and
 * overruns (FrameTimingMetric), median and P90/P99 in benchmark.yml's job summary. A trend on
 * an emulator with a software GPU, not an absolute number.
 */
@RunWith(AndroidJUnit4::class)
class RecipeListScrollBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun scrollRecipeList() = rule.measureRepeated(
        packageName = DemoJourney.PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.UseIfAvailable),
        startupMode = StartupMode.WARM,
        iterations = ITERATIONS,
        setupBlock = {
            ensureDemoMode()
            openRecipeList()
        },
    ) {
        flingDownAndUp(recipeList())
    }

    companion object {
        const val ITERATIONS = 5

        @JvmStatic
        @BeforeClass
        fun clearApp() {
            DemoJourney.clearData(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()))
        }
    }
}
