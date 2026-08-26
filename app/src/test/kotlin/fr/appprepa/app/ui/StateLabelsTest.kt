package fr.appprepa.app.ui

import fr.appprepa.core.engine.Assessment
import fr.appprepa.core.engine.CardInFlight
import fr.appprepa.core.engine.SessionState
import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.Judgement
import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.model.SessionStats
import fr.appprepa.core.model.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StateLabelsTest {

    private val inFlight = CardInFlight(
        ReviewCard(1, 0, "Prepa", "recto", "verso", 4, false),
        "question",
        emptyList(),
        0L,
    )

    @Test
    fun `chaque etat a un libelle court et non vide`() {
        val states = listOf(
            SessionState.Idle,
            SessionState.Loading,
            SessionState.Preparing(inFlight.card),
            SessionState.Asking(inFlight),
            SessionState.Listening(inFlight),
            SessionState.Judging(inFlight, "reponse"),
            SessionState.SpeakingVerdict(
                inFlight,
                Assessment.Judged(
                    Judgement(Verdict.CORRECT, Ease.GOOD, "ok", emptyList(), null, null),
                ),
            ),
            SessionState.AwaitingCorrection(inFlight, Assessment.SelfGrade("verso")),
            SessionState.Revisiting(
                inFlight,
                fr.appprepa.core.engine.PendingAnswer(
                    card = inFlight.card,
                    ease = Ease.GOOD,
                    timeTakenMs = 0L,
                    record = fr.appprepa.core.model.JournalRecord(
                        atMs = 0L,
                        noteId = 1,
                        cardOrd = 0,
                        deckName = "Prepa",
                        question = "q",
                        transcript = "",
                        proposedEase = Ease.GOOD,
                        committedEase = null,
                        verdict = Verdict.CORRECT,
                        mode = fr.appprepa.core.model.WriteMode.JOURNAL_ONLY,
                    ),
                ),
            ),
            SessionState.Finished(SessionStats(answered = 2, correct = 1)),
            SessionState.Failed("panne"),
        )

        states.forEach { state ->
            val label = StateLabels.of(state)
            assertTrue("libelle vide pour $state", label.isNotBlank())
            assertTrue("libelle trop long pour $state : $label", label.length <= 40)
        }
    }

    @Test
    fun `l'ecoute est annoncee explicitement`() {
        assertEquals("Je t'écoute", StateLabels.of(SessionState.Listening(inFlight)))
    }

    @Test
    fun `la fin affiche le decompte`() {
        val label = StateLabels.of(SessionState.Finished(SessionStats(answered = 5, correct = 4)))
        assertTrue(label.contains("5"))
        assertTrue(label.contains("4"))
    }
}
