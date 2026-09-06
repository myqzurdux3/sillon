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

    /**
     * Le cas que la fenetre d'ecoute ne peut pas couvrir : une pause de plus de deux
     * secondes et demie tranche la phrase, et il ne reste qu'un debut. Le juger
     * reviendrait a noter faux quelqu'un qui reflechissait.
     */
    @Test
    fun `une phrase tronquee est relancee, pas jugee`() {
        val result = apres(Intention.INCOMPLET)
        assertTrue(
            "une phrase coupee ne doit pas partir au jugement",
            result.effects.none { it is Effect.Judge },
        )
        val etat = result.session.state as SessionState.Listening
        assertEquals("le debut de reponse doit etre garde", "peu importe", etat.partial)
        assertTrue(result.session.askedToContinue)
        assertEquals(listOf(Effect.Speak("Continue, je t'écoute.")), result.effects)
    }

    /** Une seule relance par carte : au-dela, insister vaut moins que juger ce qu'on a. */
    @Test
    fun `une deuxieme troncature est jugee plutot que relancee`() {
        val result = apres(Intention.INCOMPLET, Session(askedToContinue = true))
        val juge = result.effects.filterIsInstance<Effect.Judge>().single()
        assertEquals("peu importe", juge.transcript)
    }

    /**
     * Mesure contre le modele avant correction : « attends deux secondes » etait lu comme
     * `PASSER`. Demander un instant au volant est la chose la plus naturelle du monde, et
     * ca lui coutait la carte.
     */
    @Test
    fun `attendre rouvre l'oreille sans juger ni noter`() {
        val result = apres(Intention.ATTENDRE)
        assertTrue(result.session.state is SessionState.Listening)
        assertTrue("attendre ne doit rien juger", result.effects.none { it is Effect.Judge })
        assertTrue("attendre ne doit rien noter", result.effects.none { it is Effect.Commit })
        assertEquals(0, result.session.stats.skipped)
        assertEquals(listOf(Effect.Speak("D'accord, prends ton temps.")), result.effects)
    }

    /**
     * Une action fausse coute une carte ou une note sans que l'utilisateur sache pourquoi ;
     * un aveu d'incomprehension ne coute qu'une seconde.
     */
    @Test
    fun `une demande hors vocabulaire est avouee, pas devinee`() {
        val result = apres(Intention.INCONNU)
        assertTrue(result.session.state is SessionState.Listening)
        assertTrue(result.effects.none { it is Effect.Judge || it is Effect.Commit })
        assertEquals(0, result.session.stats.skipped)
        assertEquals(listOf(Effect.Speak("Je n'ai pas compris. Tu peux redire ?")), result.effects)
    }

    /**
     * « mets très dur à la précédente au lieu de facile » : la note est dans la phrase,
     * la redemander serait faire repeter l'utilisateur au volant.
     */
    @Test
    fun `une note dictee pour la carte precedente s'applique sans redemander`() {
        val precedente = PendingAnswer(
            card = carte.copy(noteId = 9),
            ease = Ease.EASY,
            timeTakenMs = 1_000L,
            record = fr.appprepa.core.model.JournalRecord(
                atMs = 0, noteId = 9, cardOrd = 0, deckName = "Info", question = "q",
                transcript = "t", proposedEase = Ease.EASY, committedEase = null,
                verdict = null, mode = fr.appprepa.core.model.WriteMode.JOURNAL_ONLY,
            ),
        )
        val session = Session(pending = listOf(precedente))
        val result = ReviewSessionEngine.reduce(
            session.copy(state = SessionState.Judging(enVol, "peu importe")),
            Event.Judged(juge(Intention.REVENIR).copy(easeVoulue = Ease.HARD)),
            1_000L,
        )

        assertEquals(Ease.HARD, result.session.pending.single().ease)
        assertTrue(
            "la question en cours doit reprendre, pas une parenthese",
            result.session.state is SessionState.Asking,
        )
        assertEquals(
            listOf(Effect.Speak("C'est noté, je passe la précédente en difficile.")),
            result.effects,
        )
    }

    /** Sans note dictee, on rouvre la parenthese et on demande : on n'invente pas. */
    @Test
    fun `revenir sans note dictee redemande`() {
        val precedente = PendingAnswer(
            card = carte.copy(noteId = 9),
            ease = Ease.EASY,
            timeTakenMs = 1_000L,
            record = fr.appprepa.core.model.JournalRecord(
                atMs = 0, noteId = 9, cardOrd = 0, deckName = "Info", question = "q",
                transcript = "t", proposedEase = Ease.EASY, committedEase = null,
                verdict = null, mode = fr.appprepa.core.model.WriteMode.JOURNAL_ONLY,
            ),
        )
        val result = ReviewSessionEngine.reduce(
            Session(pending = listOf(precedente), state = SessionState.Judging(enVol, "x")),
            Event.Judged(juge(Intention.REVENIR)),
            1_000L,
        )
        assertTrue(result.session.state is SessionState.Revisiting)
        assertEquals(Ease.EASY, result.session.pending.single().ease)
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
