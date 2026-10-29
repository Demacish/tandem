package casa.moller.tandem.feature.scan

import casa.moller.tandem.core.data.recipe.RecipeRepository
import casa.moller.tandem.core.data.scan.FoodAliasRepository
import casa.moller.tandem.core.domain.scan.FoodMatcher
import casa.moller.tandem.core.model.recipe.Food
import casa.moller.tandem.core.model.scan.FoodMatchVia
import casa.moller.tandem.core.model.scan.MatchedFood
import casa.moller.tandem.core.model.scan.ReceiptItem
import casa.moller.tandem.core.model.scan.ScanReviewItem
import casa.moller.tandem.core.model.scan.ScanSource
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Finds the food for a scanned text (plan §8.8): first a learned barcode or receipt name
 * (`FOOD_ALIAS`, shared by the household's phones), then the space's stored foods (core:domain
 * [FoodMatcher], on the device so it works offline and in demo mode). Nothing found = the row is
 * "okänd" and the user picks or creates the food in the review.
 */
class ScanMatcher @Inject constructor(private val aliases: FoodAliasRepository, private val recipes: RecipeRepository) {
    /** The space's foods from the last sync (demo: the demo foods). */
    suspend fun foods(): List<Food> = recipes.observeFoods().first()

    /** The learned food for [text] (a barcode or a printed receipt line), or null. */
    suspend fun learned(text: String, foods: List<Food>): MatchedFood? = aliases.find(text)?.let { alias ->
        val id = alias.foodId ?: foods.firstOrNull { it.name.equals(alias.foodName, ignoreCase = true) }?.id
        MatchedFood(id = id, name = alias.foodName, via = FoodMatchVia.ALIAS)
    }

    /** The stored food that fits [text] well enough, or null. */
    fun suggested(text: String, foods: List<Food>): MatchedFood? =
        FoodMatcher.best(text, foods)?.let { MatchedFood(it.id, it.name, FoodMatchVia.SEARCH) }

    /** Up to [limit] foods to offer for [text], best first. */
    fun suggestions(text: String, foods: List<Food>, limit: Int = SUGGESTIONS): List<Food> =
        FoodMatcher.rank(text, foods, limit).map { it.food }

    /** The receipt's items as review rows: learned name, else a suggestion, else unknown. Matches are learned on save. */
    suspend fun receiptRows(items: List<ReceiptItem>, foods: List<Food>): List<ScanReviewItem> = items.map { item ->
        val learned = learned(item.name, foods)
        ScanReviewItem(
            key = newKey(),
            text = item.name,
            source = ScanSource.RECEIPT,
            food = learned ?: suggested(item.name, foods),
            amount = item.amount,
            unit = item.unit,
            learn = learned == null,
        )
    }

    companion object {
        const val SUGGESTIONS: Int = 3

        fun newKey(): String = UUID.randomUUID().toString()
    }
}
