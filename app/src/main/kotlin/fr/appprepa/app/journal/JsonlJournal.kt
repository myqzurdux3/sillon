package fr.appprepa.app.journal

import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.Intention
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
class JsonlJournal(
    private val file: File,
    /** Au-dela, le journal ne sert plus a rien : on ne relit que la journee ecoulee. */
    private val retentionDays: Int = RETENTION_DAYS,
) : Journal {

    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Le nettoyage a lieu une fois par session, pas a chaque ligne ecrite. */
    private var pruned = false

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
        /** Le silence subi entre la fin de la reponse et le verdict, en millisecondes. */
        val attenteMs: Long? = null,
        /** Ce que le modele a lu : une reponse, ou une demande. */
        val intention: String? = null,
    )

    override suspend fun record(entry: JournalRecord) {
        withContext(Dispatchers.IO) {
            lock.withLock {
                file.parentFile?.mkdirs()
                if (!pruned) {
                    pruned = true
                    prune(entry.atMs)
                }
                file.appendText(json.encodeToString(entry.toRow()) + "\n")
            }
        }
    }

    /**
     * Jette les lignes trop anciennes. Sans cela le fichier grossit sans fin et chaque
     * ouverture de l'ecran du journal le relit en entier, alors que seule la journee
     * ecoulee y est affichee.
     */
    private fun prune(nowMs: Long) {
        if (!file.exists()) return
        val floor = nowMs - retentionDays * 86_400_000L
        val kept = file.readLines().filter { line ->
            line.isNotBlank() &&
                (runCatching { json.decodeFromString<Row>(line).ts }.getOrNull() ?: 0L) >= floor
        }
        if (kept.size == file.readLines().count { it.isNotBlank() }) return
        file.writeText(kept.joinToString("\n", postfix = "\n"))
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

    companion object {
        /** Un mois : de quoi relire plusieurs semaines de trajets, pas une annee. */
        const val RETENTION_DAYS = 30
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
        attenteMs = attenteMs,
        intention = intention?.name,
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
        attenteMs = attenteMs,
        // Les lignes ecrites avant l'ajout du champ n'en ont pas : on ne suppose rien.
        intention = intention?.let { runCatching { Intention.valueOf(it) }.getOrNull() },
    )
}
