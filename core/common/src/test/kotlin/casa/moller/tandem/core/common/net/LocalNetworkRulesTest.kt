package casa.moller.tandem.core.common.net

import java.net.InetAddress
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Fas 5 (sdk37 lane): when Android 17 treats a server address as local (docs/BYGGE.md "Target SDK 37"). */
class LocalNetworkRulesTest {

    /** An IP literal as bytes; InetAddress never asks DNS for a literal. */
    private fun ip(literal: String): ByteArray = InetAddress.getByName(literal).address

    @Test
    fun onlyAndroid17WithTarget37NeedsThePermission() {
        assertFalse(LocalNetworkRules.enforced(sdkInt = 36, targetSdk = 37))
        assertFalse(LocalNetworkRules.enforced(sdkInt = 37, targetSdk = 36))
        assertTrue(LocalNetworkRules.enforced(sdkInt = 37, targetSdk = 37))
        assertTrue(LocalNetworkRules.enforced(sdkInt = 38, targetSdk = 37))

        assertTrue(LocalNetworkRules.permissionMissing(sdkInt = 37, targetSdk = 37, granted = false))
        assertFalse(LocalNetworkRules.permissionMissing(sdkInt = 37, targetSdk = 37, granted = true))
        // Android 16 (the owner's test phone): never missing, whatever checkSelfPermission says.
        assertFalse(LocalNetworkRules.permissionMissing(sdkInt = 36, targetSdk = 37, granted = false))
        // The Wear OS app targets 36: not even Wear OS 7 blocks it.
        assertFalse(LocalNetworkRules.permissionMissing(sdkInt = 37, targetSdk = 36, granted = false))
    }

    @Test
    fun privateLinkLocalCgnatMulticastAndBroadcastIpv4AreLocal() {
        listOf(
            "10.0.0.1", "10.255.255.254",
            "172.16.0.1", "172.31.255.254",
            "192.168.0.10", "192.168.255.1",
            "169.254.10.20",
            "100.64.0.1", "100.127.255.254",
            "224.0.0.251", "239.255.255.250",
            "255.255.255.255",
        ).forEach { assertTrue(LocalNetworkRules.isLocalAddress(ip(it)), it) }
    }

    @Test
    fun publicAndLoopbackIpv4AreNotLocal() {
        listOf(
            "8.8.8.8", "172.15.255.255", "172.32.0.1", "192.167.1.1", "100.63.255.255", "100.128.0.1",
            "127.0.0.1", "127.1.2.3", "0.0.0.0", "198.51.100.7",
        ).forEach { assertFalse(LocalNetworkRules.isLocalAddress(ip(it)), it) }
    }

    @Test
    fun ipv6LinkLocalMulticastAndUniqueLocalAreLocal() {
        listOf("fe80::1", "febf::1", "ff02::fb", "fd12:3456:789a::10", "fc00::1")
            .forEach { assertTrue(LocalNetworkRules.isLocalAddress(ip(it)), it) }
        listOf("::1", "2001:db8::1", "2606:4700::1111", "fec0::1")
            .forEach { assertFalse(LocalNetworkRules.isLocalAddress(ip(it)), it) }
    }

    @Test
    fun ipv4MappedIpv6FollowsTheIpv4Rule() {
        val mapped = ByteArray(16).also {
            it[10] = 0xff.toByte()
            it[11] = 0xff.toByte()
        }
        assertTrue(LocalNetworkRules.isLocalAddress(mapped.copyOf().also { ip("192.168.1.2").copyInto(it, 12) }))
        assertFalse(LocalNetworkRules.isLocalAddress(mapped.copyOf().also { ip("8.8.4.4").copyInto(it, 12) }))
        assertFalse(LocalNetworkRules.isLocalAddress(mapped.copyOf().also { ip("127.0.0.1").copyInto(it, 12) }))
    }

    @Test
    fun cgnatThroughAVpnIsNotLocalButHomeAddressesStillAre() {
        assertFalse(LocalNetworkRules.isLocalAddress(ip("100.101.102.103"), vpnActive = true))
        assertTrue(LocalNetworkRules.isLocalAddress(ip("192.168.1.20"), vpnActive = true))
    }

    @Test
    fun aGlobalIpv6AddressOnADirectlyConnectedRouteIsLocal() {
        val onLink = IpPrefix(ip("2001:db8:1:2::"), 64)
        assertTrue(LocalNetworkRules.isLocalAddress(ip("2001:db8:1:2::42"), connectedPrefixes = listOf(onLink)))
        assertFalse(LocalNetworkRules.isLocalAddress(ip("2001:db8:1:3::42"), connectedPrefixes = listOf(onLink)))
        // A default route is no directly connected network, and loopback never is.
        val default = IpPrefix(ip("::"), 0)
        assertFalse(LocalNetworkRules.isLocalAddress(ip("2606:4700::1111"), connectedPrefixes = listOf(default)))
        assertFalse(LocalNetworkRules.isLocalAddress(ip("127.0.0.1"), connectedPrefixes = listOf(IpPrefix(ip("127.0.0.0"), 8))))
    }

    @Test
    fun prefixesWithOddLengthsMatchBitByBit() {
        val prefix = IpPrefix(ip("203.0.113.128"), 25)
        assertTrue(prefix.contains(ip("203.0.113.200")))
        assertFalse(prefix.contains(ip("203.0.113.127")))
        assertFalse(prefix.contains(ip("2001:db8::1")))
    }

    @Test
    fun mdnsNamesAreLocalWithoutResolving() {
        val resolve: (String) -> List<ByteArray> = { error("must not resolve $it") }
        assertTrue(LocalNetworkRules.isLocalHost("tandoor.local", resolve))
        assertTrue(LocalNetworkRules.isLocalHost("Tandoor.LOCAL.", resolve))
        assertFalse(LocalNetworkRules.isMdnsName("tandoor.localhost"))
        assertFalse(LocalNetworkRules.isMdnsName("local.example.com"))
    }

    @Test
    fun namesFollowTheirAddresses() {
        val dns = mapOf(
            "tandoor.example.com" to listOf(ip("198.51.100.7")),
            "home.example.com" to listOf(ip("2001:db8::7"), ip("192.168.1.7")),
            "localhost" to listOf(ip("127.0.0.1"), ip("::1")),
        )
        val resolve: (String) -> List<ByteArray> = { dns[it] ?: throw java.net.UnknownHostException(it) }
        assertFalse(LocalNetworkRules.isLocalHost("tandoor.example.com", resolve))
        assertTrue(LocalNetworkRules.isLocalHost("home.example.com", resolve))
        assertFalse(LocalNetworkRules.isLocalHost("localhost", resolve))
        // Unknown names stay "unknown host", not "local network".
        assertFalse(LocalNetworkRules.isLocalHost("nowhere.example.com", resolve))
        // Literals, also IPv6 in brackets as in a URL.
        val literal: (String) -> List<ByteArray> = { listOf(ip(it)) }
        assertTrue(LocalNetworkRules.isLocalHost("192.168.1.7", literal))
        assertTrue(LocalNetworkRules.isLocalHost("[fe80::1]", literal))
        assertFalse(LocalNetworkRules.isLocalHost("", literal))
    }
}
