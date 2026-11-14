package casa.moller.tandem.core.timer

import android.Manifest
import android.app.Application
import android.media.AudioManager
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.data.voice.VoiceAvailability
import casa.moller.tandem.core.data.voice.VoiceDownload
import casa.moller.tandem.core.data.voice.VoiceEngine
import casa.moller.tandem.core.data.voice.VoiceFailure
import casa.moller.tandem.core.data.voice.VoiceResult
import casa.moller.tandem.core.timer.voice.AndroidStepSpeaker
import casa.moller.tandem.core.timer.voice.AndroidVoiceCommandRecognizer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** Fas 5 (kok lane, plan §8.1 "Handsfree"): the Android edges of the on-device recognizer. */
@RunWith(AndroidJUnit4::class)
class VoicePlatformTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun errorCodesEndTheRoundAsCookModeExpects() {
        val nothing = listOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        nothing.forEach { assertEquals(VoiceResult.NothingHeard, AndroidVoiceCommandRecognizer.resultOfError(it)) }
        val busy = VoiceResult.Failed(VoiceFailure.BUSY)
        assertEquals(busy, AndroidVoiceCommandRecognizer.resultOfError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
        assertEquals(busy, AndroidVoiceCommandRecognizer.resultOfError(SpeechRecognizer.ERROR_TOO_MANY_REQUESTS))
        assertEquals(
            VoiceResult.Failed(VoiceFailure.NO_PERMISSION),
            AndroidVoiceCommandRecognizer.resultOfError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS),
        )
        // Device test B2: 12 (no such language) and 13 (not downloaded yet) are not the same.
        assertEquals(
            VoiceResult.Failed(VoiceFailure.LANGUAGE_UNAVAILABLE),
            AndroidVoiceCommandRecognizer.resultOfError(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED),
        )
        assertEquals(
            VoiceResult.Failed(VoiceFailure.LANGUAGE_NOT_DOWNLOADED),
            AndroidVoiceCommandRecognizer.resultOfError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE),
        )
        assertEquals(VoiceResult.Failed(VoiceFailure.AUDIO), AndroidVoiceCommandRecognizer.resultOfError(SpeechRecognizer.ERROR_AUDIO))
        // A network error (only the opt-in speech service can have one): another failure, with back-off.
        assertEquals(VoiceResult.Failed(VoiceFailure.OTHER), AndroidVoiceCommandRecognizer.resultOfError(SpeechRecognizer.ERROR_NETWORK))
        assertEquals(VoiceResult.Failed(VoiceFailure.OTHER), AndroidVoiceCommandRecognizer.resultOfError(SpeechRecognizer.ERROR_CLIENT))
    }

    @Test
    fun theLanguagePartDecidesAvailability() {
        fun of(installed: List<String>, pending: List<String> = emptyList(), supported: List<String> = emptyList()) =
            AndroidVoiceCommandRecognizer.availabilityOf("sv-SE", installed, pending, supported)
        assertEquals(VoiceAvailability.AVAILABLE, of(listOf("en-US", "sv-SE")))
        assertEquals(VoiceAvailability.AVAILABLE, of(listOf("sv")))
        assertEquals(VoiceAvailability.DOWNLOADABLE, of(listOf("en-US"), supported = listOf("sv-SE", "de-DE")))
        assertEquals(VoiceAvailability.DOWNLOADABLE, of(listOf("en-US"), pending = listOf("sv-SE")))
        assertEquals(VoiceAvailability.UNAVAILABLE, of(listOf("en-US"), supported = listOf("de-DE")))
        assertEquals(VoiceAvailability.AVAILABLE, of(listOf("sv_SE")))
        // Device test B2: a recognizer that reports nothing at all says nothing; the first round tells.
        assertEquals(VoiceAvailability.UNKNOWN, of(emptyList()))
    }

    @Test
    fun withoutARecognizerTheCheckSaysSoInAFullTag() = runTest {
        // Robolectric has neither an on-device recognizer nor a speech service.
        val recognizer = AndroidVoiceCommandRecognizer(context)
        for (engine in VoiceEngine.entries) {
            val check = recognizer.check("en", engine)
            assertEquals(VoiceAvailability.NO_SERVICE, check.availability)
            // B1: never the bare "en".
            assertTrue(check.languageTag, check.languageTag.startsWith("en-"))
            assertEquals(VoiceDownload.FAILED, recognizer.downloadLanguage(check.languageTag, engine))
        }
        recognizer.release()
    }

    @Test
    fun withoutTheMicrophoneTheRoundFailsAtOnce() = runTest {
        val recognizer = AndroidVoiceCommandRecognizer(context)
        shadowOf(context).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertFalse(recognizer.hasPermission())
        assertEquals(VoiceResult.Failed(VoiceFailure.NO_PERMISSION), recognizer.listenOnce("sv-SE", VoiceEngine.ON_DEVICE))
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertTrue(recognizer.hasPermission())
        recognizer.release()
    }

    @Test
    fun aSilentMediaVolumeIsSeen() = runTest {
        // Device test B11: reading aloud plays on the media stream; at 0 nothing is heard.
        val audio = context.getSystemService(AudioManager::class.java)
        val speaker = AndroidStepSpeaker(context)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        assertTrue(speaker.observeSilent().first())
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
        assertFalse(speaker.observeSilent().first())
        speaker.release()
    }

    @Test
    fun theManifestAsksForTheMicrophoneOnly() {
        val info = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        assertTrue(Manifest.permission.RECORD_AUDIO in info.requestedPermissions.orEmpty())
        assertFalse("android.permission.FOREGROUND_SERVICE_MICROPHONE" in info.requestedPermissions.orEmpty())
    }
}
