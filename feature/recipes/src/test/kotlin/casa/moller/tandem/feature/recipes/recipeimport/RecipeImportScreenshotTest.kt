package casa.moller.tandem.feature.recipes.recipeimport

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.ui.navigation.AppChrome
import casa.moller.tandem.feature.recipes.PHONE
import casa.moller.tandem.feature.recipes.PHONE_TALL
import casa.moller.tandem.feature.recipes.TABLET
import casa.moller.tandem.feature.recipes.TestFrame
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Reference images of "Importera recept" (Fas 3, import lane; `src/test/screenshots/import/`): the
 * form with and without an AI provider, demo mode, a guest, the server reading, the result with
 * a duplicate warning and the pictures to choose from (phone, dark, font scale 2, tablet), a page
 * without a recipe and an offline failure.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class RecipeImportScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun form() = capture("phone_import_form_light_sv", RecipeImportShot.FORM)

    @Test
    fun formDark() = capture("phone_import_form_dark", RecipeImportShot.FORM, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun formNoAi() = capture("phone_import_form_no_ai_light_sv", RecipeImportShot.FORM_NO_AI)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun formDemo() = capture("phone_import_form_demo_light_sv", RecipeImportShot.FORM_DEMO)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun formGuest() = capture("phone_import_form_guest_light_sv", RecipeImportShot.FORM_GUEST)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun working() = capture("phone_import_working_light_sv", RecipeImportShot.WORKING)

    @Test
    @Config(qualifiers = "sv-$PHONE_TALL")
    fun result() = capture("phone_tall_import_result_light_sv", RecipeImportShot.RESULT)

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun resultDark() = capture("phone_tall_import_result_dark", RecipeImportShot.RESULT, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$PHONE_TALL")
    fun resultFontScale2() = capture("phone_tall_import_result_font2_light_sv", RecipeImportShot.RESULT, fontScale = 2f)

    @Test
    @Config(qualifiers = "sv-$TABLET")
    fun resultTablet() = capture("tablet_import_result_light_sv", RecipeImportShot.RESULT)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun problem() = capture("phone_import_problem_light_sv", RecipeImportShot.PROBLEM)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun offline() = capture("phone_import_offline_light_sv", RecipeImportShot.OFFLINE)

    private fun capture(name: String, shot: RecipeImportShot, darkTheme: Boolean = false, fontScale: Float? = null) {
        composeRule.setContent { TestFrame(darkTheme = darkTheme, chrome = AppChrome(), fontScale = fontScale) { shot.content() } }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/import/$name.png")
    }
}
