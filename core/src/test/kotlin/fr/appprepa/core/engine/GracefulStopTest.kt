package fr.appprepa.core.engine

import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.model.WriteMode
import fr.appprepa.core.ports.ListenResult
import fr.appprepa.core.ports.Listener
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Un arret manuel ne doit pas jeter la note de la carte qu'on vient de repondre :
 * elle est en attente d'ecriture, et seule une fin propre la vide.
 */
class GracefulStopTest {

    private fun card(id: Long) =
        ReviewCard(id, 0, "Prepa", "recto $id", "verso $id", 4, false)

    /** Repond a la premiere carte, puis demande l'arret pendant l'ecoute suivante. */
    private class StoppingListener(private val answers: MutableList<String>) : Listener {
        lateinit var loop: SessionLoop
        var stopAfter = 2
        private var calls = 0

        override suspend fun listen(timeoutMs: Long): ListenResult {
            calls++
            if (calls > stopAfter) {
                loop.requestStop()
                return ListenResult.Silence
            }
            return answers.removeFirstOrNull()
                ?.let { ListenResult.Transcript(it) }
                ?: ListenResult.Silence
        }
    }

    @Test
    fun `l'arret manuel ecrit la note de la carte en attente`() = runTest {
        val cards = listOf(card(1), card(2), card(3))
        val gateway = FakeAnkiGateway(cards)
        val journal = FakeJournal()
        val listener = StoppingListener(mutableListOf("ma reponse", "facile"))

        val loop = SessionLoop(
            gateway, FakeTutor(), FakeSpeaker(), listener, journal, FakeClock(),
            WriteMode.WRITE_THROUGH,
        )
        listener.loop = loop

        loop.run(emptySet(), 30)

        assertEquals(
            "la carte repondue avant l'arret doit etre ecrite",
            listOf(1L),
            gateway.answered.map { it.first },
        )
        assertEquals(Ease.EASY, gateway.answered.single().third)
        assertTrue("le journal doit garder une trace", journal.entries.isNotEmpty())
    }

    @Test
    fun `l'arret sans reponse n'ecrit rien`() = runTest {
        val cards = listOf(card(1), card(2))
        val gateway = FakeAnkiGateway(cards)
        val listener = StoppingListener(mutableListOf()).apply { stopAfter = 0 }

        val loop = SessionLoop(
            gateway, FakeTutor(), FakeSpeaker(), listener, FakeJournal(), FakeClock(),
            WriteMode.WRITE_THROUGH,
        )
        listener.loop = loop

        loop.run(emptySet(), 30)
        assertTrue("aucune note ne doit partir", gateway.answered.isEmpty())
    }
}
