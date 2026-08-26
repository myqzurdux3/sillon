package fr.appprepa.core.engine

import fr.appprepa.core.model.ReviewCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chercher ses mots au volant ne doit pas couper la reponse.
 *
 * La reconnaissance vocale rend la main sur un silence, sans savoir si la phrase etait
 * finie. Quand elle ne l'est visiblement pas, l'application redemande au lieu de juger
 * une demi-reponse — et recolle les deux morceaux.
 */
class RelanceTest {

    private fun card(id: Long) = ReviewCard(id, 0, "Prepa", "recto $id", "verso $id", 4, false)

    private fun inFlight(id: Long) =
        CardInFlight(card(id), "question orale $id", listOf("point $id"), 1_000L)

    private fun spoken(r: Reduction) =
        r.effects.filterIsInstance<Effect.Speak>().joinToString(" ") { it.text }

    private fun judged(r: Reduction) =
        r.effects.filterIsInstance<Effect.Judge>().singleOrNull()

    @Test
    fun `une phrase laissee en suspens est relancee au lieu d'etre jugee`() {
        val session = Session(state = SessionState.Listening(inFlight(1)))
        val result = ReviewSessionEngine.reduce(
            session,
            Event.Heard("la dérivée de ce produit vaut donc"),
            2_000L,
        )

        assertEquals("rien ne doit partir au jugement", null, judged(result))
        assertTrue(spoken(result).isNotEmpty())
        val etat = result.session.state as SessionState.Listening
        assertEquals("la dérivée de ce produit vaut donc", etat.partial)
    }

    @Test
    fun `la suite est recollee au debut avant d'etre jugee`() {
        var session = Session(state = SessionState.Listening(inFlight(1)))
        session = ReviewSessionEngine.reduce(
            session,
            Event.Heard("la dérivée de ce produit vaut donc"),
            2_000L,
        ).session
        // L'enonce de la relance se termine, l'oreille se rouvre.
        session = ReviewSessionEngine.reduce(session, Event.SpeechFinished, 3_000L).session

        val result = ReviewSessionEngine.reduce(
            session,
            Event.Heard("u prime v plus u v prime"),
            5_000L,
        )

        assertEquals(
            "la dérivée de ce produit vaut donc u prime v plus u v prime",
            judged(result)?.transcript,
        )
    }

    @Test
    fun `on ne relance qu'une fois par carte`() {
        var session = Session(state = SessionState.Listening(inFlight(1)))
        session = ReviewSessionEngine.reduce(session, Event.Heard("il faut montrer que"), 2_000L)
            .session
        session = ReviewSessionEngine.reduce(session, Event.SpeechFinished, 3_000L).session

        // Deuxieme morceau lui aussi en suspens : on juge ce qu'on a plutot que d'insister.
        val result = ReviewSessionEngine.reduce(session, Event.Heard("la fonction est"), 5_000L)
        assertEquals("il faut montrer que la fonction est", judged(result)?.transcript)
    }

    @Test
    fun `un silence apres la relance juge ce qui a deja ete dit`() {
        var session = Session(state = SessionState.Listening(inFlight(1)))
        session = ReviewSessionEngine.reduce(session, Event.Heard("la limite vaut donc"), 2_000L)
            .session
        session = ReviewSessionEngine.reduce(session, Event.SpeechFinished, 3_000L).session

        val result = ReviewSessionEngine.reduce(session, Event.HeardNothing, 5_000L)

        assertEquals(
            "ce qui a ete dit ne doit pas etre jete",
            "la limite vaut donc",
            judged(result)?.transcript,
        )
    }

    @Test
    fun `une commande reste une commande apres une relance`() {
        var session = Session(state = SessionState.Listening(inFlight(1)))
        session = ReviewSessionEngine.reduce(session, Event.Heard("le résultat est"), 2_000L).session
        session = ReviewSessionEngine.reduce(session, Event.SpeechFinished, 3_000L).session

        val result = ReviewSessionEngine.reduce(session, Event.Heard("explique"), 5_000L)
        assertTrue(result.effects.any { it is Effect.Explain })
    }

    @Test
    fun `une reponse qui se tient part directement au jugement`() {
        val session = Session(state = SessionState.Listening(inFlight(1)))
        val result = ReviewSessionEngine.reduce(
            session,
            Event.Heard("c'est le théorème de Rolle"),
            2_000L,
        )

        assertEquals("c'est le théorème de Rolle", judged(result)?.transcript)
        assertFalse(result.effects.any { it is Effect.Speak })
    }
}
