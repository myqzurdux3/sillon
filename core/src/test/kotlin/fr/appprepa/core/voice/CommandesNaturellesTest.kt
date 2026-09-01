package fr.appprepa.core.voice

import fr.appprepa.core.model.Ease
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Les tournures reellement dites au volant. Le parseur ne reconnaissait que la commande
 * nue : « repete la question » partait au modele comme une reponse, se faisait juger faux,
 * et la carte defilait. C'est le seul defaut que l'usage a fait remonter deux fois.
 */
class CommandesNaturellesTest {

    @Test
    fun `repete se dit rarement tout seul`() {
        listOf(
            "répète",
            "répète la question",
            "tu peux répéter",
            "peux-tu répéter s'il te plaît",
            "attends répète",
            "redis la question",
            "j'ai pas entendu",
            "je n'ai pas compris la question",
        ).forEach {
            assertEquals(it, VoiceCommand.Repeat, VoiceCommandParser.parse(it))
        }
    }

    @Test
    fun `les autres commandes tolerent la meme enveloppe`() {
        assertEquals(VoiceCommand.Skip, VoiceCommandParser.parse("passe la carte"))
        assertEquals(VoiceCommand.Skip, VoiceCommandParser.parse("suivante s'il te plaît"))
        assertEquals(VoiceCommand.Explain, VoiceCommandParser.parse("explique moi"))
        assertEquals(VoiceCommand.Explain, VoiceCommandParser.parse("je bloque"))
        assertEquals(VoiceCommand.Undo, VoiceCommandParser.parse("annule ça"))
        assertEquals(VoiceCommand.Stop, VoiceCommandParser.parse("arrête tout"))
        assertEquals(VoiceCommand.Revisit, VoiceCommandParser.parse("reviens en arrière"))
    }

    /**
     * La contrepartie de la tolerance : une reponse de deux mots qui commence par un mot
     * de commande. Ce sont de vraies phrases de fiche de prepa, pas des cas d'ecole.
     */
    @Test
    fun `une reponse courte n'est pas prise pour une commande`() {
        listOf(
            "la suite est finie",
            "on passe à la limite",
            "l'ensemble est fini",
            "il faut passer par la contraposée",
            "le passage à la limite est licite",
            "c'est un calcul facile à mener",
            "non, la réponse est l'inverse",
            "arrêt de la suite en zéro",
        ).forEach {
            assertEquals(it, VoiceCommand.None, VoiceCommandParser.parse(it))
        }
    }

    @Test
    fun `les notes gardent leur exigence de phrase entiere`() {
        // Une note se dit d'un mot. L'elargir ferait passer des reponses pour des notes.
        assertEquals(VoiceCommand.Correct(Ease.GOOD), VoiceCommandParser.parse("bien"))
        assertEquals(VoiceCommand.Correct(Ease.AGAIN), VoiceCommandParser.parse("pas bon"))
        assertEquals(VoiceCommand.None, VoiceCommandParser.parse("bien vu la formule"))
    }
}
