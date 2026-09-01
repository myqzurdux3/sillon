package fr.appprepa.app.session

import fr.appprepa.core.ports.ListenKind
import fr.appprepa.core.ports.ListenResult
import fr.appprepa.core.ports.Listener
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Remplace le micro par un champ de texte. Reserve a la mise au point : l'emulateur n'a
 * pas d'entree audio realiste, et iterer sur les prompts en montant en voiture n'est pas
 * une methode de travail.
 */
class DebugListener : Listener {

    private val inbox = Channel<String>(Channel.BUFFERED)

    suspend fun submit(text: String) {
        inbox.send(text)
    }

    override suspend fun listen(kind: ListenKind, timeoutMs: Long): ListenResult =
        when (val text = withTimeoutOrNull(timeoutMs) { inbox.receive() }) {
            null -> ListenResult.Silence
            else -> if (text.isBlank()) ListenResult.Silence else ListenResult.Transcript(text)
        }

    companion object {
        /** Partage entre l'interface qui saisit et le service qui ecoute. */
        val shared = DebugListener()
    }
}
