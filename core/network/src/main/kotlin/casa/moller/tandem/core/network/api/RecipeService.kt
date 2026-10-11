package casa.moller.tandem.core.network.api

import casa.moller.tandem.core.network.dto.PageDto
import casa.moller.tandem.core.network.dto.RecipeDto
import casa.moller.tandem.core.network.dto.RecipeFlatDto
import casa.moller.tandem.core.network.dto.RecipeOverviewDto
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Recipes (plan 3.3, FACTS "Fas 1 b-d"). Every path must be listed in
 * `api-schema/used-endpoints.txt` (UsedEndpointsTest).
 *
 * Call [list] through the [RecipeListQuery] overload; the raw method takes every filter one by
 * one so that Retrofit can repeat the array parameters (`keywords_or=1&keywords_or=2`).
 */
interface RecipeService {
    /**
     * Every recipe the user may see, as `{id, name, image}`: unpaginated, sorted by name, with a
     * RELATIVE image path. The sync's index and its only way to notice deletions (no tombstones).
     */
    @GET("api/recipe/flat/")
    suspend fun flat(): List<RecipeFlatDto>

    /** `GET /api/recipe/` with every filter of plan 3.3; null and empty values are left out. */
    @GET("api/recipe/")
    suspend fun list(
        @Query("query") query: String?,
        @Query("keywords_or") keywordsOr: List<Long>?,
        @Query("keywords_and") keywordsAnd: List<Long>?,
        @Query("keywords_or_not") keywordsOrNot: List<Long>?,
        @Query("keywords_and_not") keywordsAndNot: List<Long>?,
        @Query("foods_or") foodsOr: List<Long>?,
        @Query("foods_and") foodsAnd: List<Long>?,
        @Query("foods_or_not") foodsOrNot: List<Long>?,
        @Query("foods_and_not") foodsAndNot: List<Long>?,
        @Query("books_or") booksOr: List<Long>?,
        @Query("books_and") booksAnd: List<Long>?,
        @Query("include_children") includeChildren: Boolean?,
        @Query("rating_gte") ratingAtLeast: Int?,
        @Query("rating_lte") ratingAtMost: Int?,
        @Query("timescooked_gte") timesCookedAtLeast: Int?,
        @Query("timescooked_lte") timesCookedAtMost: Int?,
        @Query("cookedon_gte") cookedOnOrAfter: LocalDate?,
        @Query("cookedon_lte") cookedOnOrBefore: LocalDate?,
        @Query("viewedon_gte") viewedOnOrAfter: LocalDate?,
        @Query("createdon_gte") createdOnOrAfter: LocalDate?,
        @Query("updatedon_gte") updatedOnOrAfter: LocalDate?,
        @Query("makenow") makeNow: Boolean?,
        @Query("new") newFirst: Boolean?,
        @Query("random") random: Boolean?,
        @Query("internal") internalOnly: Boolean?,
        @Query("num_recent") numRecent: Int?,
        @Query("filter") customFilter: Long?,
        @Query("sort_order") sortOrder: String?,
        @Query("page") page: Int?,
        @Query("page_size") pageSize: Int?,
    ): PageDto<RecipeOverviewDto>

    /** `GET /api/recipe/{id}/`. 404 = deleted or never existed; 403 + JSON = someone else's private recipe. */
    @GET("api/recipe/{id}/")
    suspend fun recipe(@Path("id") id: Long): RecipeDto

    /**
     * The same request as [recipe], as raw JSON, for storing it unchanged (plan 3.6). Decode it
     * with `TandoorJson.decodeFromJsonElement(RecipeDto.serializer(), json)`.
     */
    @GET("api/recipe/{id}/")
    suspend fun recipeJson(@Path("id") id: Long): JsonObject
}

/**
 * `sort_order` values of `GET /api/recipe/` that work on 2.6.13 (FACTS "Fas 1 c").
 * `times_cooked`/`-times_cooked` are deliberately missing: the server answers 500. Use
 * [RecipeListQuery.timesCookedAtLeast] = 1 with [LAST_COOKED_DESC] and count from the cook log.
 */
enum class RecipeSortOrder(val apiValue: String) {
    SCORE_DESC("-score"),
    NAME("name"),
    NAME_DESC("-name"),
    LAST_COOKED("lastcooked"),
    LAST_COOKED_DESC("-lastcooked"),
    RATING("rating"),
    RATING_DESC("-rating"),
    CREATED("created_at"),
    CREATED_DESC("-created_at"),
    LAST_VIEWED("lastviewed"),
    LAST_VIEWED_DESC("-lastviewed"),
}

/**
 * A recipe search for `GET /api/recipe/` (plan 3.3). Dates are plain dates on purpose: with a
 * time `updatedon_gte` answers 500 (FACTS "Fas 1 c").
 *
 * Notes from FACTS: [text] is a fuzzy (trigram) match on the name; [newFirst] only SORTS new
 * recipes first, filter on `RecipeOverviewDto.isNew` yourself; `keywords_or`/`books_or` include
 * child keywords unless [includeChildren] is false; [cookedOnOrBefore] leaves out recipes never
 * cooked.
 *
 * @property pageSize clamped to 1..[MAX_PAGE_SIZE] when sent.
 */
data class RecipeListQuery(
    val text: String? = null,
    val keywordsOr: Set<Long> = emptySet(),
    val keywordsAnd: Set<Long> = emptySet(),
    val keywordsOrNot: Set<Long> = emptySet(),
    val keywordsAndNot: Set<Long> = emptySet(),
    val foodsOr: Set<Long> = emptySet(),
    val foodsAnd: Set<Long> = emptySet(),
    val foodsOrNot: Set<Long> = emptySet(),
    val foodsAndNot: Set<Long> = emptySet(),
    val booksOr: Set<Long> = emptySet(),
    val booksAnd: Set<Long> = emptySet(),
    val includeChildren: Boolean? = null,
    val ratingAtLeast: Int? = null,
    val ratingAtMost: Int? = null,
    val timesCookedAtLeast: Int? = null,
    val timesCookedAtMost: Int? = null,
    val cookedOnOrAfter: LocalDate? = null,
    val cookedOnOrBefore: LocalDate? = null,
    val viewedOnOrAfter: LocalDate? = null,
    val createdOnOrAfter: LocalDate? = null,
    val updatedOnOrAfter: LocalDate? = null,
    val makeNow: Boolean? = null,
    val newFirst: Boolean? = null,
    val random: Boolean? = null,
    val internalOnly: Boolean? = null,
    val numRecent: Int? = null,
    val customFilter: Long? = null,
    val sortOrder: RecipeSortOrder? = null,
    val pageSize: Int = MAX_PAGE_SIZE,
) {
    companion object {
        /** `recipe/` refuses larger pages (plan 3.4); the default is 25. */
        const val MAX_PAGE_SIZE: Int = 100
    }
}

/** [RecipeService.list] for [query]; [page] is 1-based, null for the first page. */
suspend fun RecipeService.list(query: RecipeListQuery, page: Int? = null): PageDto<RecipeOverviewDto> = list(
    query = query.text?.trim()?.takeIf { it.isNotEmpty() },
    keywordsOr = query.keywordsOr.sortedOrNull(),
    keywordsAnd = query.keywordsAnd.sortedOrNull(),
    keywordsOrNot = query.keywordsOrNot.sortedOrNull(),
    keywordsAndNot = query.keywordsAndNot.sortedOrNull(),
    foodsOr = query.foodsOr.sortedOrNull(),
    foodsAnd = query.foodsAnd.sortedOrNull(),
    foodsOrNot = query.foodsOrNot.sortedOrNull(),
    foodsAndNot = query.foodsAndNot.sortedOrNull(),
    booksOr = query.booksOr.sortedOrNull(),
    booksAnd = query.booksAnd.sortedOrNull(),
    includeChildren = query.includeChildren,
    ratingAtLeast = query.ratingAtLeast,
    ratingAtMost = query.ratingAtMost,
    timesCookedAtLeast = query.timesCookedAtLeast,
    timesCookedAtMost = query.timesCookedAtMost,
    cookedOnOrAfter = query.cookedOnOrAfter,
    cookedOnOrBefore = query.cookedOnOrBefore,
    viewedOnOrAfter = query.viewedOnOrAfter,
    createdOnOrAfter = query.createdOnOrAfter,
    updatedOnOrAfter = query.updatedOnOrAfter,
    makeNow = query.makeNow,
    newFirst = query.newFirst,
    random = query.random,
    internalOnly = query.internalOnly,
    numRecent = query.numRecent,
    customFilter = query.customFilter,
    sortOrder = query.sortOrder?.apiValue,
    page = page?.takeIf { it > 1 },
    pageSize = query.pageSize.coerceIn(1, RecipeListQuery.MAX_PAGE_SIZE),
)

private fun Set<Long>.sortedOrNull(): List<Long>? = if (isEmpty()) null else sorted()
