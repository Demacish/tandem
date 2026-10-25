package casa.moller.tandem.core.data.remote

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.server.ServerInfo
import casa.moller.tandem.core.data.session.TokenRevocation
import casa.moller.tandem.core.model.error.InvalidUrlReason
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.catchingUnexpected
import casa.moller.tandem.core.model.error.map
import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.network.auth.DeviceCredentials
import casa.moller.tandem.core.network.auth.RevokeResult
import casa.moller.tandem.core.network.auth.TandoorAuthenticator
import casa.moller.tandem.core.network.dto.ServerSettingsDto
import casa.moller.tandem.core.network.probe.Compatibility
import casa.moller.tandem.core.network.probe.ServerProbe
import casa.moller.tandem.core.network.probe.TandoorCompatibility
import casa.moller.tandem.core.network.probe.TandoorServer
import javax.inject.Inject
import kotlin.time.Instant

/** [TandoorRemote] on top of core:network's probe and authenticator. */
internal class NetworkTandoorRemote @Inject constructor(
    private val serverProbe: ServerProbe,
    private val authenticator: TandoorAuthenticator,
) : TandoorRemote {

    override suspend fun probe(url: String): Outcome<ServerInfo> = serverProbe.probe(url).map { it.toServerInfo() }

    override suspend fun login(server: ServerInfo, username: String, password: String): Outcome<RemoteCredentials> = requireUsableBaseUrl {
        when (val result = authenticator.login(server.toTandoorServer(), username, password)) {
            is Outcome.Success -> Outcome.Success(result.value.toRemoteCredentials())

            is Outcome.Failure ->
                if (result.error == TandoorAuthenticator.PASSWORD_LOGIN_UNAVAILABLE) {
                    Outcome.Failure(DataProblem.PASSWORD_LOGIN_UNAVAILABLE.toError())
                } else {
                    result
                }
        }
    }

    override suspend fun loginWithToken(server: ServerInfo, token: String): Outcome<RemoteCredentials> = requireUsableBaseUrl {
        authenticator.loginWithPastedToken(server.toTandoorServer(), token).map { it.toRemoteCredentials() }
    }

    override suspend fun revoke(baseUrl: String, token: String, tokenId: Long): Outcome<TokenRevocation> = requireUsableBaseUrl {
        authenticator.revokeDeviceToken(baseUrl, token, tokenId).map { it.toTokenRevocation() }
    }

    /**
     * The API factory refuses a base URL that is not an https URL with IllegalArgumentException.
     * Probed and stored addresses always are, but a restored or corrupted value must not crash
     * the app: it becomes [InvalidUrlReason.MALFORMED] instead.
     *
     * Anything else that escapes the authenticator is a bug (in v0.0.1 a broken R8 build threw
     * ClassCastException while creating the Retrofit services, outside every `apiCall`): it
     * becomes [TandemError.Unknown] instead of crashing the app ([catchingUnexpected]).
     */
    private inline fun <T> requireUsableBaseUrl(block: () -> Outcome<T>): Outcome<T> = catchingUnexpected {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            Outcome.Failure(TandemError.InvalidUrl(InvalidUrlReason.MALFORMED))
        }
    }
}

internal fun TandoorServer.toServerInfo(): ServerInfo =
    ServerInfo(baseUrl = baseUrl, version = version, compatibility = compatibility.toServerCompatibility())

/** The authenticator only reads base URL and version; the settings are not needed for login. */
internal fun ServerInfo.toTandoorServer(): TandoorServer = TandoorServer(
    baseUrl = baseUrl,
    version = version,
    compatibility = compatibility.toNetworkCompatibility(),
    settings = ServerSettingsDto(version = version),
)

internal fun Compatibility.toServerCompatibility(): ServerCompatibility = when (this) {
    Compatibility.SUPPORTED -> ServerCompatibility.SUPPORTED
    Compatibility.UNTESTED_NEWER -> ServerCompatibility.UNTESTED_NEWER
    Compatibility.UNTESTED_OLDER -> ServerCompatibility.UNTESTED_OLDER
    Compatibility.TOO_OLD -> ServerCompatibility.TOO_OLD
    Compatibility.UNRECOGNIZED -> ServerCompatibility.UNRECOGNIZED
}

internal fun ServerCompatibility.toNetworkCompatibility(): Compatibility = when (this) {
    ServerCompatibility.SUPPORTED -> Compatibility.SUPPORTED
    ServerCompatibility.UNTESTED_NEWER -> Compatibility.UNTESTED_NEWER
    ServerCompatibility.UNTESTED_OLDER -> Compatibility.UNTESTED_OLDER
    ServerCompatibility.TOO_OLD -> Compatibility.TOO_OLD
    ServerCompatibility.UNRECOGNIZED -> Compatibility.UNRECOGNIZED
}

/** Compatibility of a stored version string (e.g. `ServerAccount.serverVersion`). */
internal fun compatibilityOf(version: String?): ServerCompatibility = TandoorCompatibility.classify(version).toServerCompatibility()

internal fun DeviceCredentials.toRemoteCredentials(): RemoteCredentials = RemoteCredentials(
    baseUrl = baseUrl,
    token = token,
    tokenId = tokenId,
    tokenExpires = expires?.let { Instant.fromEpochSeconds(it.epochSecond, it.nano) },
    userId = userId,
    username = username,
    displayName = displayName.takeIf { it.isNotBlank() },
    spaceId = spaceId,
    spaceName = spaceName,
    serverVersion = serverVersion,
)

internal fun RevokeResult.toTokenRevocation(): TokenRevocation = when (this) {
    RevokeResult.REVOKED -> TokenRevocation.REVOKED
    RevokeResult.ALREADY_INVALID -> TokenRevocation.ALREADY_INVALID
}
