package casa.moller.tandem.navigation

import android.app.Application
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.core.model.timer.TimerState
import casa.moller.tandem.core.timer.TimerIntents
import casa.moller.tandem.feature.shell.navigation.TimerOpenRequest
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** core:timer's "open the app at this timer" intent as MainActivity reads it (Phase 1b integration). */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class TimerLaunchTest {
    private val context = RuntimeEnvironment.getApplication()
    private val now = Instant.parse("2000-01-15T18:00:00Z")

    private fun timer(recipeId: Long?, stepIndex: Int?) = CookTimer(
        id = 7,
        label = "Pasta · 10 min",
        recipeId = recipeId,
        stepIndex = stepIndex,
        duration = 10.minutes,
        state = TimerState.RUNNING,
        endsAt = now + 10.minutes,
        createdAt = now,
    )

    @Test
    fun aRecipeTimerOpensCookModeAtItsStep() {
        // The intent core:timer really builds (its launcher component comes from the package manager).
        val intent = TimerIntents.openTimer(context, timer(recipeId = 6, stepIndex = 1))
            ?: Intent(TimerIntents.ACTION_OPEN_TIMER).putExtra(TimerIntents.EXTRA_RECIPE_ID, 6L).putExtra(TimerIntents.EXTRA_STEP_INDEX, 1)
        assertEquals(TimerOpenRequest(recipeId = 6, stepIndex = 1), TimerLaunch.requestFrom(intent))
    }

    @Test
    fun theTimersSourceComesAlong() {
        // fix-ui (0.2.0 review): demo mode or the account, so the shell never opens another source's recipe.
        val intent = Intent(TimerIntents.ACTION_OPEN_TIMER)
            .putExtra(TimerIntents.EXTRA_RECIPE_ID, 6L)
            .putExtra(TimerIntents.EXTRA_SOURCE_KEY, "demo")
        assertEquals(TimerOpenRequest(recipeId = 6, stepIndex = null, sourceKey = "demo"), TimerLaunch.requestFrom(intent))
        // Without a recipe the source does not matter; a blank one is no source.
        assertEquals(TimerOpenRequest(null, null), TimerLaunch.requestOf(recipeId = null, stepIndex = null, sourceKey = "demo"))
        assertEquals(TimerOpenRequest(6, null), TimerLaunch.requestOf(recipeId = 6, stepIndex = null, sourceKey = " "))
    }

    @Test
    fun aTimerWithoutARecipeOpensTheTimerList() {
        val intent = Intent(TimerIntents.ACTION_OPEN_TIMER).putExtra(TimerIntents.EXTRA_TIMER_ID, 7L)
        assertEquals(TimerOpenRequest(recipeId = null, stepIndex = null), TimerLaunch.requestFrom(intent))
    }

    @Test
    fun otherIntentsAreNoTimerRequest() {
        assertNull(TimerLaunch.requestFrom(null))
        assertNull(TimerLaunch.requestFrom(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)))
    }

    @Test
    fun invalidExtrasFromAnotherAppAreDropped() {
        val wrongTypes = Intent(TimerIntents.ACTION_OPEN_TIMER)
            .putExtra(TimerIntents.EXTRA_RECIPE_ID, "6")
            .putExtra(TimerIntents.EXTRA_STEP_INDEX, "1")
        assertEquals(TimerOpenRequest(null, null), TimerLaunch.requestFrom(wrongTypes))
        assertEquals(TimerOpenRequest(null, null), TimerLaunch.requestOf(recipeId = -3, stepIndex = 2))
        assertEquals(TimerOpenRequest(5, null), TimerLaunch.requestOf(recipeId = 5, stepIndex = -1))
        assertEquals(TimerOpenRequest(5, null), TimerLaunch.requestOf(recipeId = 5, stepIndex = 100_000))
    }
}
