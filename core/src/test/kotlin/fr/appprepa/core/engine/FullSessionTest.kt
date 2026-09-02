package fr.appprepa.core.engine

import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.model.WriteMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FullSessionTest {

    /** [muette] : un verso sans texte, le seul motif qui ecarte vraiment une carte. */
    private fun card(id: Long, muette: Boolean = false) =
        ReviewCard(id, 0, "Prepa", "recto $id", if (muette) "" else "verso $id", 4, muette)

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
        val stats = loop(cards, script, gateway = gateway).run(emptySet(), 30)

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
            .run(emptySet(), 30)

        assertTrue("aucune ecriture Anki attendue", gateway.answered.isEmpty())
        assertEquals(2, journal.entries.count { it.proposedEase != null })
        assertTrue(journal.entries.all { it.committedEase == null })
    }

    @Test
    fun `une correction vocale prime sur la note du LLM`() = runTest {
        val cards = listOf(card(1))
        val gateway = FakeAnkiGateway(cards)
        val script = mutableListOf<String?>("reponse 1", "a revoir")
        loop(cards, script, gateway = gateway).run(emptySet(), 30)

        assertEquals(listOf(Triple(1L, 0, Ease.AGAIN)), gateway.answered)
    }

    @Test
    fun `annule empeche l'ecriture de la carte precedente`() = runTest {
        val cards = listOf(card(1), card(2))
        val gateway = FakeAnkiGateway(cards)
        val script = mutableListOf<String?>("reponse 1", null, "reponse 2", "annule")
        loop(cards, script, gateway = gateway).run(emptySet(), 30)

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
        val stats = loop(cards, script, tutor = tutor, gateway = gateway).run(emptySet(), 30)

        assertEquals(2, stats.answered)
        assertEquals(listOf(Ease.EASY, Ease.GOOD), gateway.answered.map { it.third })
    }

    @Test
    fun `les cartes a media sont ecartees sans etre notees`() = runTest {
        val cards = listOf(card(1, muette = true), card(2))
        val gateway = FakeAnkiGateway(cards)
        val journal = FakeJournal()
        val script = mutableListOf<String?>("reponse 2", null)
        loop(cards, script, gateway = gateway, journal = journal).run(emptySet(), 30)

        assertEquals(listOf(2L), gateway.answered.map { it.first })
        assertTrue(journal.entries.any { it.noteId == 1L && it.note != null })
    }

    @Test
    fun `la reformulation de la carte suivante est demandee pendant l'ecoute`() = runTest {
        val cards = listOf(card(1), card(2), card(3))
        val tutor = FakeTutor()
        val script = mutableListOf<String?>("reponse 1", null, "reponse 2", null, "reponse 3", null)
        loop(cards, script, tutor = tutor).run(emptySet(), 30)

        assertEquals("une reformulation par carte, pas davantage", 3, tutor.reformulations)
    }

    @Test
    fun `revenir sur la carte precedente change sa note sans perdre la carte en cours`() = runTest {
        val cards = listOf(card(1), card(2), card(3))
        val gateway = FakeAnkiGateway(cards)
        // carte 1 : repondue puis validee. carte 2 : on revient sur la 1 et on la renote.
        val script = mutableListOf<String?>(
            "reponse 1", null,
            "reviens", "a revoir",
            "reponse 2", null,
            "reponse 3", null,
        )
        loop(cards, script, gateway = gateway).run(emptySet(), 30)

        assertEquals(listOf(1L, 2L, 3L), gateway.answered.map { it.first })
        assertEquals(
            "la carte 1 doit porter la note dictee apres coup",
            Ease.AGAIN,
            gateway.answered.first { it.first == 1L }.third,
        )
    }

    @Test
    fun `une collection sans carte due se termine proprement`() = runTest {
        val stats = loop(emptyList(), mutableListOf()).run(emptySet(), 30)
        assertEquals(0, stats.answered)
    }

    @Test
    fun `une ecriture refusee par anki est dite dans le journal, pas passee sous silence`() =
        runTest {
            val cards = listOf(card(1), card(2))
            val journal = FakeJournal()
            val gateway = object : FakeAnkiGateway(cards) {
                override suspend fun answer(
                    noteId: Long,
                    cardOrd: Int,
                    ease: Ease,
                    timeTakenMs: Long,
                ) = throw IllegalStateException("collection verrouillee")
            }
            val script = mutableListOf<String?>("reponse 1", null, "reponse 2", null)
            val stats = loop(cards, script, gateway = gateway, journal = journal)
                .run(emptySet(), 30)

            assertEquals("le refus doit etre compte", 2, stats.writeFailures)
            val notes = journal.entries.mapNotNull { it.note }
            assertTrue("le journal doit dire le refus : $notes", notes.all { "refuse" in it })
            assertTrue(
                "une note refusee n'est pas une note ecrite",
                journal.entries.all { it.committedEase == null },
            )
        }

    @Test
    fun `une phrase relancee traverse la boucle et arrive recollee au jugement`() = runTest {
        val cards = listOf(card(1))
        val tutor = FakeTutor()
        // Premier morceau coupe par un silence de reflexion, puis la suite, puis le
        // silence de la fenetre de correction.
        val script = mutableListOf<String?>(
            "la dérivée de ce produit vaut donc",
            "u prime v plus u v prime",
            null,
        )
        loop(cards, script, tutor = tutor).run(emptySet(), 30)

        assertEquals(
            listOf("la dérivée de ce produit vaut donc u prime v plus u v prime"),
            tutor.transcripts,
        )
    }

    @Test
    fun `un silence apres la relance ne fait pas perdre le debut de la reponse`() = runTest {
        val cards = listOf(card(1))
        val tutor = FakeTutor()
        val script = mutableListOf<String?>("la limite vaut donc", null, null)
        loop(cards, script, tutor = tutor).run(emptySet(), 30)

        assertEquals(listOf("la limite vaut donc"), tutor.transcripts)
    }
}
