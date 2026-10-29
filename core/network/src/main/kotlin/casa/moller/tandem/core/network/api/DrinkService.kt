package casa.moller.tandem.core.network.api

import casa.moller.tandem.core.network.dto.KeywordDto
import casa.moller.tandem.core.network.dto.KeywordMoveDto
import casa.moller.tandem.core.network.dto.RecipeImageDto
import kotlinx.serialization.json.JsonObject
import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path

// Fas S (drycker lane): drinks and "Passar med" (#43, B20, plan §8.8; FACTS "Fas S" c, f). Paths are
// relative (sub-path installs) and listed in api-schema/used-endpoints.txt (UsedEndpointsTest).
// Writes go through the outbox (`RECIPE_KEYWORDS` with pairing fields, `drink` CREATE and
// `RECIPE_IMAGE`). A recipe's keywords are PATCHed with BookService.patchRecipe (Phase 2).

/** Keywords under "Dryck" and "Passar med", a drink as a recipe and its photo. */
interface DrinkService {
    /**
     * `{name, description}` → 201 (get-or-create by name, case ignored: the same name answers the
     * same id). `parent` is read-only here and ignored: use [moveKeyword] (FACTS "Fas S c").
     */
    @POST("api/keyword/")
    suspend fun createKeyword(@Body body: JsonObject): KeywordDto

    /** One keyword with its `parent` and `full_name` ("Passar med > Kraftigt rött"). */
    @GET("api/keyword/{id}/")
    suspend fun keyword(@Path("id") id: Long): KeywordDto

    /** Moves keyword [id] under [parent] → 200 `{msg}` (the body is ignored). */
    @PUT("api/keyword/{id}/move/{parent}/")
    suspend fun moveKeyword(@Path("id") id: Long, @Path("parent") parent: Long, @Body body: JsonObject): KeywordMoveDto

    /**
     * A drink: `{name, description, keywords: [{name}...], servings, steps: [], internal: true}` →
     * 201 with the whole recipe as raw JSON (stored like a detail). Without `steps` 400; without
     * `internal` the server stores false (FACTS "Fas S f").
     */
    @POST("api/recipe/")
    suspend fun createRecipe(@Body body: JsonObject): JsonObject

    /** The recipe's photo, multipart `image` → 200 `{image, image_url}`; POST answers 405 (FACTS "Fas S f"). */
    @Multipart
    @PUT("api/recipe/{id}/image/")
    suspend fun uploadImage(@Path("id") id: Long, @Part image: MultipartBody.Part): RecipeImageDto
}
