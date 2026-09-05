package fr.appprepa.core.engine

import fr.appprepa.core.model.Langue
import fr.appprepa.core.ports.ListenKind
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

    /** Ce qui a reellement ete soumis au jugement, pour verifier le recollement. */
    val transcripts = mutableListOf<String>()

    override suspend fun judge(
        card: ReviewCard,
        expectedPoints: List<String>,
        transcript: String,
        memory: SessionMemory,
        langueCorrection: Langue,
    ): Judgement {
        if (card.noteId in failOn) error("panne simulee")
        transcripts += transcript
        val verdict = verdicts[card.noteId] ?: Verdict.CORRECT
        return Judgement(verdict, Ease.fromVerdict(verdict), "retour", null, "theme")
    }

    override suspend fun explain(card: ReviewCard, langueCorrection: Langue) =
        "explication de ${card.noteId}"
}

class FakeSpeaker : Speaker {
    val spoken = mutableListOf<String>()

    /** Ce qui a ete prechauffe, pour verifier que la delegation ne se perd pas en route. */
    val warmed = mutableListOf<String>()

    /** La langue de chaque enonce : c'est elle qui choisit la voix sur l'appareil. */
    val langues = mutableListOf<Langue>()

    override suspend fun speak(text: String, langue: Langue) {
        spoken += text
        langues += langue
    }

    override suspend fun warm(text: String, langue: Langue) { warmed += text }

    override fun stop() = Unit
}

/** Rend les transcripts dans l'ordre ; `null` signifie silence. */
class ScriptedListener(private val script: MutableList<String?>) : Listener {
    /** La langue de chaque ecoute : c'est elle qui regle le moteur de reconnaissance. */
    val langues = mutableListOf<Langue>()

    override suspend fun listen(kind: ListenKind, timeoutMs: Long, langue: Langue): ListenResult {
        langues += langue
        return when (val next = script.removeFirstOrNull()) {
            null -> ListenResult.Silence
            else -> ListenResult.Transcript(next)
        }
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
