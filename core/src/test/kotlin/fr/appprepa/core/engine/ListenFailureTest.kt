package fr.appprepa.core.engine

import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.model.WriteMode
import fr.appprepa.core.ports.ListenResult
import fr.appprepa.core.ports.Listener
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Un micro en panne ne doit pas ressembler a du silence. Sans cela, la session defile
 * entiere en notant tout « a revoir », sans jamais dire pourquoi.
 */
class ListenFailureTest {

    private fun card(id: Long) = ReviewCard(id, 0, "Prepa", "recto $id", "verso $id", 4, false)

    private fun inFlight(id: Long) =
        CardInFlight(card(id), "question orale $id", listOf("point $id"), 1_000L)

    private fun spoken(r: Reduction) =
        r.effects.filterIsInstance<Effect.Speak>().joinToString(" ") { it.text }

    @Test
    fun `un premier echec d'ecoute est annonce, pas ignore`() {
        val session = Session(state = SessionState.Listening(inFlight(1)))
        val result = ReviewSessionEngine.reduce(
            session,
            Event.ListenFailed("micro indisponible"),
            2_000L,
        )

        val dit = spoken(result).lowercase()
        assertTrue("l'utilisateur doit etre prevenu : ${spoken(result)}", dit.contains("entends"))
        assertTrue("la carte ne doit pas etre notee sur un echec technique", result.session.pending.isEmpty())
    }

    @Test
    fun `deux echecs consecutifs arretent la session avec une raison`() {
        val session = Session(state = SessionState.Listening(inFlight(1)))
        val premier = ReviewSessionEngine.reduce(session, Event.ListenFailed("micro"), 2_000L)
        val parle = ReviewSessionEngine.reduce(premier.session, Event.SpeechFinished, 3_000L)
        val second = ReviewSessionEngine.reduce(parle.session, Event.ListenFailed("micro"), 4_000L)

        val state = second.session.state
        assertTrue("la session doit s'arreter clairement : $state", state is SessionState.Failed)
        assertTrue((state as SessionState.Failed).reason.lowercase().contains("micro"))
        assertTrue(second.effects.contains(Effect.Finish))
    }

    @Test
    fun `une reponse entendue efface le compteur d'echecs`() {
        val session = Session(state = SessionState.Listening(inFlight(1)))
        val echoue = ReviewSessionEngine.reduce(session, Event.ListenFailed("micro"), 2_000L)
        val parle = ReviewSessionEngine.reduce(echoue.session, Event.SpeechFinished, 3_000L)
        val entendu = ReviewSessionEngine.reduce(parle.session, Event.Heard("ma reponse"), 4_000L)

        assertEquals(0, entendu.session.listenFailures)
    }

    @Test
    fun `un silence ordinaire reste un silence`() {
        val session = Session(state = SessionState.Listening(inFlight(1)))
        val result = ReviewSessionEngine.reduce(session, Event.HeardNothing, 2_000L)

        assertEquals(0, result.session.listenFailures)
        assertTrue(result.session.state is SessionState.Asking)
        assertTrue(spoken(result).contains("Je t'écoute"))
    }

    /** Ecouteur toujours en panne, comme un micro refuse ou un service absent. */
    private class BrokenListener : Listener {
        var calls = 0
            private set
        override suspend fun listen(timeoutMs: Long): ListenResult {
            calls++
            return ListenResult.Failure("micro indisponible")
        }
    }

    @Test
    fun `un micro en panne arrete la session au lieu de tout noter a revoir`() = runTest {
        val cards = listOf(card(1), card(2), card(3))
        val gateway = FakeAnkiGateway(cards)
        val listener = BrokenListener()

        val stats = SessionLoop(
            gateway, FakeTutor(), FakeSpeaker(), listener, FakeJournal(), FakeClock(),
            WriteMode.WRITE_THROUGH,
        ).run(emptySet(), 30)

        assertTrue("aucune carte ne doit etre notee", gateway.answered.isEmpty())
        assertEquals("la session doit s'arreter, pas defiler", 0, stats.answered)
        assertTrue("l'ecoute ne doit pas etre retentee indefiniment", listener.calls <= 3)
    }
}
