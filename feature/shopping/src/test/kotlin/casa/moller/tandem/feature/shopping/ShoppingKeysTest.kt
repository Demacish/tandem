package casa.moller.tandem.feature.shopping

import casa.moller.tandem.feature.shopping.navigation.ShoppingHistoryKey
import casa.moller.tandem.feature.shopping.navigation.ShoppingListChoiceKey
import casa.moller.tandem.feature.shopping.navigation.ShoppingListKey
import casa.moller.tandem.feature.shopping.navigation.StoreModeKey
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShoppingKeysTest {
    private fun <T> roundTrip(serializer: KSerializer<T>, key: T): T =
        Json.decodeFromString(serializer, Json.encodeToString(serializer, key))

    @Test
    fun keysSurviveSerialization() {
        // Process death: Navigation 3 saves the back stack with the keys' serializers.
        assertEquals(ShoppingListKey(listId = 1, supermarketId = 2), roundTrip(ShoppingListKey.serializer(), ShoppingListKey(1, 2)))
        assertEquals(ShoppingListKey(), roundTrip(ShoppingListKey.serializer(), ShoppingListKey()))
        assertEquals(StoreModeKey(listId = -3), roundTrip(StoreModeKey.serializer(), StoreModeKey(listId = -3)))
        assertEquals(ShoppingListChoiceKey, roundTrip(ShoppingListChoiceKey.serializer(), ShoppingListChoiceKey))
        assertEquals(ShoppingHistoryKey, roundTrip(ShoppingHistoryKey.serializer(), ShoppingHistoryKey))
    }

    @Test
    fun storeModeHidesTheNavigationOnEveryWidth() {
        assertTrue(StoreModeKey().hidesNavigationOnAllWidths)
        assertFalse(ShoppingHistoryKey.hidesNavigationOnAllWidths)
    }
}
