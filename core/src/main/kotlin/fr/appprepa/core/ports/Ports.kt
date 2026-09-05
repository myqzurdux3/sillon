package fr.appprepa.core.ports

import fr.appprepa.core.deck.DeckInfo
import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.Judgement
import fr.appprepa.core.model.JournalRecord
import fr.appprepa.core.model.Langue
import fr.appprepa.core.model.ReformulatedQuestion
import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.model.SessionMemory

/** Acces a la collection AnkiDroid. */
interface AnkiGateway {
    /**
     * Cartes dues des paquets demandes, entrelacees. Un ensemble vide vaut « tous les
     * paquets ».
     */
    suspend fun dueCards(deckIds: Set<Long>, limit: Int): List<ReviewCard>
    suspend fun answer(noteId: Long, cardOrd: Int, ease: Ease, timeTakenMs: Long)

    /** Les paquets, avec leur nombre de cartes dues. */
    suspend fun decks(): List<DeckInfo>
}

/** Le LLM, vu par le moteur. */
interface Tutor {
    suspend fun reformulate(card: ReviewCard, memory: SessionMemory): ReformulatedQuestion
    suspend fun judge(
        card: ReviewCard,
        expectedPoints: List<String>,
        transcript: String,
        memory: SessionMemory,
        langueCorrection: Langue = Langue.FRANCAIS,
    ): Judgement
    suspend fun explain(card: ReviewCard, langueCorrection: Langue = Langue.FRANCAIS): String
}

/**
 * Synthese vocale. [speak] ne rend la main qu'a la fin de l'enonce.
 *
 * La langue est portee par chaque enonce, et non fixee a la construction : une session
 * melange des cartes francaises et anglaises, et la question d'une carte anglaise lue par
 * une voix francaise est inecoutable.
 */
interface Speaker {
    suspend fun speak(text: String, langue: Langue = Langue.FRANCAIS)
    fun stop()

    /**
     * Prepare un enonce sans le dire, si l'implementation sait le faire.
     *
     * Une synthese distante coute un aller-retour avant le premier mot. Celui de la
     * question est evitable : elle est connue pendant que l'utilisateur repond encore a
     * la carte precedente. Celui du verdict ne l'est pas, il depend de la reponse.
     *
     * Sans effet par defaut : une synthese embarquee n'a rien a prechauffer, et un
     * echec de prechauffage ne doit jamais empecher de parler ensuite.
     */
    suspend fun warm(text: String, langue: Langue = Langue.FRANCAIS) = Unit
}

sealed interface ListenResult {
    data class Transcript(val text: String) : ListenResult
    data object Silence : ListenResult
    data class Failure(val cause: String) : ListenResult
}

/**
 * Les deux fenetres d'ecoute. Elles n'attendent pas la meme chose : une reponse est une
 * phrase que l'on cherche parfois, une correction est un mot que l'on connait deja.
 * L'adaptateur s'en sert pour regler la duree de silence qui met fin a l'ecoute.
 */
enum class ListenKind { ANSWER, CORRECTION }

/**
 * Reconnaissance vocale.
 *
 * [langue] n'a pas de valeur par defaut, volontairement : l'oublier reglerait le micro sur
 * le francais sans rien signaler, et une reponse anglaise ainsi transcrite ressemble a une
 * reponse fausse, pas a une panne.
 */
interface Listener {
    suspend fun listen(kind: ListenKind, timeoutMs: Long, langue: Langue): ListenResult
}

interface Journal {
    suspend fun record(entry: JournalRecord)
}

interface Clock {
    fun nowMs(): Long
}
