package casa.moller.tandem.core.timer

import android.Manifest
import android.app.Application
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.data.voice.VoiceAvailability
import casa.moller.tandem.core.data.voice.VoiceFailure
import casa.moller.tandem.core.data.voice.VoiceResult
import casa.moller.tandem.core.timer.voice.AndroidVoiceCommandRecognizer
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
        listOf(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE).forEach {
            assertEquals(VoiceResult.Failed(VoiceFailure.LANGUAGE_UNAVAILABLE), AndroidVoiceCommandRecognizer.resultOfError(it))
        }
        assertEquals(VoiceResult.Failed(VoiceFailure.AUDIO), AndroidVoiceCommandRecognizer.resultOfError(SpeechRecognizer.ERROR_AUDIO))
        // A network error can only come from a network recognizer, which is never used: just another failure.
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
        // An engine that reports nothing at all is tried.
        assertEquals(VoiceAvailability.AVAILABLE, of(emptyList()))
    }

    @Test
    fun withoutTheMicrophoneTheRoundFailsAtOnce() = runTest {
        val recognizer = AndroidVoiceCommandRecognizer(context)
        shadowOf(context).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertFalse(recognizer.hasPermission())
        assertEquals(VoiceResult.Failed(VoiceFailure.NO_PERMISSION), recognizer.listenOnce("sv-SE"))
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertTrue(recognizer.hasPermission())
        recognizer.release()
    }

    @Test
    fun theManifestAsksForTheMicrophoneOnly() {
        val info = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        assertTrue(Manifest.permission.RECORD_AUDIO in info.requestedPermissions.orEmpty())
        assertFalse("android.permission.FOREGROUND_SERVICE_MICROPHONE" in info.requestedPermissions.orEmpty())
    }
}
