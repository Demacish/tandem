package casa.moller.tandem.core.network.api

import casa.moller.tandem.core.network.dto.CustomFilterDto
import casa.moller.tandem.core.network.dto.PageDto
import casa.moller.tandem.core.network.dto.RecipeBookDto
import casa.moller.tandem.core.network.dto.RecipeBookEntryDto
import casa.moller.tandem.core.network.dto.UserDto
import casa.moller.tandem.core.network.dto.UserSpaceDto
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

// Phase 2 (böcker lane): recipe books, favourites and "Vill laga" (plan 3.3, 3.7, FACTS "Fas 2 g")
// and the user's role in the space (FACTS "Fas 2 a", feature #42). Paths are relative (sub-path
// installs) and listed in api-schema/used-endpoints.txt (UsedEndpointsTest). Writes go through the
// outbox; their bodies are the queued JSON payloads (JsonObject). The book and entry LISTS are read
// with TaxonomyService (Phase 1).

/** Writes of books, entries, saved filters and a recipe's keywords; the space's users. */
interface BookService {
    /** `{name, description, shared: [{id}], filter}` → 201. `shared` is required (FACTS "Fas 1 h"). */
    @POST("api/recipe-book/")
    suspend fun createBook(@Body body: JsonObject): RecipeBookDto

    /** 204; 404 when it is gone; 403 for a book the user does not own. */
    @DELETE("api/recipe-book/{id}/")
    suspend fun deleteBook(@Path("id") id: Long)

    /** `{book, recipe}` → 201; 400 "unique set" when the recipe is in the book already. */
    @POST("api/recipe-book-entry/")
    suspend fun createEntry(@Body body: JsonObject): RecipeBookEntryDto

    /** 204; 404 when it is gone; 403 in someone else's book (FACTS "Fas 2 g"). */
    @DELETE("api/recipe-book-entry/{id}/")
    suspend fun deleteEntry(@Path("id") id: Long)

    /** The entries of every visible book that hold [recipe] (finds an entry whose 201 was lost). */
    @GET("api/recipe-book-entry/")
    suspend fun entriesOfRecipe(
        @Query("recipe") recipe: Long,
        @Query("page") page: Int?,
        @Query("page_size") pageSize: Int = TaxonomyService.MAX_PAGE_SIZE,
    ): PageDto<RecipeBookEntryDto>

    /** `{name, search, shared}`: `search` holds keyword IDS (`{"keywords_or":[8]}`), objects give a 500 later. */
    @POST("api/custom-filter/")
    suspend fun createFilter(@Body body: JsonObject): CustomFilterDto

    /**
     * `{keywords: [{id, name} ..., {name}]}`: the WHOLE keyword list (a keyword missing from it is
     * removed, a new name is created or reused by name). Every member may do this, a guest gets
     * 403 (FACTS "Fas 2 g"). Answers the full recipe as raw JSON, stored like a detail.
     */
    @PATCH("api/recipe/{id}/")
    suspend fun patchRecipe(@Path("id") id: Long, @Body body: JsonObject): JsonObject

    /** The users of the active space (an array, not paginated): who a favourites book is shared with. */
    @GET("api/user/")
    suspend fun spaceUsers(): List<UserDto>
}

/** The user's memberships (`/api/household/` is admin-only, FACTS "Fas 2 a"). */
interface UserSpaceService {
    /** One element per space of the caller, with `groups` (`guest`/`user`/`admin`) and `household`. */
    @GET("api/user-space/all_personal/")
    suspend fun allPersonal(): List<UserSpaceDto>
}
