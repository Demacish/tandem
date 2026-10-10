package casa.moller.tandem.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /api/space/current/`: only what Phase 0 needs (plan 3.1 households, 3.6 capabilities). */
@Serializable
data class SpaceDto(
    val id: Long? = null,
    val name: String = "",
    @SerialName("created_by") val createdBy: UserDto? = null,
    @SerialName("ai_enabled") val aiEnabled: Boolean? = null,
    @SerialName("ai_credits_balance") val aiCreditsBalance: Double? = null,
    @SerialName("space_setup_completed") val spaceSetupCompleted: Boolean? = null,
    @SerialName("household_setup_completed") val householdSetupCompleted: Boolean? = null,
)
