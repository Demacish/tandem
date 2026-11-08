package casa.moller.tandem.core.timer.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import casa.moller.tandem.core.data.voice.SpeakResult
import casa.moller.tandem.core.data.voice.StepSpeaker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fas 5 (kok lane, plan §8.1 "Handsfree": "uppläsning på svenska"): core:data's [StepSpeaker] on
 * the phone's [TextToSpeech] engine. While it reads it holds TRANSIENT audio focus that lets music
 * duck (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`), and gives it back as soon as the step is read.
 *
 * The engine starts at the first [speak] (never at app start) and is shut down in [release].
 */
@Singleton
internal class AndroidStepSpeaker @Inject constructor(@ApplicationContext private val context: Context) : StepSpeaker {

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()

    /** Main thread only. */
    private var engine: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null

    override suspend fun speak(text: String, languageTag: String): SpeakResult = withContext(Dispatchers.Main.immediate) {
        val tts = start() ?: return@withContext SpeakResult.UNAVAILABLE
        val language = tts.setLanguage(Locale.forLanguageTag(languageTag))
        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
            return@withContext SpeakResult.UNAVAILABLE
        }
        val audio = context.getSystemService(AudioManager::class.java)
        audio?.requestAudioFocus(focus)
        try {
            read(tts, text.take(TextToSpeech.getMaxSpeechInputLength()))
        } finally {
            audio?.abandonAudioFocusRequest(focus)
        }
    }

    private suspend fun read(tts: TextToSpeech, text: String): SpeakResult = suspendCancellableCoroutine { continuation ->
        val id = UUID.randomUUID().toString()
        val once = AtomicBoolean(false)
        val finish: (SpeakResult) -> Unit = { result ->
            if (once.compareAndSet(false, true) && continuation.isActive) continuation.resume(result)
        }
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id) finish(SpeakResult.DONE)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id) finish(SpeakResult.FAILED)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId == id) finish(SpeakResult.FAILED)
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    if (utteranceId == id) finish(SpeakResult.FAILED)
                }
            },
        )
        tts.setAudioAttributes(attributes)
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR) finish(SpeakResult.FAILED)
        continuation.invokeOnCancellation {
            once.set(true)
            tts.stop()
        }
    }

    /** The engine, started once and waited for (at most [INIT_TIMEOUT_MILLIS]); null when there is none. */
    private suspend fun start(): TextToSpeech? {
        val waiting = ready ?: CompletableDeferred<Boolean>().also { deferred ->
            ready = deferred
            engine = TextToSpeech(context) { status -> deferred.complete(status == TextToSpeech.SUCCESS) }
        }
        val ok = withTimeoutOrNull(INIT_TIMEOUT_MILLIS) { waiting.await() } == true
        if (!ok) release()
        return engine.takeIf { ok }
    }

    override fun release() {
        engine?.shutdown()
        engine = null
        ready = null
    }

    private companion object {
        const val INIT_TIMEOUT_MILLIS = 5_000L
    }
}
