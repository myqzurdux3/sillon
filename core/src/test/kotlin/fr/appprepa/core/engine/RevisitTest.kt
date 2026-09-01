package fr.appprepa.core.engine

import fr.appprepa.core.ports.ListenKind
import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.Judgement
import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.model.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Revenir sur la carte precedente : la renoter, ou se la faire expliquer, sans perdre
 * la carte en cours. C'est la latence d'un tour a l'ecriture qui rend cela possible.
 */
class RevisitTest {

    private fun card(id: Long) = ReviewCard(id, 0, "Prepa", "recto $id", "verso $id", 4, false)

    private fun inFlight(id: Long) =
        CardInFlight(card(id), "question orale $id", listOf("point $id"), 1_000L)

    private fun judgement(verdict: Verdict = Verdict.CORRECT) =
        Judgement(verdict, Ease.fromVerdict(verdict), "retour", null, "theme")

    /** Session sur la carte 2, la note de la carte 1 encore en attente d'ecriture. */
    private fun enCours(state: SessionState) = Session(
        state = state,
        pending = listOf(PendingAnswerFixtures.pending(card(1), Ease.GOOD)),
        queue = listOf(card(3)),
    )

    private fun spoken(r: Reduction) =
        r.effects.filterIsInstance<Effect.Speak>().joinToString(" ") { it.text }

    // --- ouverture de la parenthese ---------------------------------------

    @Test
    fun `reviens pendant la reponse ouvre la parenthese`() {
        val session = enCours(SessionState.Listening(inFlight(2)))
        val result = ReviewSessionEngine.reduce(session, Event.Heard("reviens"), 2_000L)

        val state = result.session.state as SessionState.Revisiting
        assertEquals(1L, state.target.card.noteId)
        assertEquals("la carte en cours doit etre gardee", 2L, state.inFlight.card.noteId)
        assertTrue("la question precedente doit etre rappelee", spoken(result).contains("recto 1"))
    }

    @Test
    fun `reviens pendant la correction ouvre aussi la parenthese`() {
        val session = enCours(
            SessionState.AwaitingCorrection(inFlight(2), Assessment.Judged(judgement())),
        )
        val result = ReviewSessionEngine.reduce(session, Event.Heard("la precedente"), 2_000L)
        assertTrue(result.session.state is SessionState.Revisiting)
    }

    @Test
    fun `sans carte precedente le retour est refuse poliment`() {
        val session = Session(state = SessionState.Listening(inFlight(2)))
        val result = ReviewSessionEngine.reduce(session, Event.Heard("reviens"), 2_000L)

        assertTrue(result.session.state is SessionState.Listening)
        assertTrue(spoken(result).lowercase().contains("pas de carte"))
    }

    // --- ce qu'on fait dans la parenthese ---------------------------------

    @Test
    fun `une note dictee dans la parenthese remplace celle de la carte precedente`() {
        val session = enCours(SessionState.Listening(inFlight(2)))
        val ouverte = ReviewSessionEngine.reduce(session, Event.Heard("reviens"), 2_000L)
        val parlee = ReviewSessionEngine.reduce(ouverte.session, Event.SpeechFinished, 3_000L)
        val renote = ReviewSessionEngine.reduce(parlee.session, Event.Heard("a revoir"), 4_000L)

        assertEquals(
            "la note de la carte 1 doit avoir change",
            Ease.AGAIN,
            renote.session.pending.single().ease,
        )
    }

    @Test
    fun `la parenthese refermee reenonce la question en cours`() {
        val session = enCours(SessionState.Listening(inFlight(2)))
        val ouverte = ReviewSessionEngine.reduce(session, Event.Heard("reviens"), 2_000L)
        val parlee = ReviewSessionEngine.reduce(ouverte.session, Event.SpeechFinished, 3_000L)
        val renote = ReviewSessionEngine.reduce(parlee.session, Event.Heard("facile"), 4_000L)

        val state = renote.session.state as SessionState.Asking
        assertEquals(2L, state.inFlight.card.noteId)
        assertTrue(spoken(renote).contains("question orale 2"))
    }

    @Test
    fun `explique dans la parenthese demande l'explication de la precedente`() {
        val session = enCours(SessionState.Listening(inFlight(2)))
        val ouverte = ReviewSessionEngine.reduce(session, Event.Heard("reviens"), 2_000L)
        val parlee = ReviewSessionEngine.reduce(ouverte.session, Event.SpeechFinished, 3_000L)
        val demande = ReviewSessionEngine.reduce(parlee.session, Event.Heard("explique"), 4_000L)

        assertEquals(listOf(Effect.Explain(card(1))), demande.effects)
    }

    @Test
    fun `explique la precedente ouvre la parenthese et demande directement`() {
        val session = enCours(SessionState.Listening(inFlight(2)))
        val result = ReviewSessionEngine.reduce(
            session,
            Event.Heard("explique la precedente"),
            2_000L,
        )
        assertTrue(result.effects.contains(Effect.Explain(card(1))))
        assertTrue(result.session.state is SessionState.Revisiting)
    }

    @Test
    fun `l'explication recue est enoncee puis la question en cours reprend`() {
        val session = enCours(SessionState.Listening(inFlight(2)))
        val ouverte = ReviewSessionEngine.reduce(
            session,
            Event.Heard("explique la precedente"),
            2_000L,
        )
        val explique = ReviewSessionEngine.reduce(
            ouverte.session,
            Event.Explained("voici pourquoi"),
            3_000L,
        )
        assertTrue(spoken(explique).contains("voici pourquoi"))

        val reprise = ReviewSessionEngine.reduce(explique.session, Event.SpeechFinished, 4_000L)
        val state = reprise.session.state as SessionState.Asking
        assertEquals(2L, state.inFlight.card.noteId)
    }

    @Test
    fun `un silence dans la parenthese reprend sans rien changer`() {
        val session = enCours(SessionState.Listening(inFlight(2)))
        val ouverte = ReviewSessionEngine.reduce(session, Event.Heard("reviens"), 2_000L)
        val parlee = ReviewSessionEngine.reduce(ouverte.session, Event.SpeechFinished, 3_000L)
        val rien = ReviewSessionEngine.reduce(parlee.session, Event.HeardNothing, 4_000L)

        assertEquals(Ease.GOOD, rien.session.pending.single().ease)
        assertTrue(rien.session.state is SessionState.Asking)
    }

    @Test
    fun `annule dans la parenthese jette la note de la precedente`() {
        val session = enCours(SessionState.Listening(inFlight(2)))
        val ouverte = ReviewSessionEngine.reduce(session, Event.Heard("reviens"), 2_000L)
        val parlee = ReviewSessionEngine.reduce(ouverte.session, Event.SpeechFinished, 3_000L)
        val annule = ReviewSessionEngine.reduce(parlee.session, Event.Heard("annule"), 4_000L)

        assertTrue("plus rien en attente", annule.session.pending.isEmpty())
        assertTrue(
            annule.effects.filterIsInstance<Effect.Record>()
                .any { it.entry.noteId == 1L && it.entry.committedEase == null },
        )
    }

    // --- la file bornee ----------------------------------------------------

    @Test
    fun `la file d'attente ne garde qu'une carte par defaut`() {
        val awaiting = Session(
            state = SessionState.AwaitingCorrection(inFlight(2), Assessment.Judged(judgement())),
            pending = listOf(PendingAnswerFixtures.pending(card(1), Ease.GOOD)),
            queue = listOf(card(3)),
            prefetch = fr.appprepa.core.model.ReformulatedQuestion("question orale 3", emptyList()),
            prefetchFor = 3L,
        )
        val result = ReviewSessionEngine.reduce(awaiting, Event.HeardNothing, 5_000L)

        assertEquals("la plus ancienne part a l'ecriture", 1, result.session.pending.size)
        assertEquals(2L, result.session.pending.single().card.noteId)
        assertEquals(1L, result.effects.filterIsInstance<Effect.Commit>().single().pending.card.noteId)
    }

    @Test
    fun `la fin de session ecrit tout ce qui reste en attente`() {
        val awaiting = Session(
            state = SessionState.AwaitingCorrection(inFlight(2), Assessment.Judged(judgement())),
            pending = listOf(PendingAnswerFixtures.pending(card(1), Ease.GOOD)),
            queue = emptyList(),
        )
        val result = ReviewSessionEngine.reduce(awaiting, Event.HeardNothing, 5_000L)

        assertTrue(result.session.state is SessionState.Finished)
        assertEquals(
            "les deux cartes doivent etre ecrites",
            listOf(1L, 2L),
            result.effects.filterIsInstance<Effect.Commit>().map { it.pending.card.noteId },
        )
        assertTrue(result.session.pending.isEmpty())
    }

    @Test
    fun `la fenetre de correction laisse le temps de reagir au volant`() {
        assertTrue(
            "3 secondes, c'est trop court en conduisant",
            ReviewSessionEngine.CORRECTION_TIMEOUT_MS >= 6_000L,
        )
        assertTrue(
            ReviewSessionEngine.SELF_GRADE_TIMEOUT_MS >= ReviewSessionEngine.CORRECTION_TIMEOUT_MS,
        )
    }

    @Test
    fun `la parenthese laisse une fenetre encore plus longue`() {
        val session = enCours(SessionState.Listening(inFlight(2)))
        val ouverte = ReviewSessionEngine.reduce(session, Event.Heard("reviens"), 2_000L)
        val parlee = ReviewSessionEngine.reduce(ouverte.session, Event.SpeechFinished, 3_000L)

        val listen = parlee.effects.filterIsInstance<Effect.Listen>().single()
        assertEquals(ListenKind.CORRECTION, listen.kind)
        assertTrue(listen.timeoutMs >= ReviewSessionEngine.CORRECTION_TIMEOUT_MS)
    }

    @Test
    fun `revenir pendant la reponse abandonne la reponse en cours`() {
        val session = enCours(SessionState.Listening(inFlight(2)))
        val result = ReviewSessionEngine.reduce(session, Event.Heard("reviens"), 2_000L)
        assertNull(
            "aucun jugement ne doit partir sur la reponse abandonnee",
            result.effects.filterIsInstance<Effect.Judge>().firstOrNull(),
        )
    }
}
