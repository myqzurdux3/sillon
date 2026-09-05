package fr.appprepa.core.voice

/**
 * Recolle les morceaux d'un tour de parole, et decide quand il est fini.
 *
 * Un service de reconnaissance en flux n'envoie pas une reponse, il envoie une suite de
 * messages : des resultats provisoires qui se corrigent les uns les autres, des resultats
 * definitifs qui, eux, s'additionnent, et un signal de fin de tour. Melanger les deux
 * familles donne du texte double ou tronque.
 *
 * La regle vient de la documentation du service, et elle a deux chemins parce que les
 * deux echouent dans des situations differentes : `speech_final` repose sur le silence, et
 * un habitacle de voiture n'est jamais silencieux ; `UtteranceEnd` repose sur l'ecart
 * entre deux mots, et tient donc au bruit de roulement. On accepte le premier des deux
 * qui arrive.
 *
 * La classe est volontairement dans `:core` : c'est le seul endroit du chemin vocal qu'on
 * peut eprouver sans micro, sans reseau et sans appareil.
 */
class TurnAccumulator {

    private val definitifs = mutableListOf<String>()

    /** Le dernier provisoire, garde au cas ou le tour se cloture sans definitif. */
    private var provisoire: String = ""

    /** Ce qui a ete recolte jusqu'ici. */
    val texte: String
        get() = (definitifs + provisoire)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    /**
     * Un message de resultat. Rend le tour complet s'il vient de se terminer, `null` s'il
     * faut continuer d'ecouter.
     */
    fun onResult(transcript: String, isFinal: Boolean, speechFinal: Boolean): String? {
        if (isFinal) {
            // Un definitif remplace le provisoire qu'il conclut : garder les deux
            // ferait dire deux fois le meme morceau de phrase.
            provisoire = ""
            if (transcript.isNotBlank()) definitifs += transcript
        } else {
            provisoire = transcript
        }
        return if (speechFinal) cloture() else null
    }

    /**
     * Le service signale un ecart entre deux mots assez long pour valoir une fin de tour.
     * Ignore si rien n'a encore ete entendu : ce serait un tour vide.
     */
    fun onUtteranceEnd(): String? = cloture()

    /**
     * Le flux s'arrete — delai depasse, ou reseau coupe. On rend ce qu'on a : jeter une
     * reponse a moitie entendue est la pire des reactions, elle serait notee fausse.
     */
    fun onClose(): String? = texte.takeIf { it.isNotBlank() }

    private fun cloture(): String? = texte.takeIf { it.isNotBlank() }
}
