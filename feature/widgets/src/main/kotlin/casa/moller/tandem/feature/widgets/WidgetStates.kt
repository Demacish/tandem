package casa.moller.tandem.feature.widgets

import casa.moller.tandem.core.model.mealplan.MealPlanEntry
import casa.moller.tandem.core.model.mealplan.UpcomingMeals
import casa.moller.tandem.core.model.shopping.ShoppingEntry
import kotlin.time.Instant

// Fas 3 (widgets lane): what the widgets show, as plain values built from core:data's models, so
// the mapping is testable without Glance (docs/BYGGE.md "Widgets, snabbinställning och genvägar").

/**
 * The shopping list widget: the open entries in list order, at most [MAX_ROWS] shown.
 *
 * @property rows the entries to show ("2 dl grädde").
 * @property remaining how many entries are open in all (the header's "7 kvar").
 * @property signedIn false when there is no account and no demo: the widget says "Logga in".
 */
data class ShoppingWidgetState(val rows: List<Row> = emptyList(), val remaining: Int = 0, val signedIn: Boolean = true) {
    /** One line: [text] as the list shows it; a tap checks entry [id]. */
    data class Row(val id: Long, val text: String)

    companion object {
        const val MAX_ROWS: Int = 12

        /** The open (unchecked, not delayed) entries of [entries] at [now], in list order. */
        fun of(entries: List<ShoppingEntry>, now: Instant, signedIn: Boolean = true): ShoppingWidgetState {
            val open = entries.filter { !it.checked && !it.isDelayed(now) }.sortedBy { it.order }
            return ShoppingWidgetState(
                rows = open.take(MAX_ROWS).map { Row(it.id, lineOf(it)) },
                remaining = open.size,
                signedIn = signedIn,
            )
        }

        /** "2 dl grädde", "3 ägg", "salt" (plural names as the list uses them are left to the app). */
        fun lineOf(entry: ShoppingEntry): String = listOfNotNull(
            amountText(entry.amount),
            entry.unit?.name?.takeIf { entry.amount > 0 },
            entry.food.name,
        ).joinToString(" ")

        private fun amountText(amount: Double): String? = when {
            amount <= 0 -> null
            amount == amount.toLong().toDouble() -> amount.toLong().toString()
            else -> amount.toString().replace('.', ',')
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
