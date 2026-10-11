package casa.moller.tandem.core.sync.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [NetworkStatus] from Android's default network callback. "Online" means the default network
 * has the internet capability; it is not validated (a private network without a captive portal
 * check would otherwise count as offline). Requests still fail cleanly when the server cannot be
 * reached; this only spares requests that cannot succeed.
 */
@Singleton
internal class ConnectivityMonitor @Inject constructor(@ApplicationContext context: Context) : NetworkStatus {
    private val manager: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)
    private val state = MutableStateFlow(currentlyOnline())

    override val online: StateFlow<Boolean> = state.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            state.value = true
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            state.value = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        override fun onLost(network: Network) {
            state.value = false
        }
    }

    init {
        try {
            manager?.registerDefaultNetworkCallback(callback)
        } catch (e: SecurityException) {
            // Without ACCESS_NETWORK_STATE the app assumes it is online; requests fail cleanly.
            state.value = true
        } catch (e: RuntimeException) {
            // Too many callbacks registered (never expected for this singleton): same fallback.
            state.value = true
        }
    }

    private fun currentlyOnline(): Boolean {
        val connectivity = manager ?: return true
        return try {
            val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } catch (e: SecurityException) {
            true
        }
    }
}
