package fr.appprepa.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La notice affichee dans l'application doit dire vrai.
 *
 * Une notice qui annonce un mot que le parseur ignore est pire que pas de notice : au
 * volant, on repete le mot, il ne se passe rien, et on ne sait pas si c'est le micro, le
 * bruit, ou soi-meme. Les tableaux des documents avaient deja derive de cette facon.
 */
class VoiceHelpTest {

    @Test
    fun `chaque tournure annoncee est reconnue par le parseur`() {
        VoiceHelp.ENTREES.forEach { entree ->
            entree.phrases.forEach { phrase ->
                assertEquals(
                    "« $phrase » est annonce pour « ${entree.action} »",
                    entree.commande,
                    VoiceCommandParser.parse(phrase),
                )
            }
        }
    }

    @Test
    fun `aucune tournure n'est annoncee deux fois`() {
        val toutes = VoiceHelp.ENTREES.flatMap { it.phrases }
        val doublons = toutes.groupBy { it }.filterValues { it.size > 1 }.keys
        assertTrue("tournures annoncees plusieurs fois : $doublons", doublons.isEmpty())
    }

    @Test
    fun `chaque commande utile a une entree dans la notice`() {
        val annoncees = VoiceHelp.ENTREES.map { it.commande }
        val attendues = listOf(
            VoiceCommand.Repeat, VoiceCommand.Skip, VoiceCommand.Explain,
            VoiceCommand.Undo, VoiceCommand.Revisit, VoiceCommand.RevisitExplain,
            VoiceCommand.Stop,
        )
        attendues.forEach {
            assertTrue("commande absente de la notice : $it", it in annoncees)
        }
        assertEquals("les quatre notes doivent y etre", 4, annoncees.count { it is VoiceCommand.Correct })
    }
}
