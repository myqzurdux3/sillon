package fr.appprepa.app.session

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import fr.appprepa.app.anki.AnkiAvailability
import fr.appprepa.app.anki.AnkiDroidGateway
import fr.appprepa.app.anki.AnkiStatus
import fr.appprepa.app.llm.AnthropicTutor
import fr.appprepa.core.model.SessionMemory
import fr.appprepa.core.model.Verdict
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Fait tourner les deux appels du modele sur de vraies cartes de la collection.
 * La cle arrive par argument d'instrumentation, jamais par un fichier sur le telephone :
 *
 *   am instrument -e apiKey <cle> -e class ...RealSessionTest ...
 *
 * Aucune ecriture dans Anki : ce test ne fait que lire et interroger le modele.
 */
class RealSessionTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val apiKey: String
        get() = InstrumentationRegistry.getArguments().getString("apiKey").orEmpty()

    @Test
    fun leModeleReformuleEtJugeDeVraiesCartes() = runBlocking {
        assumeTrue("cle d'API absente", apiKey.isNotBlank())
        assumeTrue(AnkiAvailability.check(context) == AnkiStatus.Ready)

        val gateway = AnkiDroidGateway(context.contentResolver)
        // On vise les cartes qui portent vraiment de la notation : ce sont elles qui
        // decident si le produit est utilisable sur un deck de prepa.
        val toutes = gateway.dueCards(emptySet(), 30)
        val cards = toutes.filter { it.question.length > 25 || it.answer.length > 120 }
            .ifEmpty { toutes }
        assumeTrue("aucune carte due", cards.isNotEmpty())

        val tutor = AnthropicTutor(apiKey)

        cards.take(2).forEachIndexed { index, card ->
            Log.i("Reel", "===== carte $index : ${card.deckName} =====")
            Log.i("Reel", "SOURCE  : ${card.question.take(180)}")

            val debut = System.currentTimeMillis()
            val question = tutor.reformulate(card, SessionMemory())
            val duree = System.currentTimeMillis() - debut

            Log.i("Reel", "QUESTION: ${question.question}")
            Log.i("Reel", "ATTENDU : ${question.expectedPoints}")
            Log.i("Reel", "LATENCE : ${duree} ms")

            assertFalse(
                "la question contient encore du LaTeX : ${question.question}",
                question.question.contains("\\"),
            )
            assertTrue(question.question.isNotBlank())

            // Une reponse volontairement vague : le modele doit la juger severement.
            val vague = "euh je crois que c'est un truc avec des limites"
            val debutJuge = System.currentTimeMillis()
            val jugement = tutor.judge(card, question.expectedPoints, vague, SessionMemory())
            val dureeJuge = System.currentTimeMillis() - debutJuge

            Log.i("Reel", "VERDICT : ${jugement.verdict} -> ease ${jugement.ease}")
            Log.i("Reel", "RETOUR  : ${jugement.spokenFeedback}")
            Log.i("Reel", "THEME   : ${jugement.topic}")
            Log.i("Reel", "LATENCE : ${dureeJuge} ms")

            assertFalse(
                "le retour parle contient du LaTeX : ${jugement.spokenFeedback}",
                jugement.spokenFeedback.contains("\\"),
            )
            assertTrue(
                "une reponse vague ne doit pas etre jugee correcte",
                jugement.verdict != Verdict.CORRECT,
            )
        }
    }
}
