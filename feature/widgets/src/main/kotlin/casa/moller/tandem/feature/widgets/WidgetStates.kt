package casa.moller.tandem.feature.widgets

import casa.moller.tandem.core.domain.format.FractionStyle
import casa.moller.tandem.core.domain.format.toNumberStyle
import casa.moller.tandem.core.domain.shopping.ShoppingLineFormat
import casa.moller.tandem.core.model.mealplan.MealPlanEntry
import casa.moller.tandem.core.model.mealplan.UpcomingMeals
import casa.moller.tandem.core.model.preferences.UserPreferences
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.core.model.shopping.ShoppingEntry
import java.util.Locale
import kotlin.time.Instant

// Fas 3 (widgets lane): what the widgets show, as plain values built from core:data's models, so
// the mapping is testable without Glance (docs/BYGGE.md "Widgets, snabbinställning och genvägar").

/**
 * The shopping list widget: the open entries in list order, at most [MAX_ROWS] shown.
 *
 * @property rows the entries to show ("2 dl grädde").
 * @property remaining how many entries are open in all (the header's "7 kvar").
 * @property signedIn false when there is no account and no demo: the widget says "Logga in".
 * @property readOnly a guest (plan 8.6): the rows have no check icon and a tap checks nothing.
 */
data class ShoppingWidgetState(
    val rows: List<Row> = emptyList(),
    val remaining: Int = 0,
    val signedIn: Boolean = true,
    val readOnly: Boolean = false,
) {
    /** One line: [text] as the list shows it; a tap checks entry [id]. */
    data class Row(val id: Long, val text: String)

    companion object {
        const val MAX_ROWS: Int = 12

        /**
         * The open (unchecked, not delayed) entries of [entries] at [now], in list order, written
         * as Handla writes them: [preferences]' amounts in [locale]'s number format (plan 6.7).
         */
        fun of(
            entries: List<ShoppingEntry>,
            now: Instant,
            signedIn: Boolean = true,
            readOnly: Boolean = false,
            locale: Locale = Locale.getDefault(),
            preferences: UserPreferences = UserPreferences.Default,
        ): ShoppingWidgetState {
            val style = locale.toNumberStyle(FractionStyle.TYPOGRAPHIC)
            val format = ShoppingLineFormat(style, preferences.useFractions, preferences.ingredientDecimals)
            val open = entries.filter { !it.checked && !it.isDelayed(now) }.sortedBy { it.order }
            return ShoppingWidgetState(
                rows = open.take(MAX_ROWS).map { Row(it.id, format.line(it)) },
                remaining = open.size,
                signedIn = signedIn,
                readOnly = readOnly,
            )
        }
    }
}

/**
 * The "I dag" widget: today's planned meals.
 *
 * @property meals today's meals in meal-type order; a tap opens the recipe when there is one.
 * @property signedIn false when there is no account and no demo.
 */
data class TonightWidgetState(val meals: List<Meal> = emptyList(), val signedIn: Boolean = true) {
    /** One meal: its [title] ("Krämig svamppasta"), the [mealType] ("Middag") and the recipe to open. */
    data class Meal(val id: Long, val title: String, val mealType: String, val recipeId: Long?)

    companion object {
        const val MAX_MEALS: Int = 4

        fun of(upcoming: UpcomingMeals, signedIn: Boolean = true): TonightWidgetState = TonightWidgetState(
            meals = upcoming.todayMeals
                .sortedWith(compareBy<MealPlanEntry> { it.mealType.order }.thenBy { it.mealType.time?.toSecondOfDay() ?: 0 })
                .take(MAX_MEALS)
                .map { Meal(it.id, it.displayTitle, it.mealType.name, it.recipe?.id) },
            signedIn = signedIn,
        )
    }
}

/** A recipe in the launcher's "senast lagade" shortcuts (plan 8.7). */
data class RecentRecipe(val id: Long, val name: String) {
    companion object {
        const val MAX_RECENT: Int = 2

        /** The newest cooked of [recipes] (they come sorted by last cooked, never cooked last). */
        fun of(recipes: List<RecipeOverview>): List<RecentRecipe> =
            recipes.filter { it.lastCooked != null }.take(MAX_RECENT).map { RecentRecipe(it.id, it.name) }
    }
}
