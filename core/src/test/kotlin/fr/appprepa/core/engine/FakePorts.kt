package fr.appprepa.core.engine

import fr.appprepa.core.deck.DeckInfo
import fr.appprepa.core.deck.DeckMerge
import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.Judgement
import fr.appprepa.core.model.JournalRecord
import fr.appprepa.core.model.ReformulatedQuestion
import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.model.SessionMemory
import fr.appprepa.core.model.Verdict
import fr.appprepa.core.ports.AnkiGateway
import fr.appprepa.core.ports.Clock
import fr.appprepa.core.ports.Journal
import fr.appprepa.core.ports.ListenResult
import fr.appprepa.core.ports.Listener
import fr.appprepa.core.ports.Speaker
import fr.appprepa.core.ports.Tutor

open class FakeAnkiGateway(private val cards: List<ReviewCard>) : AnkiGateway {
    val answered = mutableListOf<Triple<Long, Int, Ease>>()
    var lastDeckIds: Set<Long> = emptySet()
        private set

    override suspend fun dueCards(deckIds: Set<Long>, limit: Int): List<ReviewCard> {
        lastDeckIds = deckIds
        val retenues = if (deckIds.isEmpty()) {
            cards
        } else {
            cards.filter { deckIds.contains(deckIdOf(it.deckName)) }
        }
        return DeckMerge.interleave(listOf(retenues), limit) { it.noteId to it.cardOrd }
    }

    override suspend fun answer(noteId: Long, cardOrd: Int, ease: Ease, timeTakenMs: Long) {
        answered += Triple(noteId, cardOrd, ease)
    }

    override suspend fun decks() = cards.map { it.deckName }.distinct().mapIndexed { i, name ->
        DeckInfo(deckIdOf(name), name, cards.count { it.deckName == name })
    }

    private fun deckIdOf(name: String): Long = name.hashCode().toLong()
}

class FakeTutor(
    private val verdicts: Map<Long, Verdict> = emptyMap(),
    private val failOn: Set<Long> = emptySet(),
) : Tutor {
    var reformulations = 0
        private set

    override suspend fun reformulate(card: ReviewCard, memory: SessionMemory): ReformulatedQuestion {
        if (card.noteId in failOn) error("panne simulee")
        reformulations++
        return ReformulatedQuestion("Question sur ${card.noteId} ?", listOf("point ${card.noteId}"))
    }

    override suspend fun judge(
        card: ReviewCard,
        expectedPoints: List<String>,
        transcript: String,
        memory: SessionMemory,
    ): Judgement {
        if (card.noteId in failOn) error("panne simulee")
        val verdict = verdicts[card.noteId] ?: Verdict.CORRECT
        return Judgement(verdict, Ease.fromVerdict(verdict), "retour", null, "theme")
    }

    override suspend fun explain(card: ReviewCard) = "explication de ${card.noteId}"
}

class FakeSpeaker : Speaker {
    val spoken = mutableListOf<String>()
    override suspend fun speak(text: String) { spoken += text }
    override fun stop() = Unit
}

/** Rend les transcripts dans l'ordre ; `null` signifie silence. */
class ScriptedListener(private val script: MutableList<String?>) : Listener {
    override suspend fun listen(timeoutMs: Long): ListenResult =
        when (val next = script.removeFirstOrNull()) {
            null -> ListenResult.Silence
            else -> ListenResult.Transcript(next)
        }
}

class FakeJournal : Journal {
    val entries = mutableListOf<JournalRecord>()
    override suspend fun record(entry: JournalRecord) { entries += entry }
}

class FakeClock(private var now: Long = 0L) : Clock {
    override fun nowMs(): Long {
        now += 1_000L
        return now
    }
}
