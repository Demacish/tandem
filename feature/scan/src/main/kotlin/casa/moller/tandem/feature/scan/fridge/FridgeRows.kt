package casa.moller.tandem.feature.scan.fridge

import casa.moller.tandem.core.model.ai.RecognizedItem
import casa.moller.tandem.core.model.recipe.Food
import casa.moller.tandem.core.model.scan.FoodMatchVia
import casa.moller.tandem.core.model.scan.ScanReviewItem
import casa.moller.tandem.core.model.scan.ScanSource
import casa.moller.tandem.feature.scan.ScanMatcher

/** Below this the AI was unsure: the row starts as skipped and is marked "Osäker" (plan §8.8, B22). */
internal const val LOW_CONFIDENCE: Double = 0.6

/** The AI was unsure about this fridge-photo row (marked in the review). */
internal val ScanReviewItem.lowConfidence: Boolean
    get() = source == ScanSource.FRIDGE && (confidence ?: 1.0) < LOW_CONFIDENCE

/**
 * The AI's items as review rows. EVERY row is a suggestion the user confirms in the review: a
 * learned name or a stored food that fits is offered as "Förslag" (never "Känd"), an unknown item
 * waits for a food, and a row the AI was unsure about starts as skipped (one tap takes it along).
 * Nothing from the AI is ever learned as an alias.
 */
internal suspend fun ScanMatcher.fridgeRows(items: List<RecognizedItem>, foods: List<Food>): List<ScanReviewItem> = items.map { item ->
    val match = learned(item.name, foods) ?: suggested(item.name, foods)
    val row = ScanReviewItem(
        key = ScanMatcher.newKey(),
        text = item.name,
        source = ScanSource.FRIDGE,
        food = match?.copy(via = FoodMatchVia.SEARCH),
        amount = item.amount,
        unit = item.unit,
        confidence = item.confidence,
        note = item.note,
        learn = false,
    )
    row.copy(include = !row.lowConfidence)
}
