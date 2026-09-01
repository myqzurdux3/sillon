package fr.appprepa.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import fr.appprepa.core.model.Langue
import fr.appprepa.core.ports.ListenKind
import fr.appprepa.core.ports.ListenResult
import fr.appprepa.core.ports.Listener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * `SpeechRecognizer` doit etre cree et pilote depuis le thread principal ; l'adaptateur
 * s'en charge et n'expose qu'une fonction suspendue.
 */
class AndroidListener(
    private val context: Context,
    /**
     * L'accent anglais vise. Il ne change pas la langue reconnue, mais le modele
     * acoustique : un moteur regle sur l'americain transcrit mal un accent britannique
     * marque, et inversement.
     */
    private val accentAnglais: Locale = Locale.UK,
) : Listener {

    private val main = Handler(Looper.getMainLooper())

    companion object {

        /**
         * Silence apres lequel la reconnaissance considere la reponse finie.
         *
         * Ce silence est du temps mort pur : l'utilisateur a fini de parler et attend. Une
         * seconde et demie coupait quelqu'un qui cherche ses mots, deux et demie se
         * remarquaient a chaque carte. Deux secondes tiennent parce que le depassement est
         * rattrape ailleurs : le moteur relance quand la phrase s'arrete sur un mot qui
         * appelle une suite, voir `Utterance`.
         */
        const val SILENCE_REPONSE_MS = 2_000L

        /**
         * Une correction est un mot connu d'avance — « bien », « faux », « passe ». On ne
         * cherche pas ses mots pour la dire, et rien ne la relance : attendre aussi
         * longtemps qu'apres une reponse ne fait qu'ajouter du blanc entre deux cartes.
         */
        const val SILENCE_CORRECTION_MS = 1_100L

        private fun silence(kind: ListenKind): Long = when (kind) {
            ListenKind.ANSWER -> SILENCE_REPONSE_MS
            ListenKind.CORRECTION -> SILENCE_CORRECTION_MS
        }

        /**
         * Verifie que l'ecoute est possible avant de partir. AnkiDroid et la synthese
         * vocale sont deja controles au demarrage ; le micro ne l'etait pas, et une
         * panne se confondait alors avec du silence pendant tout le trajet.
         */
        fun unavailableReason(context: Context): String? {
            if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                return "L'accès au micro n'a pas été autorisé."
            }
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                return "Aucun service de reconnaissance vocale n'est disponible sur ce téléphone."
            }
            return null
        }
    }

    /** Le francais de France, ou l'accent anglais choisi dans les reglages. */
    private fun locale(langue: Langue): Locale = when (langue) {
        Langue.FRANCAIS -> Locale.FRANCE
        Langue.ANGLAIS -> accentAnglais
    }

    override suspend fun listen(
        kind: ListenKind,
        timeoutMs: Long,
        langue: Langue,
    ): ListenResult =
        suspendCancellableCoroutine { continuation ->
            main.post {
                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    continuation.resume(ListenResult.Failure("reconnaissance vocale indisponible"))
                    return@post
                }

                val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                val settled = AtomicBoolean(false)
                lateinit var expiry: Runnable

                // `SpeechRecognizer` n'existe que sur le thread principal, y compris pour
                // mourir. L'annulation, elle, arrive du thread de la boucle : elle repasse
                // donc par le Handler au lieu de detruire le service depuis ailleurs.
                fun settle(result: ListenResult) {
                    if (!settled.compareAndSet(false, true)) return
                    main.removeCallbacks(expiry)
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
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale(langue).toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                        silence(kind),
                    )
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                        silence(kind),
                    )
                }

                expiry = Runnable { settle(ListenResult.Silence) }
                main.postDelayed(expiry, timeoutMs)
                continuation.invokeOnCancellation { main.post { settle(ListenResult.Silence) } }
                recognizer.startListening(intent)
            }
        }
}
