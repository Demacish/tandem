package casa.moller.tandem.feature.pantry

import casa.moller.tandem.core.ui.navigation.FullScreenDestination
import casa.moller.tandem.feature.pantry.navigation.MakeNowKey
import casa.moller.tandem.feature.pantry.navigation.PantryEntryKey
import casa.moller.tandem.feature.pantry.navigation.PantryKey
import casa.moller.tandem.feature.pantry.navigation.PantryLocationKey
import casa.moller.tandem.feature.pantry.navigation.StaplesKey
import casa.moller.tandem.feature.pantry.navigation.UnpackKey
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Fas S pantry keys survive process death (Navigation 3 saves them as JSON). */
class PantryKeysTest {
    private fun <T> roundTrip(serializer: KSerializer<T>, key: T): T =
        Json.decodeFromString(serializer, Json.encodeToString(serializer, key))

    @Test
    fun keysSurviveSerialization() {
        assertEquals(PantryKey, roundTrip(PantryKey.serializer(), PantryKey))
        assertEquals(PantryLocationKey(-3), roundTrip(PantryLocationKey.serializer(), PantryLocationKey(-3)))
        val entry = PantryEntryKey(entryId = 11, locationId = 2)
        assertEquals(entry, roundTrip(PantryEntryKey.serializer(), entry))
        assertEquals(PantryEntryKey(), roundTrip(PantryEntryKey.serializer(), PantryEntryKey()))
        assertEquals(UnpackKey, roundTrip(UnpackKey.serializer(), UnpackKey))
        assertEquals(MakeNowKey(useUpFirst = true), roundTrip(MakeNowKey.serializer(), MakeNowKey(useUpFirst = true)))
        assertEquals(StaplesKey, roundTrip(StaplesKey.serializer(), StaplesKey))
    }

    @Test
    fun theEntryFormIsFullScreen() {
        assertTrue((PantryEntryKey() as Any) is FullScreenDestination)
        assertFalse((PantryKey as Any) is FullScreenDestination)
    }
}
