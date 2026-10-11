package casa.moller.tandem.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * `GET /api/space/current/` (plan 3.1 households, 3.6 capabilities). Phase 1 reads the fields
 * that describe the space and its limits; logos and theme files are left as raw JSON.
 * [aiEnabled] and [householdSetupCompleted] feed the capability map
 * ([casa.moller.tandem.core.network.capabilities.TandoorCapabilities]).
 */
@Serializable
data class SpaceDto(
    val id: Long? = null,
    val name: String = "",
    @SerialName("created_by") val createdBy: UserDto? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val message: String = "",
    /** 0 = unlimited. */
    @SerialName("max_recipes") val maxRecipes: Int = 0,
    @SerialName("max_file_storage_mb") val maxFileStorageMb: Int = 0,
    @SerialName("max_users") val maxUsers: Int = 0,
    @SerialName("allow_sharing") val allowSharing: Boolean = true,
    val demo: Boolean = false,
    @SerialName("food_inherit") val foodInherit: JsonElement? = null,
    @SerialName("user_count") val userCount: Int = 0,
    @SerialName("recipe_count") val recipeCount: Int = 0,
    @SerialName("file_size_mb") val fileSizeMb: Double = 0.0,
    val image: JsonElement? = null,
    @SerialName("space_theme") val spaceTheme: String = "",
    @SerialName("nav_bg_color") val navBgColor: String = "",
    @SerialName("nav_text_color") val navTextColor: String = "",
    @SerialName("ai_credits_monthly") val aiCreditsMonthly: Int = 0,
    @SerialName("ai_credits_balance") val aiCreditsBalance: Double? = null,
    @SerialName("ai_monthly_credits_used") val aiMonthlyCreditsUsed: Int = 0,
    @SerialName("ai_enabled") val aiEnabled: Boolean? = null,
    @SerialName("ai_default_provider") val aiDefaultProvider: JsonElement? = null,
    @SerialName("space_setup_completed") val spaceSetupCompleted: Boolean? = null,
    @SerialName("household_setup_completed") val householdSetupCompleted: Boolean? = null,
)
