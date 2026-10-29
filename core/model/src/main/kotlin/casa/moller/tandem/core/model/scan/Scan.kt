package casa.moller.tandem.core.model.scan

import kotlinx.datetime.LocalDate

// Fas S (grund lane): the three scanning paths (plan §8.8 "Skanning", B21: barcode #27, receipt
// #44, fridge photo #45) end in ONE review list of [ScanReviewItem]s; nothing is saved before the
// user confirms it.

/** Where a review item came from. */
enum class ScanSource {
    BARCODE,
    RECEIPT,

    /** The fridge photo through the user's own AI key (experimental, B22). */
    FRIDGE,
}

/** How a review item found its food. */
enum class FoodMatchVia {
    /** A learned barcode or receipt name (`FOOD_ALIAS`, shared by the household's phones). */
    ALIAS,

    /** Tandoor's food search on the text (or the Open Food Facts product name). */
    SEARCH,

    /** Chosen or typed by the user in the review list. */
    MANUAL,

    /** A new food that Tandoor creates by name when the entry is saved. */
    NEW,
}

/** The food a review item is matched to; [id] null for a food Tandoor creates by [name] when saved. */
data class MatchedFood(val id: Long?, val name: String, val via: FoodMatchVia)

/**
 * One row of the shared review list (barcode, receipt and fridge photo).
 *
 * @property key stable id of the row inside one review (for lists and edits).
 * @property text what was scanned: the receipt line, the product name or the AI's item text.
 * @property food the matched food, null = not matched yet (the row must be matched or skipped).
 * @property amount the amount, null = the default (1).
 * @property unit the unit's name, null = the default unit.
 * @property locationId where it goes, null = the review's default location.
 * @property expires the best-before day, null = none (a barcode has no date, plan §8.8).
 * @property barcode the EAN of a barcode row (learned as an alias when the user matched it).
 * @property confidence the AI's confidence 0..1 (fridge photo only), null otherwise.
 * @property note free text from the source (the AI's note), not saved unless the user keeps it.
 * @property include false = skipped by the user.
 * @property learn true = remember [text] (or [barcode]) -> [food] as a `FOOD_ALIAS` when saving.
 */
data class ScanReviewItem(
    val key: String,
    val text: String,
    val source: ScanSource,
    val food: MatchedFood? = null,
    val amount: Double? = null,
    val unit: String? = null,
    val locationId: Long? = null,
    val expires: LocalDate? = null,
    val barcode: String? = null,
    val confidence: Double? = null,
    val note: String? = null,
    val include: Boolean = true,
    val learn: Boolean = false,
) {
    /** Can be saved: included and matched. */
    val ready: Boolean
        get() = include && food != null
}

/** A product from Open Food Facts (only the EAN was sent, plan §8.8). */
data class BarcodeProduct(val ean: String, val name: String?, val brand: String? = null, val quantity: String? = null)

/** The answer of a barcode lookup. */
sealed interface BarcodeLookupResult {
    data class Found(val product: BarcodeProduct) : BarcodeLookupResult

    /** Open Food Facts does not know the code (remembered for a while). */
    data object NotFound : BarcodeLookupResult
}

/** What a learned alias stands for (Tandoor automation `name`: "Tandem: streckkod" / "Tandem: kvitto"). */
enum class FoodAliasKind {
    BARCODE,
    RECEIPT,

    /** A FOOD_ALIAS made on the web: used for matching, not edited by the app. */
    OTHER,
}

/**
 * A learned barcode or receipt name: a `FOOD_ALIAS` automation (`param_1` = [alias], `param_2` =
 * the food's name). Matched without case and at most 128 characters (FACTS "Fas S d").
 */
data class FoodAlias(
    val id: Long,
    val alias: String,
    val foodName: String,
    val kind: FoodAliasKind,
    val foodId: Long? = null,
    val pending: Boolean = false,
) {
    companion object {
        /** Tandoor's limit for `param_1` and `param_2` (FACTS "Fas S d": 129 gives 400). */
        const val MAX_LENGTH: Int = 128
    }
}

/** One line of recognised receipt text, in reading order. */
data class ReceiptLine(val index: Int, val text: String)

/** What a receipt line is (core:domain `ReceiptLineFilter`). Only [ITEM] lines reach the review. */
enum class ReceiptLineKind {
    ITEM,
    TOTAL,
    VAT,
    DEPOSIT,
    DISCOUNT,
    PAYMENT,
    HEADER,
    OTHER,

    /** skanning lane: the weight or count of the item next to it ("1,234 kg x 24,90 kr/kg", "2 st x 12,90"). */
    QUANTITY,

    /** skanning lane: loyalty and member lines (member numbers, points); never shown or stored. */
    LOYALTY,

    /** skanning lane: dates and times of the purchase. */
    DATE,
}

/** A receipt line the filter kept as an item: the text without price, and the amount if one was read. */
data class ReceiptItem(val line: ReceiptLine, val name: String, val amount: Double? = null, val unit: String? = null)
