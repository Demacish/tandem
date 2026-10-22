package casa.moller.tandem.feature.cook

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.feature.cook.navigation.CookKey
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** en-XA: every string longer and accented, plan 6.7. */
private const val LONG_TEXT = "en-rXA"

/** ar-XB: every string mirrored, right to left (plan 6.7). */
private const val RIGHT_TO_LEFT = "ar-rXB-ldrtl"

/**
 * Cook mode in Android's pseudo-locales (plan 5.6/6.7): a step with two timers and the "Klart!"
 * page with longer text (en-XA) and right to left (ar-XB). Recorded in CI like the other
 * screenshots (build/outputs/roborazzi/cook/phone_*_en_xa.png, *_ar_xb.png).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE)
class CookPseudoLocaleScreenshotTest : CookScreenshotTest("phone") {

    @Test
    @Config(qualifiers = "$LONG_TEXT-$PHONE")
    fun stepWithTimersLongText() = capture("step_timers_light_en_xa", fakes = CookFakes().withTimers()) { CookWithFakes(fakes = it) }

    @Test
    @Config(qualifiers = "$RIGHT_TO_LEFT-$PHONE")
    fun stepWithTimersRightToLeft() = capture("step_timers_light_ar_xb", fakes = CookFakes().withTimers()) { CookWithFakes(fakes = it) }

    @Test
    @Config(qualifiers = "$LONG_TEXT-$PHONE")
    fun doneLongText() = capture("done_light_en_xa") { CookWithFakes(key = CookKey(pasta.id, startStep = 3), fakes = it) }

    @Test
    @Config(qualifiers = "$RIGHT_TO_LEFT-$PHONE")
    fun ringingRightToLeftDark() = capture("ringing_dark_ar_xb", darkTheme = true, fakes = CookFakes().withRingingTimer()) {
        CookWithFakes(fakes = it)
    }

    /** Guards the screenshots above: without generated pseudo-locales they would silently show English. */
    @Test
    @Config(qualifiers = "$LONG_TEXT-$PHONE")
    fun theLongTextLocaleIsGenerated() {
        val title = ApplicationProvider.getApplicationContext<android.content.Context>().getString(R.string.cook_title)
        assertTrue("en-XA must be pseudo-localized, got '$title'", title != "Cook mode" && title.startsWith("["))
    }
}
