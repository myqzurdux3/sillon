package fr.appprepa.core.voice

import fr.appprepa.core.model.Ease
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCommandParserTest {
    @Test
    fun `reconnait les corrections de note`() {
        assertEquals(VoiceCommand.Correct(Ease.AGAIN), VoiceCommandParser.parse("encore"))
        assertEquals(VoiceCommand.Correct(Ease.AGAIN), VoiceCommandParser.parse("à revoir"))
        assertEquals(VoiceCommand.Correct(Ease.HARD), VoiceCommandParser.parse("difficile"))
        assertEquals(VoiceCommand.Correct(Ease.GOOD), VoiceCommandParser.parse("bien"))
        assertEquals(VoiceCommand.Correct(Ease.EASY), VoiceCommandParser.parse("facile"))
    }

    @Test
    fun `ignore les accents et la casse`() {
        assertEquals(VoiceCommand.Correct(Ease.AGAIN), VoiceCommandParser.parse("A REVOIR"))
        assertEquals(VoiceCommand.Repeat, VoiceCommandParser.parse("Répète"))
    }

    @Test
    fun `reconnait les commandes de navigation`() {
        assertEquals(VoiceCommand.Repeat, VoiceCommandParser.parse("repete"))
        assertEquals(VoiceCommand.Skip, VoiceCommandParser.parse("passe"))
        assertEquals(VoiceCommand.Explain, VoiceCommandParser.parse("je seche"))
        assertEquals(VoiceCommand.Explain, VoiceCommandParser.parse("je ne sais pas"))
        assertEquals(VoiceCommand.Undo, VoiceCommandParser.parse("annule"))
        assertEquals(VoiceCommand.Stop, VoiceCommandParser.parse("stop"))
    }

    @Test
    fun `reconnait le retour sur la carte precedente`() {
        assertEquals(VoiceCommand.Revisit, VoiceCommandParser.parse("reviens"))
        assertEquals(VoiceCommand.Revisit, VoiceCommandParser.parse("la precedente"))
        assertEquals(VoiceCommand.Revisit, VoiceCommandParser.parse("carte d'avant"))
        assertEquals(VoiceCommand.Revisit, VoiceCommandParser.parse("retour"))
    }

    @Test
    fun `reconnait la demande d'explication sur la precedente`() {
        assertEquals(VoiceCommand.RevisitExplain, VoiceCommandParser.parse("explique la precedente"))
        assertEquals(VoiceCommand.RevisitExplain, VoiceCommandParser.parse("c'etait quoi deja"))
        assertEquals(VoiceCommand.RevisitExplain, VoiceCommandParser.parse("redis moi la precedente"))
    }

    @Test
    fun `annule reste distinct du retour`() {
        // « annule » jette la note ; « reviens » ouvre une parenthese pour la refaire.
        assertEquals(VoiceCommand.Undo, VoiceCommandParser.parse("annule"))
        assertEquals(VoiceCommand.Undo, VoiceCommandParser.parse("annuler"))
    }

    @Test
    fun `tolere la ponctuation et les espaces`() {
        assertEquals(VoiceCommand.Stop, VoiceCommandParser.parse("  Stop.  "))
        assertEquals(VoiceCommand.Correct(Ease.EASY), VoiceCommandParser.parse("facile !"))
    }

    @Test
    fun `ne declenche pas sur une commande noyee dans une phrase`() {
        assertEquals(VoiceCommand.None, VoiceCommandParser.parse("c'est un calcul facile a mener"))
        assertEquals(VoiceCommand.None, VoiceCommandParser.parse("il faut passer par la contraposee"))
        assertEquals(VoiceCommand.None, VoiceCommandParser.parse("non, la reponse est l'inverse"))
    }

    @Test
    fun `accepte une commande accompagnee d'un mot de politesse`() {
        assertEquals(VoiceCommand.Correct(Ease.EASY), VoiceCommandParser.parse("c'etait facile"))
        assertEquals(VoiceCommand.Stop, VoiceCommandParser.parse("stop s'il te plait"))
    }

    @Test
    fun `renvoie None sur une reponse ordinaire`() {
        assertEquals(
            VoiceCommand.None,
            VoiceCommandParser.parse("le theoreme de Rolle s'applique sur un intervalle ferme"),
        )
    }

    @Test
    fun `renvoie None sur une chaine vide`() {
        assertEquals(VoiceCommand.None, VoiceCommandParser.parse("   "))
    }

    @Test
    fun `reconnait les refus les plus naturels apres un verdict trop genereux`() {
        listOf("faux", "c'est faux", "mauvais", "nul", "pas bon").forEach {
            assertEquals(it, VoiceCommand.Correct(Ease.AGAIN), VoiceCommandParser.parse(it))
        }
        assertEquals(VoiceCommand.Correct(Ease.HARD), VoiceCommandParser.parse("trop dur"))
    }
}
