package fr.appprepa.core.engine

import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.model.WriteMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FullSessionTest {

    private fun card(id: Long, media: Boolean = false) =
        ReviewCard(id, 0, "Prepa", "recto $id", "verso $id", 4, media)

    private fun loop(
        cards: List<ReviewCard>,
        script: MutableList<String?>,
        tutor: FakeTutor = FakeTutor(),
        gateway: FakeAnkiGateway = FakeAnkiGateway(cards),
        journal: FakeJournal = FakeJournal(),
        mode: WriteMode = WriteMode.WRITE_THROUGH,
    ) = SessionLoop(
        gateway, tutor, FakeSpeaker(), ScriptedListener(script), journal, FakeClock(), mode,
    )

    @Test
    fun `trois cartes sont posees jugees et ecrites dans l'ordre`() = runTest {
        val cards = listOf(card(1), card(2), card(3))
        val gateway = FakeAnkiGateway(cards)
        val script = mutableListOf<String?>(
            "reponse 1", null, "reponse 2", null, "reponse 3", null,
        )
        val stats = loop(cards, script, gateway = gateway).run(null, 30)

        assertEquals(3, stats.answered)
        assertEquals(listOf(1L, 2L, 3L), gateway.answered.map { it.first })
        assertTrue(gateway.answered.all { it.third == Ease.GOOD })
    }

    @Test
    fun `en mode journal rien n'est ecrit dans anki`() = runTest {
        val cards = listOf(card(1), card(2))
        val gateway = FakeAnkiGateway(cards)
        val journal = FakeJournal()
        val script = mutableListOf<String?>("reponse 1", null, "reponse 2", null)
        loop(cards, script, gateway = gateway, journal = journal, mode = WriteMode.JOURNAL_ONLY)
            .run(null, 30)

        assertTrue("aucune ecriture Anki attendue", gateway.answered.isEmpty())
        assertEquals(2, journal.entries.count { it.proposedEase != null })
        assertTrue(journal.entries.all { it.committedEase == null })
    }

    @Test
    fun `une correction vocale prime sur la note du LLM`() = runTest {
        val cards = listOf(card(1))
        val gateway = FakeAnkiGateway(cards)
        val script = mutableListOf<String?>("reponse 1", "a revoir")
        loop(cards, script, gateway = gateway).run(null, 30)

        assertEquals(listOf(Triple(1L, 0, Ease.AGAIN)), gateway.answered)
    }

    @Test
    fun `annule empeche l'ecriture de la carte precedente`() = runTest {
        val cards = listOf(card(1), card(2))
        val gateway = FakeAnkiGateway(cards)
        val script = mutableListOf<String?>("reponse 1", null, "reponse 2", "annule")
        loop(cards, script, gateway = gateway).run(null, 30)

        assertEquals(
            "seule la carte 2 doit etre ecrite",
            listOf(2L),
            gateway.answered.map { it.first },
        )
    }

    @Test
    fun `la panne du LLM bascule en lecture simple et la session continue`() = runTest {
        val cards = listOf(card(1), card(2))
        val gateway = FakeAnkiGateway(cards)
        val tutor = FakeTutor(failOn = setOf(1L, 2L))
        val script = mutableListOf<String?>("reponse 1", "facile", "reponse 2", "bien")
        val stats = loop(cards, script, tutor = tutor, gateway = gateway).run(null, 30)

        assertEquals(2, stats.answered)
        assertEquals(listOf(Ease.EASY, Ease.GOOD), gateway.answered.map { it.third })
    }

    @Test
    fun `les cartes a media sont ecartees sans etre notees`() = runTest {
        val cards = listOf(card(1, media = true), card(2))
        val gateway = FakeAnkiGateway(cards)
        val journal = FakeJournal()
        val script = mutableListOf<String?>("reponse 2", null)
        loop(cards, script, gateway = gateway, journal = journal).run(null, 30)

        assertEquals(listOf(2L), gateway.answered.map { it.first })
        assertTrue(journal.entries.any { it.noteId == 1L && it.note != null })
    }

    @Test
    fun `la reformulation de la carte suivante est demandee pendant l'ecoute`() = runTest {
        val cards = listOf(card(1), card(2), card(3))
        val tutor = FakeTutor()
        val script = mutableListOf<String?>("reponse 1", null, "reponse 2", null, "reponse 3", null)
        loop(cards, script, tutor = tutor).run(null, 30)

        assertEquals("une reformulation par carte, pas davantage", 3, tutor.reformulations)
    }

    @Test
    fun `une collection sans carte due se termine proprement`() = runTest {
        val stats = loop(emptyList(), mutableListOf()).run(null, 30)
        assertEquals(0, stats.answered)
    }
}
