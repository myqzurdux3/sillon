package fr.appprepa.core.engine

import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.Judgement
import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.model.Verdict
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L'invariant qui tient la session debout.
 *
 * La boucle ne tourne que tant qu'il lui reste un evenement a traiter, et seuls les effets
 * en produisent : `Speak` rend un `SpeechFinished`, `Listen` rend ce qui a ete entendu.
 * Une reduction qui ne rend ni l'un ni l'autre, en pleine session, vide la file et arrete
 * tout — sans erreur, sans un mot, au milieu du trajet. C'est arrive une fois avec
 * « reviens » sans carte precedente, et une seconde fois avec « annule ».
 *
 * Ce test parcourt donc toutes les commandes dans toutes les fenetres d'ecoute.
 */
class JamaisMuetTest {

    private fun card(id: Long) = ReviewCard(id, 0, "Prepa", "recto $id", "verso $id", 4, false)

    private fun inFlight(id: Long) =
        CardInFlight(card(id), "question orale $id", listOf("point $id"), 1_000L)

    private fun judgement() =
        Judgement(Verdict.CORRECT, Ease.GOOD, "retour", null, "theme")

    /** Toutes les phrases que le parseur reconnait, plus une reponse ordinaire. */
    private val phrases = listOf(
        "à revoir", "faux", "difficile", "bien", "facile",
        "répète", "passe", "explique", "annule", "reviens",
        "explique la précédente", "stop", "une réponse tout à fait ordinaire",
    )

    /** Avec et sans note en attente : c'est l'absence de note qui avait revele le trou. */
    private fun sessions(state: SessionState) = listOf(
        Session(state = state),
        Session(state = state, pending = listOf(PendingAnswerFixtures.pending(card(1), Ease.GOOD))),
    )

    private fun etats() = listOf(
        SessionState.Listening(inFlight(2)),
        SessionState.AwaitingCorrection(inFlight(2), Assessment.Judged(judgement())),
        SessionState.AwaitingCorrection(inFlight(2), Assessment.SelfGrade("verso 2")),
        SessionState.Revisiting(inFlight(2), PendingAnswerFixtures.pending(card(1), Ease.GOOD)),
    )

    @Test
    fun `aucune commande ne laisse la session sans rien a faire`() {
        etats().forEach { etat ->
            sessions(etat).forEach { session ->
                phrases.forEach { phrase ->
                    verifie(session, Event.Heard(phrase), "« $phrase » dans $etat")
                }
                verifie(session, Event.HeardNothing, "un silence dans $etat")
            }
        }
    }

    /**
     * Une reduction relance la boucle si elle parle, si elle ecoute, si elle attend une
     * reponse de port — ou si elle a fini, ce qui est une facon legitime de s'arreter.
     */
    private fun verifie(session: Session, event: Event, quoi: String) {
        var courante = ReviewSessionEngine.reduce(session, event, 5_000L)

        // Un enonce ne rend la main qu'a la fin : on suit la chaine jusqu'a l'ecoute.
        repeat(4) {
            if (relance(courante)) return
            if (courante.effects.none { it is Effect.Speak }) return@repeat
            courante = ReviewSessionEngine.reduce(courante.session, Event.SpeechFinished, 6_000L)
        }

        assertTrue("session muette apres $quoi : ${courante.effects}", relance(courante))
    }

    private fun relance(reduction: Reduction): Boolean =
        reduction.session.state is SessionState.Finished ||
            reduction.session.state is SessionState.Failed ||
            reduction.effects.any {
                it is Effect.Listen || it is Effect.Judge ||
                    it is Effect.Explain || it is Effect.Reformulate || it is Effect.LoadCards
            }
}
