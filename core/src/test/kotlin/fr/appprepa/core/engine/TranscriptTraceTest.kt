package fr.appprepa.core.engine

import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.Judgement
import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.model.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Le journal du soir sert a comparer ce que l'utilisateur a dit et ce que le modele en a
 * fait. Sans la reponse transcrite, il ne sert a rien.
 */
class TranscriptTraceTest {

    private fun card(id: Long) = ReviewCard(id, 0, "Prepa", "recto $id", "verso $id", 4, false)

    private fun inFlight(id: Long) =
        CardInFlight(card(id), "question orale $id", listOf("point $id"), 1_000L)

    private fun judgement() =
        Judgement(Verdict.CORRECT, Ease.GOOD, "retour", emptyList(), null, "theme")

    @Test
    fun `la reponse parlee est portee jusqu'au journal`() {
        val listening = Session(state = SessionState.Listening(inFlight(1)))

        val judging = ReviewSessionEngine.reduce(
            listening,
            Event.Heard("le theoreme s'applique sur un intervalle ferme"),
            2_000L,
        )
        val verdict = ReviewSessionEngine.reduce(judging.session, Event.Judged(judgement()), 3_000L)
        val awaiting = ReviewSessionEngine.reduce(verdict.session, Event.SpeechFinished, 4_000L)
        val settled = ReviewSessionEngine.reduce(awaiting.session, Event.HeardNothing, 5_000L)

        val record = settled.effects.filterIsInstance<Effect.Record>().first().entry
        assertEquals(
            "le transcript doit arriver intact dans le journal",
            "le theoreme s'applique sur un intervalle ferme",
            record.transcript,
        )
    }

    @Test
    fun `le mode degrade journalise aussi la reponse`() {
        val listening = Session(state = SessionState.Listening(inFlight(1)), degraded = true)

        val selfGrade = ReviewSessionEngine.reduce(listening, Event.Heard("ma reponse orale"), 2_000L)
        val awaiting = ReviewSessionEngine.reduce(selfGrade.session, Event.SpeechFinished, 3_000L)
        val settled = ReviewSessionEngine.reduce(awaiting.session, Event.Heard("facile"), 4_000L)

        val record = settled.effects.filterIsInstance<Effect.Record>().first().entry
        assertEquals("ma reponse orale", record.transcript)
    }

    @Test
    fun `une carte passee a la voix ne porte pas de transcript de reponse`() {
        val listening = Session(
            state = SessionState.Listening(inFlight(1)),
            queue = listOf(card(2)),
        )
        val skipped = ReviewSessionEngine.reduce(listening, Event.Heard("passe"), 2_000L)
        val record = skipped.effects.filterIsInstance<Effect.Record>().first().entry
        assertEquals("", record.transcript)
    }
}
