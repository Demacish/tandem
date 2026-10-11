package casa.moller.tandem.core.sync.account

import casa.moller.tandem.core.database.DatabaseKind
import casa.moller.tandem.core.model.account.ServerAccount
import casa.moller.tandem.core.model.server.WritePolicy
import casa.moller.tandem.core.network.api.TandoorApi
import casa.moller.tandem.core.network.capabilities.TandoorVersionTable

/**
 * Where the repositories read and write right now (plan 5.3, 6.2): nothing, the demo database, or
 * the real database plus the server of the logged-in account. Derived from the session.
 */
internal sealed interface ActiveSource {
    /** The session is not known yet (the first moments after app start). */
    data object Loading : ActiveSource

    /** Nobody is logged in and demo mode is off, or local storage needs recovery. */
    data object None : ActiveSource

    /** A source with its own rows in one of the two databases. */
    sealed interface Stored : ActiveSource {
        /** The `account_id` of every row this source reads and writes. */
        val accountId: String

        /** The database file the rows live in. */
        val kind: DatabaseKind
    }

    /** Demo mode: the demo database, filled from the bundled demo content. Never the network. */
    data object Demo : Stored {
        override val accountId: String = DEMO_ACCOUNT_ID
        override val kind: DatabaseKind = DatabaseKind.DEMO
    }

    /**
     * A logged-in account: the real database and the account's server.
     *
     * @property account the account as the session reports it (version updates included).
     * @property api the Retrofit services for the account's server, with its token.
     */
    data class Remote(val account: ServerAccount, val api: TandoorApi) : Stored {
        override val accountId: String
            get() = account.id

        override val kind: DatabaseKind
            get() = DatabaseKind.REAL

        /** The server's normalized base URL (trailing slash), e.g. for image URLs. */
        val baseUrl: String
            get() = api.baseUrl

        /** Identifies the account and its token: a new login (new token) clears an auth pause. */
        val authKey: String
            get() = account.id + "|" + account.tokenId

        /**
         * What happens to queued changes (plan 3.6): tested versions send, untested ones in the
         * supported range wait (safe mode), anything outside the range never sends.
         */
        val writePolicy: WritePolicy
            get() = TandoorVersionTable.assess(account.serverVersion).writePolicy
    }
}

/** The `account_id` of every row in the demo database. */
internal const val DEMO_ACCOUNT_ID: String = "demo"

/** A short identity for "the source changed" checks (an account's version update is no change). */
internal val ActiveSource.identity: String
    get() = when (this) {
        ActiveSource.Loading -> "loading"
        ActiveSource.None -> "none"
        ActiveSource.Demo -> "demo"
        is ActiveSource.Remote -> "remote:" + accountId + "|" + baseUrl
    }
