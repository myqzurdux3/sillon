package fr.appprepa.core.engine

import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.Judgement
import fr.appprepa.core.model.JournalRecord
import fr.appprepa.core.model.ReformulatedQuestion
import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.model.Verdict
import fr.appprepa.core.model.WriteMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NominalLoopTest {

    private fun card(id: Long, media: Boolean = false) = ReviewCard(
        noteId = id,
        cardOrd = 0,
        deckName = "Prepa",
        question = "recto $id",
        answer = "verso $id",
        buttonCount = 4,
        hasMedia = media,
    )

    private fun reformulated(id: Long) =
        ReformulatedQuestion("question orale $id", listOf("point $id"))

    private fun judgement(verdict: Verdict = Verdict.CORRECT) = Judgement(
        verdict = verdict,
        ease = Ease.fromVerdict(verdict),
        spokenFeedback = "retour parle",
        missed = emptyList(),
        formulationNote = null,
        topic = "theme",
    )

    @Test
    fun `demarrer demande le chargement des cartes`() {
        val result = ReviewSessionEngine.reduce(Session(), Event.Start(deckId = 7L, limit = 30), 0L)
        assertEquals(SessionState.Loading, result.session.state)
        assertEquals(listOf(Effect.LoadCards(7L, 30)), result.effects)
    }

    @Test
    fun `les cartes chargees declenchent la reformulation de la premiere`() {
        val loading = Session(state = SessionState.Loading)
        val result = ReviewSessionEngine.reduce(
            loading,
            Event.CardsLoaded(listOf(card(1), card(2))),
            0L,
        )
        assertEquals(SessionState.Preparing(card(1)), result.session.state)
        assertEquals(listOf(card(2)), result.session.queue)
        assertEquals(
            listOf(Effect.Reformulate(card(1), result.session.memory)),
            result.effects,
        )
    }

    @Test
    fun `une carte a media est ecartee et journalisee sans etre notee`() {
        val loading = Session(state = SessionState.Loading)
        val result = ReviewSessionEngine.reduce(
            loading,
            Event.CardsLoaded(listOf(card(1, media = true), card(2))),
            1_000L,
        )
        assertEquals(SessionState.Preparing(card(2)), result.session.state)
        val records = result.effects.filterIsInstance<Effect.Record>()
        assertEquals(1, records.size)
        assertEquals(1L, records.single().entry.noteId)
        assertEquals(null, records.single().entry.proposedEase)
        assertEquals(1, result.session.stats.skipped)
    }

    @Test
    fun `aucune carte due termine la session`() {
        val loading = Session(state = SessionState.Loading)
        val result = ReviewSessionEngine.reduce(loading, Event.CardsLoaded(emptyList()), 0L)
        assertTrue(result.session.state is SessionState.Finished)
        assertTrue(result.effects.any { it is Effect.Speak })
        assertTrue(result.effects.contains(Effect.Finish))
    }

    @Test
    fun `la reformulation recue fait enoncer la question`() {
        val preparing = Session(state = SessionState.Preparing(card(1)), queue = listOf(card(2)))
        val result = ReviewSessionEngine.reduce(
            preparing,
            Event.Reformulated(card(1), reformulated(1)),
            5_000L,
        )
        val state = result.session.state as SessionState.Asking
        assertEquals("question orale 1", state.inFlight.question)
        assertEquals(5_000L, state.inFlight.askedAtMs)
        assertEquals(listOf(Effect.Speak("question orale 1")), result.effects)
    }

    @Test
    fun `la fin de l'enonce ouvre l'ecoute et precharge la carte suivante`() {
        val inFlight = CardInFlight(card(1), "question orale 1", listOf("point 1"), 5_000L)
        val asking = Session(state = SessionState.Asking(inFlight), queue = listOf(card(2)))
        val result = ReviewSessionEngine.reduce(asking, Event.SpeechFinished, 6_000L)

        assertEquals(SessionState.Listening(inFlight), result.session.state)
        assertTrue(result.effects.contains(Effect.Listen(ListenKind.ANSWER, 15_000L)))
        assertTrue(
            "le prechargement doit partir des le debut de l'ecoute",
            result.effects.contains(Effect.Reformulate(card(2), result.session.memory)),
        )
    }

    @Test
    fun `ne precharge pas quand la file est vide`() {
        val inFlight = CardInFlight(card(1), "question orale 1", listOf("point 1"), 5_000L)
        val asking = Session(state = SessionState.Asking(inFlight), queue = emptyList())
        val result = ReviewSessionEngine.reduce(asking, Event.SpeechFinished, 6_000L)
        assertEquals(listOf(Effect.Listen(ListenKind.ANSWER, 15_000L)), result.effects)
    }

    @Test
    fun `une reponse entendue part au jugement`() {
        val inFlight = CardInFlight(card(1), "question orale 1", listOf("point 1"), 5_000L)
        val listening = Session(state = SessionState.Listening(inFlight))
        val result = ReviewSessionEngine.reduce(
            listening,
            Event.Heard("le theoreme s'applique sur un intervalle ferme"),
            9_000L,
        )
        assertTrue(result.session.state is SessionState.Judging)
        assertEquals(
            listOf(
                Effect.Judge(
                    card = card(1),
                    expectedPoints = listOf("point 1"),
                    transcript = "le theoreme s'applique sur un intervalle ferme",
                    memory = listening.memory,
                ),
            ),
            result.effects,
        )
    }

    @Test
    fun `le jugement recu est enonce avec la note proposee`() {
        val inFlight = CardInFlight(card(1), "question orale 1", listOf("point 1"), 5_000L)
        val judging = Session(state = SessionState.Judging(inFlight, "ma reponse"))
        val result = ReviewSessionEngine.reduce(judging, Event.Judged(judgement()), 10_000L)

        assertTrue(result.session.state is SessionState.SpeakingVerdict)
        val spoken = result.effects.filterIsInstance<Effect.Speak>().single().text
        assertTrue(spoken.contains("retour parle"))
        assertTrue("la note proposee doit etre annoncee", spoken.contains("bien"))
    }

    @Test
    fun `la fin du verdict ouvre la fenetre de correction`() {
        val inFlight = CardInFlight(card(1), "question orale 1", listOf("point 1"), 5_000L)
        val speaking = Session(
            state = SessionState.SpeakingVerdict(inFlight, Assessment.Judged(judgement())),
        )
        val result = ReviewSessionEngine.reduce(speaking, Event.SpeechFinished, 12_000L)
        assertTrue(result.session.state is SessionState.AwaitingCorrection)
        assertEquals(
            listOf(
                Effect.Listen(
                    ListenKind.CORRECTION,
                    ReviewSessionEngine.CORRECTION_TIMEOUT_MS,
                ),
            ),
            result.effects,
        )
    }

    @Test
    fun `le silence pendant la correction valide la note proposee`() {
        val inFlight = CardInFlight(card(1), "question orale 1", listOf("point 1"), 5_000L)
        val awaiting = Session(
            state = SessionState.AwaitingCorrection(inFlight, Assessment.Judged(judgement())),
            queue = emptyList(),
        )
        val result = ReviewSessionEngine.reduce(awaiting, Event.HeardNothing, 13_000L)

        assertEquals(1, result.session.memory.answered)
        assertEquals(1, result.session.memory.correct)
        assertTrue(
            "la derniere note doit etre ecrite a la fin",
            result.effects.filterIsInstance<Effect.Commit>().single().pending.ease == Ease.GOOD,
        )
    }

    @Test
    fun `l'ecriture de la carte precedente n'a lieu qu'a la carte suivante`() {
        val inFlight = CardInFlight(card(2), "question orale 2", listOf("point 2"), 20_000L)
        val previous = PendingAnswerFixtures.pending(card(1), Ease.GOOD)
        val awaiting = Session(
            state = SessionState.AwaitingCorrection(inFlight, Assessment.Judged(judgement())),
            pending = listOf(previous),
            queue = listOf(card(3)),
            prefetch = ReformulatedQuestion("question orale 3", emptyList()),
            prefetchFor = 3L,
        )
        val result = ReviewSessionEngine.reduce(awaiting, Event.HeardNothing, 25_000L)

        val commits = result.effects.filterIsInstance<Effect.Commit>()
        assertEquals("seule la carte 1 doit etre ecrite", 1, commits.size)
        assertEquals(1L, commits.single().pending.card.noteId)
        assertEquals("la carte 2 reste en attente", 2L, result.session.pending.single().card.noteId)
    }

    @Test
    fun `la file vide termine la session en ecrivant la derniere note`() {
        val inFlight = CardInFlight(card(1), "question orale 1", listOf("point 1"), 5_000L)
        val awaiting = Session(
            state = SessionState.AwaitingCorrection(inFlight, Assessment.Judged(judgement())),
            queue = emptyList(),
        )
        val afterAnswer = ReviewSessionEngine.reduce(awaiting, Event.HeardNothing, 13_000L)
        assertTrue(afterAnswer.session.state is SessionState.Finished)
        assertEquals(1, afterAnswer.effects.filterIsInstance<Effect.Commit>().size)
        assertTrue(afterAnswer.effects.contains(Effect.Finish))
    }

    private fun card3() = card(3)
}

/** Petit constructeur partage par les tests du moteur. */
object PendingAnswerFixtures {
    fun pending(card: ReviewCard, ease: Ease): PendingAnswer =
        PendingAnswer(
            card = card,
            ease = ease,
            timeTakenMs = 4_000L,
            record = JournalRecord(
                atMs = 0L,
                noteId = card.noteId,
                cardOrd = card.cardOrd,
                deckName = card.deckName,
                question = card.question,
                transcript = "",
                proposedEase = ease,
                committedEase = null,
                verdict = Verdict.CORRECT,
                mode = WriteMode.JOURNAL_ONLY,
            ),
        )
}
