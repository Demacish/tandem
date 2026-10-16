package casa.moller.tandem.core.domain.timer

import casa.moller.tandem.core.model.timer.TimerSuggestionSource
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StepTimerSuggestionsTest {
    private val suggestions = StepTimerSuggestions()

    @Test
    fun stepTimeComesFirstThenTheTextInOrder() {
        val text = "Koka pastan ca 8–10 min. Stek svampen 5 minuter."
        val result = suggestions.suggest(text, stepTimeMinutes = 15, languageTag = "sv")
        assertEquals(
            listOf(TimerSuggestionSource.STEP_TIME, TimerSuggestionSource.TEXT, TimerSuggestionSource.TEXT),
            result.map { it.source },
        )
        assertEquals("15 min", result[0].label)
        assertEquals(15.minutes, result[0].duration)

        val range = result[1]
        assertEquals("ca 8–10 min", range.label)
        assertEquals(8.minutes, range.duration)
        assertEquals(10.minutes, range.maxDuration)
        assertTrue(range.isApproximate)
        assertEquals(range.label, text.substring(range.textRange!!))
        assertEquals("5 minuter", text.substring(result[2].textRange!!))
    }

    @Test
    fun theStepTimeIsNotRepeatedWhenTheTextSaysTheSame() {
        val result = suggestions.suggest("Bake for 1 hour 15 minutes.", stepTimeMinutes = 75, languageTag = "sv")
        assertEquals(listOf(TimerSuggestionSource.TEXT), result.map { it.source })
        assertEquals(75.minutes, result.single().duration)
    }

    @Test
    fun noTimeNoChips() {
        val none = suggestions.suggest("Servera med persilja. Min mamma tog 180 grader.", stepTimeMinutes = 0, languageTag = "sv")
        assertEquals(emptyList<Any>(), none)
        val onlyStepTime = suggestions.suggest("", stepTimeMinutes = 20, languageTag = "en")
        assertEquals(listOf(TimerSuggestionSource.STEP_TIME), onlyStepTime.map { it.source })
    }
}
