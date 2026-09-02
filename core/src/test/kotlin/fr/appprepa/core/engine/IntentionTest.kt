package fr.appprepa.core.engine

import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.Intention
import fr.appprepa.core.model.Judgement
import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.model.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Les demandes dites en phrase entiere.
 *
 * Le journal de l'utilisateur en portait deux, notees fausses toutes les deux :
 * « j'ai pas bien entendu est-ce que tu peux répéter » et « puis-je corriger la question
 * d'avant ». Douze mots la ou le parseur de mots-cles en tolerait quatre. Ces tests
 * verifient que le moteur agit sur la lecture du modele, et surtout qu'aucune de ces
 * demandes ne laisse une note derriere elle.
 */
class IntentionTest {

    private val carte = ReviewCard(
        noteId = 1,
        cardOrd = 0,
        deckName = "Info",
        question = "recto",
        answer = "verso",
        buttonCount = 4,
        hasMedia = false,
    )

    private val enVol = CardInFlight(carte, "question orale", listOf("point"), 0L)

    private fun juge(intention: Intention) = Judgement(
        verdict = Verdict.FAUX,
        ease = Ease.AGAIN,
        spokenFeedback = "",
        formulationNote = null,
        topic = null,
        intention = intention,
    )

    private fun apres(intention: Intention, session: Session = Session()) =
        ReviewSessionEngine.reduce(
            session.copy(state = SessionState.Judging(enVol, "peu importe")),
            Event.Judged(juge(intention)),
            1_000L,
        )

    /**
     * Le coeur du probleme : une demande ne doit jamais produire de note. C'est ce qui
     * transformait « tu peux répéter » en carte ratee.
     */
    @Test
    fun `aucune demande ne laisse de note derriere elle`() {
        listOf(
            Intention.REPETER,
            Intention.PASSER,
            Intention.EXPLIQUER,
            Intention.ANNULER,
            Intention.ARRETER,
        ).forEach { intention ->
            val result = apres(intention)
            assertTrue(
                "$intention ne doit pas proposer de note",
                result.effects.filterIsInstance<Effect.Commit>().none {
                    it.pending.card.noteId == carte.noteId
                },
            )
        }
    }

    @Test
    fun `repeter repose la question sans rien noter`() {
        val result = apres(Intention.REPETER)
        assertTrue(result.session.state is SessionState.Asking)
        assertEquals(listOf(Effect.Speak("question orale")), result.effects)
    }

    @Test
    fun `repeter remet la patience a zero`() {
        val fatiguee = Session(retriedAnswer = true, askedToContinue = true)
        val result = apres(Intention.REPETER, fatiguee)
        assertEquals(false, result.session.retriedAnswer)
        assertEquals(false, result.session.askedToContinue)
    }

    @Test
    fun `passer compte la carte comme sautee et l'inscrit au journal`() {
        val result = apres(Intention.PASSER)
        assertEquals(1, result.session.stats.skipped)
        val trace = result.effects.filterIsInstance<Effect.Record>().single()
        assertEquals("passee a la voix", trace.entry.note)
        assertEquals(null, trace.entry.proposedEase)
    }

    @Test
    fun `expliquer demande l'explication au lieu de juger`() {
        val result = apres(Intention.EXPLIQUER)
        assertEquals(1, result.effects.filterIsInstance<Effect.Explain>().size)
    }

    @Test
    fun `arreter termine la session`() {
        assertTrue(apres(Intention.ARRETER).session.state is SessionState.Finished)
    }

    /**
     * L'invariant de vivacite de la boucle : une reduction qui n'emet aucun effet vide la
     * file et arrete la session en silence. Chaque intention doit donc rendre la parole
     * ou l'oreille.
     */
    @Test
    fun `aucune intention ne laisse la boucle sans effet`() {
        Intention.entries.filter { it != Intention.REPONSE }.forEach { intention ->
            val result = apres(intention)
            assertTrue(
                "$intention n'emet aucun effet : la session s'arreterait en silence",
                result.effects.isNotEmpty(),
            )
        }
    }

    @Test
    fun `une vraie reponse est jugee normalement`() {
        val result = ReviewSessionEngine.reduce(
            Session(state = SessionState.Judging(enVol, "ma reponse")),
            Event.Judged(
                Judgement(Verdict.CORRECT, Ease.GOOD, "Exact.", null, "theme", Intention.REPONSE),
            ),
            1_000L,
        )
        assertTrue(result.session.state is SessionState.SpeakingVerdict)
    }
}
