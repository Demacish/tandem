package casa.moller.tandem.feature.mealplan

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.mealplan.MealPlanConflict
import casa.moller.tandem.core.model.mealplan.MealPlanDay
import casa.moller.tandem.core.model.mealplan.MealPlanEntry
import casa.moller.tandem.core.model.mealplan.MealPlanWeek
import casa.moller.tandem.core.model.mealplan.MealType
import casa.moller.tandem.core.model.mealplan.WeekShoppingIngredient
import casa.moller.tandem.core.model.mealplan.WeekShoppingMeal
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.core.ui.navigation.AppChrome
import casa.moller.tandem.core.ui.navigation.LocalAppChrome
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

/** Device configurations of the screenshot and accessibility tests (plan 6.5). */
internal object Devices {
    const val PHONE = "w412dp-h915dp-port-xhdpi"
    const val PHONE_TALL = "w412dp-h1800dp-port-xhdpi"
    const val PHONE_LANDSCAPE = "w915dp-h412dp-land-xhdpi"
    const val TABLET_PORTRAIT = "w800dp-h1280dp-port-mdpi"
    const val TABLET = "w1280dp-h800dp-land-mdpi"
}

/**
 * The Veckan tab's test data: week 43 of 2026 (Monday 19 October), today Thursday 22 October,
 * meals of every kind: with a recipe, title only ("Rester"), two on today (breakfast and dinner),
 * one waiting to sync, one on the shopping list, Saturday empty.
 */
internal object MealPlanSamples {
    val today = LocalDate(2026, 10, 22)
    val weekStart = LocalDate(2026, 10, 19)

    /** Noon UTC on [today]: the same day in every time zone a test may run in. */
    val now: Instant = Instant.parse("2026-10-22T12:00:00Z")

    val breakfast = MealType(1, "Frukost", 0, LocalTime(7, 30), "#D97706")
    val lunch = MealType(2, "Lunch", 1, LocalTime(12, 0), "#4D7C0F")
    val dinner = MealType(3, "Middag", 2, LocalTime(18, 0), "#C2410C")
    val fika = MealType(4, "Fika", 3, LocalTime(15, 0), "#8C5A3C")
    val mealTypes = listOf(breakfast, lunch, dinner, fika)

    val lentils = RecipeOverview(1, "Linsgryta med kokosmjölk", workingMinutes = 30)
    val tomatoSoup = RecipeOverview(2, "Tomatsoppa och surdegsbröd", workingMinutes = 25)
    val porridge = RecipeOverview(3, "Havregrynsgröt med äpple", workingMinutes = 10)
    val pasta = RecipeOverview(4, "Krämig svamppasta", workingMinutes = 35)
    val salmon = RecipeOverview(5, "Ugnsbakad lax med dillpotatis", workingMinutes = 30)
    val chicken = RecipeOverview(6, "Ugnsstekt citronkyckling", workingMinutes = 20, waitingMinutes = 45)
    val recipes = listOf(lentils, tomatoSoup, porridge, pasta, salmon, chicken)

    val monday = MealPlanEntry(11, recipe = lentils, servings = 4.0, fromDate = day(0), mealType = dinner)
    val tuesday = MealPlanEntry(12, recipe = tomatoSoup, servings = 4.0, fromDate = day(1), mealType = dinner)
    val leftovers = MealPlanEntry(13, title = "Rester", servings = 2.0, fromDate = day(2), mealType = lunch)
    val todayBreakfast = MealPlanEntry(14, recipe = porridge, servings = 2.0, fromDate = day(3), mealType = breakfast)
    val todayDinner = MealPlanEntry(
        15,
        recipe = pasta,
        servings = 4.0,
        note = "Dubbel sats, frys hälften",
        fromDate = day(3),
        mealType = dinner,
    )
    val friday = MealPlanEntry(-16, recipe = salmon, servings = 4.0, fromDate = day(4), mealType = dinner, pending = true)
    val sunday = MealPlanEntry(17, recipe = chicken, servings = 6.0, fromDate = day(6), mealType = dinner, onShoppingList = true)
    val meals = listOf(monday, tuesday, leftovers, todayBreakfast, todayDinner, friday, sunday)

    /** A meal in the next week (only that week is planned). */
    val nextWednesday = MealPlanEntry(18, recipe = chicken, servings = 6.0, fromDate = day(9), mealType = dinner)

    fun day(offset: Int): LocalDate = weekStart.plus(offset, DateTimeUnit.DAY)

    fun week(meals: List<MealPlanEntry> = this.meals, start: LocalDate = weekStart): List<MealPlanDay> =
        (0 until MealPlanWeek.DAYS).map { offset ->
            val date = start.plus(offset, DateTimeUnit.DAY)
            MealPlanDay(date, meals.filter { it.isOn(date) }.sortedBy { it.mealType.order })
        }

    /** The Veckan tab's state, as [WeekViewModel] builds it. */
    fun state(
        meals: List<MealPlanEntry> = this.meals,
        readOnly: Boolean = false,
        start: LocalDate = weekStart,
        move: MoveMealState? = null,
        conflict: ConflictState? = null,
        selectedDay: LocalDate? = null,
    ): WeekUiState = WeekUiState(
        loading = false,
        weekStart = start,
        today = today,
        days = week(meals, start),
        mealTypes = mealTypes,
        readOnly = readOnly,
        selectedDay = selectedDay,
        move = move,
        conflict = conflict,
    )

    val moveState = MoveMealState(todayDinner, date = day(5), mealTypeId = dinner.id)

    val conflictState = ConflictState(
        MealPlanConflict(todayDinner.id, "from_date", mine = day(5).toString(), theirs = day(4).toString()),
        mealTitle = pasta.name,
    )

    /** The plan-meal page with search results (a new meal on Saturday). */
    fun planState(
        kind: MealKind = MealKind.RECIPE,
        recipe: RecipeOverview? = null,
        title: String = "",
        editing: Boolean = false,
        showErrors: Boolean = false,
        readOnly: Boolean = false,
    ): PlanMealUiState = PlanMealUiState(
        loading = false,
        editing = editing,
        date = day(5),
        today = today,
        mealTypes = mealTypes,
        mealTypeId = dinner.id,
        kind = kind,
        kinds = if (editing && recipe != null) listOf(MealKind.RECIPE) else MealKind.entries,
        recipe = recipe,
        query = if (recipe == null && kind == MealKind.RECIPE) "la" else "",
        results = if (recipe == null) listOf(salmon, lentils, chicken) else emptyList(),
        title = title,
        servings = 4.0,
        note = if (editing) "Dubbel sats, frys hälften" else "",
        addToShopping = true,
        showErrors = showErrors,
        readOnly = readOnly,
    )

    val weekShopping: WeekShoppingUiState
        get() {
            val pastaMeal = WeekShoppingMeal(
                meal = todayDinner,
                alreadyOnList = false,
                selected = true,
                ingredients = listOf(
                    WeekShoppingIngredient(101, "pasta", 500.0, "g"),
                    WeekShoppingIngredient(102, "champinjoner", 250.0, "g"),
                    WeekShoppingIngredient(103, "grädde", 3.0, "dl"),
                    WeekShoppingIngredient(104, "salt", 0.0, null, onHand = true),
                ),
            )
            val salmonMeal = WeekShoppingMeal(meal = friday, alreadyOnList = false, selected = true, ingredients = emptyList())
            val chickenMeal = WeekShoppingMeal(meal = sunday, alreadyOnList = true, selected = false)
            val meals = listOf(pastaMeal, salmonMeal, chickenMeal).map { meal ->
                val chosen = meal.ingredients.filter { it.selected }.mapTo(LinkedHashSet()) { it.ingredientId }
                val selected = !meal.alreadyOnList && chosen.isNotEmpty()
                WeekShoppingMealState(meal, selected = selected, chosen = chosen, expanded = meal.meal.id == todayDinner.id)
            }
            return WeekShoppingUiState(loading = false, weekStart = weekStart, meals = meals, autoAdd = true)
        }
}

/** Theme, app chrome and font scale around a screen under test. */
@Composable
internal fun MealPlanTestTheme(
    darkTheme: Boolean = false,
    fontScale: Float = 1f,
    chrome: AppChrome = AppChrome(),
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale * density.fontScale)) {
        TandemTheme(darkTheme = darkTheme) {
            CompositionLocalProvider(LocalAppChrome provides chrome, content = content)
        }
    }
}
