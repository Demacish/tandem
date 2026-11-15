package casa.moller.tandem.core.model.inbox

import kotlin.time.Instant

// Fas 5 (rss lane, plan §7 #35, §8.4 "RSS-inkorg"): the recipe inbox, a generic Miniflux client.
// Posts in the categories the user chose are listed (cached for offline reading) and one goes to
// the same import review as a shared link (#18) with two taps.

/**
 * One post in the inbox: an unread Miniflux entry of a chosen category, or one the user imported
 * (kept with "Importerad" until it is dismissed or 30 days old).
 *
 * @property id the entry's id on the Miniflux server.
 * @property url the post's own address: what the import sends to Tandoor's recipe-from-source.
 * @property feedTitle the feed (blog, site) the post comes from.
 * @property categoryId the Miniflux category, null for the demo inbox.
 * @property publishedAt when the feed says it was published.
 * @property imageUrl an image of the post (an image enclosure, or the first image in its text).
 * @property excerpt the start of the post's text, plain (no HTML), at most [EXCERPT_LENGTH] characters.
 * @property importedAt when the user imported it on this phone, null when not.
 */
data class InboxEntry(
    val id: Long,
    val title: String,
    val url: String,
    val feedTitle: String,
    val categoryId: Long? = null,
    val publishedAt: Instant? = null,
    val imageUrl: String? = null,
    val excerpt: String = "",
    val importedAt: Instant? = null,
) {
    val imported: Boolean
        get() = importedAt != null

    companion object {
        /** The longest excerpt kept per post. */
        const val EXCERPT_LENGTH: Int = 400
    }
}

/**
 * A Miniflux category the user can choose.
 *
 * @property unread unread posts in it, null when the server did not say (older than Miniflux 2.0.46).
 */
data class InboxCategory(val id: Long, val title: String, val unread: Int? = null)

/** Where the inbox can be used. */
enum class InboxAvailability {
    /** The session is not known yet. */
    LOADING,

    /** Nobody is logged in: the inbox belongs to an account. */
    NO_ACCOUNT,

    /** Demo mode: a small built-in inbox, nothing to set up, no network. */
    DEMO,

    /** A logged-in account (guests too: reading the inbox writes nothing to Tandoor). */
    AVAILABLE,
}

/** Why the inbox's server could not be used. The UI says what to do for each. */
enum class InboxProblem {
    /** The address is not an https address. Nothing was sent. */
    INVALID_URL,

    /** Miniflux refused the API key (401/403): stopped until a new key is entered. */
    AUTH,

    /** Something answered, but not Miniflux's API (an HTML page, a redirect, 404 on `/v1/me`). */
    NOT_MINIFLUX,

    /** The server could not be reached (no network, unknown host, timeout). */
    UNREACHABLE,

    /** The certificate is not trusted or the TLS handshake failed. */
    TLS,

    /** 429: Miniflux or something in front of it asked the app to wait. */
    THROTTLED,

    /** Miniflux answered 5xx. */
    SERVER,

    /** The inbox's own guard holds requests back after several failures (plan §5.4). */
    PAUSED,

    /** Any other failure. */
    OTHER,
}

/**
 * The inbox's settings and status for the active account.
 *
 * @property connected an API key is stored (the server answered to it when it was entered).
 * @property serverUrl the Miniflux address, normalized (https, trailing slash); never a key.
 * @property categories the chosen categories; the inbox is on when [connected] and there is at least one.
 * @property markReadOnImport after an import the post is marked read in Miniflux (on by default).
 * @property showOnToday "Nytt i inkorgen" on Idag (on by default once the inbox is set up).
 * @property lastRefresh when the posts were last read from the server.
 * @property problem the last refresh's problem, null after a success.
 */
data class InboxSettings(
    val connected: Boolean = false,
    val serverUrl: String? = null,
    val categories: List<InboxCategory> = emptyList(),
    val markReadOnImport: Boolean = true,
    val showOnToday: Boolean = true,
    val lastRefresh: Instant? = null,
    val problem: InboxProblem? = null,
) {
    /** Set up and in use: a key and at least one category. */
    val enabled: Boolean
        get() = connected && categories.isNotEmpty()
}

/** What the inbox screens show. */
data class InboxState(val availability: InboxAvailability = InboxAvailability.LOADING, val settings: InboxSettings = InboxSettings()) {
    /** The inbox has posts to show: set up for an account, or the demo's built-in one. */
    val inUse: Boolean
        get() = availability == InboxAvailability.DEMO || (availability == InboxAvailability.AVAILABLE && settings.enabled)
}
