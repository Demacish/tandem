package casa.moller.tandem.feature.shell.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 2 (konto lane): Mer → Konton och servrar is a More page that survives process death. */
class AccountsKeyTest {
    @Test
    fun accountsKeySurvivesSerializationAsAShellKey() {
        val json = Json.encodeToString(ShellKey.serializer(), AccountsKey)
        assertEquals(AccountsKey, Json.decodeFromString(ShellKey.serializer(), json))
    }

    @Test
    fun accountsIsAMorePageThatReplacesTheOpenOne() {
        assertTrue(AccountsKey.isMorePage)
        val more = mutableListOf<NavKey>(MoreKey)
        val stacks = ShellTab.entries.associateWith { if (it == ShellTab.MORE) more else mutableListOf<NavKey>(it.root) }
        val state = ShellNavigationState(stacks)
        state.selectTab(ShellTab.MORE)
        state.navigate(SyncSettingsKey)
        state.navigate(AccountsKey, replace = { it.isMorePage })
        assertEquals(listOf(MoreKey, AccountsKey), state.stackOf(ShellTab.MORE))
    }
}
