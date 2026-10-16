package casa.moller.tandem.core.domain.timer

import casa.moller.tandem.core.model.timer.TimerSuggestionSource
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StepTimerSuggestionsTest {
    private val suggestions = StepTimerSuggestions()

    /** The non-breaking space between a number and its unit (DurationText.NUMBER_UNIT_SEPARATOR). */
    private val nbsp = ' '

    @Test
    fun stepTimeComesFirstThenTheTextInOrder() {
        val text = "Koka pastan ca 8–10 min. Stek svampen 5 minuter."
        val result = suggestions.suggest(text, stepTimeMinutes = 15, languageTag = "sv")
        assertEquals(
            listOf(TimerSuggestionSource.STEP_TIME, TimerSuggestionSource.TEXT, TimerSuggestionSource.TEXT),
            result.map { it.source },
        )
        assertEquals("15${nbsp}min", result[0].label)
        assertEquals(15.minutes, result[0].duration)

        val range = result[1]
        assertEquals("ca 8–10 min (10${nbsp}min)", range.label)
        assertEquals(10.minutes, range.duration)
        assertEquals(8.minutes, range.minDuration)
        assertTrue(range.isRange)
        assertTrue(range.isApproximate)
        assertEquals("ca 8–10 min", text.substring(range.textRange!!))
        assertEquals("5 minuter", text.substring(result[2].textRange!!))
        assertEquals("5 minuter", result[2].label)
        assertFalse(result[2].isRange)
    }

    @Test
    fun rangesRunForTheUpperBoundAndSaySo() {
        val swedish = suggestions.suggest("Grädda ca 20–25 minuter.", stepTimeMinutes = 0, languageTag = "sv").single()
        assertEquals(25.minutes, swedish.duration)
        assertEquals(20.minutes, swedish.minDuration)
        assertEquals("ca 20–25 minuter (25${nbsp}min)", swedish.label)

        val between = suggestions.suggest("Koka mellan 10 och 12 minuter.", stepTimeMinutes = 0, languageTag = "sv").single()
        assertEquals(12.minutes, between.duration)
        assertEquals("mellan 10 och 12 minuter (12${nbsp}min)", between.label)

        val english = suggestions.suggest("Bake 45 minutes to 1 1/2 hours.", stepTimeMinutes = 0, languageTag = "en").single()
        assertEquals(1.5.hours, english.duration)
        assertEquals("45 minutes to 1 1/2 hours (1${nbsp}h 30${nbsp}min)", english.label)

        val seconds = suggestions.suggest("Mixa 30–45 sekunder.", stepTimeMinutes = 0, languageTag = "sv").single()
        assertEquals(45.seconds, seconds.duration)
        assertEquals("30–45 sekunder (45${nbsp}s)", seconds.label)
    }

    @Test
    fun theStepTimeIsNotRepeatedWhenTheTextSaysTheSame() {
        val result = suggestions.suggest("Bake for 1 hour 15 minutes.", stepTimeMinutes = 75, languageTag = "sv")
        assertEquals(listOf(TimerSuggestionSource.TEXT), result.map { it.source })
        assertEquals(75.minutes, result.single().duration)
    }

    @Test
    fun aRangeEndingAtTheStepTimeStillKeepsTheStepChip() {
        // The step chip is exact, the range chip says "(25 min)": both are offered.
        val result = suggestions.suggest("Grädda 20–25 min.", stepTimeMinutes = 25, languageTag = "sv")
        assertEquals(listOf(TimerSuggestionSource.STEP_TIME, TimerSuggestionSource.TEXT), result.map { it.source })
    }

    @Test
    fun noTimeNoChips() {
        val none = suggestions.suggest("Servera med persilja. Min mamma tog 180 grader.", stepTimeMinutes = 0, languageTag = "sv")
        assertEquals(emptyList<Any>(), none)
        val overnight = suggestions.suggest(
            "Låt stå i kylen över natten. Tillsätt 2 dl grädde kl. 18.",
            stepTimeMinutes = 0,
            languageTag = "sv",
        )
        assertEquals(emptyList<Any>(), overnight)
        val onlyStepTime = suggestions.suggest("", stepTimeMinutes = 20, languageTag = "en")
        assertEquals(listOf(TimerSuggestionSource.STEP_TIME), onlyStepTime.map { it.source })
    }

    @Test
    fun theAppLanguageOnlyBreaksTies() {
        val detector = TimerDetector()
        // "10 min" alone is both Swedish and English: the app's language decides which rule set "found" it.
        assertEquals("sv", detector.detect("10 min", preferredLanguage = "sv").single().languageTag)
        assertEquals("en", detector.detect("10 min", preferredLanguage = "en-GB").single().languageTag)
        // The text's own words win over the app's language.
        assertEquals("en", detector.detect("Simmer the sauce 10 min.", preferredLanguage = "sv").single().languageTag)
        // ...and the app's language never invents a duration.
        assertEquals(emptyList<DetectedTimer>(), detector.detect("Servera kl. 18.", preferredLanguage = "en"))
    }
}
