package casa.moller.tandem.core.database.query

import casa.moller.tandem.core.database.relation.RecipeListItem
import casa.moller.tandem.core.model.recipe.RecipeSort
import java.text.Collator
import java.util.Locale

/**
 * Orders of the recipe list ([RecipeSort]), the same as the fake repository in core:testing:
 * ties and [RecipeSort.NAME] use a locale collator (Swedish: å, ä, ö after z), unknown values
 * (unrated, never cooked, no times, no creation time) come last.
 */
object RecipeSorting {
    /** A comparator for [sort]; names are compared with a [Collator] for [locale]. */
    fun comparator(sort: RecipeSort, locale: Locale): Comparator<RecipeListItem> {
        val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
        val byName = compareBy<RecipeListItem, String>(collator) { it.overview.name }
        return when (sort) {
            RecipeSort.NAME -> byName

            RecipeSort.RATING -> compareByDescending<RecipeListItem> { it.overview.rating ?: -1.0 }.then(byName)

            RecipeSort.LAST_COOKED -> compareByDescending<RecipeListItem> { it.overview.lastCookedEpochMillis }.then(byName)

            RecipeSort.TIMES_COOKED ->
                compareByDescending<RecipeListItem> { it.overview.timesCooked ?: -1 }
                    .thenByDescending { it.overview.lastCookedEpochMillis }
                    .then(byName)

            // By creation time, not by the stored `new` flag: that flag is only true for 7 days on
            // the server but is not re-read until the recipe changes again.
            RecipeSort.NEWEST ->
                compareByDescending<RecipeListItem> { it.overview.createdAtEpochMillis ?: Long.MIN_VALUE }
                    .thenByDescending { it.overview.serverId }

            RecipeSort.TOTAL_TIME ->
                compareBy<RecipeListItem> { item ->
                    val total = item.overview.workingMinutes + item.overview.waitingMinutes
                    if (total > 0) total else Int.MAX_VALUE
                }.then(byName)
        }
    }
}
