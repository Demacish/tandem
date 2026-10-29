package casa.moller.tandem.core.network.api

import casa.moller.tandem.core.network.dto.AutomationDto
import casa.moller.tandem.core.network.dto.FoodDto
import casa.moller.tandem.core.network.dto.InventoryEntryDto
import casa.moller.tandem.core.network.dto.InventoryLocationDto
import casa.moller.tandem.core.network.dto.InventoryLogDto
import casa.moller.tandem.core.network.dto.PageDto
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

// Fas S (hemma-data lane): the pantry, learned aliases and "har hemma" in batch (plan §8.8, FACTS
// "Fas S" b, d, e). Paths are relative (sub-path installs) and listed in
// api-schema/used-endpoints.txt (UsedEndpointsTest). Writes go through the outbox; their bodies are
// the queued JSON payloads (JsonObject), with the location expanded by the sender.

/** Locations, stock entries and the stock log (all space-wide on the server, FACTS "Fas S b"). */
interface InventoryService {
    @GET("api/inventory-location/")
    suspend fun locations(
        @Query("page") page: Int?,
        @Query("page_size") pageSize: Int = TaxonomyService.MAX_PAGE_SIZE,
    ): PageDto<InventoryLocationDto>

    /** `{name, is_freezer, household: {id, name}}` → 201; without `household` 400. */
    @POST("api/inventory-location/")
    suspend fun createLocation(@Body body: JsonObject): InventoryLocationDto

    /** `{name}` / `{is_freezer}` → 200 (the household stays). */
    @PATCH("api/inventory-location/{id}/")
    suspend fun patchLocation(@Path("id") id: Long, @Body body: JsonObject): InventoryLocationDto

    /** `204`; the location's entries are deleted with it (CASCADE). */
    @DELETE("api/inventory-location/{id}/")
    suspend fun deleteLocation(@Path("id") id: Long)

    /**
     * Entries with amount > 0, by `expires` (null last). With [empty] set to ANY value (also
     * `false`) the entries with amount 0 ("Slut") are listed too (FACTS "Fas S b"); null leaves it out.
     */
    @GET("api/inventory-entry/")
    suspend fun entries(
        @Query("page") page: Int?,
        @Query("page_size") pageSize: Int = TaxonomyService.MAX_PAGE_SIZE,
        @Query("food_id") foodId: Long? = null,
        @Query("inventory_location_id") locationId: Long? = null,
        @Query("empty") empty: Boolean? = null,
    ): PageDto<InventoryEntryDto>

    /**
     * `{inventory_location: {...the whole location}, food: {name[, id]}, unit: {name}, amount, expires,
     * note, sub_location}` → 201; without a unit or with `unit: null` 400.
     */
    @POST("api/inventory-entry/")
    suspend fun createEntry(@Body body: JsonObject): InventoryEntryDto

    /** `{amount}` ("Slut" = 0), `{note, expires}`, `{inventory_location: {...}}` (a move) → 200; 404 when deleted. */
    @PATCH("api/inventory-entry/{id}/")
    suspend fun patchEntry(@Path("id") id: Long, @Body body: JsonObject): InventoryEntryDto

    /** `204`; 404 when it was already deleted. */
    @DELETE("api/inventory-entry/{id}/")
    suspend fun deleteEntry(@Path("id") id: Long)

    /** The log of one entry ([entryId]) or one food ([foodId]), newest first. Read only (POST = 405). */
    @GET("api/inventory-log/")
    suspend fun log(
        @Query("entry_id") entryId: Long?,
        @Query("food_id") foodId: Long? = null,
        @Query("page") page: Int? = null,
        @Query("page_size") pageSize: Int = TaxonomyService.MAX_PAGE_SIZE,
    ): PageDto<InventoryLogDto>
}

/** Automations; the app uses only `FOOD_ALIAS` (learned barcodes and receipt names, FACTS "Fas S d"). */
interface AutomationService {
    @GET("api/automation/")
    suspend fun automations(
        @Query("type") type: String?,
        @Query("page") page: Int?,
        @Query("page_size") pageSize: Int = TaxonomyService.MAX_PAGE_SIZE,
    ): PageDto<AutomationDto>

    /** `{type: "FOOD_ALIAS", name, description, param_1, param_2}` → 201; more than 128 characters 400; guest 403. */
    @POST("api/automation/")
    suspend fun createAutomation(@Body body: JsonObject): AutomationDto

    /** `{param_2}` → 200; 404 when deleted. */
    @PATCH("api/automation/{id}/")
    suspend fun patchAutomation(@Path("id") id: Long, @Body body: JsonObject): AutomationDto

    /** `204`. */
    @DELETE("api/automation/{id}/")
    suspend fun deleteAutomation(@Path("id") id: Long)
}

/** "Har hemma" for many foods at once, and a new food by name (staples, FACTS "Fas S e"). */
interface PantryFoodService {
    /**
     * `{foods: [ids], on_hand: true|false}` → 200 with an EMPTY object `{}`; sets "har hemma" for the
     * whole household (the same visibility as `PATCH food_onhand`). Guest 403.
     */
    @PUT("api/food/batch_update/")
    suspend fun batchUpdate(@Body body: JsonObject): JsonObject

    /** `{name}` → 201 with the food (a staple the space has no food for yet). */
    @POST("api/food/")
    suspend fun createFood(@Body body: JsonObject): FoodDto
}
