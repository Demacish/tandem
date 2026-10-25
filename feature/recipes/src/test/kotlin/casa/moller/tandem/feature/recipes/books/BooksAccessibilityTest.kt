package casa.moller.tandem.feature.recipes.books

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.feature.recipes.PHONE
import casa.moller.tandem.feature.recipes.TABLET
import casa.moller.tandem.feature.recipes.TestFrame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ATF accessibility checks (core:testing `assertAccessible`, plan 5.6/6.6) of every books screen
 * and state ([BookShot]): touch targets, labels on the remove / plan / heart buttons, contrast in
 * light and dark, font scale 2, the guest's read-only views and the tablet's master-detail.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class BooksAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun check(shot: BookShot, darkTheme: Boolean = false, fontScale: Float? = null) {
        composeRule.setContent { TestFrame(darkTheme = darkTheme, fontScale = fontScale) { shot.content() } }
        composeRule.waitForIdle()
        composeRule.assertAccessible()
    }

    @Test
    fun books() = check(BookShot.BOOKS)

    @Test
    fun booksDark() = check(BookShot.BOOKS, darkTheme = true)

    @Test
    fun booksFontScale2() = check(BookShot.BOOKS, fontScale = 2f)

    @Test
    fun booksGuest() = check(BookShot.BOOKS_GUEST)

    @Test
    fun booksEmpty() = check(BookShot.BOOKS_EMPTY)

    @Test
    fun newBookDialog() = check(BookShot.BOOKS_CREATE)

    @Test
    fun ownBook() = check(BookShot.OWN_BOOK)

    @Test
    fun ownBookDark() = check(BookShot.OWN_BOOK, darkTheme = true)

    @Test
    fun sharedBook() = check(BookShot.SHARED_BOOK)

    @Test
    fun emptyBook() = check(BookShot.EMPTY_BOOK)

    @Test
    fun favorites() = check(BookShot.FAVORITES)

    @Test
    fun favoritesGuest() = check(BookShot.FAVORITES_GUEST, darkTheme = true)

    @Test
    fun wantToCook() = check(BookShot.WANT_TO_COOK)

    @Test
    fun wantToCookFontScale2() = check(BookShot.WANT_TO_COOK, fontScale = 2f)

    @Test
    fun missing() = check(BookShot.MISSING)

    @Test
    @Config(qualifiers = TABLET)
    fun tabletMasterDetail() = check(BookShot.MASTER_DETAIL)

    @Test
    fun detailButtons() = check(BookShot.BUTTONS)

    @Test
    fun detailButtonsDark() = check(BookShot.BUTTONS, darkTheme = true)

    @Test
    fun detailButtonsGuest() = check(BookShot.BUTTONS_GUEST)

    @Test
    fun addToBook() = check(BookShot.ADD_TO_BOOK)

    @Test
    fun addToBookFontScale2() = check(BookShot.ADD_TO_BOOK, fontScale = 2f)

    @Test
    fun recipeListWithCollections() = check(BookShot.LIST_WITH_COLLECTIONS)

    @Test
    fun recipeListWithCollectionsGuest() = check(BookShot.LIST_WITH_COLLECTIONS_GUEST, darkTheme = true)

    @Test
    fun readOnlySheet() = check(BookShot.READ_ONLY_SHEET)

    @Test
    fun readOnlySheetDark() = check(BookShot.READ_ONLY_SHEET, darkTheme = true)
}
