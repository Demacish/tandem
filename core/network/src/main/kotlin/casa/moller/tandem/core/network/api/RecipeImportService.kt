package casa.moller.tandem.core.network.api

import casa.moller.tandem.core.network.dto.RecipeFromSourceResponseDto
import casa.moller.tandem.core.network.dto.RecipeImageDto
import kotlinx.serialization.json.JsonObject
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path

/**
 * Fas 3 (import lane): Tandoor's import, which parses on the server and answers a recipe WITHOUT
 * creating it (FACTS "Fas 3 e-f"). Paths are relative (sub-path installs) and listed in
 * `api-schema/used-endpoints.txt` (UsedEndpointsTest). Online calls, never through the outbox.
 */
interface RecipeImportService {
    /**
     * `{url}` (the page is fetched and scraped: schema.org JSON-LD, site plugins) or `{data}` (a
     * text or raw HTML the app already has) → 200 `{recipe, images, duplicates}`; a page without
     * a recipe → 200 `{error: true, msg}` (observed, FACTS "Fas 3 e"). Also answers 400 for a
     * body with neither.
     */
    @POST("api/recipe-from-source/")
    suspend fun fromSource(@Body body: JsonObject): RecipeFromSourceResponseDto

    /**
     * A photo or PDF (multipart `file`) and/or `text`, read by the space's AI provider
     * `ai_provider_id` (`space/current` `ai_default_provider`), → 200 in the same shape. Without a
     * working provider the server answers an error (FACTS "Fas 3 f").
     */
    @Multipart
    @POST("api/ai-import/")
    suspend fun aiImport(
        @Part("ai_provider_id") providerId: RequestBody,
        @Part file: MultipartBody.Part?,
        @Part("text") text: RequestBody?,
    ): RecipeFromSourceResponseDto

    /**
     * The recipe's picture from the WEB (an import's `image_url`): the server downloads it
     * (multipart field `image_url`, the same endpoint as the photo upload) → 200 `{image, image_url}`.
     */
    @Multipart
    @PUT("api/recipe/{id}/image/")
    suspend fun setImageUrl(@Path("id") id: Long, @Part("image_url") imageUrl: RequestBody): RecipeImageDto
}
