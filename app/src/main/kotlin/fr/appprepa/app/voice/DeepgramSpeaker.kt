package fr.appprepa.app.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import fr.appprepa.core.model.Langue
import fr.appprepa.core.ports.Speaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Synthese vocale par Deepgram, jouee pendant qu'elle arrive.
 *
 * Le flux n'est pas une optimisation, c'est la condition pour que ce soit utilisable.
 * Mesure sur une question reelle : le premier octet arrive au bout d'une seconde, le
 * fichier complet au bout de 3,8. Attendre le fichier ajouterait pres de quatre secondes
 * de silence avant chaque phrase, soit huit par carte — l'application deviendrait plus
 * lente qu'avant, alors que la lenteur est justement l'un des reproches.
 *
 * Le format demande est du PCM brut, pas du MP3 : il part directement dans [AudioTrack]
 * sans decodeur, donc sans tampon supplementaire ni delai de decodage.
 *
 * En cas de panne — reseau coupe, tunnel, quota — on repasse par [repli], la synthese
 * embarquee d'Android. Une voix moins belle qui parle vaut infiniment mieux qu'une belle
 * voix muette : sans elle, la seance continuerait a l'aveugle.
 */
class DeepgramSpeaker(
    private val apiKey: String,
    private val repli: Speaker,
    private val voixFrancaise: String = VOIX_FR_DEFAUT,
    private val accentAnglais: Locale = Locale.UK,
    private val client: OkHttpClient = OkHttpClient.Builder().build(),
) : Speaker {

    /** La lecture en cours, pour que [stop] puisse la couper depuis un autre fil. */
    private val enCours = AtomicReference<AudioTrack?>(null)

    @Volatile
    private var arrete = false

    override suspend fun speak(text: String, langue: Langue) {
        if (text.isBlank()) return
        arrete = false
        val joue = runCatching { diffuser(text, langue) }.getOrElse {
            Log.w(TAG, "synthese distante indisponible : ${it.message}")
            false
        }
        // Le repli couvre aussi le cas d'une reponse vide ou tronquee : ce qui compte est
        // que quelque chose soit dit, sinon la boucle enchaine en silence.
        if (!joue && !arrete) repli.speak(text, langue)
    }

    private suspend fun diffuser(text: String, langue: Langue): Boolean =
        withContext(Dispatchers.IO) {
            val corps = JSONObject().put("text", text).toString()
                .toRequestBody("application/json".toMediaType())

            client.newCall(
                Request.Builder()
                    .url(url(langue))
                    .header("Authorization", "Token $apiKey")
                    .post(corps)
                    .build(),
            ).execute().use { reponse ->
                if (!reponse.isSuccessful) {
                    Log.w(TAG, "synthese refusee : ${reponse.code}")
                    return@withContext false
                }
                val flux = reponse.body?.byteStream() ?: return@withContext false
                jouer(flux)
            }
        }

    /** Ecrit le flux dans la sortie audio au fil de son arrivee, puis attend la fin. */
    private fun jouer(flux: java.io.InputStream): Boolean {
        val minimum = AudioTrack.getMinBufferSize(SAMPLE_RATE, CANAL, FORMAT)
        if (minimum <= 0) return false

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(FORMAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CANAL)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minimum, TAILLE_TAMPON))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        enCours.set(track)
        var ecrits = 0L
        try {
            track.play()
            val buffer = ByteArray(TAILLE_PAQUET)
            while (!arrete) {
                val lus = flux.read(buffer)
                if (lus < 0) break
                if (lus == 0) continue
                // Ecriture bloquante : c'est elle qui cale la lecture du reseau sur le
                // rythme de la parole, sans quoi tout l'audio s'accumulerait en memoire.
                val n = track.write(buffer, 0, lus, AudioTrack.WRITE_BLOCKING)
                if (n < 0) break
                ecrits += n
            }
            if (arrete) return false
            attendreLaFin(track, ecrits / OCTETS_PAR_TRAME)
            return ecrits > 0
        } catch (erreur: Throwable) {
            Log.w(TAG, "lecture interrompue : ${erreur.message}")
            return false
        } finally {
            enCours.compareAndSet(track, null)
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    /**
     * `write` rend la main des que le tampon a avale les octets, pas quand ils ont ete
     * entendus. Sans cette attente, l'application rouvrirait le micro par-dessus la fin
     * de sa propre phrase — et s'entendrait elle-meme.
     */
    private fun attendreLaFin(track: AudioTrack, tramesEcrites: Long) {
        val limite = System.currentTimeMillis() + ATTENTE_MAX_MS
        while (!arrete && System.currentTimeMillis() < limite) {
            val jouees = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            if (jouees >= tramesEcrites) return
            Thread.sleep(INTERVALLE_ATTENTE_MS)
        }
    }

    override fun stop() {
        arrete = true
        enCours.getAndSet(null)?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
        }
        repli.stop()
    }

    private fun url(langue: Langue): String {
        val voix = when (langue) {
            Langue.FRANCAIS -> voixFrancaise
            Langue.ANGLAIS -> if (accentAnglais.country == "US") VOIX_EN_US else VOIX_EN_GB
        }
        return "https://api.deepgram.com/v1/speak" +
            "?model=$voix&encoding=linear16&sample_rate=$SAMPLE_RATE&container=none"
    }

    companion object {
        private const val TAG = "DeepgramSpeaker"

        /** Les deux seules voix francaises d'Aura-2. Le choix revient a l'utilisateur. */
        const val VOIX_FR_DEFAUT = "aura-2-agathe-fr"
        val VOIX_FRANCAISES = listOf("aura-2-agathe-fr", "aura-2-hector-fr")

        const val VOIX_EN_GB = "aura-2-asteria-en"
        const val VOIX_EN_US = "aura-2-thalia-en"

        const val SAMPLE_RATE = 24_000
        private const val CANAL = AudioFormat.CHANNEL_OUT_MONO
        private const val FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val OCTETS_PAR_TRAME = 2

        /** Environ un dixieme de seconde : assez pour ne pas hacher, assez peu pour couper vite. */
        private const val TAILLE_PAQUET = SAMPLE_RATE / 10 * OCTETS_PAR_TRAME

        /** Une demi-seconde d'avance : absorbe une hesitation du reseau sans allonger le debut. */
        private const val TAILLE_TAMPON = SAMPLE_RATE / 2 * OCTETS_PAR_TRAME

        /** Garde-fou : une lecture qui ne se termine pas ne doit pas figer la seance. */
        private const val ATTENTE_MAX_MS = 30_000L
        private const val INTERVALLE_ATTENTE_MS = 20L
    }
}
