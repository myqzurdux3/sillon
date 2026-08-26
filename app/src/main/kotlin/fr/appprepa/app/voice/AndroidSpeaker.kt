package fr.appprepa.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import fr.appprepa.core.ports.Speaker
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class AndroidSpeaker(context: Context) : Speaker {

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
        return result != TextToSpeech.LANG_MISSING_DATA &&
            result != TextToSpeech.LANG_NOT_SUPPORTED
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
}
