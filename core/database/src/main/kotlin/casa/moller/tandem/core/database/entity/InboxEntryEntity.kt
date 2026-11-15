package casa.moller.tandem.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index

// Fas 5 (schema 7, rss lane; plan §7 #35, §8.4 "RSS-inkorg"): the recipe inbox's posts, cached so the
// inbox can be read offline. Only the Miniflux entry's id, its title, address, feed, date, one image
// address and a short plain-text excerpt are kept (never the post's HTML, never the API key).

/**
 * One post of the recipe inbox.
 *
 * @property entryId the Miniflux entry id (unique per Miniflux user; the account has one server).
 * @property unread the post was unread in a chosen category at the last refresh.
 * @property importedAt the user imported it on this phone ("Importerad"), epoch ms.
 * @property dismissedAt the user marked it read or dismissed it here, epoch ms: hidden.
 * @property readPending "read" still has to be sent to Miniflux (offline, or a failed request).
 * @property fetchedAt when the post was last seen in a refresh, epoch ms.
 */
@Entity(
    tableName = "inbox_entry",
    primaryKeys = ["account_id", "entry_id"],
    indices = [Index(value = ["account_id", "published_at"])],
)
data class InboxEntryEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "entry_id") val entryId: Long,
    @ColumnInfo(name = "category_id") val categoryId: Long?,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "feed_title") val feedTitle: String,
    @ColumnInfo(name = "published_at") val publishedAt: Long?,
    @ColumnInfo(name = "image_url") val imageUrl: String?,
    @ColumnInfo(name = "excerpt") val excerpt: String,
    @ColumnInfo(name = "unread") val unread: Boolean = true,
    @ColumnInfo(name = "imported_at") val importedAt: Long? = null,
    @ColumnInfo(name = "dismissed_at") val dismissedAt: Long? = null,
    @ColumnInfo(name = "read_pending") val readPending: Boolean = false,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long = 0,
)
