package casa.moller.tandem.core.network.capabilities

import casa.moller.tandem.core.model.server.ServerCompatibility
import java.net.URLEncoder

/**
 * Fas 5 (kalender lane, plan §3.1, §7 #36): Tandoor's meal plan feed for a calendar subscription
 * (FACTS "Fas 5: matsedeln i kalendern", `Phase5IcalContractTest`).
 *
 * - `GET api/meal-plan/ical/` answers `text/calendar` and opens ONLY with a token of the scope
 *   [SCOPE] (a `read write` token gets 403; a `mealplan` token opens nothing else).
 * - A calendar can only subscribe to an address, so the token goes in the query as [TOKEN_PARAMETER]
 *   (OAuth 2 bearer in the query, RFC 6750 §2.3, which django-oauth-toolkit accepts); other names
 *   are refused. Without `from_date`/`to_date` the feed covers −90..+360 days from the moment it is
 *   read, so the address holds no date and never goes stale.
 * - Seen on 2.6.13 and 2.7.1 (and 2.6.15, 2.7.2, `latest` in the watch); every version the app
 *   accepts is assumed to work like them, the watch flags a change ([tokenInUrl]).
 */
object MealPlanIcal {
    /** The feed, relative to the server's base URL (sub-path installs work). */
    const val PATH: String = "api/meal-plan/ical/"

    /** The query parameter that carries the token. */
    const val TOKEN_PARAMETER: String = "access_token"

    /** The only scope the feed accepts. */
    const val SCOPE: String = "mealplan"

    /** Whether a server of [assessment] takes the token in the address (else the link is not offered). */
    fun tokenInUrl(assessment: VersionAssessment): Boolean = assessment.compatibility != ServerCompatibility.TOO_OLD

    /** The subscription address: [baseUrl] (with or without the trailing slash) + [PATH] + the token. */
    fun subscriptionUrl(baseUrl: String, token: String): String =
        baseUrl.trimEnd('/') + "/" + PATH + "?" + TOKEN_PARAMETER + "=" + URLEncoder.encode(token, Charsets.UTF_8)
}
