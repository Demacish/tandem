package casa.moller.tandem.core.data.internal

import android.content.Context
import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.account.AccountRepository
import casa.moller.tandem.core.data.account.AccountSessions
import casa.moller.tandem.core.data.files.CacheDirectories
import casa.moller.tandem.core.data.remote.RemoteCredentials
import casa.moller.tandem.core.data.remote.TandoorRemote
import casa.moller.tandem.core.data.remote.compatibilityOf
import casa.moller.tandem.core.data.server.ServerInfo
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.data.session.SessionRepository
import casa.moller.tandem.core.data.session.TokenRevocation
import casa.moller.tandem.core.datastore.AiKeyStore
import casa.moller.tandem.core.datastore.AiPreferencesStore
import casa.moller.tandem.core.datastore.SecureAccountStore
import casa.moller.tandem.core.datastore.SettingsStore
import casa.moller.tandem.core.datastore.StorageHealthMonitor
import casa.moller.tandem.core.model.account.ServerAccount
import casa.moller.tandem.core.model.account.TokenOrigin
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.settings.AppSettings
import casa.moller.tandem.core.model.settings.StorageHealth
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Accounts and session on top of [TandoorRemote] (network) and the encrypted
 * [SecureAccountStore] plus [SettingsStore] (device). Phase 0: no offline cache yet, so this is
 * a thin layer; Room and sync come in Phase 1.
 */
@Singleton
internal class DefaultAccountRepository(
    private val remote: TandoorRemote,
    private val accountStore: SecureAccountStore,
    private val settingsStore: SettingsStore,
    private val health: StorageHealthMonitor,
    private val now: () -> Instant,
    private val newId: () -> String,
    // Fas S (ai-foto lane): "Rensa appdata" also removes the AI keys and settings (B22).
    private val aiKeys: AiKeyStore? = null,
    private val aiPreferences: AiPreferencesStore? = null,
    // Fas S komplettering: the app's cache directory ("Rensa appdata" empties cache/drink-camera).
    private val cacheDir: File? = null,
) : AccountRepository,
    SessionRepository,
    AccountSessions {

    @Inject
    constructor(
        remote: TandoorRemote,
        accountStore: SecureAccountStore,
        settingsStore: SettingsStore,
        health: StorageHealthMonitor,
        aiKeys: AiKeyStore,
        aiPreferences: AiPreferencesStore,
        @ApplicationContext context: Context,
    ) : this(
        remote = remote,
        accountStore = accountStore,
        settingsStore = settingsStore,
        health = health,
        now = { Instant.fromEpochMilliseconds(System.currentTimeMillis()) },
        newId = { UUID.randomUUID().toString() },
        aiKeys = aiKeys,
        aiPreferences = aiPreferences,
        cacheDir = context.cacheDir,
    )

    /** Serializes writes that read-modify-write accounts and settings together. */
    private val writeLock = Mutex()

    override val session: Flow<Session> =
        combine(settingsStore.settings, accountStore.accounts, health.health) { settings, accounts, storage ->
            resolveSession(settings, accounts, storage)
        }.distinctUntilChanged()

    // --- AccountRepository ---------------------------------------------------------------

    override suspend fun probe(url: String): Outcome<ServerInfo> = remote.probe(url)

    override suspend fun login(server: ServerInfo, username: String, password: String): Outcome<ServerAccount> {
        if (!server.compatibility.canLogIn) return Outcome.Failure(DataProblem.SERVER_TOO_OLD.toError())
        val user = username.trim()
        if (user.isEmpty() || password.isEmpty()) {
            return Outcome.Failure(
                TandemError.BadRequest(
                    buildMap {
                        if (user.isEmpty()) put(FIELD_USERNAME, emptyList())
                        if (password.isEmpty()) put(FIELD_PASSWORD, emptyList())
                    },
                ),
            )
        }
        return when (val result = remote.login(server, user, password)) {
            is Outcome.Failure -> result
            is Outcome.Success -> persist(result.value, createdByApp = true)
        }
    }

    override suspend fun loginWithToken(server: ServerInfo, token: String): Outcome<ServerAccount> {
        if (!server.compatibility.canLogIn) return Outcome.Failure(DataProblem.SERVER_TOO_OLD.toError())
        if (token.isBlank()) return Outcome.Failure(TandemError.BadRequest(mapOf(FIELD_TOKEN to emptyList())))
        return when (val result = remote.loginWithToken(server, token)) {
            is Outcome.Failure -> result
            is Outcome.Success -> persist(result.value, createdByApp = false)
        }
    }

    /**
     * Stores the account and its token and makes it active.
     *
     * A token the app created itself is revoked again if it cannot be kept (no space, storage
     * failure); a pasted token belongs to the user and is never revoked. A token is only revoked
     * once no stored account refers to it: if the settings cannot be written after the account
     * was, the account is rolled back first, and if even that fails the token is kept (it is
     * stored and must stay valid). Clean-up requests run to the end even when the caller (the
     * login screen) is cancelled.
     */
    private suspend fun persist(credentials: RemoteCredentials, createdByApp: Boolean): Outcome<ServerAccount> {
        val spaceId = credentials.spaceId
        if (spaceId == null) {
            if (createdByApp) revokeQuietly(credentials)
            return Outcome.Failure(DataProblem.NO_SPACE.toError())
        }
        val origin = if (createdByApp) TokenOrigin.CREATED else TokenOrigin.PASTED
        return when (val saved = writeLock.withLock { save(credentials, spaceId, origin) }) {
            is Saved.Stored -> {
                saved.replaced?.let { cleanUpReplacedToken(it, credentials) }
                Outcome.Success(saved.account)
            }

            Saved.NotStored -> {
                if (createdByApp) revokeQuietly(credentials)
                Outcome.Failure(DataProblem.SECURE_STORAGE.toError())
            }

            // The new token is still stored, so it must stay valid; nothing is revoked.
            Saved.StoredWithoutSettings -> Outcome.Failure(DataProblem.SECURE_STORAGE.toError())
        }
    }

    private sealed interface Saved {
        /** Account, token and settings are written. [replaced] is the account it replaced. */
        data class Stored(val account: ServerAccount, val replaced: ReplacedAccount?) : Saved

        /** Nothing on the device refers to the new token. */
        data object NotStored : Saved

        /** The settings write failed and the rollback too: the account and token are stored. */
        data object StoredWithoutSettings : Saved
    }

    private class ReplacedAccount(val account: ServerAccount, val token: String?)

    /** Writes account, token and settings; on a settings failure the account write is undone. */
    private suspend fun save(credentials: RemoteCredentials, spaceId: Long, origin: TokenOrigin): Saved {
        val baseUrl = credentials.baseUrl.trimEnd('/')
        // Logging in again to the same user on the same server replaces that account (keeps its
        // local id, so later per-account data stays attached to it).
        val existing = accountStore.accounts.first()
            .firstOrNull { it.baseUrl.trimEnd('/') == baseUrl && it.userId == credentials.userId }
        val existingToken = existing?.let { accountStore.token(it.id) }
        val account = ServerAccount(
            id = existing?.id ?: newId(),
            baseUrl = baseUrl,
            username = credentials.username,
            displayName = credentials.displayName,
            userId = credentials.userId,
            spaceId = spaceId,
            spaceName = credentials.spaceName,
            tokenId = credentials.tokenId,
            tokenExpires = credentials.tokenExpires,
            serverVersion = credentials.serverVersion,
            addedAt = existing?.addedAt ?: now(),
            tokenOrigin = origin,
        )
        try {
            accountStore.upsert(account, credentials.token)
        } catch (e: IOException) {
            return Saved.NotStored
        }
        return try {
            settingsStore.update {
                it.copy(activeAccountId = account.id, demoMode = false, onboardingCompleted = true)
            }
            Saved.Stored(account, existing?.let { ReplacedAccount(it, existingToken) })
        } catch (e: IOException) {
            try {
                if (existing != null && existingToken != null) {
                    accountStore.upsert(existing, existingToken)
                } else {
                    accountStore.remove(account.id)
                }
                Saved.NotStored
            } catch (rollback: IOException) {
                Saved.StoredWithoutSettings
            }
        }
    }

    /**
     * After a new login replaced an account, the old device token of that account is no longer
     * used by anything on this device. If Tandem created it, it is revoked (best effort), so
     * logging in again never leaves a forgotten token valid on the server for a year.
     */
    private suspend fun cleanUpReplacedToken(replaced: ReplacedAccount, credentials: RemoteCredentials) {
        val old = replaced.account
        val token = replaced.token ?: return
        val tokenId = old.tokenId ?: return
        if (old.tokenOrigin != TokenOrigin.CREATED || token == credentials.token || tokenId == credentials.tokenId) return
        revokeQuietly(old.baseUrl, token, tokenId)
    }

    private suspend fun revokeQuietly(credentials: RemoteCredentials) {
        val tokenId = credentials.tokenId ?: return
        revokeQuietly(credentials.baseUrl, credentials.token, tokenId)
    }

    /**
     * Best effort: the result does not change what the user sees. NonCancellable, because the
     * caller is usually a screen whose ViewModel may be cleared while the request runs; a
     * cancelled clean-up would leave a valid token on the server that nothing knows about.
     */
    private suspend fun revokeQuietly(baseUrl: String, token: String, tokenId: Long) {
        withContext(NonCancellable) { remote.revoke(baseUrl, token, tokenId) }
    }

    // --- SessionRepository ---------------------------------------------------------------

    override suspend fun refreshServerInfo(): Outcome<ServerInfo> {
        val account = activeAccount() ?: return Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
        val info = when (val result = remote.probe(account.baseUrl)) {
            is Outcome.Failure -> return result
            is Outcome.Success -> result.value
        }
        if (info.version != account.serverVersion) {
            try {
                writeLock.withLock { accountStore.update(account.copy(serverVersion = info.version)) }
            } catch (e: IOException) {
                // The version is shown from the probe result anyway; it is stored next time.
            }
        }
        return Outcome.Success(info)
    }

    override suspend fun logout(revokeToken: Boolean): Outcome<TokenRevocation> {
        val account = activeAccount() ?: return Outcome.Success(TokenRevocation.SKIPPED)
        return removeAccount(account, revokeToken)
    }

    // --- AccountSessions (Phase 2, konto lane) -----------------------------------------------

    override suspend fun activate(accountId: String): Outcome<Unit> {
        if (accountStore.accounts.first().none { it.id == accountId }) return Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
        return updateSettings { it.copy(activeAccountId = accountId, demoMode = false, onboardingCompleted = true) }
    }

    override suspend fun remove(accountId: String, revokeToken: Boolean): Outcome<TokenRevocation> {
        val account = accountStore.accounts.first().firstOrNull { it.id == accountId }
            ?: return Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
        return removeAccount(account, revokeToken)
    }

    /**
     * Removes [account] and its token (logout of the active account, or removal of another stored
     * one), then revokes the token if asked and allowed. The active account stays active unless it
     * was the one removed; then the first remaining account takes over.
     */
    private suspend fun removeAccount(account: ServerAccount, revokeToken: Boolean): Outcome<TokenRevocation> {
        // Read before the account and its token are removed; kept in memory for the request.
        val token = if (revokeToken) accountStore.token(account.id) else null
        try {
            writeLock.withLock { accountStore.remove(account.id) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // Still stored, so the token must stay valid: nothing is revoked.
            return Outcome.Failure(DataProblem.SECURE_STORAGE.toError())
        }
        try {
            writeLock.withLock {
                val remaining = accountStore.accounts.first()
                settingsStore.update { current ->
                    // Konto lane: removing another stored account keeps the active one.
                    val stillActive = current.activeAccountId?.takeIf { id -> remaining.any { it.id == id } }
                    current.copy(
                        activeAccountId = stillActive ?: remaining.firstOrNull()?.id,
                        onboardingCompleted = remaining.isNotEmpty(),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // The account is gone; a stale activeAccountId falls back to the remaining accounts.
        }
        val revocation = when {
            // A pasted token is never revoked, and the logout dialog does not even offer it
            // (revokeToken = false). The user must still hear that it stays valid on the server.
            account.tokenOrigin != TokenOrigin.CREATED -> TokenRevocation.NOT_OWNED

            revokeToken -> withContext(NonCancellable) { revoke(account, token) }

            else -> TokenRevocation.SKIPPED
        }
        return Outcome.Success(revocation)
    }

    /** Revokes [token] of the (already removed) [account] if Tandem created it. */
    private suspend fun revoke(account: ServerAccount, token: String?): TokenRevocation {
        if (account.tokenOrigin != TokenOrigin.CREATED) return TokenRevocation.NOT_OWNED
        if (token == null) return TokenRevocation.SKIPPED
        val tokenId = account.tokenId ?: return TokenRevocation.UNKNOWN_TOKEN_ID
        return when (val result = remote.revoke(account.baseUrl, token, tokenId)) {
            is Outcome.Success -> result.value
            is Outcome.Failure -> TokenRevocation.FAILED
        }
    }

    override suspend fun startDemo(): Outcome<Unit> = updateSettings { it.copy(demoMode = true) }

    override suspend fun leaveDemo(): Outcome<Unit> = updateSettings { it.copy(demoMode = false) }

    override fun acknowledgeRecovery() {
        health.acknowledge()
    }

    override suspend fun clearAppData(): Outcome<Unit> {
        var failed = false
        writeLock.withLock {
            try {
                // On a write failure the store destroys its keys instead (cryptographic erase).
                accountStore.clearAll()
            } catch (e: IOException) {
                failed = true
            }
            try {
                settingsStore.update { AppSettings.Default }
            } catch (e: IOException) {
                failed = true
            }
            try {
                aiKeys?.clearAll()
                aiPreferences?.clear()
            } catch (e: IOException) {
                failed = true
            }
            // Fas S komplettering (fix-ui #3/#22): the camera originals of "Ny dryck" go too
            // (often with EXIF location); the drinks' scaled copies go with the account's rows.
            cacheDir?.let { dir -> withContext(Dispatchers.IO) { File(dir, CacheDirectories.DRINK_CAMERA).deleteRecursively() } }
        }
        health.acknowledge()
        return if (failed) Outcome.Failure(DataProblem.SECURE_STORAGE.toError()) else Outcome.Success(Unit)
    }

    private suspend fun updateSettings(transform: (AppSettings) -> AppSettings): Outcome<Unit> = try {
        writeLock.withLock { settingsStore.update(transform) }
        Outcome.Success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Outcome.Failure(DataProblem.SECURE_STORAGE.toError())
    }

    private suspend fun activeAccount(): ServerAccount? = activeAccount(settingsStore.settings.first(), accountStore.accounts.first())

    internal companion object {
        const val FIELD_USERNAME = "username"
        const val FIELD_PASSWORD = "password"
        const val FIELD_TOKEN = "token"

        /** See [SessionRepository.session] for the rules. */
        fun resolveSession(settings: AppSettings, accounts: List<ServerAccount>, health: StorageHealth): Session {
            if (health.recoveryNeeded) return Session.RecoveryNeeded(health.recoveryReasons)
            if (settings.demoMode) return Session.Demo
            val account = activeAccount(settings, accounts) ?: return Session.NoAccount
            return Session.LoggedIn(
                account = account,
                serverVersion = account.serverVersion,
                compatibility = compatibilityOf(account.serverVersion),
            )
        }

        /** The active account; falls back to the first one when the id is unset or stale. */
        fun activeAccount(settings: AppSettings, accounts: List<ServerAccount>): ServerAccount? =
            accounts.firstOrNull { it.id == settings.activeAccountId } ?: accounts.firstOrNull()
    }
}
