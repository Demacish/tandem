package casa.moller.tandem.core.network.http

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import casa.moller.tandem.core.common.net.IpPrefix
import casa.moller.tandem.core.common.net.LocalNetworkRules
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [LocalNetworkGuard] on the phone (Fas 5, sdk37 lane): the permission from the package manager,
 * the addresses from the system resolver and the active network's directly connected routes from
 * [ConnectivityManager]. The decisions are [LocalNetworkRules] (unit tested in `core:common`).
 *
 * Nothing here is logged: host names and addresses of the user's server stay on the device.
 */
@Singleton
class AndroidLocalNetworkGuard @Inject constructor(@ApplicationContext private val context: Context) : LocalNetworkGuard {

    override fun enforced(): Boolean = LocalNetworkRules.enforced(Build.VERSION.SDK_INT, context.applicationInfo.targetSdkVersion)

    override fun permissionMissing(): Boolean = LocalNetworkRules.permissionMissing(
        sdkInt = Build.VERSION.SDK_INT,
        targetSdk = context.applicationInfo.targetSdkVersion,
        granted = context.checkSelfPermission(LocalNetworkRules.PERMISSION) == PackageManager.PERMISSION_GRANTED,
    )

    override fun isLocal(host: String): Boolean {
        val network = activeNetwork()
        return LocalNetworkRules.isLocalHost(
            host = host,
            resolve = { name -> InetAddress.getAllByName(name).map { it.address } },
            vpnActive = network.vpn,
            connectedPrefixes = network.connectedPrefixes,
        )
    }

    private class ActiveNetwork(val vpn: Boolean, val connectedPrefixes: List<IpPrefix>)

    /**
     * Whether a VPN carries the traffic, and the directly connected routes (no gateway, not a
     * default route) of the active network when it is Wi-Fi or Ethernet, the networks Android
     * calls local. Errors or a missing service give "no VPN, no routes": the address rules alone.
     */
    private fun activeNetwork(): ActiveNetwork = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching NO_NETWORK
        val network = manager.activeNetwork ?: return@runCatching NO_NETWORK
        val capabilities = manager.getNetworkCapabilities(network)
        val vpn = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val broadcastCapable = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val prefixes = if (vpn || !broadcastCapable) {
            emptyList()
        } else {
            manager.getLinkProperties(network)?.routes.orEmpty()
                .filter { !it.hasGateway() && it.destination.prefixLength > 0 }
                .map { IpPrefix(it.destination.address.address, it.destination.prefixLength) }
        }
        ActiveNetwork(vpn = vpn, connectedPrefixes = prefixes)
    }.getOrDefault(NO_NETWORK)

    private companion object {
        val NO_NETWORK = ActiveNetwork(vpn = false, connectedPrefixes = emptyList())
    }
}
