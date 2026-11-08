package casa.moller.tandem.core.domain.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/** Fas 5 (plan §8.1 "Handsfree", #32): what the recognizer heard → cook mode's command. */
class VoiceCommandsTest {

    @ParameterizedTest(name = "\"{0}\" → {1}")
    @CsvSource(
        // Swedish, as the on-device recognizer writes it (capitals, punctuation).
        "nästa, NEXT",
        "Nästa., NEXT",
        "nästa steg, NEXT",
        "Nästa steg!, NEXT",
        "gå vidare, NEXT",
        "fortsätt, NEXT",
        "framåt, NEXT",
        "okej nästa, NEXT",
        "nästa tack, NEXT",
        "Tandem nästa, NEXT",
        "nästa nästa, NEXT",
        "nasta, NEXT",
        "tillbaka, PREVIOUS",
        "Gå tillbaka, PREVIOUS",
        "bakåt, PREVIOUS",
        "föregående steg, PREVIOUS",
        "förra steget, PREVIOUS",
        "backa, PREVIOUS",
        "tillbaka tillbaka, PREVIOUS",
        "upprepa, REPEAT",
        "Upprepa!, REPEAT",
        "säg igen, REPEAT",
        "säg det igen, REPEAT",
        "läs upp, REPEAT",
        "läs igen, REPEAT",
        "en gång till, REPEAT",
        "vad sa du?, REPEAT",
        "igen, REPEAT",
        // English.
        "next, NEXT",
        "Next step., NEXT",
        "next please, NEXT",
        "go on, NEXT",
        "back, PREVIOUS",
        "Go back, PREVIOUS",
        "previous step, PREVIOUS",
        "repeat, REPEAT",
        "Say it again, REPEAT",
        "one-more-time, REPEAT",
        "read it again, REPEAT",
    )
    fun commands(heard: String, expected: VoiceCommand) {
        assertEquals(expected, VoiceCommands.parse(heard))
    }

    @ParameterizedTest(name = "\"{0}\" is no command")
    @ValueSource(
        strings = [
            "",
            "   ",
            "nästan klart",
            "vi tar nästa vecka",
            "kan du ta fram grädden",
            "tillbaka till jobbet i morgon",
            "nästa tillbaka",
            "repeat after me the whole recipe again please now",
            "next week",
            "okej",
            "tack",
            "please",
        ],
    )
    fun talkIsNoCommand(heard: String) {
        assertNull(VoiceCommands.parse(heard))
    }

    @Test
    fun theFirstAlternativeThatIsACommandWins() {
        assertEquals(VoiceCommand.NEXT, VoiceCommands.parse(listOf("nästan", "nästa", "tillbaka")))
        assertEquals(VoiceCommand.PREVIOUS, VoiceCommands.parse(listOf("till baka", "tillbaka", "nästa")))
        assertNull(VoiceCommands.parse(listOf("nästan", "näst")))
        assertNull(VoiceCommands.parse(emptyList()))
    }

    @Test
    fun normalizeDropsCaseDiacriticsAndPunctuation() {
        assertEquals("nasta steg", VoiceCommands.normalize("  Nästa,  steg! "))
        assertEquals("ga tillbaka", VoiceCommands.normalize("Gå tillbaka."))
        assertEquals("one more time", VoiceCommands.normalize("one-more-time"))
        assertEquals("sag igen", VoiceCommands.normalize("SÄG IGEN?"))
    }
}
