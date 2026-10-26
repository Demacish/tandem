package casa.moller.tandem.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.mealplan.UpcomingMeals
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.core.ui.AppStatus
import casa.moller.tandem.core.ui.navigation.AppChrome
import casa.moller.tandem.core.ui.navigation.LocalAppChrome
import casa.moller.tandem.feature.home.ui.TodayScreen
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import kotlin.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Compact phone (portrait), medium tablet (portrait) and expanded tablet (landscape). */
private const val PHONE = "w412dp-h915dp-port-xhdpi"
private const val PHONE_TALL = "w412dp-h1800dp-port-xhdpi"
private const val MEDIUM = "w800dp-h1280dp-port-mdpi"
private const val TABLET = "w1280dp-h800dp-land-mdpi"

/**
 * Phase 2 (books lane): three "Vill laga" recipes that are in none of the sample carousels (the same
 * recipe twice on one screen would be two identical TalkBack items).
 */
internal val wantToCookRecipes: List<RecipeOverview> = listOf(
    RecipeOverview(id = 9_001, name = "Linsgryta med kokosmjölk", workingMinutes = 15, waitingMinutes = 25, rating = 4.5),
    RecipeOverview(id = 9_002, name = "Ugnsbakad lax med dill", workingMinutes = 10, waitingMinutes = 20),
    RecipeOverview(id = 9_003, name = "Fredagstacos", workingMinutes = 30, rating = 4.0),
)

/** Today with the sample meal plan and carousels (core:testing), as the ViewModel builds it. */
internal fun sampleHomeState(meals: UpcomingMeals = SampleRecipes.upcomingMeals, offline: Boolean = false): HomeUiState {
    val (tonight, alsoToday) = meals.splitToday()
    return HomeUiState(
        loading = false,
        dayPart = DayPart.EVENING,
        today = meals.today,
        tonight = tonight,
        alsoToday = alsoToday,
        tomorrow = meals.tomorrowMeals.sortedBy { it.mealType.order },
        sections = SampleRecipes.homeSections,
        offline = offline,
        lastSynced = Instant.parse("2026-10-06T16:14:00Z"),
    )
}

/**
 * The Today tab (plan 6.2, Stitch idag_*, idag_demol_ge, idag_surfplatta_*) on a phone, a medium
 * tablet in portrait and an expanded tablet in landscape, light and dark, English and Swedish, at
 * font scale 2, in demo mode, offline and with nothing planned. Recorded in CI (screenshots).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class TodayScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val loaded = sampleHomeState()
    private val wantToCook = TodayBooksState(wantToCook = wantToCookRecipes)
    private val guest = TodayBooksState(wantToCook = wantToCookRecipes.take(2), readOnly = true)
    private val nothingPlanned = sampleHomeState(meals = UpcomingMeals(today = SampleRecipes.Today)).copy(sections = emptyList())

    @Test
    fun phone() = capture("phone_today_light", loaded)

    // --- Phase 2 (books lane): the "Vill laga" row and the guest's read-only banner (#42). ---

    @Test
    @Config(qualifiers = "sv-$PHONE_TALL")
    fun phoneWantToCook() = capture("phone_today_want_to_cook_light_sv", loaded, books = wantToCook)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun phoneGuest() = capture("phone_today_guest_light_sv", loaded, books = TodayBooksState(readOnly = true))

    @Test
    fun phoneGuestDarkFontScale2() = capture("phone_today_guest_font2_dark", loaded, darkTheme = true, fontScale = 2f, books = guest)

    // Phase 2 (matsedel lane): a guest with nothing planned is not sent to plan the week.
    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun phoneNothingPlannedGuest() = capture(
        "phone_today_nothing_planned_guest_light_sv",
        nothingPlanned,
        books = TodayBooksState(readOnly = true),
    )

    @Test
    @Config(qualifiers = TABLET)
    fun tabletWantToCook() = capture("tablet_today_want_to_cook_dark", loaded, darkTheme = true, books = wantToCook)

    @Test
    fun phoneDark() = capture("phone_today_dark", loaded, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun phoneSwedish() = capture("phone_today_light_sv", loaded)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun phoneSwedishDark() = capture("phone_today_dark_sv", loaded, darkTheme = true)

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun phoneWholeList() = capture("phone_today_full_light", loaded)

    @Test
    fun phoneFontScale2() = capture("phone_today_font2_light", loaded, fontScale = 2f)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun phoneDemo() = capture(
        "phone_today_demo_light_sv",
        loaded,
        chrome = AppChrome(status = AppStatus.Demo, showDemoBanner = true),
    )

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun phoneNothingPlanned() = capture("phone_today_nothing_planned_light_sv", nothingPlanned)

    @Test
    fun phoneNothingPlannedDark() = capture("phone_today_nothing_planned_dark", nothingPlanned, darkTheme = true)

    @Test
    fun phoneOffline() = capture(
        "phone_today_offline_dark",
        sampleHomeState(offline = true),
        darkTheme = true,
        chrome = AppChrome(status = AppStatus.Offline),
    )

    @Test
    fun phoneLoading() = capture("phone_today_loading_light", HomeUiState())

    @Test
    @Config(qualifiers = MEDIUM)
    fun mediumPortrait() = capture("medium_today_light", loaded)

    @Test
    @Config(qualifiers = MEDIUM)
    fun mediumPortraitDark() = capture("medium_today_dark", loaded, darkTheme = true)

    @Test
    @Config(qualifiers = TABLET)
    fun tablet() = capture("tablet_today_light", loaded)

    @Test
    @Config(qualifiers = TABLET)
    fun tabletDark() = capture("tablet_today_dark", loaded, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$TABLET")
    fun tabletSwedish() = capture("tablet_today_light_sv", loaded)

    @Test
    @Config(qualifiers = TABLET)
    fun tabletNothingPlanned() = capture("tablet_today_nothing_planned_light", nothingPlanned)

    private fun capture(
        name: String,
        state: HomeUiState,
        darkTheme: Boolean = false,
        fontScale: Float? = null,
        chrome: AppChrome = AppChrome(),
        books: TodayBooksState = TodayBooksState(),
    ) {
        composeRule.setContent {
            WithFontScale(fontScale) {
                TandemTheme(darkTheme = darkTheme) {
                    CompositionLocalProvider(LocalAppChrome provides chrome) {
                        TodayScreen(state = state, onAction = {}, books = books)
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/home/$name.png")
    }

    @Composable
    private fun WithFontScale(fontScale: Float?, content: @Composable () -> Unit) {
        if (fontScale == null) {
            content()
        } else {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), content = content)
        }
    }
}
