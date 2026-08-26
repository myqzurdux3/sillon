package fr.appprepa.core.ports

import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.Judgement
import fr.appprepa.core.model.JournalRecord
import fr.appprepa.core.model.ReformulatedQuestion
import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.model.SessionMemory

/** Acces a la collection AnkiDroid. */
interface AnkiGateway {
    suspend fun dueCards(deckId: Long?, limit: Int): List<ReviewCard>
    suspend fun answer(noteId: Long, cardOrd: Int, ease: Ease, timeTakenMs: Long)
    suspend fun decks(): Map<Long, String>
}

/** Le LLM, vu par le moteur. */
interface Tutor {
    suspend fun reformulate(card: ReviewCard, memory: SessionMemory): ReformulatedQuestion
    suspend fun judge(
        card: ReviewCard,
        expectedPoints: List<String>,
        transcript: String,
        memory: SessionMemory,
    ): Judgement
    suspend fun explain(card: ReviewCard): String
}

/** Synthese vocale. [speak] ne rend la main qu'a la fin de l'enonce. */
interface Speaker {
    suspend fun speak(text: String)
    fun stop()
}

sealed interface ListenResult {
    data class Transcript(val text: String) : ListenResult
    data object Silence : ListenResult
    data class Failure(val cause: String) : ListenResult
}

/** Reconnaissance vocale. */
interface Listener {
    suspend fun listen(timeoutMs: Long): ListenResult
}

interface Journal {
    suspend fun record(entry: JournalRecord)
}

interface Clock {
    fun nowMs(): Long
}
