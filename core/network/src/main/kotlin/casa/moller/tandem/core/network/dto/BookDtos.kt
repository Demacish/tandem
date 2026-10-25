package casa.moller.tandem.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Phase 2 (böcker lane): saved filters (smart books) and the user's memberships (FACTS "Fas 2 a",
// "Fas 2 g"). The books and entries themselves are RecipeBookDto / RecipeBookEntryDto (Phase 1).

/**
 * `POST /api/custom-filter/` (and a book's `filter`): a saved search. [search] is a JSON STRING
 * such as `{"keywords_or":[8]}`; [createdBy] is a plain user id here (not an object).
 */
@Serializable
data class CustomFilterDto(
    val id: Long? = null,
    val name: String = "",
    val search: String = "",
    val shared: List<UserDto> = emptyList(),
    @SerialName("created_by") val createdBy: Long? = null,
)

/** `{id, name}` of a Django group (`guest`, `user`, `admin`). */
@Serializable
data class GroupDto(val id: Long? = null, val name: String = "")

/** `{id, name}` of a household (Tandoor 2.6+); the timestamps are not used. */
@Serializable
data class HouseholdDto(val id: Long? = null, val name: String = "")

/**
 * One element of `GET /api/user-space/all_personal/`: the caller's membership in one space, with
 * the groups (the role, feature #42) and the household (`null` without one).
 */
@Serializable
data class UserSpaceDto(
    val id: Long? = null,
    val user: UserDto? = null,
    val space: Long? = null,
    val groups: List<GroupDto> = emptyList(),
    val household: HouseholdDto? = null,
    val active: Boolean = false,
)
