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
            if (gateway.dueCards(emptySet(), 1).isEmpty()) {
                AnkiTestFixtures.seedBasicNotes(context.contentResolver, 5)
            }
        }
    }

    @Test
    fun listeLesDecksAvecLeursCompteurs() = runBlocking {
        val decks = gateway.decks()
        assertTrue("au moins un deck attendu", decks.isNotEmpty())
        assertTrue("les noms ne doivent pas etre vides", decks.all { it.name.isNotBlank() })
        assertTrue(
            "au moins un paquet doit annoncer des cartes dues",
            decks.any { it.dueCount > 0 },
        )
    }

    @Test
    fun interrogerUnPaquetPreciseNeRendQueLuiOuSesEnfants() = runBlocking {
        val decks = gateway.decks().filter { it.dueCount > 0 }
        assumeTrue("aucun paquet avec des cartes dues", decks.isNotEmpty())
        val cible = decks.first()

        val cartes = gateway.dueCards(setOf(cible.id), limit = 5)
        assertTrue("le paquet cible doit rendre des cartes", cartes.isNotEmpty())
        assertTrue(
            "les cartes doivent venir du paquet demande : ${cartes.map { it.deckName }.distinct()}",
            cartes.all { it.deckName == cible.name || it.deckName.startsWith(cible.name + "::") },
        )
    }

    @Test
    fun litLesCartesDuesAvecLeurTexte() = runBlocking {
        val cards = gateway.dueCards(deckIds = emptySet(), limit = 3)
        assertTrue("au moins une carte due attendue", cards.isNotEmpty())
        val card = cards.first()
        assertTrue("le recto ne doit pas etre vide", card.question.isNotBlank())
        assertTrue("le verso ne doit pas etre vide", card.answer.isNotBlank())
        assertTrue("le recto ne doit pas contenir de HTML", !card.question.contains("<div"))
        assertTrue("button count plausible : ${card.buttonCount}", card.buttonCount in 2..4)
    }

    @Test
    fun repondreALaCarteLaRetireDesCartesDues() = runBlocking {
        val before = gateway.dueCards(deckIds = emptySet(), limit = 1)
        assumeTrue(before.isNotEmpty())
        val card = before.first()

        gateway.answer(card.noteId, card.cardOrd, Ease.GOOD, timeTakenMs = 4_000L)

        val after = gateway.dueCards(deckIds = emptySet(), limit = 1)
        val stillSame = after.firstOrNull()
            ?.let { it.noteId == card.noteId && it.cardOrd == card.cardOrd } ?: false
        assertEquals("la carte notee ne doit plus etre proposee", false, stillSame)
    }
}
