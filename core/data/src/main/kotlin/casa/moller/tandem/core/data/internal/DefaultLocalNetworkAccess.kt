package casa.moller.tandem.core.data.internal

import casa.moller.tandem.core.common.net.LocalNetworkRules
import casa.moller.tandem.core.data.server.LocalNetworkAccess
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.network.http.LocalNetworkGuard
import casa.moller.tandem.core.network.probe.ServerUrlNormalizer
import java.net.URI
import java.net.URISyntaxException
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [LocalNetworkAccess] over `core:network`'s [LocalNetworkGuard] (Fas 5, sdk37 lane). The host is
 * taken from the address exactly as the probe will read it ([ServerUrlNormalizer]).
 */
internal class DefaultLocalNetworkAccess(
    private val guard: LocalNetworkGuard,
    private val ioDispatcher: CoroutineDispatcher,
    private val resolveTimeout: Duration,
) : LocalNetworkAccess {

    @Inject
    constructor(guard: LocalNetworkGuard) : this(guard, Dispatchers.IO, RESOLVE_TIMEOUT)

    override val permission: String?
        get() = LocalNetworkRules.PERMISSION.takeIf { guard.enforced() }

    override fun permissionMissing(): Boolean = guard.permissionMissing()

    override suspend fun needsPermission(serverAddress: String): Boolean {
        if (!guard.permissionMissing()) return false
        val host = hostOf(serverAddress) ?: return false
        // The guard never throws (an unresolvable name is "not local"). The lookup runs on its own,
        // so a resolver that hangs costs at most [resolveTimeout]: a blocking DNS call cannot be cancelled.
        val lookup = CoroutineScope(ioDispatcher).async { guard.isLocal(host) }
        return withTimeoutOrNull(resolveTimeout) { lookup.await() } ?: false.also { lookup.cancel() }
    }

    /** The host of the normalized base URL (`https://host:port/path/`); IPv6 literals keep their brackets. */
    private fun hostOf(serverAddress: String): String? = when (val normalized = ServerUrlNormalizer.normalize(serverAddress)) {
        is Outcome.Success -> try {
            URI(normalized.value).host
        } catch (e: URISyntaxException) {
            null
        }

        is Outcome.Failure -> null
    }

    private companion object {
        /** A slow resolver must not hold the "Connect" button: the probe tells what happens anyway. */
        val RESOLVE_TIMEOUT: Duration = 3.seconds
    }
}
