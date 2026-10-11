package casa.moller.tandem.core.network.api

import casa.moller.tandem.core.network.dto.CookLogCreateRequestDto
import casa.moller.tandem.core.network.dto.CookLogDto
import casa.moller.tandem.core.network.dto.FoodDto
import casa.moller.tandem.core.network.dto.KeywordDto
import casa.moller.tandem.core.network.dto.MealPlanDto
import casa.moller.tandem.core.network.dto.MealTypeDto
import casa.moller.tandem.core.network.dto.PageDto
import casa.moller.tandem.core.network.dto.RecipeBookDto
import casa.moller.tandem.core.network.dto.RecipeBookEntryDto
import casa.moller.tandem.core.network.dto.UnitDto
import casa.moller.tandem.core.network.dto.UserPreferenceDto
import casa.moller.tandem.core.network.dto.ViewLogCreateRequestDto
import casa.moller.tandem.core.network.dto.ViewLogDto
import kotlinx.datetime.LocalDate
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

// Phase 1 services (plan 3.3). Paths are relative (sub-path installs) and every one is listed in
// api-schema/used-endpoints.txt (UsedEndpointsTest). Paginated lists take a 1-based `page`
// (null = first page) for casa.moller.tandem.core.network.paging.Paginator.

/**
 * The small tables, read in full (plan 3.5): DRF pages with `timestamp`, at most
 * [MAX_PAGE_SIZE] per page (plan 3.4, 4.1: big pages, few requests).
 */
interface TaxonomyService {
    /** All levels of the keyword tree (`parent` = id of the parent). */
    @GET("api/keyword/")
    suspend fun keywords(@Query("page") page: Int?, @Query("page_size") pageSize: Int = MAX_PAGE_SIZE): PageDto<KeywordDto>

    @GET("api/food/")
    suspend fun foods(@Query("page") page: Int?, @Query("page_size") pageSize: Int = MAX_PAGE_SIZE): PageDto<FoodDto>

    @GET("api/unit/")
    suspend fun units(@Query("page") page: Int?, @Query("page_size") pageSize: Int = MAX_PAGE_SIZE): PageDto<UnitDto>

    @GET("api/meal-type/")
    suspend fun mealTypes(@Query("page") page: Int?, @Query("page_size") pageSize: Int = MAX_PAGE_SIZE): PageDto<MealTypeDto>

    /** Only the user's own books and books shared with them (FACTS "Fas 1 h"). */
    @GET("api/recipe-book/")
    suspend fun recipeBooks(@Query("page") page: Int?, @Query("page_size") pageSize: Int = MAX_PAGE_SIZE): PageDto<RecipeBookDto>

    /** Entries of one book ([book]) or of every visible book (null). */
    @GET("api/recipe-book-entry/")
    suspend fun recipeBookEntries(
        @Query("book") book: Long?,
        @Query("page") page: Int?,
        @Query("page_size") pageSize: Int = MAX_PAGE_SIZE,
    ): PageDto<RecipeBookEntryDto>

    companion object {
        /** Most lists allow 200 per page (plan 3.4). */
        const val MAX_PAGE_SIZE: Int = 200
    }
}

/** The meal plan (plan 3.3, FACTS "Fas 1 g"). */
interface MealPlanService {
    /**
     * Entries whose `to_date` lies in [from]..[to] (both inclusive, plain dates). Without the
     * dates the server uses -90..+360 days, so always send them.
     */
    @GET("api/meal-plan/")
    suspend fun mealPlans(
        @Query("from_date") from: LocalDate,
        @Query("to_date") to: LocalDate,
        @Query("page") page: Int?,
        @Query("page_size") pageSize: Int = TaxonomyService.MAX_PAGE_SIZE,
    ): PageDto<MealPlanDto>
}

/** The user's Tandoor preferences, which the app follows (plan 3.2). */
interface PreferenceService {
    /** An array with exactly the caller's entry (FACTS d); answers 500 for an account without a space. */
    @GET("api/user-preference/")
    suspend fun userPreferences(): List<UserPreferenceDto>
}

/** Cook log and view log (FACTS "Fas 1 f"); writes go through the outbox. */
interface LogService {
    /** The whole space's cook logs (not only the caller's) for [recipe], or for every recipe when null. */
    @GET("api/cook-log/")
    suspend fun cookLogs(
        @Query("recipe") recipe: Long?,
        @Query("page") page: Int?,
        @Query("page_size") pageSize: Int = TaxonomyService.MAX_PAGE_SIZE,
    ): PageDto<CookLogDto>

    /** `201`. Not idempotent: a retry after a timeout can create a duplicate (plan 3.5). */
    @POST("api/cook-log/")
    suspend fun createCookLog(@Body body: CookLogCreateRequestDto): CookLogDto

    /** `201`; the server merges views of the same recipe within 5 minutes (same id). */
    @POST("api/view-log/")
    suspend fun createViewLog(@Body body: ViewLogCreateRequestDto): ViewLogDto
}

/**
 * Capability probes (plan 3.6, 4.1): list endpoints only, never an `{id}` path, so a missing
 * feature answers with Tandoor's 302 to the login page instead of a 404 (FACTS g). The body is
 * never parsed. Used only by [casa.moller.tandem.core.network.capabilities.CapabilityResolver].
 */
internal interface CapabilityProbeService {
    @GET("api/view-log/")
    suspend fun viewLogs(@Query("page_size") pageSize: Int = 1): Response<ResponseBody>

    @GET("api/household/")
    suspend fun households(@Query("page_size") pageSize: Int = 1): Response<ResponseBody>

    @GET("api/inventory-location/")
    suspend fun inventoryLocations(@Query("page_size") pageSize: Int = 1): Response<ResponseBody>
}
