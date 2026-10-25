package casa.moller.tandem.feature.recipes

import casa.moller.tandem.feature.recipes.navigation.BookDetailKey
import casa.moller.tandem.feature.recipes.navigation.BooksKey
import casa.moller.tandem.feature.recipes.navigation.FavoritesKey
import casa.moller.tandem.feature.recipes.navigation.WantToCookKey
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class BookKeysTest {
    private fun <T> roundTrip(serializer: KSerializer<T>, key: T): T =
        Json.decodeFromString(serializer, Json.encodeToString(serializer, key))

    @Test
    fun keysSurviveSerialization() {
        assertEquals(BooksKey, roundTrip(BooksKey.serializer(), BooksKey))
        assertEquals(BookDetailKey(-2), roundTrip(BookDetailKey.serializer(), BookDetailKey(-2)))
        assertEquals(FavoritesKey, roundTrip(FavoritesKey.serializer(), FavoritesKey))
        assertEquals(WantToCookKey, roundTrip(WantToCookKey.serializer(), WantToCookKey))
    }
}
