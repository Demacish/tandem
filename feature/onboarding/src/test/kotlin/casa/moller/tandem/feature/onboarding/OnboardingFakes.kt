package casa.moller.tandem.feature.onboarding

import androidx.lifecycle.SavedStateHandle
import casa.moller.tandem.core.data.account.AccountRepository
import casa.moller.tandem.core.data.server.ServerCompatibility
import casa.moller.tandem.core.data.server.ServerInfo
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.data.session.SessionRepository
import casa.moller.tandem.core.data.session.TokenRevocation
import casa.moller.tandem.core.model.account.ServerAccount
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.ui.language.AppLanguage
import casa.moller.tandem.core.ui.language.LanguageController
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

internal val SUPPORTED_SERVER = ServerInfo(
    baseUrl = "https://tandoor.example.com/",
    version = "2.6.13",
    compatibility = ServerCompatibility.SUPPORTED,
)

internal val NEWER_SERVER = ServerInfo(
    baseUrl = "https://tandoor.example.com/",
    version = "2.7.1",
    compatibility = ServerCompatibility.UNTESTED_NEWER,
)

internal val OLD_SERVER = ServerInfo(
    baseUrl = "https://old.example.com/",
    version = "1.5.19",
    compatibility = ServerCompatibility.TOO_OLD,
)

internal val ACCOUNT = ServerAccount(
    id = "account-1",
    baseUrl = "https://tandoor.example.com",
    username = "anna",
    displayName = "Anna",
    userId = 7,
    spaceId = 1,
    spaceName = "Home",
    tokenId = 42,
    serverVersion = "2.6.13",
    addedAt = Instant.parse("2026-10-09T12:00:00Z"),
)

/** Scripted [AccountRepository]; set [gate] to hold a call open and observe in-progress state. */
internal class FakeAccountRepository : AccountRepository {
    var probeResult: Outcome<ServerInfo> = Outcome.Success(SUPPORTED_SERVER)
    var loginResult: Outcome<ServerAccount> = Outcome.Success(ACCOUNT)
    var tokenLoginResult: Outcome<ServerAccount> = Outcome.Success(ACCOUNT.copy(tokenId = null))
    var gate: CompletableDeferred<Unit>? = null

    /** Thrown by every call when set: a bug or a broken release build below the ViewModel. */
    var unexpected: Throwable? = null

    val probes = mutableListOf<String>()
    val logins = mutableListOf<Triple<ServerInfo, String, String>>()
    val tokenLogins = mutableListOf<Pair<ServerInfo, String>>()

    override suspend fun probe(url: String): Outcome<ServerInfo> {
        probes += url
        gate?.await()
        unexpected?.let { throw it }
        return probeResult
    }

    override suspend fun login(server: ServerInfo, username: String, password: String): Outcome<ServerAccount> {
        logins += Triple(server, username, password)
        gate?.await()
        unexpected?.let { throw it }
        return loginResult
    }

    override suspend fun loginWithToken(server: ServerInfo, token: String): Outcome<ServerAccount> {
        tokenLogins += server to token
        gate?.await()
        unexpected?.let { throw it }
        return tokenLoginResult
    }
}

internal class FakeSessionRepository : SessionRepository {
    val state = MutableStateFlow<Session>(Session.NoAccount)
    var startDemoResult: Outcome<Unit> = Outcome.Success(Unit)
    var demoStarts = 0

    override val session: Flow<Session> = state

    override suspend fun refreshServerInfo(): Outcome<ServerInfo> = Outcome.Success(SUPPORTED_SERVER)

    override suspend fun logout(revokeToken: Boolean): Outcome<TokenRevocation> = Outcome.Success(TokenRevocation.SKIPPED)

    override suspend fun startDemo(): Outcome<Unit> {
        demoStarts++
        if (startDemoResult is Outcome.Success) state.value = Session.Demo
        return startDemoResult
    }

    override suspend fun leaveDemo(): Outcome<Unit> = Outcome.Success(Unit)

    override fun acknowledgeRecovery() = Unit

    override suspend fun clearAppData(): Outcome<Unit> = Outcome.Success(Unit)
}

internal class FakeLanguageController(override var current: AppLanguage = AppLanguage.SYSTEM) : LanguageController {
    val selected = mutableListOf<AppLanguage>()

    override fun select(language: AppLanguage) {
        selected += language
        current = language
    }
}

internal fun viewModel(
    accounts: AccountRepository = FakeAccountRepository(),
    session: SessionRepository = FakeSessionRepository(),
    handle: SavedStateHandle = SavedStateHandle(),
    config: OnboardingConfig = OnboardingConfig(),
) = OnboardingViewModel(handle, accounts, session, config)

/** Does nothing; for screenshot tests of fixed states. */
internal object NoOpActions : OnboardingActions {
    override fun onGetStarted() = Unit
    override fun onBack() = Unit
    override fun onServerUrlChange(value: String) = Unit
    override fun onConnect() = Unit
    override fun onContinueInSafeMode() = Unit
    override fun onUsernameChange(value: String) = Unit
    override fun onPasswordChange(value: String) = Unit
    override fun onTokenChange(value: String) = Unit
    override fun onLoginMethodChange(method: LoginMethod) = Unit
    override fun onLogin() = Unit
    override fun onStartDemo() = Unit
}
