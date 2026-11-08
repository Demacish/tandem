package casa.moller.tandem.core.timer.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import casa.moller.tandem.core.data.voice.VoiceAvailability
import casa.moller.tandem.core.data.voice.VoiceCommandRecognizer
import casa.moller.tandem.core.data.voice.VoiceFailure
import casa.moller.tandem.core.data.voice.VoiceResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fas 5 (kok lane, plan §8.1 "Handsfree", #32): core:data's [VoiceCommandRecognizer] on Android's
 * ON-DEVICE recognizer only ([SpeechRecognizer.createOnDeviceSpeechRecognizer], Android 12+; the
 * app's minSdk is 34). There is no fallback to the network recognizer (`createSpeechRecognizer`):
 * without on-device recognition cook mode says so and offers the volume keys.
 *
 * Every call to the recognizer runs on the main thread (its requirement). One recognizer is kept
 * between rounds and destroyed after a failure (a fresh one recovers from "busy" and a dead
 * service) and in [release]. Listening is one utterance per [listenOnce]; the caller restarts it.
 */
@Singleton
internal class AndroidVoiceCommandRecognizer @Inject constructor(@ApplicationContext private val context: Context) :
    VoiceCommandRecognizer {

    private val main = Handler(Looper.getMainLooper())

    /** Main thread only. */
    private var recognizer: SpeechRecognizer? = null

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override suspend fun availability(languageTag: String): VoiceAvailability = withContext(Dispatchers.Main.immediate) {
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return@withContext VoiceAvailability.UNAVAILABLE
        val support = withTimeoutOrNull(SUPPORT_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                val once = AtomicBoolean(false)
                obtain().checkRecognitionSupport(
                    intent(languageTag),
                    ContextCompat.getMainExecutor(context),
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                            if (once.compareAndSet(false, true)) continuation.resume(recognitionSupport)
                        }

                        override fun onError(error: Int) {
                            if (once.compareAndSet(false, true)) continuation.resume(null)
                        }
                    },
                )
            }
        }
        if (support == null) {
            // No answer (some engines never call back) or an error: try to listen; a missing
            // language then ends the round with LANGUAGE_UNAVAILABLE.
            VoiceAvailability.AVAILABLE
        } else {
            availabilityOf(
                languageTag = languageTag,
                installed = support.installedOnDeviceLanguages,
                pending = support.pendingOnDeviceLanguages,
                supported = support.supportedOnDeviceLanguages,
            )
        }
    }

    override fun downloadLanguage(languageTag: String) {
        onMain {
            if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) obtain().triggerModelDownload(intent(languageTag))
        }
    }

    override suspend fun listenOnce(languageTag: String): VoiceResult = withContext(Dispatchers.Main.immediate) {
        when {
            !hasPermission() -> VoiceResult.Failed(VoiceFailure.NO_PERMISSION)
            !SpeechRecognizer.isOnDeviceRecognitionAvailable(context) -> VoiceResult.Failed(VoiceFailure.LANGUAGE_UNAVAILABLE)
            else -> listen(obtain(), languageTag).also { result -> if (result is VoiceResult.Failed) destroy() }
        }
    }

    private suspend fun listen(speech: SpeechRecognizer, languageTag: String): VoiceResult = suspendCancellableCoroutine { continuation ->
        val once = AtomicBoolean(false)
        val finish: (VoiceResult) -> Unit = { result -> if (once.compareAndSet(false, true)) continuation.resumeIfActive(result) }
        speech.setRecognitionListener(Listener(finish))
        speech.startListening(intent(languageTag))
        // Cook mode paused, the screen went off or a command is read aloud: the microphone closes now.
        continuation.invokeOnCancellation {
            once.set(true)
            onMain { speech.cancel() }
        }
    }

    override fun release() {
        onMain { destroy() }
    }

    private fun obtain(): SpeechRecognizer = recognizer ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { recognizer = it }

    private fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun intent(languageTag: String): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        // A hint, not a filter: the commands are what cook mode listens for (core:domain VoiceCommands).
        .putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, BIASING)

    /** One round's callbacks: the end of the round is the only thing that matters. */
    private class Listener(private val finish: (VoiceResult) -> Unit) : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().filter { it.isNotBlank() }
            finish(if (heard.isEmpty()) VoiceResult.NothingHeard else VoiceResult.Heard(heard))
        }

        override fun onError(error: Int) = finish(resultOfError(error))

        override fun onReadyForSpeech(params: Bundle?) = Unit

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onPartialResults(partialResults: Bundle?) = Unit

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    internal companion object {
        private const val MAX_RESULTS = 5
        private const val SUPPORT_TIMEOUT_MILLIS = 3_000L
        private val BIASING = arrayListOf("nästa", "tillbaka", "upprepa", "next", "back", "repeat")

        /** How a [SpeechRecognizer] error code ends the round. */
        fun resultOfError(error: Int): VoiceResult = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceResult.NothingHeard

            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> VoiceResult.Failed(VoiceFailure.BUSY)

            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceResult.Failed(VoiceFailure.NO_PERMISSION)

            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                VoiceResult.Failed(VoiceFailure.LANGUAGE_UNAVAILABLE)

            SpeechRecognizer.ERROR_AUDIO -> VoiceResult.Failed(VoiceFailure.AUDIO)

            else -> VoiceResult.Failed(VoiceFailure.OTHER)
        }

        /**
         * Whether the on-device recognizer can listen in [languageTag], from its support lists
         * (BCP-47 tags such as "sv-SE"; the language part decides, "sv" matches "sv-SE"). Empty
         * lists say nothing (some engines report none): then it is tried.
         */
        fun availabilityOf(
            languageTag: String,
            installed: List<String>,
            pending: List<String>,
            supported: List<String>,
        ): VoiceAvailability {
            val language = Locale.forLanguageTag(languageTag).language
            fun List<String>.has(): Boolean = any { Locale.forLanguageTag(it).language.equals(language, ignoreCase = true) }
            return when {
                installed.has() -> VoiceAvailability.AVAILABLE
                pending.has() || supported.has() -> VoiceAvailability.DOWNLOADABLE
                installed.isEmpty() && pending.isEmpty() && supported.isEmpty() -> VoiceAvailability.AVAILABLE
                else -> VoiceAvailability.UNAVAILABLE
            }
        }

        private fun <T> CancellableContinuation<T>.resumeIfActive(value: T) {
            if (isActive) resume(value)
        }
    }
}
