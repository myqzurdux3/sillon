package fr.appprepa.core.engine

import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.Judgement
import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.model.Verdict
import fr.appprepa.core.model.WriteMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ce qui doit arriver apres avoir parle sans changer d'etat.
 *
 * Le moteur ne rend la main que par un effet : un enonce qui n'est suivi d'aucun effet
 * vide la file de la boucle, et la session s'arrete en silence au milieu du trajet.
 */
class ReprisesTest {

    private fun card(id: Long) = ReviewCard(id, 0, "Prepa", "recto $id", "verso $id", 4, false)

    private fun inFlight(id: Long) =
        CardInFlight(card(id), "question orale $id", listOf("point $id"), 1_000L)

    private fun judgement(verdict: Verdict = Verdict.CORRECT) =
        Judgement(verdict, Ease.fromVerdict(verdict), "retour", null, "theme")

    private fun listens(r: Reduction) = r.effects.filterIsInstance<Effect.Listen>()

    private fun spoken(r: Reduction) =
        r.effects.filterIsInstance<Effect.Speak>().joinToString(" ") { it.text }

    @Test
    fun `un refus de retour pendant la reponse rouvre l'ecoute`() {
        val refus = ReviewSessionEngine.reduce(
            Session(state = SessionState.Listening(inFlight(2))),
            Event.Heard("reviens"),
            2_000L,
        )
        val apres = ReviewSessionEngine.reduce(refus.session, Event.SpeechFinished, 3_000L)

        assertEquals(
            "sans ecoute rouverte la session s'arrete en silence",
            listOf(ListenKind.ANSWER),
            listens(apres).map { it.kind },
        )
        assertEquals(ReviewSessionEngine.ANSWER_TIMEOUT_MS, listens(apres).first().timeoutMs)
    }

    @Test
    fun `un refus de retour pendant la correction rouvre la fenetre de correction`() {
        val refus = ReviewSessionEngine.reduce(
            Session(
                state = SessionState.AwaitingCorrection(inFlight(2), Assessment.Judged(judgement())),
            ),
            Event.Heard("reviens"),
            2_000L,
        )
        val apres = ReviewSessionEngine.reduce(refus.session, Event.SpeechFinished, 3_000L)

        assertEquals(listOf(ListenKind.CORRECTION), listens(apres).map { it.kind })
    }

    @Test
    fun `repete pendant la correction redit le verdict sans valider la note`() {
        val session = Session(
            state = SessionState.AwaitingCorrection(
                inFlight(2),
                Assessment.Judged(judgement(Verdict.PARTIEL)),
            ),
        )
        val result = ReviewSessionEngine.reduce(session, Event.Heard("répète"), 2_000L)

        assertTrue("la note ne doit pas etre figee", result.effects.none { it is Effect.Commit })
        assertTrue(result.session.state is SessionState.AwaitingCorrection)
        assertTrue(spoken(result).contains("retour"))
    }

    @Test
    fun `annule pendant la reponse jette la note precedente et rouvre l'ecoute`() {
        val session = Session(
            state = SessionState.Listening(inFlight(2)),
            pending = listOf(PendingAnswerFixtures.pending(card(1), Ease.GOOD)),
        )
        val result = ReviewSessionEngine.reduce(session, Event.Heard("annule"), 2_000L)

        assertTrue("la note en attente doit disparaitre", result.session.pending.isEmpty())
        assertTrue("pas de jugement sur une commande", result.effects.none { it is Effect.Judge })
        val trace = result.effects.filterIsInstance<Effect.Record>().single()
        assertNull(trace.entry.committedEase)

        // L'annulation doit se dire : c'est la fin de cet enonce qui rend l'oreille.
        assertTrue(spoken(result).lowercase().contains("annule"))
        val apres = ReviewSessionEngine.reduce(result.session, Event.SpeechFinished, 3_000L)
        assertEquals(listOf(ListenKind.ANSWER), listens(apres).map { it.kind })
    }

    @Test
    fun `un echec d'ecoute laisse la parenthese ouverte au lieu de la perdre`() {
        val session = Session(
            state = SessionState.Revisiting(
                inFlight(2),
                PendingAnswerFixtures.pending(card(1), Ease.GOOD),
            ),
        )
        val result = ReviewSessionEngine.reduce(session, Event.ListenFailed("micro muet"), 2_000L)

        assertTrue(
            "on doit revenir a la carte en cours, pas rester bloque",
            result.session.state is SessionState.Asking,
        )
    }

    // --- ce que le compteur affiche ---------------------------------------

    @Test
    fun `les cartes a media ne decalent pas le rang de la premiere carte`() {
        val cards = listOf(
            ReviewCard(1, 0, "d", "q1", "a1", 4, hasMedia = true),
            ReviewCard(2, 0, "d", "q2", "a2", 4, hasMedia = true),
            ReviewCard(3, 0, "d", "q3", "a3", 4, hasMedia = false),
            ReviewCard(4, 0, "d", "q4", "a4", 4, hasMedia = false),
        )
        val result = ReviewSessionEngine.reduce(Session(), Event.CardsLoaded(cards), 0L)

        assertEquals(2, result.session.total)
        assertEquals("la premiere carte utilisable est la premiere", 1, result.session.position)
    }

    // --- retour au mode normal --------------------------------------------

    @Test
    fun `la carte suivante retente le LLM apres une panne`() {
        val session = Session(
            state = SessionState.AwaitingCorrection(inFlight(1), Assessment.SelfGrade("verso 1")),
            degraded = true,
            queue = listOf(card(2)),
        )
        val result = ReviewSessionEngine.reduce(session, Event.Heard("facile"), 5_000L)

        assertTrue(
            "sans nouvelle tentative, une coupure de dix secondes condamne tout le trajet",
            result.effects.any { it is Effect.Reformulate },
        )
        assertFalse(result.session.degraded)
    }

    @Test
    fun `la panne reste en vigueur pour la carte en cours`() {
        val session = Session(state = SessionState.Judging(inFlight(1), "ma reponse"))
        val result = ReviewSessionEngine.reduce(session, Event.TutorFailed("reseau"), 2_000L)

        assertTrue(result.session.degraded)
        assertTrue(result.session.state is SessionState.SpeakingVerdict)
    }

    // --- coherence du journal ----------------------------------------------

    @Test
    fun `renoter en mode journal n'annonce aucune ecriture`() {
        val session = Session(
            state = SessionState.Revisiting(
                inFlight(2),
                PendingAnswerFixtures.pending(card(1), Ease.GOOD),
            ),
            pending = listOf(PendingAnswerFixtures.pending(card(1), Ease.GOOD)),
            writeMode = WriteMode.JOURNAL_ONLY,
        )
        val result = ReviewSessionEngine.reduce(session, Event.Heard("difficile"), 2_000L)

        val renotee = result.session.pending.single()
        assertEquals(Ease.HARD, renotee.ease)
        assertNull(
            "en mode journal rien n'est ecrit : le journal ne doit pas pretendre le contraire",
            renotee.record.committedEase,
        )
    }

    @Test
    fun `un echec d'ecoute isole n'arrete pas une session par ailleurs saine`() {
        val apresEchec = ReviewSessionEngine.reduce(
            Session(
                state = SessionState.AwaitingCorrection(
                    inFlight(1),
                    Assessment.Judged(judgement()),
                ),
            ),
            Event.ListenFailed("micro occupe"),
            1_000L,
        )
        assertEquals(1, apresEchec.session.listenFailures)

        val entendu = ReviewSessionEngine.reduce(
            apresEchec.session.copy(
                state = SessionState.AwaitingCorrection(
                    inFlight(1),
                    Assessment.Judged(judgement()),
                ),
            ),
            Event.Heard("bien"),
            2_000L,
        )
        assertEquals(
            "une reponse entendue prouve que le micro remarche",
            0,
            entendu.session.listenFailures,
        )
    }
}
