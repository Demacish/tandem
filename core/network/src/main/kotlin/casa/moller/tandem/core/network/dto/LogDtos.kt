package casa.moller.tandem.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /api/cook-log/?recipe=` (the whole SPACE's logs for the recipe) and the `201` of a
 * create (FACTS "Fas 1 f"). The source for "last cooked", "times cooked" and ratings.
 */
@Serializable
data class CookLogDto(
    val id: Long? = null,
    val recipe: Long? = null,
    val servings: Int? = null,
    /** 1-5, or null. */
    val rating: Int? = null,
    val comment: String? = null,
    @SerialName("created_by") val createdBy: UserDto? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/**
 * Body of `POST /api/cook-log/` (Phase 1b, sent through the outbox). Null fields are left out.
 * [createdAt] is writable: an entry queued offline keeps the time it was cooked
 * (`TandoorDates.format`).
 */
@Serializable
data class CookLogCreateRequestDto(
    val recipe: Long,
    val servings: Int? = null,
    val rating: Int? = null,
    val comment: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

/** `201` of `POST /api/view-log/`. The same recipe within 5 minutes returns the same [id]. */
@Serializable
data class ViewLogDto(
    val id: Long? = null,
    val recipe: Long? = null,
    @SerialName("created_by") val createdBy: Long? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

/** Body of `POST /api/view-log/`. */
@Serializable
data class ViewLogCreateRequestDto(val recipe: Long)
