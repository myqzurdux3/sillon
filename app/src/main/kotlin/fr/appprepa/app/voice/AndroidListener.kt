package fr.appprepa.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import fr.appprepa.core.ports.ListenResult
import fr.appprepa.core.ports.Listener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * `SpeechRecognizer` doit etre cree et pilote depuis le thread principal ; l'adaptateur
 * s'en charge et n'expose qu'une fonction suspendue.
 */
class AndroidListener(private val context: Context) : Listener {

    private val main = Handler(Looper.getMainLooper())

    override suspend fun listen(timeoutMs: Long): ListenResult =
        suspendCancellableCoroutine { continuation ->
            main.post {
                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    continuation.resume(ListenResult.Failure("reconnaissance vocale indisponible"))
                    return@post
                }

                val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                var settled = false

                fun settle(result: ListenResult) {
                    if (settled) return
                    settled = true
                    runCatching { recognizer.destroy() }
                    if (continuation.isActive) continuation.resume(result)
                }

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle?) {
                        val text = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                        settle(
                            if (text.isNullOrEmpty()) {
                                ListenResult.Silence
                            } else {
                                ListenResult.Transcript(text)
                            },
                        )
                    }

                    override fun onError(error: Int) = settle(
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                            -> ListenResult.Silence
                            else -> ListenResult.Failure("erreur de reconnaissance $error")
                        },
                    )

                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.FRENCH.toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                        1_500L,
                    )
                }

                main.postDelayed({ settle(ListenResult.Silence) }, timeoutMs)
                continuation.invokeOnCancellation { settle(ListenResult.Silence) }
                recognizer.startListening(intent)
            }
        }
}
