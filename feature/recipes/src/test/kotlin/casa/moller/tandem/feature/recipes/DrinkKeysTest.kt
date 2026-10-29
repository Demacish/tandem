package casa.moller.tandem.feature.recipes

import casa.moller.tandem.core.ui.navigation.FullScreenDestination
import casa.moller.tandem.feature.recipes.navigation.CreateDrinkKey
import casa.moller.tandem.feature.recipes.navigation.DrinkDetailKey
import casa.moller.tandem.feature.recipes.navigation.DrinksKey
import casa.moller.tandem.feature.recipes.navigation.PairingPickerKey
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Fas S drink keys survive process death (Navigation 3 saves them as JSON). */
class DrinkKeysTest {
    private fun <T> roundTrip(serializer: KSerializer<T>, key: T): T =
        Json.decodeFromString(serializer, Json.encodeToString(serializer, key))

    @Test
    fun keysSurviveSerialization() {
        assertEquals(DrinksKey("Vin"), roundTrip(DrinksKey.serializer(), DrinksKey("Vin")))
        assertEquals(DrinksKey(), roundTrip(DrinksKey.serializer(), DrinksKey()))
        assertEquals(DrinkDetailKey(-2), roundTrip(DrinkDetailKey.serializer(), DrinkDetailKey(-2)))
        assertEquals(PairingPickerKey(7), roundTrip(PairingPickerKey.serializer(), PairingPickerKey(7)))
        assertEquals(CreateDrinkKey(7), roundTrip(CreateDrinkKey.serializer(), CreateDrinkKey(7)))
        assertTrue(CreateDrinkKey() is FullScreenDestination)
    }
}
