package casa.moller.tandem.core.model.mealplan

import kotlin.time.Instant

// Fas 5 (kalender lane, plan §3.1, §7 #36): "Matsedeln i kalendern", two independent options,
// both off by default: a subscription link (a token of its own with the scope `mealplan`) and
// the plan written into a local calendar of the phone.

/** Who may use "Matsedeln i kalendern" right now. */
enum class MealCalendarAvailability {
    /** The session is not known yet. */
    LOADING,

    /** Nobody is logged in. */
    NO_ACCOUNT,

    /** Demo mode: neither option (no server, and nothing of the demo goes into the phone's calendar). */
    DEMO,

    /** A logged-in account. */
    AVAILABLE,
}

/** Why the subscription link cannot be offered. */
enum class MealCalendarLinkBlock {
    /** Guests (#42) only read; the link needs a token of their own on the server. */
    GUEST,

    /** This Tandoor cannot take the token in the address (`?access_token=`), so no calendar can subscribe. */
    UNSUPPORTED,
}

/**
 * The subscription link (option a).
 *
 * @property block why the link is not offered at all, null when it is.
 * @property url the feed's address with the token (`https://…/api/meal-plan/ical/?access_token=tda_…`), null when off.
 * @property expires when the link's token expires (renewed automatically while it is on), null when off or unknown.
 */
data class MealCalendarLink(val block: MealCalendarLinkBlock? = null, val url: String? = null, val expires: Instant? = null) {
    val isOn: Boolean
        get() = url != null

    /** The same address for calendar apps (`webcal://`), which subscribe instead of downloading once. */
    val webcalUrl: String?
        get() = url?.let { address ->
            when {
                address.startsWith("https://") -> "webcal://" + address.removePrefix("https://")
                address.startsWith("http://") -> "webcal://" + address.removePrefix("http://")
                else -> null
            }
        }
}

/** What stands in the way of the local calendar. */
enum class LocalMealCalendarProblem {
    /** READ_CALENDAR / WRITE_CALENDAR are not granted (any more). */
    PERMISSION,

    /** The phone has no calendar storage, or it refused the write. */
    PROVIDER,
}

/**
 * The plan in the phone's own calendar (option b).
 *
 * @property enabled the user turned it on (off by default; logging out and demo mode turn it off).
 * @property problem why the last write did not happen, null when it did (or it is off).
 * @property lastWritten when the calendar was last brought in step, null when never.
 * @property events how many meals the calendar holds after that write.
 */
data class LocalMealCalendar(
    val enabled: Boolean = false,
    val problem: LocalMealCalendarProblem? = null,
    val lastWritten: Instant? = null,
    val events: Int = 0,
)

/**
 * Everything "Matsedeln i kalendern" shows.
 *
 * @property availability who may use it.
 * @property link option a.
 * @property local option b.
 */
data class MealCalendarState(
    val availability: MealCalendarAvailability = MealCalendarAvailability.LOADING,
    val link: MealCalendarLink = MealCalendarLink(),
    val local: LocalMealCalendar = LocalMealCalendar(),
)
