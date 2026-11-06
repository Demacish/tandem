package casa.moller.tandem.feature.widgets

import android.content.Context
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `update`/`updateAll` do not run `provideGlance` again in a running session (about 45 s), so a
 * widget must follow its data inside the composition: the second check within a session, or a
 * second change after a sync, has to reach the content without a new session.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetSessionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun aRunningShoppingSessionFollowsTheList() = runGlanceAppWidgetUnitTest {
        setContext(context)
        val first = ShoppingWidgetState(rows = listOf(row(1, "mjölk"), row(2, "grädde")), remaining = 2)
        val changed = ShoppingWidgetState(rows = listOf(row(2, "grädde")), remaining = 1)
        provideComposable { ShoppingWidgetLive(flowOf(first, changed), first) }
        awaitIdle()
        onNode(hasText(remaining(1))).assertExists()
        onNode(hasText(remaining(2))).assertDoesNotExist()
    }

    @Test
    fun aRunningTonightSessionFollowsTheDay() = runGlanceAppWidgetUnitTest {
        setContext(context)
        val monday = TonightWidgetState(listOf(TonightWidgetState.Meal(1, "Krämig svamppasta", "Middag", 6)))
        provideComposable { TonightWidgetLive(flowOf(monday, TonightWidgetState()), monday) }
        awaitIdle()
        onNode(hasText(context.getString(R.string.widget_tonight_empty))).assertExists()
    }

    private fun row(id: Long, text: String) = ShoppingWidgetState.Row(id, text)

    private fun remaining(count: Int): String = context.resources.getQuantityString(R.plurals.widget_shopping_remaining, count, count)
}
