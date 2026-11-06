package casa.moller.tandem.feature.recipes.recipeimport

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.feature.recipes.PHONE
import casa.moller.tandem.feature.recipes.PHONE_TALL
import casa.moller.tandem.feature.recipes.TABLET
import casa.moller.tandem.feature.recipes.TestFrame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ATF accessibility checks (core:testing `assertAccessible`) of every import state
 * ([RecipeImportShot]): 48 dp targets, a label on the pictures to choose from ("Bild 1 av 2"),
 * contrast in light and dark, font scale 2 and the tablet's width.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class RecipeImportAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun check(shot: RecipeImportShot, darkTheme: Boolean = false, fontScale: Float? = null) {
        composeRule.setContent { TestFrame(darkTheme = darkTheme, fontScale = fontScale) { shot.content() } }
        composeRule.waitForIdle()
        composeRule.assertAccessible()
    }

    @Test
    fun form() = check(RecipeImportShot.FORM)

    @Test
    fun formDark() = check(RecipeImportShot.FORM, darkTheme = true)

    @Test
    fun pages() = check(RecipeImportShot.PAGES)

    @Test
    fun pagesDarkFontScale2() = check(RecipeImportShot.PAGES, darkTheme = true, fontScale = 2f)

    @Test
    fun resultOriginal() = check(RecipeImportShot.RESULT_ORIGINAL)

    @Test
    @Config(qualifiers = TABLET)
    fun resultOriginalTablet() = check(RecipeImportShot.RESULT_ORIGINAL)

    @Test
    fun fileTooLarge() = check(RecipeImportShot.FILE_TOO_LARGE)

    @Test
    fun formNoAi() = check(RecipeImportShot.FORM_NO_AI)

    @Test
    fun formDemo() = check(RecipeImportShot.FORM_DEMO)

    @Test
    fun formGuest() = check(RecipeImportShot.FORM_GUEST)

    @Test
    fun working() = check(RecipeImportShot.WORKING)

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun result() = check(RecipeImportShot.RESULT)

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun resultDark() = check(RecipeImportShot.RESULT, darkTheme = true)

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun resultFontScale2() = check(RecipeImportShot.RESULT, fontScale = 2f)

    @Test
    @Config(qualifiers = TABLET)
    fun resultTablet() = check(RecipeImportShot.RESULT)

    @Test
    fun problem() = check(RecipeImportShot.PROBLEM)

    @Test
    fun offline() = check(RecipeImportShot.OFFLINE)

    // --- Fas 3's leftovers (lane fas3-rester) ---

    @Test
    fun page() = check(RecipeImportShot.PAGE)

    @Test
    fun pageDark() = check(RecipeImportShot.PAGE, darkTheme = true)

    @Test
    fun pageSending() = check(RecipeImportShot.PAGE_SENDING)

    @Test
    fun pageNothingFoundFontScale2() = check(RecipeImportShot.PAGE_NOTHING_FOUND, fontScale = 2f)

    @Test
    fun pageInterpret() = check(RecipeImportShot.PAGE_INTERPRET)

    @Test
    fun resultSort() = check(RecipeImportShot.RESULT_SORT)

    @Test
    fun resultSortDark() = check(RecipeImportShot.RESULT_SORT, darkTheme = true)

    @Test
    fun resultSortFontScale2() = check(RecipeImportShot.RESULT_SORT, fontScale = 2f)

    @Test
    fun resultSorting() = check(RecipeImportShot.RESULT_SORTING)

    @Test
    fun resultSorted() = check(RecipeImportShot.RESULT_SORTED)
}
