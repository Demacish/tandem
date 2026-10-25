package casa.moller.tandem.core.network.api

import casa.moller.tandem.core.network.dto.FoodDto
import casa.moller.tandem.core.network.dto.IngredientParserRequestDto
import casa.moller.tandem.core.network.dto.IngredientParserResponseDto
import casa.moller.tandem.core.network.dto.PageDto
import casa.moller.tandem.core.network.dto.ShoppingBulkCheckDto
import casa.moller.tandem.core.network.dto.ShoppingListDto
import casa.moller.tandem.core.network.dto.ShoppingListEntryDto
import casa.moller.tandem.core.network.dto.ShoppingListRecipeDto
import casa.moller.tandem.core.network.dto.SupermarketCategoryDto
import casa.moller.tandem.core.network.dto.SupermarketCategoryRelationDto
import casa.moller.tandem.core.network.dto.SupermarketDto
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

// Phase 2 (inköp-data lane): the shopping list (plan 3.3, 3.5, FACTS "Fas 2" b-e). Paths are
// relative (sub-path installs) and listed in api-schema/used-endpoints.txt (UsedEndpointsTest).
// Writes go through the outbox; their bodies are the queued JSON payloads (JsonObject), so a
// change merged offline (OutboxMerge) is sent exactly as stored.

/** The shopping list and its recipes. */
interface ShoppingService {
    /**
     * The list (DRF pages with `timestamp`): unchecked entries and entries checked inside the
     * window (midnight UTC − min(`shopping_recent_days`, 14) days), at most 1000 (FACTS "Fas 2 b").
     *
     * @param updatedAfter the server's last `timestamp` for a delta (`updated_at >=`). Retrofit
     *   URL-encodes it (`+` → `%2B`); an unencoded `+` would make Tandoor return the whole list.
     */
    @GET("api/shopping-list-entry/")
    suspend fun entries(
        @Query("updated_after") updatedAfter: String?,
        @Query("page") page: Int?,
        @Query("page_size") pageSize: Int = TaxonomyService.MAX_PAGE_SIZE,
    ): PageDto<ShoppingListEntryDto>

    /** `201`: `{food: {name[, id]}, unit: {name} | null, amount, shopping_lists?, list_recipe?, ingredient?}`. */
    @POST("api/shopping-list-entry/")
    suspend fun createEntry(@Body body: JsonObject): ShoppingListEntryDto

    /** `200`; 404 when the entry was deleted (elsewhere). */
    @PATCH("api/shopping-list-entry/{id}/")
    suspend fun patchEntry(@Path("id") id: Long, @Body body: JsonObject): ShoppingListEntryDto

    /** `204` (empty body); 404 (an HttpException, so a typed NotFound) when it was already deleted. */
    @DELETE("api/shopping-list-entry/{id}/")
    suspend fun deleteEntry(@Path("id") id: Long)

    /** `{ids, checked}` → 200; deleted ids are ignored (never a 404). Bumps `updated_at`. */
    @POST("api/shopping-list-entry/bulk/")
    suspend fun bulkCheck(@Body body: ShoppingBulkCheckDto): ShoppingBulkCheckDto

    /** `{recipe, servings, name[, mealplan]}` → 201 with the id (entries are created one by one after it). */
    @POST("api/shopping-list-recipe/")
    suspend fun createListRecipe(@Body body: JsonObject): ShoppingListRecipeDto

    /** `{servings}` scales the recipe's entries on the server. */
    @PATCH("api/shopping-list-recipe/{id}/")
    suspend fun patchListRecipe(@Path("id") id: Long, @Body body: JsonObject): ShoppingListRecipeDto

    /** `204`; the server deletes the recipe's entries too. */
    @DELETE("api/shopping-list-recipe/{id}/")
    suspend fun deleteListRecipe(@Path("id") id: Long)

    /** The named lists (`{id, name, description, color}`), a small table. */
    @GET("api/shopping-list/")
    suspend fun lists(
        @Query("page") page: Int?,
        @Query("page_size") pageSize: Int = TaxonomyService.MAX_PAGE_SIZE,
    ): PageDto<ShoppingListDto>
}

/** Stores, aisles, a food's aisle and "har hemma", and the ingredient parser (FACTS "Fas 2" d, e). */
interface SupermarketService {
    /** Every store with `category_to_supermarket` (its aisle order). */
    @GET("api/supermarket/")
    suspend fun supermarkets(
        @Query("page") page: Int?,
        @Query("page_size") pageSize: Int = TaxonomyService.MAX_PAGE_SIZE,
    ): PageDto<SupermarketDto>

    @GET("api/supermarket-category/")
    suspend fun categories(
        @Query("page") page: Int?,
        @Query("page_size") pageSize: Int = TaxonomyService.MAX_PAGE_SIZE,
    ): PageDto<SupermarketCategoryDto>

    /** `{order}` → 200. */
    @PATCH("api/supermarket-category-relation/{id}/")
    suspend fun patchRelation(@Path("id") id: Long, @Body body: JsonObject): SupermarketCategoryRelationDto

    /** `{supermarket_category: {id, name} | null}` or `{food_onhand}` → 200 (for the whole space / household). */
    @PATCH("api/food/{id}/")
    suspend fun patchFood(@Path("id") id: Long, @Body body: JsonObject): FoodDto

    /** Parses free text; CREATES the foods and units it does not know (FACTS "Fas 2 e"). */
    @POST("api/ingredient-parser/post/")
    suspend fun parseIngredients(@Body body: IngredientParserRequestDto): IngredientParserResponseDto
}
