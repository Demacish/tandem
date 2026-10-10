package casa.moller.tandem.core.data.server

import casa.moller.tandem.core.model.server.ServerCompatibility

/**
 * A server that answered the probe like Tandoor (plan 4.2 step 2). Input for the login calls of
 * [casa.moller.tandem.core.data.account.AccountRepository].
 *
 * Holds only plain values, so a ViewModel can keep it in a `SavedStateHandle` and survive
 * process death without probing again.
 *
 * @property baseUrl normalized `https://` base URL with trailing slash, e.g.
 *   `https://example.com/tandoor/`. It can differ from what the user typed: the probe adds
 *   `https://`, cuts pasted deep links and follows HTTPS redirects of the settings endpoint.
 * @property version `version` exactly as the server reported it, e.g. `2.6.13`.
 */
data class ServerInfo(val baseUrl: String, val version: String, val compatibility: ServerCompatibility) {
    /** The address without scheme and trailing slash, for display: `example.com/tandoor`. */
    val displayAddress: String
        get() = displayAddress(baseUrl)

    companion object {
        /** `https://example.com/tandoor/` -> `example.com/tandoor`. */
        fun displayAddress(baseUrl: String): String = baseUrl.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
    }
}
