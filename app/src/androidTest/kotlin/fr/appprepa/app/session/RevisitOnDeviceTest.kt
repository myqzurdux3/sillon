package fr.appprepa.app.session

import fr.appprepa.core.deck.DeckInfo
import fr.appprepa.core.engine.SessionLoop
import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.Judgement
import fr.appprepa.core.model.JournalRecord
import fr.appprepa.core.model.ReformulatedQuestion
import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.model.SessionMemory
import fr.appprepa.core.model.Verdict
import fr.appprepa.core.model.WriteMode
import fr.appprepa.core.ports.AnkiGateway
import fr.appprepa.core.ports.Clock
import fr.appprepa.core.ports.Journal
import fr.appprepa.core.ports.ListenResult
import fr.appprepa.core.ports.Listener
import fr.appprepa.core.ports.Speaker
import fr.appprepa.core.ports.Tutor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rejoue la parenthese sur l'appareil, avec le code reellement empaquete dans l'APK.
 * Piloter l'interface ne permet pas de tomber de facon fiable dans les fenetres d'ecoute ;
 * ici le scenario est deterministe.
 */
class RevisitOnDeviceTest {

    private fun card(id: Long) =
        ReviewCard(id, 0, "Deck", "recto $id", "verso $id", 4, false)

    private class Gateway(private val cards: List<ReviewCard>) : AnkiGateway {
        val answered = mutableListOf<Triple<Long, Int, Ease>>()
        override suspend fun dueCards(deckIds: Set<Long>, limit: Int) = cards.take(limit)
        override suspend fun answer(noteId: Long, cardOrd: Int, ease: Ease, timeTakenMs: Long) {
            answered += Triple(noteId, cardOrd, ease)
        }
        override suspend fun decks() = listOf(DeckInfo(1L, "Deck", cards.size))
    }

    private class StubTutor : Tutor {
        override suspend fun reformulate(card: ReviewCard, memory: SessionMemory) =
            ReformulatedQuestion("question ${card.noteId}", emptyList())
        override suspend fun judge(
            card: ReviewCard,
            expectedPoints: List<String>,
            transcript: String,
            memory: SessionMemory,
        ) = Judgement(Verdict.CORRECT, Ease.GOOD, "retour", null, "theme")
        override suspend fun explain(card: ReviewCard) = "explication ${card.noteId}"
    }

    private class Mute : Speaker {
        val said = mutableListOf<String>()
        override suspend fun speak(text: String) { said += text }
        override fun stop() = Unit
    }

    private class Script(private val lines: MutableList<String?>) : Listener {
        override suspend fun listen(timeoutMs: Long): ListenResult =
            when (val next = lines.removeFirstOrNull()) {
                null -> ListenResult.Silence
                else -> ListenResult.Transcript(next)
            }
    }

    private class Log : Journal {
        val entries = mutableListOf<JournalRecord>()
        override suspend fun record(entry: JournalRecord) { entries += entry }
    }

    private class Ticks : Clock {
        private var now = 0L
        override fun nowMs(): Long { now += 1_000L; return now }
    }

    @Test
    fun revenirSurLaPrecedenteChangeSaNote() = runBlocking {
        val cards = listOf(card(1), card(2), card(3))
        val gateway = Gateway(cards)
        val speaker = Mute()
        val script = mutableListOf<String?>(
            "ma reponse", "bien",        // carte 1 notee « bien »
            "reviens", "a revoir",       // parenthese pendant la carte 2
            "ma reponse", null,          // on reprend la carte 2
            "ma reponse", null,          // carte 3
        )

        val loop = SessionLoop(
            gateway, StubTutor(), speaker, Script(script), Log(), Ticks(),
            WriteMode.WRITE_THROUGH,
        )
        loop.run(emptySet(), 30)

        assertTrue(
            "la carte precedente doit avoir ete rappelee a voix haute",
            speaker.said.any { it.contains("Carte précédente") && it.contains("recto 1") },
        )
        assertEquals(
            "la carte 1 doit porter la note dictee dans la parenthese",
            Ease.AGAIN,
            gateway.answered.first { it.first == 1L }.third,
        )
        assertEquals(listOf(1L, 2L, 3L), gateway.answered.map { it.first })
    }
}
