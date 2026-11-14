package casa.moller.tandem.core.domain.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** Device test 2026-11-14, B1: every recognition language gets a region. */
class VoiceLocalesTest {

    @ParameterizedTest(name = "\"{0}\" on a phone in \"{1}\" → {2}")
    @CsvSource(
        // Swedish as before: one region, whatever the phone says.
        "sv, SE, sv-SE",
        "sv, '', sv-SE",
        "sv, FI, sv-SE",
        "sv-SE, US, sv-SE",
        // The bare English the device test sent: the phone's region when English is spoken there.
        "en, SE, en-US",
        "en, '', en-US",
        "en, GB, en-GB",
        "en, AU, en-AU",
        "en-GB, US, en-GB",
        "de, '', de-DE",
        "de, AT, de-AT",
        "de, SE, de-DE",
        "fr, CA, fr-CA",
        "pt, PT, pt-PT",
        "pt, '', pt-BR",
        "es, MX, es-MX",
        "ja, '', ja-JP",
        // An empty app language listens in English, a language without a usual region in the phone's.
        "'', SE, en-US",
        "is, IS, is-IS",
    )
    fun aRegionIsAlwaysAdded(app: String, phone: String, expected: String) {
        assertEquals(expected, VoiceLocales.resolve(app, phone.ifEmpty { null }))
    }

    @Test
    fun theRecognizersOwnLanguagesDecide() {
        val samsung = listOf("en-US", "de-DE", "es-ES", "fr-FR", "it-IT", "ja-JP", "pt-BR")
        // A British phone, a recognizer with only American English.
        assertEquals("en-US", VoiceLocales.resolve("en", "GB", samsung))
        assertEquals("en-US", VoiceLocales.resolve("en-GB", "GB", samsung))
        // The phone's region when the recognizer has it.
        assertEquals("en-GB", VoiceLocales.resolve("en", "GB", listOf("en_us", "EN_gb")))
        // Swedish missing: the usual tag (the recognizer then says it cannot).
        assertEquals("sv-SE", VoiceLocales.resolve("sv", "SE", samsung))
        // A region the candidates do not name but the recognizer has.
        assertEquals("de-CH", VoiceLocales.resolve("de", "SE", listOf("de-CH", "fr-FR")))
        // A list without the language and garbage in the list.
        assertEquals("nl-NL", VoiceLocales.resolve("nl", null, listOf("", "und", "fr-FR")))
    }

    @Test
    fun englishIsEnglishWithAnyRegion() {
        assertTrue(VoiceLocales.isEnglish("en"))
        assertTrue(VoiceLocales.isEnglish("en-GB"))
        assertTrue(VoiceLocales.isEnglish("en_US"))
        assertFalse(VoiceLocales.isEnglish("sv-SE"))
        assertEquals("sv", VoiceLocales.languageOf("sv-SE"))
    }
}
