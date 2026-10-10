package casa.moller.tandem.feature.cook

import casa.moller.tandem.feature.cook.navigation.CookKey
import org.junit.Assert.assertTrue
import org.junit.Test

class CookKeyTest {
    @Test
    fun cookModeHidesTheNavigationOnEveryWidth() {
        assertTrue(CookKey(recipeId = 101, servings = 4.0).hidesNavigationOnAllWidths)
    }
}
