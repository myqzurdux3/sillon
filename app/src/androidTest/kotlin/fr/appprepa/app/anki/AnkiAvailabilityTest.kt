package fr.appprepa.app.anki

import androidx.test.platform.app.InstrumentationRegistry
import fr.appprepa.core.model.ReviewCard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifie l'environnement de test lui-meme : sans cela, les tests de la passerelle
 * peuvent etre silencieusement ignores par `assumeTrue` et passer pour verts.
 */
class AnkiAvailabilityTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun ankiDroidEstAccessible() {
        assertEquals(AnkiStatus.Ready, AnkiAvailability.check(context))
    }

    @Test
    fun laCollectionContientDesCartesDues() = runBlocking {
        val gateway = AnkiDroidGateway(context.contentResolver)
        var cards: List<ReviewCard> = gateway.dueCards(null, 5)
        if (cards.isEmpty()) {
            val inserted = AnkiTestFixtures.seedBasicNotes(context.contentResolver, 5)
            assertTrue("aucune note n'a pu etre inseree", inserted > 0)
            cards = gateway.dueCards(null, 5)
        }
        assertTrue("aucune carte due apres amorcage", cards.isNotEmpty())
    }
}
