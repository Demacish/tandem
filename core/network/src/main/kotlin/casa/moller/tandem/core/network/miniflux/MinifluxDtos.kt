package casa.moller.tandem.core.network.miniflux

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Fas 5 (rss lane, plan §7 #35): the parts of Miniflux's API (https://miniflux.app/docs/api.html)
// the recipe inbox reads. Tolerant: every field but the ids has a default, unknown fields are
// ignored. Recorded answers: api-schema/fixtures/miniflux/<version>/ (MinifluxContractTest).

/** `GET /v1/me`: only proves that the key works; nothing of the user is kept. */
@Serializable
data class MinifluxUserDto(val id: Long, val username: String = "")

/**
 * `GET /v1/categories?counts=true`: `feed_count` and `total_unread` since Miniflux 2.0.46 (null before).
 */
@Serializable
data class MinifluxCategoryDto(
    val id: Long,
    val title: String = "",
    @SerialName("feed_count") val feedCount: Int? = null,
    @SerialName("total_unread") val totalUnread: Int? = null,
)

/** An entry's enclosure (podcast audio, an image ...). */
@Serializable
data class MinifluxEnclosureDto(val url: String = "", @SerialName("mime_type") val mimeType: String = "")

/** The entry's feed, with its category. */
@Serializable
data class MinifluxFeedDto(
    val id: Long = 0,
    val title: String = "",
    @SerialName("site_url") val siteUrl: String = "",
    val category: MinifluxCategoryDto? = null,
)

/**
 * One entry. [content] is the post's HTML as Miniflux sanitized it; the app never renders it, it
 * only takes a plain-text excerpt and the first image from it.
 */
@Serializable
data class MinifluxEntryDto(
    val id: Long,
    @SerialName("feed_id") val feedId: Long = 0,
    val status: String = "",
    val title: String = "",
    val url: String = "",
    val content: String = "",
    @SerialName("published_at") val publishedAt: String? = null,
    val enclosures: List<MinifluxEnclosureDto>? = null,
    val feed: MinifluxFeedDto? = null,
)

/** `GET /v1/categories/{id}/entries`: one page. */
@Serializable
data class MinifluxEntriesDto(val total: Int = 0, val entries: List<MinifluxEntryDto> = emptyList())

/** `PUT /v1/entries`: the body that marks entries read (204, no body). */
@Serializable
data class MinifluxEntryStatusDto(@SerialName("entry_ids") val entryIds: List<Long>, val status: String)
