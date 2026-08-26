package fr.appprepa.app.anki

import androidx.test.platform.app.InstrumentationRegistry
import fr.appprepa.core.model.Ease
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class AnkiDroidGatewayTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var gateway: AnkiDroidGateway

    @Before
    fun setUp() {
        val status = AnkiAvailability.check(context)
        assumeTrue(
            "AnkiDroid doit etre installe et la permission accordee (statut : $status)",
            status == AnkiStatus.Ready,
        )
        gateway = AnkiDroidGateway(context.contentResolver)

        runBlocking {
            if (gateway.dueCards(null, 1).isEmpty()) {
                AnkiTestFixtures.seedBasicNotes(context.contentResolver, 5)
            }
        }
    }

    @Test
    fun listeLesDecks() = runBlocking {
        val decks = gateway.decks()
        assertTrue("au moins un deck attendu", decks.isNotEmpty())
    }

    @Test
    fun litLesCartesDuesAvecLeurTexte() = runBlocking {
        val cards = gateway.dueCards(deckId = null, limit = 3)
        assertTrue("au moins une carte due attendue", cards.isNotEmpty())
        val card = cards.first()
        assertTrue("le recto ne doit pas etre vide", card.question.isNotBlank())
        assertTrue("le verso ne doit pas etre vide", card.answer.isNotBlank())
        assertTrue("le recto ne doit pas contenir de HTML", !card.question.contains("<div"))
        assertTrue("button count plausible : ${card.buttonCount}", card.buttonCount in 2..4)
    }

    @Test
    fun repondreALaCarteLaRetireDesCartesDues() = runBlocking {
        val before = gateway.dueCards(deckId = null, limit = 1)
        assumeTrue(before.isNotEmpty())
        val card = before.first()

        gateway.answer(card.noteId, card.cardOrd, Ease.GOOD, timeTakenMs = 4_000L)

        val after = gateway.dueCards(deckId = null, limit = 1)
        val stillSame = after.firstOrNull()
            ?.let { it.noteId == card.noteId && it.cardOrd == card.cardOrd } ?: false
        assertEquals("la carte notee ne doit plus etre proposee", false, stillSame)
    }
}
