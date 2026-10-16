package casa.moller.tandem.feature.cook

import androidx.navigation3.runtime.NavKey
import casa.moller.tandem.feature.cook.navigation.CookKey
import casa.moller.tandem.feature.cook.navigation.TimersKey
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CookKeyTest {
    @Test
    fun cookModeHidesTheNavigationOnEveryWidth() {
        assertTrue(CookKey(recipeId = 101, servings = 4.0).hidesNavigationOnAllWidths)
    }

    @Test
    fun opensAtTheFirstStepWithTheRememberedServingsByDefault() {
        val key = CookKey(recipeId = 101)
        assertEquals(0, key.startStep)
        assertNull(key.servings)
    }

    @Test
    fun keysSurviveSerialization() {
        // Process death: the back stack is saved with the keys' serializers (Navigation 3).
        val key = CookKey(recipeId = 101, servings = 2.5, startStep = 3)
        assertEquals(key, Json.decodeFromString(CookKey.serializer(), Json.encodeToString(CookKey.serializer(), key)))
        val timers: NavKey = Json.decodeFromString(TimersKey.serializer(), Json.encodeToString(TimersKey.serializer(), TimersKey))
        assertEquals(TimersKey, timers)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeStepsAreRejected() {
        CookKey(recipeId = 101, startStep = -1)
    }
}
