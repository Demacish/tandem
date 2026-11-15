package casa.moller.tandem.core.common.net

/**
 * Android 17's local network protection (Fas 5, sdk37 lane; docs/BYGGE.md "Target SDK 37").
 *
 * An app that targets API 37 may only reach the LOCAL network (a self-hosted Tandoor at home) with
 * the runtime permission `ACCESS_LOCAL_NETWORK` (permission group "Nearby devices"). Without it a
 * TCP connection to a local address just times out, so Tandem decides itself when the address is
 * local: before connecting (the intro asks for the permission in context) and after a failed
 * connection (the error says what to allow instead of "timeout").
 *
 * The rules follow Android's published definition (developer.android.com, "Local Network
 * Definition"): a broadcast-capable network (Wi-Fi, Ethernet; never cellular or a VPN) with
 * - IPv4 169.254.0.0/16 (link-local), 100.64.0.0/10 (CGNAT), 10.0.0.0/8, 172.16.0.0/12 and
 *   192.168.0.0/16 (RFC 1918), multicast 224.0.0.0/4 and the broadcast address 255.255.255.255;
 * - IPv6 link-local, multicast ff00::/8 and directly connected routes ([connectedPrefixes]);
 * - names ending in `.local` (mDNS: resolving them already needs the permission).
 *
 * Two choices of Tandem's own, where Android's rule depends on the route the system picks:
 * - **IPv6 unique local addresses (fc00::/7)** count as local: at home they are the on-link
 *   prefix. Asking once too often is cheaper than a server that silently times out.
 * - **100.64.0.0/10 does not count while a VPN is the active network**: overlay VPNs (for example
 *   a mesh VPN) hand out these addresses, and traffic through a VPN is not local network traffic.
 *
 * Loopback (127.0.0.0/8, ::1) is never local network traffic (the release smoke test's fake
 * Tandoor runs on `localhost`).
 */
object LocalNetworkRules {

    /** Android 17, the first release that enforces the protection. */
    const val ENFORCED_FROM_SDK: Int = 37

    /** The permission's name (`Manifest.permission.ACCESS_LOCAL_NETWORK`, API 37). */
    const val PERMISSION: String = "android.permission.ACCESS_LOCAL_NETWORK"

    /** Whether this device and this build need the permission at all (Android 17+ AND target 37+). */
    fun enforced(sdkInt: Int, targetSdk: Int): Boolean = sdkInt >= ENFORCED_FROM_SDK && targetSdk >= ENFORCED_FROM_SDK

    /** True when the local network is closed to this app right now: enforced and not granted. */
    fun permissionMissing(sdkInt: Int, targetSdk: Int, granted: Boolean): Boolean = enforced(sdkInt, targetSdk) && !granted

    /** A multicast DNS name (`tandoor.local`, also with a trailing dot). */
    fun isMdnsName(host: String): Boolean = host.trim().trimEnd('.').endsWith(".local", ignoreCase = true)

    /**
     * Whether traffic to [address] (4 or 16 bytes, network order) is local network traffic.
     *
     * @param vpnActive the active network is a VPN (see the class comment on 100.64.0.0/10).
     * @param connectedPrefixes the directly connected routes of the active Wi-Fi or Ethernet network.
     */
    fun isLocalAddress(address: ByteArray, vpnActive: Boolean = false, connectedPrefixes: List<IpPrefix> = emptyList()): Boolean {
        val local = when (address.size) {
            IPV4_BYTES -> isLocalIpv4(address, vpnActive)
            IPV6_BYTES -> ipv4Mapped(address)?.let { isLocalIpv4(it, vpnActive) } ?: isLocalIpv6(address)
            else -> false
        }
        return local || (!isLoopback(address) && connectedPrefixes.any { it.contains(address) })
    }

    /**
     * Whether reaching [host] is local network traffic: an mDNS name, or a name or literal whose
     * addresses ([resolve]) are local. A host that cannot be resolved is not local (the caller
     * reports "unknown host" as before). [resolve] may block (DNS): never call this on the main
     * thread.
     */
    fun isLocalHost(
        host: String,
        resolve: (String) -> List<ByteArray>,
        vpnActive: Boolean = false,
        connectedPrefixes: List<IpPrefix> = emptyList(),
    ): Boolean {
        val name = host.trim().removePrefix("[").removeSuffix("]")
        if (name.isEmpty()) return false
        if (isMdnsName(name)) return true
        val addresses = runCatching { resolve(name) }.getOrNull().orEmpty()
        return addresses.any { isLocalAddress(it, vpnActive, connectedPrefixes) }
    }

    private fun isLocalIpv4(a: ByteArray, vpnActive: Boolean): Boolean {
        val b0 = a[0].toInt() and BYTE
        val b1 = a[1].toInt() and BYTE
        return when {
            b0 == 10 -> true
            b0 == 172 && b1 in 16..31 -> true
            b0 == 192 && b1 == 168 -> true
            b0 == 169 && b1 == 254 -> true
            b0 == 100 && b1 in 64..127 -> !vpnActive
            b0 in 224..239 -> true
            a.all { (it.toInt() and BYTE) == BYTE } -> true
            else -> false
        }
    }

    private fun isLocalIpv6(a: ByteArray): Boolean {
        val b0 = a[0].toInt() and BYTE
        val b1 = a[1].toInt() and BYTE
        return when {
            // fe80::/10 link-local
            b0 == 0xfe && (b1 and 0xc0) == 0x80 -> true
            // ff00::/8 multicast
            b0 == 0xff -> true
            // fc00::/7 unique local (Tandem's choice, see the class comment)
            (b0 and 0xfe) == 0xfc -> true
            else -> false
        }
    }

    private fun isLoopback(a: ByteArray): Boolean = when (a.size) {
        IPV4_BYTES -> (a[0].toInt() and BYTE) == 127
        IPV6_BYTES -> ipv4Mapped(a)?.let { (it[0].toInt() and BYTE) == 127 }
            ?: (a.copyOfRange(0, IPV6_BYTES - 1).all { it.toInt() == 0 } && a[IPV6_BYTES - 1].toInt() == 1)
        else -> false
    }

    /** The IPv4 address inside an IPv4-mapped IPv6 address (`::ffff:a.b.c.d`), else null. */
    private fun ipv4Mapped(a: ByteArray): ByteArray? {
        val prefixZero = (0 until 10).all { a[it].toInt() == 0 }
        val ffff = (a[10].toInt() and BYTE) == BYTE && (a[11].toInt() and BYTE) == BYTE
        return if (prefixZero && ffff) a.copyOfRange(12, IPV6_BYTES) else null
    }

    private const val IPV4_BYTES = 4
    private const val IPV6_BYTES = 16
    private const val BYTE = 0xff
}

/** A route prefix such as 192.168.1.0/24 or 2001:db8:1::/64 ([address] in network order). */
class IpPrefix(address: ByteArray, val length: Int) {
    private val bytes: ByteArray = address.copyOf()

    init {
        require(bytes.size == 4 || bytes.size == 16) { "An IP address has 4 or 16 bytes" }
        require(length in 0..bytes.size * Byte.SIZE_BITS) { "Prefix length out of range" }
    }

    /** True when [address] (same family) lies inside this prefix. A /0 prefix (a default route) matches nothing. */
    fun contains(address: ByteArray): Boolean {
        if (address.size != bytes.size || length == 0) return false
        val fullBytes = length / Byte.SIZE_BITS
        if ((0 until fullBytes).any { address[it] != bytes[it] }) return false
        val restBits = length % Byte.SIZE_BITS
        if (restBits == 0) return true
        val mask = (0xff shl (Byte.SIZE_BITS - restBits)) and 0xff
        return (address[fullBytes].toInt() and mask) == (bytes[fullBytes].toInt() and mask)
    }

    override fun toString(): String = "IpPrefix(${bytes.size * Byte.SIZE_BITS}-bit /$length)"
}
