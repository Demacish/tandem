package casa.moller.tandem.feature.recipes

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.testing.data.SampleRecipes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** en-XA: every string longer and accented ("[Ŕéçîþéš one two]"), plan 6.7. */
private const val LONG_TEXT = "en-rXA"

/** ar-XB: every string mirrored, right to left (plan 6.7). */
private const val RIGHT_TO_LEFT = "ar-rXB-ldrtl"

/**
 * The recipe list and the recipe detail in Android's pseudo-locales (plan 5.6/6.7): en-XA shows
 * text that is about a third longer than English, ar-XB a right-to-left layout. They catch
 * truncated labels, fixed widths and left/right instead of start/end. Debug builds of library
 * modules generate both locales (tandem.android.roborazzi). Recorded in CI like the other
 * screenshots (build/outputs/roborazzi/recipes/phone_*_en_xa.png, *_ar_xb.png).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE)
class RecipesPseudoLocaleScreenshotTest : RecipesScreenshotTest("phone") {

    @Test
    @Config(qualifiers = "$LONG_TEXT-$PHONE")
    fun listLongText() = capture("list_light_en_xa") { ListScreen(listState()) }

    @Test
    @Config(qualifiers = "$RIGHT_TO_LEFT-$PHONE")
    fun listRightToLeft() = capture("list_light_ar_xb") { ListScreen(listState()) }

    @Test
    @Config(qualifiers = "$LONG_TEXT-$PHONE")
    fun detailLongText() = capture("detail_light_en_xa") { DetailWithFakes(SampleRecipes.MushroomPasta.id) }

    @Test
    @Config(qualifiers = "$RIGHT_TO_LEFT-$PHONE")
    fun detailRightToLeftDark() = capture("detail_dark_ar_xb", darkTheme = true) { DetailWithFakes(SampleRecipes.MushroomPasta.id) }

    /** Guards the screenshots above: without generated pseudo-locales they would silently show English. */
    @Test
    @Config(qualifiers = "$LONG_TEXT-$PHONE")
    fun theLongTextLocaleIsGenerated() {
        val title = string(R.string.recipes_title)
        assertTrue("en-XA must be pseudo-localized, got '$title'", title != "Recipes" && title.startsWith("["))
    }

    @Test
    @Config(qualifiers = "$RIGHT_TO_LEFT-$PHONE")
    fun theRightToLeftLocaleIsGenerated() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val title = string(R.string.recipes_title)
        assertTrue("ar-XB must be pseudo-localized, got '$title'", title != "Recipes" && title.contains('‮'))
        assertEquals(View.LAYOUT_DIRECTION_RTL, context.resources.configuration.layoutDirection)
    }
}
