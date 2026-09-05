package fr.appprepa.app.voice

import android.util.Log
import fr.appprepa.core.model.Langue
import fr.appprepa.core.ports.ListenKind
import fr.appprepa.core.ports.ListenResult
import fr.appprepa.core.ports.Listener
import fr.appprepa.core.voice.TurnAccumulator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reconnaissance vocale par Deepgram, en flux.
 *
 * Elle remplace `SpeechRecognizer` pour deux raisons mesurees sur l'appareil, pas
 * supposees. La transcription d'abord : le journal portait « Sily white never food » pour
 * une reponse anglaise, et cinq tours entierement vides notes « a revoir ». La detection
 * de fin de parole ensuite, qui est le vrai motif : `SpeechRecognizer` decide sur un
 * compte a rebours de silence, que j'ai regle a l'aveugle trois fois de suite ; Deepgram
 * decide sur l'ecart entre deux mots, ce qui tient dans un habitacle bruyant la ou le
 * silence n'arrive jamais.
 */
class DeepgramListener(
    private val apiKey: String,
    private val accentAnglais: Locale = Locale.UK,
    private val client: OkHttpClient = defaultClient(),
    private val micro: () -> MicrophoneStream = { MicrophoneStream() },
) : Listener {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun listen(
        kind: ListenKind,
        timeoutMs: Long,
        langue: Langue,
    ): ListenResult = withContext(Dispatchers.IO) {
        val tour = TurnAccumulator()
        val fini = CompletableDeferred<String?>()
        val micDispo = micro()

        if (!micDispo.start()) {
            return@withContext ListenResult.Failure("micro indisponible")
        }

        val socket = client.newWebSocket(
            Request.Builder()
                .url(url(kind, langue))
                .header("Authorization", "Token $apiKey")
                .build(),
            ecouteur(tour, fini),
        )

        val envoiTermine = AtomicBoolean(false)
        try {
            coroutineScope {
                // Le micro alimente la socket sur sa propre coroutine : la lecture est
                // bloquante et ne doit pas retarder la reception des transcriptions.
                val pompe = launch {
                    val buffer = ByteArray(MicrophoneStream.TAILLE_PAQUET)
                    while (!envoiTermine.get()) {
                        val lus = micDispo.read(buffer)
                        if (lus <= 0) break
                        if (!socket.send(buffer.toByteString(0, lus))) break
                    }
                }

                val texte = try {
                    withTimeout(timeoutMs) { fini.await() }
                } catch (expire: TimeoutCancellationException) {
                    // Le delai n'est pas un echec : c'est la fin de la fenetre d'ecoute.
                    // Ce qui a ete entendu avant compte, et le jeter le ferait noter faux.
                    tour.onClose()
                }

                envoiTermine.set(true)
                pompe.cancel()
                texte
            }.let { texte ->
                if (texte.isNullOrBlank()) ListenResult.Silence else ListenResult.Transcript(texte)
            }
        } catch (erreur: Throwable) {
            Log.w(TAG, "ecoute interrompue : ${erreur.message}")
            // Une coupure n'est pas un silence : les confondre ferait defiler la session
            // entiere en notant tout « a revoir ».
            tour.onClose()?.let { ListenResult.Transcript(it) }
                ?: ListenResult.Failure(erreur.message ?: "flux interrompu")
        } finally {
            envoiTermine.set(true)
            micDispo.stop()
            runCatching { socket.send(FERMETURE) }
            runCatching { socket.close(1000, null) }
        }
    }

    private fun ecouteur(
        tour: TurnAccumulator,
        fini: CompletableDeferred<String?>,
    ) = object : WebSocketListener() {

        override fun onMessage(webSocket: WebSocket, text: String) {
            val objet = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            when (objet["type"]?.jsonPrimitive?.contentOrNull) {
                "Results" -> onResultats(objet)
                "UtteranceEnd" -> tour.onUtteranceEnd()?.let { fini.complete(it) }
                // Le service annonce ses erreurs dans le flux plutot que par le code HTTP.
                "Error" -> fini.completeExceptionally(
                    IllegalStateException(objet.toString().take(200)),
                )
                else -> Unit
            }
        }

        private fun onResultats(objet: kotlinx.serialization.json.JsonObject) {
            val alternative = objet["channel"]?.jsonObject
                ?.get("alternatives")?.jsonArray?.firstOrNull()?.jsonObject
            val transcript = alternative?.get("transcript")?.jsonPrimitive?.contentOrNull.orEmpty()
            val isFinal = objet["is_final"]?.jsonPrimitive?.booleanOrNull ?: false
            val speechFinal = objet["speech_final"]?.jsonPrimitive?.booleanOrNull ?: false
            tour.onResult(transcript, isFinal, speechFinal)?.let { fini.complete(it) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!fini.isCompleted) fini.completeExceptionally(t)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!fini.isCompleted) fini.complete(tour.onClose())
        }
    }

    private fun url(kind: ListenKind, langue: Langue): String {
        val silence = when (kind) {
            // Une reponse se cherche : on laisse le temps de reprendre son souffle.
            ListenKind.ANSWER -> SILENCE_REPONSE_MS
            // Une correction est un mot connu d'avance. Attendre aussi longtemps
            // n'ajouterait que du blanc entre deux cartes.
            ListenKind.CORRECTION -> SILENCE_CORRECTION_MS
        }
        val code = when (langue) {
            Langue.FRANCAIS -> "fr"
            Langue.ANGLAIS -> if (accentAnglais.country == "US") "en-US" else "en-GB"
        }
        return buildString {
            append("wss://api.deepgram.com/v1/listen")
            append("?model=$MODEL")
            append("&language=$code")
            append("&encoding=${MicrophoneStream.ENCODING}")
            append("&sample_rate=${MicrophoneStream.SAMPLE_RATE}")
            append("&channels=1")
            // La ponctuation n'est pas cosmetique : c'est elle qui donne son intonation a
            // la synthese, et elle aide le modele a lire une reponse hesitante.
            append("&punctuate=true")
            append("&smart_format=true")
            // `utterance_end_ms` exige les resultats provisoires, faute de quoi le service
            // n'a pas de quoi mesurer l'ecart entre deux mots.
            append("&interim_results=true")
            append("&endpointing=$silence")
            append("&utterance_end_ms=$silence")
        }
    }

    companion object {
        private const val TAG = "DeepgramListener"

        /** Multilingue : c'est le meme modele qui sert le francais et l'anglais. */
        const val MODEL = "nova-3"

        /**
         * Les deux fenetres, en millisecondes. Elles gardent les valeurs eprouvees avec
         * l'ancien moteur faute de mieux, mais elles ne veulent plus dire la meme chose :
         * il s'agit d'un ecart entre deux mots, pas d'un silence acoustique. Un habitacle
         * bruyant ne les declenche plus a tort.
         */
        const val SILENCE_REPONSE_MS = 2_000
        const val SILENCE_CORRECTION_MS = 1_100

        /** Le service veut ce message pour finir proprement plutot que d'etre coupe. */
        const val FERMETURE = """{"type":"CloseStream"}"""

        /**
         * Le client est partage entre les ecoutes : rouvrir une connexion TLS a chaque
         * carte ajouterait pres d'une seconde avant le premier mot, mesuree.
         */
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder().build()
    }
}
