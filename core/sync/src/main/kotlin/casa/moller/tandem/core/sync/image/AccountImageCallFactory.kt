package casa.moller.tandem.core.sync.image

import casa.moller.tandem.core.network.di.TandoorImageHttpClient
import casa.moller.tandem.core.network.image.TandoorImageClientFactory
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSourceProvider
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The OkHttp side of Coil's network fetcher: every image request goes through the ACTIVE
 * account's image client from core:network ([TandoorImageClientFactory]: its own dispatcher and
 * rate limit, the token only for the account's own origin, a pause after repeated 4xx answers).
 * Without an account (demo mode, logged out) the base image client without any token is used.
 */
@Singleton
internal class AccountImageCallFactory @Inject constructor(
    private val sources: ActiveSourceProvider,
    private val clients: TandoorImageClientFactory,
    @TandoorImageHttpClient private val anonymous: OkHttpClient,
) : Call.Factory {
    @Volatile
    private var cached: Pair<String, OkHttpClient>? = null

    override fun newCall(request: Request): Call = clientForActiveAccount().newCall(request)

    private fun clientForActiveAccount(): OkHttpClient {
        val remote = sources.source.value as? ActiveSource.Remote ?: return anonymous
        val key = remote.accountId + "|" + remote.baseUrl
        cached?.takeIf { it.first == key }?.let { return it.second }
        val client = try {
            clients.create(remote.baseUrl, sources.tokenProvider(remote.accountId))
        } catch (e: IllegalArgumentException) {
            return anonymous
        }
        cached = key to client
        return client
    }
}
