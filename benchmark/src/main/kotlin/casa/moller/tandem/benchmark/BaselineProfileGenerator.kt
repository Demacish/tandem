package casa.moller.tandem.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import casa.moller.tandem.benchmark.DemoJourney.ensureDemoMode
import casa.moller.tandem.benchmark.DemoJourney.flingDownAndUp
import casa.moller.tandem.benchmark.DemoJourney.openAndCloseRecipe
import casa.moller.tandem.benchmark.DemoJourney.openRecipeList
import casa.moller.tandem.benchmark.DemoJourney.waitForTodayContent
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The baseline profile (docs/BYGGE.md "Prestanda"): the classes and methods of a cold start into
 * Today, the recipe list (scrolled) and a recipe's detail, so ART compiles them ahead of time on
 * install. benchmark.yml runs it against a minified build WITHOUT obfuscation
 * (-Ptandem.baselineProfile.generate=true) and uploads the result as the "baseline-profile"
 * artifact; it is committed as app/src/main/baseline-prof.txt, which AGP rewrites with each
 * build's R8 mapping.
 *
 * Only run by benchmark.yml (instrumentation argument `class`), never with the benchmarks.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Before
    fun clearApp() {
        DemoJourney.clearData(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()))
    }

    @Test
    fun generate() = rule.collect(
        packageName = DemoJourney.PACKAGE,
        // A plain baseline profile (…-baseline-prof.txt). A startup profile would only help with
        // the androidx.baselineprofile Gradle plugin's DEX layout, which Tandem does not use.
        includeInStartupProfile = false,
    ) {
        // Every round starts cold, on Today (the first one turns demo mode on).
        killProcess()
        ensureDemoMode()
        waitForTodayContent()
        openRecipeList()
        flingDownAndUp()
        openAndCloseRecipe()
    }
}
