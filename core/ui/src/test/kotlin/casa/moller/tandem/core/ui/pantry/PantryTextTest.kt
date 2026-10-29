package casa.moller.tandem.core.ui.pantry

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import java.util.Locale
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Fas S (hemma-ui lane): best-before texts, urgency and amounts of the pantry (plan §8.8). */
@RunWith(AndroidJUnit4::class)
class PantryTextTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val today = LocalDate(2026, 10, 29)

    @Test
    fun urgencyIsCountedInDaysFromToday() {
        assertEquals(ExpiryUrgency.NONE, ExpiryUrgency.of(null, today))
        assertEquals(ExpiryUrgency.EXPIRED, ExpiryUrgency.of(LocalDate(2026, 10, 28), today))
        assertEquals(ExpiryUrgency.TODAY, ExpiryUrgency.of(today, today))
        assertEquals(ExpiryUrgency.SOON, ExpiryUrgency.of(LocalDate(2026, 10, 30), today))
        assertEquals(ExpiryUrgency.SOON, ExpiryUrgency.of(LocalDate(2026, 11, 1), today))
        assertEquals(ExpiryUrgency.LATER, ExpiryUrgency.of(LocalDate(2026, 11, 2), today))
    }

    @Test
    fun amountsUseTheLanguagesDecimalSeparator() {
        val sv = Locale.forLanguageTag("sv-SE")
        assertEquals("2 dl", formatPantryAmount(2.0, "dl", sv))
        assertEquals("1,5 l", formatPantryAmount(1.5, "l", sv))
        assertEquals("0,25", formatPantryAmount(0.25, null, sv))
        assertEquals("1.5 l", formatPantryAmount(1.5, " l ", Locale.US))
        assertEquals("Grädde", foodTitle(" grädde", sv))
    }

    @Test
    @Config(qualifiers = "sv")
    fun swedishTexts() {
        assertTexts(
            mapOf(
                LocalDate(2026, 10, 25) to "gick ut för 4 dagar sedan",
                LocalDate(2026, 10, 28) to "gick ut i går",
                today to "går ut i dag",
                LocalDate(2026, 10, 30) to "går ut i morgon",
                LocalDate(2026, 11, 2) to "går ut om 4 dagar",
            ),
        )
    }

    @Test
    @Config(qualifiers = "en")
    fun englishTexts() {
        assertTexts(
            mapOf(
                LocalDate(2026, 10, 27) to "expired 2 days ago",
                LocalDate(2026, 10, 28) to "expired yesterday",
                today to "expires today",
                LocalDate(2026, 10, 30) to "expires tomorrow",
                LocalDate(2026, 10, 31) to "expires in 2 days",
            ),
        )
    }

    @Test
    @Config(qualifiers = "sv")
    fun aDayFurtherAwayIsNamed() {
        composeRule.setContent { TandemTheme { Text(expiryText(LocalDate(2027, 1, 15), today)) } }
        composeRule.onNodeWithText("bäst före", substring = true).assertExists()
        composeRule.onNodeWithText("2027", substring = true).assertExists()
    }

    private fun assertTexts(expected: Map<LocalDate, String>) {
        composeRule.setContent {
            TandemTheme {
                Column {
                    expected.keys.forEach { day -> Text(expiryText(day, today)) }
                }
            }
        }
        expected.values.forEach { text -> composeRule.onNodeWithText(text).assertExists() }
    }
}
