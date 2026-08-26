package fr.appprepa.app.anki

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Amorce l'emulateur avec de vraies cartes du deck, HTML et LaTeX compris. */
class SeedRealCardsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun semeLesCartesReelles() = runBlocking {
        assumeTrue(AnkiAvailability.check(context) == AnkiStatus.Ready)
        val inserted = AnkiTestFixtures.seedRealCards(context.contentResolver)
        assertTrue("aucune carte reelle inseree", inserted > 0)

        val cards = AnkiDroidGateway(context.contentResolver).dueCards(null, 30)
        assertTrue(cards.isNotEmpty())
    }
}
