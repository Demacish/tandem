package casa.moller.tandem.core.network.http

/**
 * Supplies the token for `Authorization: Bearer <token>`. Called on OkHttp's threads for every
 * request to the configured server, so implementations must be fast and non-blocking (e.g. read
 * an in-memory copy of the decrypted token). Return null when logged out.
 */
fun interface TokenProvider {
    fun currentToken(): String?

    companion object {
        /** For calls that must never carry a token (probe, password login). */
        val NONE: TokenProvider = TokenProvider { null }
    }
}

/** A provider whose token can be swapped between calls; used inside the login flow. */
internal class MutableTokenProvider(initial: String? = null) : TokenProvider {
    @Volatile
    var token: String? = initial

    override fun currentToken(): String? = token
}
