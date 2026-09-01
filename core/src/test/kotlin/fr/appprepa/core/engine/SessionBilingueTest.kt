package fr.appprepa.core.engine

import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.Langue
import fr.appprepa.core.model.ReformulatedQuestion
import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.ports.ListenKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Une session melange des cartes francaises et anglaises. Ce qui doit suivre la carte :
 * la voix qui pose la question, et surtout le micro qui ecoute la reponse. Une reponse
 * anglaise transcrite par un moteur regle sur le francais n'est pas une erreur visible,
 * c'est une note fausse de plus.
 */
class SessionBilingueTest {

    private fun carte(id: Long, langue: Langue) = ReviewCard(
        noteId = id,
        cardOrd = 0,
        deckName = if (langue == Langue.ANGLAIS) "Anglais" else "Prepa",
        question = "recto $id",
        answer = "verso $id",
        buttonCount = 4,
        hasMedia = false,
        langue = langue,
    )

    private fun enVol(langue: Langue) = CardInFlight(
        card = carte(1, langue),
        question = "question orale",
        expectedPoints = listOf("point"),
        askedAtMs = 0L,
    )

    @Test
    fun `la question d'une carte anglaise est enoncee en anglais`() {
        val session = Session(state = SessionState.Preparing(carte(1, Langue.ANGLAIS)))
        val result = ReviewSessionEngine.reduce(
            session,
            Event.Reformulated(carte(1, Langue.ANGLAIS), ReformulatedQuestion("What is X?", emptyList())),
            0L,
        )
        assertEquals(
            listOf(Effect.Speak("What is X?", Langue.ANGLAIS)),
            result.effects,
        )
    }

    @Test
    fun `le micro ecoute dans la langue de la carte`() {
        val session = Session(state = SessionState.Asking(enVol(Langue.ANGLAIS)))
        val result = ReviewSessionEngine.reduce(session, Event.SpeechFinished, 0L)
        val ecoute = result.effects.filterIsInstance<Effect.Listen>().single()
        assertEquals(ListenKind.ANSWER, ecoute.kind)
        assertEquals(Langue.ANGLAIS, ecoute.langue)
    }

    @Test
    fun `une commande anglaise est reconnue pendant la reponse`() {
        val session = Session(state = SessionState.Listening(enVol(Langue.ANGLAIS)))
        val result = ReviewSessionEngine.reduce(session, Event.Heard("skip"), 0L)
        assertTrue(
            "« skip » doit passer la carte, pas partir au jugement",
            result.effects.none { it is Effect.Judge },
        )
        assertEquals(1, result.session.stats.skipped)
    }

    /**
     * Le pendant du test precedent : la meme commande, dite en francais sur une carte
     * francaise. Les deux vocabulaires doivent coexister sans se marcher dessus.
     */
    @Test
    fun `une commande francaise reste reconnue sur une carte francaise`() {
        val session = Session(state = SessionState.Listening(enVol(Langue.FRANCAIS)))
        val result = ReviewSessionEngine.reduce(session, Event.Heard("passe"), 0L)
        assertEquals(1, result.session.stats.skipped)
    }

    @Test
    fun `la relance suit la langue de la carte`() {
        val session = Session(state = SessionState.Listening(enVol(Langue.ANGLAIS)))
        val result = ReviewSessionEngine.reduce(
            session,
            Event.Heard("the mitochondria produces energy because"),
            0L,
        )
        val dit = result.effects.filterIsInstance<Effect.Speak>().single()
        assertEquals(Langue.ANGLAIS, dit.langue)
        assertEquals("Go on, I'm listening.", dit.text)
    }

    // --- langue de la correction -----------------------------------------

    @Test
    fun `par defaut la correction est en francais, meme sur une carte anglaise`() {
        val session = Session(
            state = SessionState.Judging(enVol(Langue.ANGLAIS), "an answer"),
            correctionEnFrancais = true,
        )
        val result = ReviewSessionEngine.reduce(
            session,
            Event.Judged(judgementCorrect()),
            0L,
        )
        val dit = result.effects.filterIsInstance<Effect.Speak>().single()
        assertEquals(Langue.FRANCAIS, dit.langue)
        assertTrue(dit.text, dit.text.endsWith("Je mets bien."))
    }

    @Test
    fun `reglee sur la carte, la correction passe en anglais`() {
        val session = Session(
            state = SessionState.Judging(enVol(Langue.ANGLAIS), "an answer"),
            correctionEnFrancais = false,
        )
        val result = ReviewSessionEngine.reduce(session, Event.Judged(judgementCorrect()), 0L)
        val dit = result.effects.filterIsInstance<Effect.Speak>().single()
        assertEquals(Langue.ANGLAIS, dit.langue)
        assertTrue(dit.text, dit.text.endsWith("I'll mark it good."))
    }

    @Test
    fun `la fenetre de correction ecoute dans la langue du verdict`() {
        val francais = Session(
            state = SessionState.SpeakingVerdict(
                enVol(Langue.ANGLAIS),
                Assessment.Judged(judgementCorrect()),
                "an answer",
            ),
            correctionEnFrancais = true,
        )
        val ecoute = ReviewSessionEngine.reduce(francais, Event.SpeechFinished, 0L)
            .effects.filterIsInstance<Effect.Listen>().single()
        assertEquals(Langue.FRANCAIS, ecoute.langue)

        val anglais = francais.copy(correctionEnFrancais = false)
        val ecouteEn = ReviewSessionEngine.reduce(anglais, Event.SpeechFinished, 0L)
            .effects.filterIsInstance<Effect.Listen>().single()
        assertEquals(Langue.ANGLAIS, ecouteEn.langue)
    }

    @Test
    fun `la note se dicte dans la langue du verdict`() {
        val session = Session(
            state = SessionState.AwaitingCorrection(
                enVol(Langue.ANGLAIS),
                Assessment.Judged(judgementCorrect()),
                "an answer",
            ),
            correctionEnFrancais = false,
        )
        val result = ReviewSessionEngine.reduce(session, Event.Heard("hard"), 0L)
        val commit = result.effects.filterIsInstance<Effect.Commit>().single()
        assertEquals(Ease.HARD, commit.pending.ease)
    }

    /**
     * Le cas ou la langue de la correction ne peut pas s'appliquer : l'enonce recite le
     * verso de la carte. Une voix francaise lisant un verso anglais est inintelligible,
     * donc la carte l'emporte sur le reglage.
     */
    @Test
    fun `l'enonce qui recite le verso suit la carte, pas le reglage`() {
        val session = Session(
            state = SessionState.Listening(enVol(Langue.ANGLAIS)),
            retriedAnswer = true,
            correctionEnFrancais = true,
        )
        val result = ReviewSessionEngine.reduce(session, Event.HeardNothing, 0L)
        val dit = result.effects.filterIsInstance<Effect.Speak>().single()
        assertEquals(Langue.ANGLAIS, dit.langue)
        assertTrue(dit.text, dit.text.startsWith("No answer."))
    }

    @Test
    fun `le mode degrade recite le verso dans la langue de la carte`() {
        val session = Session(
            state = SessionState.Judging(enVol(Langue.ANGLAIS), "an answer"),
            correctionEnFrancais = true,
        )
        val result = ReviewSessionEngine.reduce(session, Event.TutorFailed("panne"), 0L)
        val dit = result.effects.filterIsInstance<Effect.Speak>().single()
        assertEquals(Langue.ANGLAIS, dit.langue)
        assertTrue(dit.text, dit.text.contains("again, hard, good or easy"))
    }

    private fun judgementCorrect() = fr.appprepa.core.model.Judgement(
        verdict = fr.appprepa.core.model.Verdict.CORRECT,
        ease = Ease.GOOD,
        spokenFeedback = "Exact.",
        formulationNote = null,
        topic = "theme",
    )
}
