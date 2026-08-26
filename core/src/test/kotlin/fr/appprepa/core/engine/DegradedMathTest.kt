package fr.appprepa.core.engine

import fr.appprepa.core.model.ReviewCard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * En mode degrade, aucun LLM ne verbalise : tout ce qui part a la synthese vocale doit
 * avoir ete traduit, sinon la carte est prononcee « backslash cos ».
 */
class DegradedMathTest {

    private val carte = ReviewCard(
        noteId = 1,
        cardOrd = 0,
        deckName = "Maths",
        question = """Dérivée de \(\cos\) : \(\cos'(x)\)""",
        answer = """\(\cos'(x) = -\sin(x)\) et \(\frac{\pi}{2}\)""",
        buttonCount = 4,
        hasMedia = false,
    )

    private fun spoken(effects: List<Effect>) =
        effects.filterIsInstance<Effect.Speak>().joinToString(" ") { it.text }

    @Test
    fun `la question lue en degrade ne contient plus de LaTeX`() {
        val preparing = Session(state = SessionState.Preparing(carte))
        val result = ReviewSessionEngine.reduce(preparing, Event.TutorFailed("reseau"), 1_000L)

        val text = spoken(result.effects)
        assertFalse("LaTeX restant : $text", text.contains("\\"))
        assertTrue(text.contains("cosinus"))
    }

    @Test
    fun `le verso lu en degrade ne contient plus de LaTeX`() {
        val inFlight = CardInFlight(carte, "question", emptyList(), 1_000L)
        val judging = Session(state = SessionState.Judging(inFlight, "ma reponse"))
        val result = ReviewSessionEngine.reduce(judging, Event.TutorFailed("reseau"), 2_000L)

        val text = spoken(result.effects)
        assertFalse("LaTeX restant : $text", text.contains("\\"))
        assertTrue(text.contains("sinus"))
        assertTrue(text.contains("sur"))
    }

    @Test
    fun `la carte suivante en degrade est aussi verbalisee`() {
        val inFlight = CardInFlight(carte, "question", emptyList(), 1_000L)
        val session = Session(
            state = SessionState.AwaitingCorrection(inFlight, Assessment.SelfGrade("verso")),
            degraded = true,
            queue = listOf(carte.copy(noteId = 2)),
        )
        val result = ReviewSessionEngine.reduce(session, Event.Heard("facile"), 3_000L)

        val text = spoken(result.effects)
        assertFalse("LaTeX restant : $text", text.contains("\\"))
    }

    @Test
    fun `l'abandon apres silence lit un verso verbalise`() {
        val inFlight = CardInFlight(carte, "question", emptyList(), 1_000L)
        val session = Session(
            state = SessionState.Listening(inFlight),
            retriedAnswer = true,
        )
        val result = ReviewSessionEngine.reduce(session, Event.HeardNothing, 2_000L)

        val text = spoken(result.effects)
        assertFalse("LaTeX restant : $text", text.contains("\\"))
    }
}
