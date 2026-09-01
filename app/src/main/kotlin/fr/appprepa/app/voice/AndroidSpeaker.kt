package fr.appprepa.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import fr.appprepa.core.ports.Speaker
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class AndroidSpeaker(
    context: Context,
    /** Vitesse d'elocution, 1 = celle du moteur. Reglable : c'est une affaire de gout. */
    private val speechRate: Float = DEFAULT_RATE,
) : Speaker {

    private val initialized = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    /** Deux enonces ne doivent jamais partager un identifiant : l'un ne reviendrait pas. */
    private val counter = AtomicLong(0)

    /**
     * Le rappel d'initialisation peut se declencher avant que le constructeur ait rendu
     * `engine`. On ne fait donc rien d'autre, dans ce rappel, que publier le statut ;
     * le choix de la langue attend [awaitReady], appelee apres construction.
     */
    private val engine = TextToSpeech(context.applicationContext) { status ->
        initialized.complete(status == TextToSpeech.SUCCESS)
    }

    /** La voix retenue, pour l'afficher dans les reglages. Nulle avant [awaitReady]. */
    @Volatile
    var voiceName: String? = null
        private set

    init {
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                pending.remove(utteranceId)?.complete(Unit)
            }

            @Deprecated("remplacee par onError(String, Int)")
            override fun onError(utteranceId: String?) {
                pending.remove(utteranceId)?.complete(Unit)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                pending.remove(utteranceId)?.complete(Unit)
            }
        })
    }

    suspend fun awaitReady(): Boolean {
        if (!initialized.await()) return false
        val result = engine.setLanguage(Locale.FRENCH)
        if (result == TextToSpeech.LANG_MISSING_DATA ||
            result == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            return false
        }

        engine.setSpeechRate(speechRate)
        engine.setPitch(PITCH)
        chooseVoice()
        return true
    }

    /**
     * Le moteur retient la premiere voix francaise venue, qui est souvent la plus pauvre :
     * une voix concatenative, plate, la ou le meme moteur en embarque une neuronale. Le
     * classement va donc chercher la meilleure explicitement.
     *
     * Les voix embarquees passent avant les voix reseau, meme mieux notees. Une voix reseau
     * ajoute un aller-retour a chaque enonce, et se tait dans un tunnel : le moteur signale
     * alors une erreur, l'application enchaine sans un mot, et la seance continue a l'aveugle.
     * Une voix un cran en dessous qui parle toujours vaut mieux que la plus belle qui
     * s'interrompt sur l'autoroute.
     */
    private fun chooseVoice() {
        val francaises = runCatching { engine.voices }.getOrNull()
            ?.filter { it.locale.language == Locale.FRENCH.language }
            ?.filterNot { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features }
            ?.takeIf { it.isNotEmpty() }
            ?: return

        // Une voix plus rapide a demarrer departage deux voix de meme qualite : c'est
        // autant de silence en moins entre la fin de la reponse et le verdict.
        val classement = compareBy<Voice> { it.quality }.thenBy { -it.latency }
        val embarquees = francaises.filterNot { it.isNetworkConnectionRequired }
        val best = (embarquees.ifEmpty { francaises }).maxWith(classement)

        if (engine.setVoice(best) == TextToSpeech.SUCCESS) voiceName = best.name
    }

    /** Ne rend la main qu'a la fin reelle de l'enonce. */
    override suspend fun speak(text: String) {
        if (!initialized.await()) return
        val id = "u${counter.getAndIncrement()}"
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        engine.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        done.await()
    }

    override fun stop() {
        engine.stop()
        pending.values.forEach { it.complete(Unit) }
        pending.clear()
    }

    fun release() {
        stop()
        engine.shutdown()
    }

    companion object {
        /**
         * Le debit par defaut des moteurs francais est cale sur la lecture d'un ecran, pas
         * sur quelqu'un qui ecoute en conduisant et connait deja le vocabulaire. Un cran
         * au-dessus se suit sans effort et raccourcit chaque carte.
         */
        const val DEFAULT_RATE = 1.15f

        /** Les bornes proposees dans les reglages. En dessous c'est traînant, au-dessus illisible. */
        const val MIN_RATE = 0.8f
        const val MAX_RATE = 1.6f

        /** Monter la hauteur rend la voix nasillarde : elle reste a celle du moteur. */
        private const val PITCH = 1.0f
    }
}
