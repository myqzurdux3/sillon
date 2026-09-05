package fr.appprepa.core.engine

import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.model.WriteMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La question de la carte suivante doit etre prechauffee pendant qu'on repond a la carte
 * en cours, sinon la synthese distante ajoute un aller-retour avant chaque question.
 *
 * Ce test existe parce que le prechauffage a deja ete inoperant en silence : le
 * decorateur qui enveloppe le locuteur heritait de l'implementation vide de l'interface
 * au lieu de deleguer, et rien — ni compilation, ni test — ne le signalait. Il verifie
 * donc le trajet complet, de la boucle jusqu'au locuteur reel.
 */
class PrechauffageTest {

    private fun card(id: Long) =
        ReviewCard(id, 0, "Prepa", "recto $id", "verso $id", 4, false)

    /** Un decorateur comme il en existe un en production, pour eprouver la delegation. */
    private class Enveloppe(private val delegate: fr.appprepa.core.ports.Speaker) :
        fr.appprepa.core.ports.Speaker by delegate

    @Test
    fun `la question suivante est prechauffee pendant la reponse en cours`() = runTest {
        val cards = listOf(card(1), card(2))
        val speaker = FakeSpeaker()
        val loop = SessionLoop(
            FakeAnkiGateway(cards),
            FakeTutor(),
            Enveloppe(speaker),
            ScriptedListener(mutableListOf("reponse 1", null, "reponse 2", null)),
            FakeJournal(),
            FakeClock(),
            WriteMode.JOURNAL_ONLY,
        )
        loop.run(emptySet(), 30)

        assertTrue(
            "la question de la carte 2 n'a jamais ete prechauffee : ${speaker.warmed}",
            speaker.warmed.any { it.contains("2") },
        )
    }

    @Test
    fun `ce qui est prechauffe est bien ce qui sera dit`() = runTest {
        val cards = listOf(card(1), card(2))
        val speaker = FakeSpeaker()
        val loop = SessionLoop(
            FakeAnkiGateway(cards),
            FakeTutor(),
            Enveloppe(speaker),
            ScriptedListener(mutableListOf("reponse 1", null, "reponse 2", null)),
            FakeJournal(),
            FakeClock(),
            WriteMode.JOURNAL_ONLY,
        )
        loop.run(emptySet(), 30)

        // Un prechauffage dont le texte differe de l'enonce ne sert a rien : le cache est
        // retrouve par egalite exacte.
        speaker.warmed.forEach {
            assertTrue("« $it » a ete prechauffe mais jamais dit", it in speaker.spoken)
        }
    }
}
