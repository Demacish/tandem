package casa.moller.tandem.feature.recipes.books

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.ui.AppStatus
import casa.moller.tandem.core.ui.navigation.AppChrome
import casa.moller.tandem.feature.recipes.PHONE
import casa.moller.tandem.feature.recipes.TABLET
import casa.moller.tandem.feature.recipes.TestFrame
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Phone in landscape. */
internal const val PHONE_LANDSCAPE = "w915dp-h412dp-land-xhdpi"

/** Tablet in portrait (Galaxy Tab S10+ class). */
internal const val TABLET_PORTRAIT = "w800dp-h1280dp-port-mdpi"

/**
 * Books, favourites and "Vill laga" (plan 3.7, 6.2, 6.5; Stitch receptbok_master_detail_surfplatta,
 * recept_s_k_filter_samlingar, tandem_g_stl_ge_skrivskyddat): phone portrait and landscape, tablet
 * (master-detail) in both orientations, light and dark, English and Swedish, font scale 2, empty,
 * shared (read-only), guest and demo. Recorded in CI (Screenshots workflow), under `books/`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class BooksScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun books() = capture("phone_books_light", BookShot.BOOKS)

    @Test
    fun booksDark() = capture("phone_books_dark", BookShot.BOOKS, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun booksSwedish() = capture("phone_books_light_sv", BookShot.BOOKS)

    @Test
    fun booksFontScale2() = capture("phone_books_font2_light", BookShot.BOOKS, fontScale = 2f)

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun booksLandscape() = capture("phone_landscape_books_light", BookShot.BOOKS)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun booksGuest() = capture("phone_books_guest_light_sv", BookShot.BOOKS_GUEST)

    @Test
    fun booksEmptyDemo() = capture("phone_books_empty_demo_light", BookShot.BOOKS_EMPTY, chrome = DEMO)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun ownBook() = capture("phone_book_own_light_sv", BookShot.OWN_BOOK)

    @Test
    fun ownBookDark() = capture("phone_book_own_dark", BookShot.OWN_BOOK, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun sharedBook() = capture("phone_book_shared_light_sv", BookShot.SHARED_BOOK)

    @Test
    fun sharedBookFontScale2() = capture("phone_book_shared_font2_light", BookShot.SHARED_BOOK, fontScale = 2f)

    @Test
    fun emptyBook() = capture("phone_book_empty_light", BookShot.EMPTY_BOOK)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun favorites() = capture("phone_favorites_light_sv", BookShot.FAVORITES)

    @Test
    fun favoritesGuestDark() = capture("phone_favorites_guest_dark", BookShot.FAVORITES_GUEST, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun wantToCook() = capture("phone_want_to_cook_light_sv", BookShot.WANT_TO_COOK)

    @Test
    fun wantToCookEmpty() = capture("phone_want_to_cook_empty_light", BookShot.WANT_TO_COOK_EMPTY)

    @Test
    fun missing() = capture("phone_book_missing_light", BookShot.MISSING)

    @Test
    @Config(qualifiers = "sv-$TABLET")
    fun tabletMasterDetail() = capture("tablet_books_light_sv", BookShot.MASTER_DETAIL)

    @Test
    @Config(qualifiers = TABLET)
    fun tabletMasterDetailDark() = capture("tablet_books_dark", BookShot.MASTER_DETAIL, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$TABLET_PORTRAIT")
    fun tabletPortraitMasterDetail() = capture("tablet_portrait_books_light_sv", BookShot.MASTER_DETAIL)

    @Test
    @Config(qualifiers = TABLET_PORTRAIT)
    fun tabletPortraitWantToCook() = capture("tablet_portrait_want_to_cook_light", BookShot.WANT_TO_COOK)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun detailButtons() = capture("phone_detail_book_buttons_light_sv", BookShot.BUTTONS)

    @Test
    fun detailButtonsDark() = capture("phone_detail_book_buttons_dark", BookShot.BUTTONS, darkTheme = true)

    @Test
    fun detailButtonsGuest() = capture("phone_detail_book_buttons_guest_light", BookShot.BUTTONS_GUEST)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun addToBookSheet() = capture("phone_add_to_book_light_sv", BookShot.ADD_TO_BOOK)

    @Test
    fun addToBookSheetFontScale2() = capture("phone_add_to_book_font2_light", BookShot.ADD_TO_BOOK, fontScale = 2f)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun recipeListWithCollections() = capture("phone_list_collections_light_sv", BookShot.LIST_WITH_COLLECTIONS)

    @Test
    fun recipeListWithCollectionsGuestDark() =
        capture("phone_list_collections_guest_dark", BookShot.LIST_WITH_COLLECTIONS_GUEST, darkTheme = true)

    @Test
    @Config(qualifiers = TABLET)
    fun recipeListWithCollectionsTablet() = capture("tablet_list_collections_light", BookShot.LIST_WITH_COLLECTIONS)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun readOnlySheet() = capture("phone_read_only_sheet_light_sv", BookShot.READ_ONLY_SHEET)

    @Test
    fun readOnlySheetDarkFontScale2() =
        capture("phone_read_only_sheet_font2_dark", BookShot.READ_ONLY_SHEET, darkTheme = true, fontScale = 2f)

    private fun capture(
        name: String,
        shot: BookShot,
        darkTheme: Boolean = false,
        fontScale: Float? = null,
        chrome: AppChrome = AppChrome(),
    ) {
        composeRule.setContent { TestFrame(darkTheme = darkTheme, chrome = chrome, fontScale = fontScale) { shot.content() } }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/books/$name.png")
    }

    private companion object {
        val DEMO = AppChrome(status = AppStatus.Demo, showDemoBanner = true)
    }
}
