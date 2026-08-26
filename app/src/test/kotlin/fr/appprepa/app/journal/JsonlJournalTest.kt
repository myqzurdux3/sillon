package fr.appprepa.app.journal

import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.JournalRecord
import fr.appprepa.core.model.Verdict
import fr.appprepa.core.model.WriteMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JsonlJournalTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun record(id: Long, ease: Ease? = Ease.GOOD) = JournalRecord(
        atMs = 1_700_000_000_000 + id,
        noteId = id,
        cardOrd = 0,
        deckName = "Prepa",
        question = "question $id",
        transcript = "reponse $id",
        proposedEase = ease,
        committedEase = null,
        verdict = Verdict.CORRECT,
        mode = WriteMode.JOURNAL_ONLY,
    )

    @Test
    fun `ecrit et relit les entrees dans l'ordre`() = runBlocking {
        val journal = JsonlJournal(folder.newFile("journal.jsonl"))
        journal.record(record(1))
        journal.record(record(2))

        val all = journal.readAll()
        assertEquals(listOf(1L, 2L), all.map { it.noteId })
        assertEquals("reponse 2", all[1].transcript)
    }

    @Test
    fun `une entree par ligne`() = runBlocking {
        val file = folder.newFile("journal.jsonl")
        val journal = JsonlJournal(file)
        journal.record(record(1))
        journal.record(record(2))
        assertEquals(2, file.readLines().filter { it.isNotBlank() }.size)
    }

    @Test
    fun `survit a une ligne corrompue`() = runBlocking {
        val file = folder.newFile("journal.jsonl")
        val journal = JsonlJournal(file)
        journal.record(record(1))
        file.appendText("ceci n'est pas du json\n")
        journal.record(record(2))

        val all = journal.readAll()
        assertEquals("la ligne illisible est ignoree", listOf(1L, 2L), all.map { it.noteId })
    }

    @Test
    fun `relit un fichier absent comme une liste vide`() = runBlocking {
        val journal = JsonlJournal(folder.root.resolve("jamais-ecrit.jsonl"))
        assertTrue(journal.readAll().isEmpty())
    }

    @Test
    fun `preserve une note nulle`() = runBlocking {
        val journal = JsonlJournal(folder.newFile("journal.jsonl"))
        journal.record(record(1, ease = null))
        assertEquals(null, journal.readAll().single().proposedEase)
    }

    @Test
    fun `ne rend que les entrees du jour`() = runBlocking {
        val journal = JsonlJournal(folder.newFile("journal.jsonl"))
        val now = 1_700_000_000_000L
        journal.record(record(1).copy(atMs = now - 3 * 86_400_000L))
        journal.record(record(2).copy(atMs = now))

        assertEquals(listOf(2L), journal.today(now).map { it.noteId })
    }
}
