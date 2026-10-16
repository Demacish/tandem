package casa.moller.tandem.feature.shell.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsKeysTest {
    @Test
    fun settingsKeysSurviveSerializationAsShellKeys() {
        // Process death: the More tab's stack is saved through the sealed ShellKey serializer.
        val keys: List<ShellKey> = listOf(AppearanceSettingsKey, CookingSettingsKey, SyncSettingsKey, PermissionsKey, DiagnosticsKey)
        for (key in keys) {
            val json = Json.encodeToString(ShellKey.serializer(), key)
            assertEquals(key, Json.decodeFromString(ShellKey.serializer(), json))
        }
        assertEquals(keys.size, keys.map { Json.encodeToString(ShellKey.serializer(), it) }.toSet().size)
    }

    @Test
    fun settingsScreensAreOpenedOnTheMoreTab() {
        val more = mutableListOf<NavKey>(MoreKey)
        val stacks = ShellTab.entries.associateWith { if (it == ShellTab.MORE) more else mutableListOf<NavKey>(it.root) }
        val state = ShellNavigationState(stacks)
        state.selectTab(ShellTab.MORE)
        state.navigate(PermissionsKey)
        assertEquals(listOf(MoreKey, PermissionsKey), state.stackOf(ShellTab.MORE))
    }
}
