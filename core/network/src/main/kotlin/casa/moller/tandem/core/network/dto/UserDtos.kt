package casa.moller.tandem.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /api/user/{id}/`, and the nested user in user-preference and space (FACTS d). */
@Serializable
data class UserDto(
    val id: Long? = null,
    val username: String = "",
    @SerialName("first_name") val firstName: String = "",
    @SerialName("last_name") val lastName: String = "",
    /** First + last name, or the username when both are empty. */
    @SerialName("display_name") val displayName: String = "",
    @SerialName("is_staff") val isStaff: Boolean = false,
    @SerialName("is_superuser") val isSuperuser: Boolean = false,
    @SerialName("is_active") val isActive: Boolean = true,
)

/**
 * One element of `GET /api/user-preference/` (an array with exactly the caller's entry).
 * `user` is a full [UserDto] object, not an id; this is how a pasted token learns who it is.
 */
@Serializable
data class UserPreferenceDto(val user: UserDto? = null)
