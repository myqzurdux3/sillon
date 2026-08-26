package fr.appprepa.core.engine

import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.Judgement
import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.model.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandsAndDegradedTest {

    private fun card(id: Long) = ReviewCard(id, 0, "Prepa", "recto $id", "verso $id", 4, false)

    private fun inFlight(id: Long) =
        CardInFlight(card(id), "question orale $id", listOf("point $id"), 1_000L)

    private fun judgement(verdict: Verdict = Verdict.CORRECT) = Judgement(
        verdict, Ease.fromVerdict(verdict), "retour", emptyList(), null, "theme",
    )

    private fun listening(id: Long, queue: List<ReviewCard> = emptyList()) =
        Session(state = SessionState.Listening(inFlight(id)), queue = queue)

    private fun awaiting(id: Long, queue: List<ReviewCard> = emptyList()) = Session(
        state = SessionState.AwaitingCorrection(inFlight(id), Assessment.Judged(judgement())),
        queue = queue,
    )

    @Test
    fun `repete reenonce la question sans consommer la carte`() {
        val result = ReviewSessionEngine.reduce(listening(1), Event.Heard("repete"), 2_000L)
        assertTrue(result.session.state is SessionState.Asking)
        assertEquals(listOf(Effect.Speak("question orale 1")), result.effects)
    }

    @Test
    fun `passe saute la carte sans la noter`() {
        val result = ReviewSessionEngine.reduce(
            listening(1, queue = listOf(card(2))),
            Event.Heard("passe"),
            2_000L,
        )
        assertNull("une carte passee ne doit rien mettre en attente", result.session.pending)
        assertEquals(1, result.session.stats.skipped)
        assertTrue(result.effects.any { it is Effect.Record })
    }

    @Test
    fun `explique demande une explication au LLM`() {
        val result = ReviewSessionEngine.reduce(listening(1), Event.Heard("je seche"), 2_000L)
        assertEquals(listOf(Effect.Explain(card(1))), result.effects)
    }

    @Test
    fun `l'explication recue est enoncee puis la carte est notee a revoir`() {
        val explaining = Session(state = SessionState.Judging(inFlight(1), "je seche"))
        val result = ReviewSessionEngine.reduce(
            explaining,
            Event.Explained("voici l'explication"),
            3_000L,
        )
        val state = result.session.state as SessionState.SpeakingVerdict
        val assessment = state.assessment as Assessment.Judged
        assertEquals(Ease.AGAIN, assessment.judgement.ease)
        assertTrue(
            result.effects.filterIsInstance<Effect.Speak>().single().text
                .contains("voici l'explication"),
        )
    }

    @Test
    fun `stop pendant l'ecoute termine la session`() {
        val result = ReviewSessionEngine.reduce(listening(1), Event.Heard("stop"), 2_000L)
        assertTrue(result.session.state is SessionState.Finished)
        assertTrue(result.effects.contains(Effect.Finish))
    }

    @Test
    fun `une correction dictee remplace la note proposee`() {
        val result = ReviewSessionEngine.reduce(awaiting(1), Event.Heard("a revoir"), 4_000L)
        assertEquals(Ease.AGAIN, result.effects.filterIsInstance<Effect.Commit>().single().pending.ease)
    }

    @Test
    fun `annule supprime la note en attente de la carte precedente`() {
        val previous = PendingAnswerFixtures.pending(card(1), Ease.EASY)
        val session = awaiting(2, queue = listOf(card(3))).copy(pending = previous)
        val result = ReviewSessionEngine.reduce(session, Event.Heard("annule"), 5_000L)

        assertTrue(
            "la carte annulee ne doit jamais etre ecrite",
            result.effects.filterIsInstance<Effect.Commit>().none { it.pending.card.noteId == 1L },
        )
        assertEquals("la carte courante prend la place", 2L, result.session.pending?.card?.noteId)
        assertTrue(
            result.effects.filterIsInstance<Effect.Record>()
                .any { it.entry.noteId == 1L && it.entry.committedEase == null },
        )
    }

    @Test
    fun `une reponse ordinaire pendant la correction vaut acceptation`() {
        val result = ReviewSessionEngine.reduce(
            awaiting(1),
            Event.Heard("oui enfin je crois que c'etait ca"),
            4_000L,
        )
        assertEquals(
            Ease.GOOD,
            result.effects.filterIsInstance<Effect.Commit>().single().pending.ease,
        )
    }

    @Test
    fun `un silence unique relance l'ecoute une fois`() {
        val result = ReviewSessionEngine.reduce(listening(1), Event.HeardNothing, 2_000L)
        assertTrue(result.session.retriedAnswer)
        assertTrue(result.session.state is SessionState.Asking)
        assertEquals(listOf(Effect.Speak("Je t'écoute.")), result.effects)
    }

    @Test
    fun `un second silence note la carte a revoir`() {
        val session = listening(1).copy(retriedAnswer = true)
        val result = ReviewSessionEngine.reduce(session, Event.HeardNothing, 2_000L)
        val state = result.session.state as SessionState.SpeakingVerdict
        val assessment = state.assessment as Assessment.Judged
        assertEquals(Ease.AGAIN, assessment.judgement.ease)
    }

    @Test
    fun `l'echec du LLM bascule en mode degrade et lit le verso`() {
        val judging = Session(state = SessionState.Judging(inFlight(1), "ma reponse"))
        val result = ReviewSessionEngine.reduce(judging, Event.TutorFailed("timeout"), 3_000L)

        assertTrue("la session doit passer en degrade", result.session.degraded)
        val state = result.session.state as SessionState.SpeakingVerdict
        assertTrue(state.assessment is Assessment.SelfGrade)
        val spoken = result.effects.filterIsInstance<Effect.Speak>().single().text
        assertTrue(spoken.contains("verso 1"))
    }

    @Test
    fun `en mode degrade la fenetre de correction est longue`() {
        val session = Session(
            state = SessionState.SpeakingVerdict(inFlight(1), Assessment.SelfGrade("verso 1")),
            degraded = true,
        )
        val result = ReviewSessionEngine.reduce(session, Event.SpeechFinished, 4_000L)
        assertEquals(
            listOf(Effect.Listen(ListenKind.CORRECTION, ReviewSessionEngine.SELF_GRADE_TIMEOUT_MS)),
            result.effects,
        )
    }

    @Test
    fun `en mode degrade un silence vaut a revoir`() {
        val session = Session(
            state = SessionState.AwaitingCorrection(inFlight(1), Assessment.SelfGrade("verso 1")),
            degraded = true,
        )
        val result = ReviewSessionEngine.reduce(session, Event.HeardNothing, 5_000L)
        assertEquals(
            Ease.AGAIN,
            result.effects.filterIsInstance<Effect.Commit>().single().pending.ease,
        )
    }

    @Test
    fun `en mode degrade la carte suivante est lue telle quelle sans appel LLM`() {
        val session = Session(
            state = SessionState.AwaitingCorrection(inFlight(1), Assessment.SelfGrade("verso 1")),
            degraded = true,
            queue = listOf(card(2)),
        )
        val result = ReviewSessionEngine.reduce(session, Event.Heard("facile"), 5_000L)
        assertTrue(result.effects.none { it is Effect.Reformulate })
        assertTrue(result.effects.contains(Effect.Speak("recto 2")))
    }

    @Test
    fun `le mode degrade evite l'appel de jugement`() {
        val session = Session(state = SessionState.Listening(inFlight(1)), degraded = true)
        val result = ReviewSessionEngine.reduce(session, Event.Heard("ma reponse"), 2_000L)
        assertFalse("pas d'appel de jugement en degrade", result.effects.any { it is Effect.Judge })
        assertTrue(result.session.state is SessionState.SpeakingVerdict)
    }
}
