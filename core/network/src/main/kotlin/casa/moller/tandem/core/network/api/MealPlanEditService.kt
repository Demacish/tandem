package casa.moller.tandem.core.network.api

import casa.moller.tandem.core.network.dto.MealPlanDto
import casa.moller.tandem.core.network.dto.MealTypeDto
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

// Phase 2 (matsedel lane): writing the meal plan (plan 3.3, 3.5, 6.2 "Veckan"; FACTS "Fas 1 g",
// "Fas 2 f"). Paths are relative (sub-path installs) and listed in api-schema/used-endpoints.txt.
// Writes go through the outbox; their bodies are the queued JSON payloads (JsonObject), so a change
// merged offline (OutboxMerge) is sent exactly as stored. Reading the window is MealPlanService.

/** One meal and the meal types (`/api/meal-plan/`, `/api/meal-type/`). */
interface MealPlanEditService {
    /** One meal (the field comparison before a PATCH, plan 3.5); 404 when it was deleted. */
    @GET("api/meal-plan/{id}/")
    suspend fun mealPlan(@Path("id") id: Long): MealPlanDto

    /**
     * `{title, recipe: {id, name} | null, servings, note, from_date, to_date?, meal_type: {id, name},
     * addshopping?}` → 201. A naive midnight `from_date` gets the meal type's time; `addshopping: true`
     * puts the recipe's ingredients on the list (the server ignores `mealplan_autoadd_shopping` here).
     */
    @POST("api/meal-plan/")
    suspend fun createMealPlan(@Body body: JsonObject): MealPlanDto

    /** Only the changed fields → 200. A move sends both dates (`from_date` alone leaves `to_date`). 404 when deleted. */
    @PATCH("api/meal-plan/{id}/")
    suspend fun patchMealPlan(@Path("id") id: Long, @Body body: JsonObject): MealPlanDto

    /** `204`; the meal's shopping list entries go with it. 404 when it was already deleted. */
    @DELETE("api/meal-plan/{id}/")
    suspend fun deleteMealPlan(@Path("id") id: Long)

    /** `{name, time?: "HH:mm", color?, order}` → 201 (`time` comes back as `HH:mm:ss`); shared by the whole space. */
    @POST("api/meal-type/")
    suspend fun createMealType(@Body body: JsonObject): MealTypeDto
}
