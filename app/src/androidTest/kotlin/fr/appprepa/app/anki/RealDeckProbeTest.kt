package fr.appprepa.app.anki

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import fr.appprepa.core.text.MathSpeech
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Sonde sur le deck reel : verifie qu'aucune carte ne partirait a la synthese vocale
 * avec du LaTeX dedans, et montre ce qui serait prononce.
 */
class RealDeckProbeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aucuneCarteNePartAvecDuLatex() = runBlocking {
        assumeTrue(AnkiAvailability.check(context) == AnkiStatus.Ready)
        val cards = AnkiDroidGateway(context.contentResolver).dueCards(emptySet(), 20)
        assumeTrue(cards.isNotEmpty())

        cards.take(6).forEachIndexed { index, card ->
            val question = MathSpeech.verbalize(card.question)
            val answer = MathSpeech.verbalize(card.answer)
            Log.i("Probe", "--- carte $index (${card.deckName}) ---")
            Log.i("Probe", "DIT  : ${question.take(220)}")
            Log.i("Probe", "PUIS : ${answer.take(220)}")
        }

        cards.forEach { card ->
            val question = MathSpeech.verbalize(card.question)
            val answer = MathSpeech.verbalize(card.answer)
            assertFalse("LaTeX restant dans le recto : $question", question.contains("\\"))
            assertFalse("LaTeX restant dans le verso : $answer", answer.contains("\\"))
        }
    }
}
