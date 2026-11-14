package casa.moller.tandem.core.timer.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import casa.moller.tandem.core.data.voice.VoiceAvailability
import casa.moller.tandem.core.data.voice.VoiceCheck
import casa.moller.tandem.core.data.voice.VoiceCommandRecognizer
import casa.moller.tandem.core.data.voice.VoiceDownload
import casa.moller.tandem.core.data.voice.VoiceEngine
import casa.moller.tandem.core.data.voice.VoiceFailure
import casa.moller.tandem.core.data.voice.VoiceResult
import casa.moller.tandem.core.domain.voice.VoiceLocales
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * speech recognizers.
 *
 * - [VoiceEngine.ON_DEVICE] (the default): [SpeechRecognizer.createOnDeviceSpeechRecognizer]
 *   (Android 12+; the app's minSdk is 34). Speech never leaves the phone.
 * - [VoiceEngine.SPEECH_SERVICE] (owner's decision 2026-11-14, device test B3; opt-in, off by
 *   default): [SpeechRecognizer.createSpeechRecognizer], the phone's regular speech service, with
 *   `EXTRA_PREFER_OFFLINE`. It may send speech to that service over the internet when it has no
 *   offline language pack; the setting says so.
 *
 * Device test 2026-11-14: the language always has a region (B1, [VoiceLocales]: "en" → "en-US";
 * a bare "en" got error 12); error 12 (not supported) and 13 (not downloaded) are told apart and
 * empty support lists are "unknown", not "available" (B2); the download is
 * [SpeechRecognizer.triggerModelDownload] with its listener (API 34), so cook mode knows when to
 * listen again.
 *
 * Every call to the recognizer runs on the main thread (its requirement). One recognizer is kept
 * between rounds and destroyed after a failure (a fresh one recovers from "busy" and a dead
 * service), when the engine changes and in [release]. Listening is one utterance per [listenOnce];
 * the caller restarts it. Nothing heard is logged or kept: the alternatives go to the caller only.
 */
@Singleton
internal class AndroidVoiceCommandRecognizer @Inject constructor(@ApplicationContext private val context: Context) :
    VoiceCommandRecognizer {

    private val main = Handler(Looper.getMainLooper())

    /** Main thread only. */
    private var recognizer: SpeechRecognizer? = null
    private var recognizerEngine: VoiceEngine? = null

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override suspend fun check(languageTag: String, engine: VoiceEngine): VoiceCheck = withContext(Dispatchers.Main.immediate) {
        val region = deviceRegion()
        val guess = VoiceLocales.resolve(languageTag, region)
        when {
            !hasService(engine) -> VoiceCheck(VoiceAvailability.NO_SERVICE, guess)

            // The speech service is not asked: it may recognise over the network, so its lists say little.
            engine == VoiceEngine.SPEECH_SERVICE -> VoiceCheck(VoiceAvailability.UNKNOWN, guess)

            else -> {
                val support = supportOf(guess)
                if (support == null) {
                    VoiceCheck(VoiceAvailability.UNKNOWN, guess)
                } else {
                    val installed = support.installedOnDeviceLanguages
                    val pending = support.pendingOnDeviceLanguages
                    val supported = support.supportedOnDeviceLanguages
                    val tag = VoiceLocales.resolve(languageTag, region, installed + pending + supported)
                    VoiceCheck(availabilityOf(tag, installed, pending, supported), tag)
                }
            }
        }
    }

    /** The on-device recognizer's support lists, or null without an answer in [SUPPORT_TIMEOUT_MILLIS]. */
    private suspend fun supportOf(languageTag: String): RecognitionSupport? = withTimeoutOrNull(SUPPORT_TIMEOUT_MILLIS) {
        suspendCancellableCoroutine { continuation ->
            val once = AtomicBoolean(false)
            obtain(VoiceEngine.ON_DEVICE).checkRecognitionSupport(
                intent(languageTag),
                ContextCompat.getMainExecutor(context),
                object : RecognitionSupportCallback {
                    override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                        if (once.compareAndSet(false, true)) continuation.resumeIfActive(recognitionSupport)
                    }

                    override fun onError(error: Int) {
                        if (once.compareAndSet(false, true)) continuation.resumeIfActive(null)
                    }
                },
            )
        }
    }

    override suspend fun downloadLanguage(languageTag: String, engine: VoiceEngine): VoiceDownload =
        withContext(Dispatchers.Main.immediate) {
            if (!hasService(engine)) return@withContext VoiceDownload.FAILED
            val speech = obtain(engine)
            // Still downloading after the wait: "later", with "Försök igen".
            withTimeoutOrNull(DOWNLOAD_TIMEOUT_MILLIS) { download(speech, languageTag) } ?: VoiceDownload.SCHEDULED
        }

    private suspend fun download(speech: SpeechRecognizer, languageTag: String): VoiceDownload =
        suspendCancellableCoroutine { continuation ->
            val once = AtomicBoolean(false)
            val finish: (VoiceDownload) -> Unit = { result -> if (once.compareAndSet(false, true)) continuation.resumeIfActive(result) }
            val listener = object : ModelDownloadListener {
                override fun onProgress(completedPercent: Int) = Unit

                override fun onSuccess() = finish(VoiceDownload.DONE)

                override fun onScheduled() = finish(VoiceDownload.SCHEDULED)

                override fun onError(error: Int) = finish(VoiceDownload.FAILED)
            }
            // A recognizer that cannot download (an old service) may throw instead of calling back.
            runCatching { speech.triggerModelDownload(intent(languageTag), ContextCompat.getMainExecutor(context), listener) }
                .onFailure { finish(VoiceDownload.FAILED) }
        }

    override suspend fun listenOnce(languageTag: String, engine: VoiceEngine): VoiceResult = withContext(Dispatchers.Main.immediate) {
        when {
            !hasPermission() -> VoiceResult.Failed(VoiceFailure.NO_PERMISSION)
            !hasService(engine) -> VoiceResult.Failed(VoiceFailure.LANGUAGE_UNAVAILABLE)
            else -> listen(obtain(engine), languageTag).also { result -> if (result is VoiceResult.Failed) destroy() }
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

    private fun hasService(engine: VoiceEngine): Boolean = when (engine) {
        VoiceEngine.ON_DEVICE -> SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        VoiceEngine.SPEECH_SERVICE -> SpeechRecognizer.isRecognitionAvailable(context)
    }

    private fun obtain(engine: VoiceEngine): SpeechRecognizer {
        val current = recognizer
        if (current != null && recognizerEngine == engine) return current
        destroy()
        val created = when (engine) {
            VoiceEngine.ON_DEVICE -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            VoiceEngine.SPEECH_SERVICE -> SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = created
        recognizerEngine = engine
        return created
    }

    private fun destroy() {
        recognizer?.destroy()
        recognizer = null
        recognizerEngine = null
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** The phone's own region ("SE"), not the app's: the per-app language usually has none. */
    private fun deviceRegion(): String? {
        val locales = Resources.getSystem().configuration.locales
        return if (locales.isEmpty) null else locales[0].country.ifEmpty { null }
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

        /** How long cook mode waits for a download before it says "later" (with "Försök igen"). */
        private const val DOWNLOAD_TIMEOUT_MILLIS = 5 * 60_000L
        private val BIASING = arrayListOf("nästa", "tillbaka", "upprepa", "next", "back", "repeat")

        /** How a [SpeechRecognizer] error code ends the round. */
        fun resultOfError(error: Int): VoiceResult = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceResult.NothingHeard

            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> VoiceResult.Failed(VoiceFailure.BUSY)

            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceResult.Failed(VoiceFailure.NO_PERMISSION)

            // B2: 12 = the recognizer has no such language, 13 = it has, but not downloaded yet.
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> VoiceResult.Failed(VoiceFailure.LANGUAGE_UNAVAILABLE)

            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> VoiceResult.Failed(VoiceFailure.LANGUAGE_NOT_DOWNLOADED)

            SpeechRecognizer.ERROR_AUDIO -> VoiceResult.Failed(VoiceFailure.AUDIO)

            else -> VoiceResult.Failed(VoiceFailure.OTHER)
        }

        /**
         * Whether the on-device recognizer can listen in [languageTag], from its support lists
         * (BCP-47 tags such as "sv-SE"; the language part decides, "sv" matches "sv-SE"). Empty
         * lists say nothing (the device test's recognizer reported none, also for a language it
         * could download, B2): [VoiceAvailability.UNKNOWN], and the first round tells.
         */
        fun availabilityOf(
            languageTag: String,
            installed: List<String>,
            pending: List<String>,
            supported: List<String>,
        ): VoiceAvailability {
            val language = VoiceLocales.languageOf(languageTag)
            fun List<String>.has(): Boolean = any { VoiceLocales.languageOf(it) == language }
            return when {
                installed.has() -> VoiceAvailability.AVAILABLE
                pending.has() || supported.has() -> VoiceAvailability.DOWNLOADABLE
                installed.isEmpty() && pending.isEmpty() && supported.isEmpty() -> VoiceAvailability.UNKNOWN
                else -> VoiceAvailability.UNAVAILABLE
            }
        }

        private fun <T> CancellableContinuation<T>.resumeIfActive(value: T) {
            if (isActive) resume(value)
        }
    }
}
