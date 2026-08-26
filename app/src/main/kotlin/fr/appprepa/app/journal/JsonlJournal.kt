package fr.appprepa.app.journal

import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.JournalRecord
import fr.appprepa.core.model.Verdict
import fr.appprepa.core.model.WriteMode
import fr.appprepa.core.ports.Journal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Un objet JSON par ligne : robuste a une ecriture interrompue, lisible tel quel. */
class JsonlJournal(private val file: File) : Journal {

    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class Row(
        val ts: Long,
        val noteId: Long,
        val cardOrd: Int,
        val deck: String,
        val question: String,
        val transcript: String,
        val proposedEase: Int? = null,
        val committedEase: Int? = null,
        val verdict: String? = null,
        val mode: String,
        val note: String? = null,
    )

    override suspend fun record(entry: JournalRecord) {
        withContext(Dispatchers.IO) {
            lock.withLock {
                file.parentFile?.mkdirs()
                file.appendText(json.encodeToString(entry.toRow()) + "\n")
            }
        }
    }

    suspend fun readAll(): List<JournalRecord> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        file.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                runCatching { json.decodeFromString<Row>(line).toRecord() }.getOrNull()
            }
    }

    /** Les entrees depuis minuit : c'est la relecture du soir qui compte. */
    suspend fun today(nowMs: Long): List<JournalRecord> {
        val dayStart = nowMs - (nowMs % 86_400_000L)
        return readAll().filter { it.atMs >= dayStart }
    }

    private fun JournalRecord.toRow() = Row(
        ts = atMs,
        noteId = noteId,
        cardOrd = cardOrd,
        deck = deckName,
        question = question,
        transcript = transcript,
        proposedEase = proposedEase?.value,
        committedEase = committedEase?.value,
        verdict = verdict?.name,
        mode = mode.name,
        note = note,
    )

    private fun Row.toRecord() = JournalRecord(
        atMs = ts,
        noteId = noteId,
        cardOrd = cardOrd,
        deckName = deck,
        question = question,
        transcript = transcript,
        proposedEase = proposedEase?.let { Ease.fromValue(it) },
        committedEase = committedEase?.let { Ease.fromValue(it) },
        verdict = verdict?.let { runCatching { Verdict.valueOf(it) }.getOrNull() },
        mode = runCatching { WriteMode.valueOf(mode) }.getOrDefault(WriteMode.JOURNAL_ONLY),
        note = note,
    )
}
