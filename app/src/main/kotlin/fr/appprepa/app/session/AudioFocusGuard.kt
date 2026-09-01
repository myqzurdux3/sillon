package fr.appprepa.app.session

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import fr.appprepa.core.model.Langue
import fr.appprepa.core.ports.Speaker
import kotlinx.coroutines.CompletableDeferred

/**
 * Focus audio transitoire avec attenuation : la musique baisse pendant la question au lieu
 * de s'arreter, et une instruction GPS ou un appel entrant suspend la session au lieu de
 * parler par-dessus.
 */
class AudioFocusGuard(
    context: Context,
    /**
     * Une perte definitive du focus — un appel qui dure, un autre assistant qui prend la
     * main — ne rend jamais le focus. Attendre une reprise qui n'arrivera pas figerait la
     * session sans un mot ; on prefere l'arreter proprement, notes en attente comprises.
     */
    private val onPermanentLoss: () -> Unit = {},
) {

    private val manager = context.getSystemService(AudioManager::class.java)

    @Volatile
    private var paused: CompletableDeferred<Unit>? = null

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setOnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    resume()
                    onPermanentLoss()
                }

                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                    synchronized(this) { if (paused == null) paused = CompletableDeferred() }

                AudioManager.AUDIOFOCUS_GAIN -> resume()

                else -> Unit
            }
        }
        .build()

    private fun resume() = synchronized(this) {
        paused?.complete(Unit)
        paused = null
    }

    suspend fun <T> withFocus(block: suspend () -> T): T {
        manager.requestAudioFocus(request)
        return try {
            block()
        } finally {
            manager.abandonAudioFocusRequest(request)
        }
    }

    /** Suspend tant que le focus est perdu. */
    suspend fun awaitResume() {
        paused?.await()
    }
}

/**
 * Le moteur ne doit rien savoir du focus audio : c'est ce decorateur qui attend la reprise
 * avant chaque enonce. La session se suspend donc a la frontiere d'une phrase.
 */
class FocusAwareSpeaker(
    private val delegate: Speaker,
    private val guard: AudioFocusGuard,
) : Speaker {
    override suspend fun speak(text: String, langue: Langue) {
        guard.awaitResume()
        delegate.speak(text, langue)
    }

    override fun stop() = delegate.stop()
}
