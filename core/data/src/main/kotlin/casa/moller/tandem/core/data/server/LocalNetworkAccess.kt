package casa.moller.tandem.core.data.server

/**
 * Fas 5 (sdk37 lane): whether Tandem may reach a server on the LOCAL network (docs/BYGGE.md
 * "Target SDK 37"). Android 17 closes the local network to apps that target API 37 unless the
 * runtime permission [permission] ("Nearby devices") is granted; without it a connection to e.g.
 * `192.168.1.10` or `tandoor.local` just times out.
 *
 * The intro asks [needsPermission] before it probes an address and asks for [permission] in
 * context (never at first start); a request that still fails for this reason ends as
 * `TandemError.LocalNetworkDenied`. Mer -> Behörigheter shows the state.
 */
interface LocalNetworkAccess {

    /** The permission to request, or null when this device and build need none (Android 16 and older). */
    val permission: String?

    /** True when the permission is needed here and not granted. */
    fun permissionMissing(): Boolean

    /**
     * True when reaching [serverAddress] (as typed: with or without `https://`, a path or a port)
     * needs the missing permission. Resolves the host name off the main thread and gives up after
     * a few seconds (then false: the probe reports what really happens).
     */
    suspend fun needsPermission(serverAddress: String): Boolean

    /** Nothing to ask: Android 16 and older, demo builds and tests. */
    object NotNeeded : LocalNetworkAccess {
        override val permission: String? = null

        override fun permissionMissing(): Boolean = false

        override suspend fun needsPermission(serverAddress: String): Boolean = false
    }
}
