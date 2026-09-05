package fr.appprepa.app.journal

import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.Intention
import fr.appprepa.core.model.JournalRecord
import fr.appprepa.core.model.Verdict
import fr.appprepa.core.model.WriteMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Le journal est le seul temoin de ce qui se passe reellement en voiture : c'est lui qui
 * a fourni la preuve des quatre defauts du 2 septembre, alors que trois series de
 * corrections avaient ete livrees a l'aveugle. Un champ de diagnostic qui n'atteint pas
 * le fichier ne sert donc a rien, et ne se verrait qu'au trajet suivant.
 */
class JournalDiagnosticTest {

    private fun fichier() = File.createTempFile("journal", ".jsonl").also { it.delete() }

    private fun entree(attente: Long?, intention: Intention?) = JournalRecord(
        atMs = 1_000L,
        noteId = 42,
        cardOrd = 0,
        deckName = "Info",
        question = "recto",
        transcript = "ma reponse",
        proposedEase = Ease.GOOD,
        committedEase = null,
        verdict = Verdict.CORRECT,
        mode = WriteMode.JOURNAL_ONLY,
        attenteMs = attente,
        intention = intention,
    )

    @Test
    fun `l'attente et l'intention atteignent le fichier`() = runBlocking {
        val f = fichier()
        JsonlJournal(f).record(entree(3_412L, Intention.REPONSE))

        val ligne = f.readLines().single()
        assertTrue("attenteMs absent de « $ligne »", ligne.contains("\"attenteMs\":3412"))
        assertTrue("intention absente de « $ligne »", ligne.contains("\"intention\":\"REPONSE\""))
        f.delete()
        Unit
    }

    @Test
    fun `l'attente et l'intention se relisent`() = runBlocking {
        val f = fichier()
        val journal = JsonlJournal(f)
        journal.record(entree(3_412L, Intention.REPETER))

        val relu = journal.readAll().single()
        assertEquals(3_412L, relu.attenteMs)
        assertEquals(Intention.REPETER, relu.intention)
        f.delete()
        Unit
    }

    /** Les lignes ecrites avant l'ajout des champs doivent rester lisibles. */
    @Test
    fun `une ligne ancienne sans les nouveaux champs se relit sans rien inventer`() = runBlocking {
        val f = fichier()
        f.writeText(
            """{"ts":1000,"noteId":42,"cardOrd":0,"deck":"Info","question":"recto",""" +
                """"transcript":"r","mode":"JOURNAL_ONLY"}""" + "\n",
        )
        val relu = JsonlJournal(f).readAll().single()
        assertEquals(null, relu.attenteMs)
        assertEquals(null, relu.intention)
        f.delete()
        Unit
    }
}
