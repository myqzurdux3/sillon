package fr.appprepa.core.engine

import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.Judgement
import fr.appprepa.core.model.JournalRecord
import fr.appprepa.core.model.ReformulatedQuestion
import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.model.SessionMemory
import fr.appprepa.core.model.SessionStats
import fr.appprepa.core.model.WriteMode

/** La carte en cours de traitement, avec sa question orale. */
data class CardInFlight(
    val card: ReviewCard,
    val question: String,
    val expectedPoints: List<String>,
    val askedAtMs: Long,
)

/**
 * Une note decidee mais pas encore ecrite dans Anki. Elle n'est validee qu'au moment ou
 * la carte suivante est finalisee : cette latence d'un tour rend « annule » possible,
 * alors que l'API AnkiDroid n'offre aucune annulation apres ecriture.
 */
data class PendingAnswer(
    val card: ReviewCard,
    val ease: Ease,
    val timeTakenMs: Long,
    val record: JournalRecord,
)

/** Ce qui est annonce a l'utilisateur apres sa reponse. */
sealed interface Assessment {
    /** Cas nominal : le LLM a juge. */
    data class Judged(val judgement: Judgement) : Assessment

    /** Mode degrade : on lit le verso, l'utilisateur se note lui-meme. */
    data class SelfGrade(val answerText: String) : Assessment
}

sealed interface SessionState {
    data object Idle : SessionState
    data object Loading : SessionState

    /** La carte est connue, sa reformulation est en vol. */
    data class Preparing(val card: ReviewCard) : SessionState
    data class Asking(val inFlight: CardInFlight) : SessionState
    data class Listening(val inFlight: CardInFlight) : SessionState
    data class Judging(val inFlight: CardInFlight, val transcript: String) : SessionState
    data class SpeakingVerdict(
        val inFlight: CardInFlight,
        val assessment: Assessment,
        /** La reponse telle qu'entendue : elle doit atteindre le journal. */
        val transcript: String = "",
    ) : SessionState
    data class AwaitingCorrection(
        val inFlight: CardInFlight,
        val assessment: Assessment,
        val transcript: String = "",
    ) : SessionState
    /**
     * Parenthese ouverte sur une carte deja repondue : on la renote ou on se la fait
     * expliquer, puis [inFlight] reprend la main.
     */
    data class Revisiting(
        val inFlight: CardInFlight,
        val target: PendingAnswer,
        val explaining: Boolean = false,
    ) : SessionState

    data class Finished(val stats: SessionStats) : SessionState
    data class Failed(val reason: String) : SessionState
}

sealed interface Event {
    data class Start(val deckId: Long?, val limit: Int) : Event
    data class CardsLoaded(val cards: List<ReviewCard>) : Event
    data class Reformulated(val card: ReviewCard, val question: ReformulatedQuestion) : Event
    data object SpeechFinished : Event
    data class Heard(val transcript: String) : Event
    data object HeardNothing : Event

    /**
     * L'ecoute a echoue pour une raison technique — micro refuse, service de
     * reconnaissance absent, reseau. A ne pas confondre avec un silence : l'un se
     * relance, l'autre se signale.
     */
    data class ListenFailed(val cause: String) : Event
    data class Judged(val judgement: Judgement) : Event
    data class Explained(val text: String) : Event

    /** Le LLM ou le reseau a lache : on bascule en lecture simple. */
    data class TutorFailed(val cause: String) : Event
    data class Fatal(val reason: String) : Event
    data object StopRequested : Event
}

enum class ListenKind { ANSWER, CORRECTION }

sealed interface Effect {
    data class Speak(val text: String) : Effect
    data class Listen(val kind: ListenKind, val timeoutMs: Long) : Effect
    data class LoadCards(val deckId: Long?, val limit: Int) : Effect
    data class Reformulate(val card: ReviewCard, val memory: SessionMemory) : Effect
    data class Judge(
        val card: ReviewCard,
        val expectedPoints: List<String>,
        val transcript: String,
        val memory: SessionMemory,
    ) : Effect
    data class Explain(val card: ReviewCard) : Effect
    data class Commit(val pending: PendingAnswer) : Effect
    data class Record(val entry: JournalRecord) : Effect
    data object Finish : Effect
}

/** L'integralite de l'etat d'une session. Immuable. */
data class Session(
    val state: SessionState = SessionState.Idle,
    val memory: SessionMemory = SessionMemory(),
    val queue: List<ReviewCard> = emptyList(),
    val prefetch: ReformulatedQuestion? = null,
    val prefetchFor: Long? = null,
    /**
     * Notes decidees mais pas encore ecrites, de la plus ancienne a la plus recente.
     * Le plafond vaut 1 aujourd'hui ; le porter a 2 ou 3 ne demande que de changer
     * [ReviewSessionEngine.MAX_PENDING].
     */
    val pending: List<PendingAnswer> = emptyList(),
    val degraded: Boolean = false,
    val stats: SessionStats = SessionStats(),
    val writeMode: WriteMode = WriteMode.JOURNAL_ONLY,
    val deckId: Long? = null,
    val retriedAnswer: Boolean = false,
    /** Echecs d'ecoute consecutifs. Remis a zero des qu'une reponse est entendue. */
    val listenFailures: Int = 0,
    /** Nombre de cartes exploitables chargees au depart. */
    val total: Int = 0,
) {
    /** Rang de la carte en cours, borne au total : « carte 7 sur 32 ». */
    val position: Int
        get() = if (total == 0) 0 else (stats.answered + stats.skipped + 1).coerceAtMost(total)
}

data class Reduction(val session: Session, val effects: List<Effect>)
