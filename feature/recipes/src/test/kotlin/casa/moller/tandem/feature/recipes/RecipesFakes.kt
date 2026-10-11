package casa.moller.tandem.feature.recipes

import casa.moller.tandem.core.data.server.ServerInfo
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.data.session.SessionRepository
import casa.moller.tandem.core.data.session.TokenRevocation
import casa.moller.tandem.core.model.account.ServerAccount
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.feature.recipes.list.RecipeListLayout
import casa.moller.tandem.feature.recipes.list.RecipeListPreferences
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** A logged-in account on the example server (never a real host). */
internal val ExampleAccount = ServerAccount(
    id = "account-1",
    baseUrl = "https://tandoor.example.com",
    username = "anna",
    userId = 1,
    spaceId = 1,
    addedAt = Instant.parse("2026-10-01T00:00:00Z"),
)

internal val LoggedIn: Session = Session.LoggedIn(ExampleAccount, serverVersion = "2.6.13", compatibility = ServerCompatibility.SUPPORTED)

/** [SessionRepository] with a settable [state]; everything else is a no-op. */
internal class FakeSessionRepository(initial: Session = LoggedIn) : SessionRepository {
    val state = MutableStateFlow(initial)

    override val session: Flow<Session> = state

    override suspend fun refreshServerInfo(): Outcome<ServerInfo> = Outcome.Failure(TandemError.Unknown("not used"))

    override suspend fun logout(revokeToken: Boolean): Outcome<TokenRevocation> = Outcome.Success(TokenRevocation.SKIPPED)

    override suspend fun startDemo(): Outcome<Unit> = Outcome.Success(Unit)

    override suspend fun leaveDemo(): Outcome<Unit> = Outcome.Success(Unit)

    override fun acknowledgeRecovery() = Unit

    override suspend fun clearAppData(): Outcome<Unit> = Outcome.Success(Unit)
}

/** In-memory [RecipeListPreferences]. */
internal class FakeRecipeListPreferences(initial: RecipeListLayout = RecipeListLayout.GRID) : RecipeListPreferences {
    val stored = MutableStateFlow(initial)

    override val layout: Flow<RecipeListLayout> = stored

    override suspend fun setLayout(layout: RecipeListLayout) {
        stored.value = layout
    }
}
