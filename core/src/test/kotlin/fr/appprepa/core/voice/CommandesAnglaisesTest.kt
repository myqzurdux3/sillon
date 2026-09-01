package fr.appprepa.core.voice

import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.Langue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sur une carte anglaise, le micro ecoute en anglais : « répète » ne sera jamais transcrit,
 * c'est « repeat » qui arrivera. Sans vocabulaire anglais, l'utilisateur perdrait toute
 * commande sur ces paquets — donc toute possibilite de corriger une note.
 */
class CommandesAnglaisesTest {

    private fun parse(t: String) = VoiceCommandParser.parse(t, Langue.ANGLAIS)

    @Test
    fun `les notes se disent d'un mot`() {
        assertEquals(VoiceCommand.Correct(Ease.AGAIN), parse("again"))
        assertEquals(VoiceCommand.Correct(Ease.AGAIN), parse("wrong"))
        assertEquals(VoiceCommand.Correct(Ease.HARD), parse("hard"))
        assertEquals(VoiceCommand.Correct(Ease.GOOD), parse("good"))
        assertEquals(VoiceCommand.Correct(Ease.GOOD), parse("that's correct"))
        assertEquals(VoiceCommand.Correct(Ease.EASY), parse("easy"))
    }

    @Test
    fun `les commandes se disent comme elles viennent`() {
        listOf("repeat", "repeat the question", "can you repeat", "say it again")
            .forEach { assertEquals(it, VoiceCommand.Repeat, parse(it)) }
        listOf("I didn't catch that", "I didn't hear")
            .forEach { assertEquals(it, VoiceCommand.Repeat, parse(it)) }

        assertEquals(VoiceCommand.Skip, parse("skip"))
        assertEquals(VoiceCommand.Skip, parse("skip this card"))
        assertEquals(VoiceCommand.Skip, parse("next"))

        assertEquals(VoiceCommand.Explain, parse("explain"))
        assertEquals(VoiceCommand.Explain, parse("I don't know"))
        assertEquals(VoiceCommand.Explain, parse("no idea"))

        assertEquals(VoiceCommand.Undo, parse("undo"))
        assertEquals(VoiceCommand.Revisit, parse("go back"))
        assertEquals(VoiceCommand.Stop, parse("stop"))
    }

    /**
     * « again » est le libelle Anki de la note la plus basse. Sur une correction c'est une
     * note ; « say it again » est une demande de repetition. Les confondre reviendrait a
     * noter « a revoir » chaque fois qu'on demande a reentendre.
     */
    @Test
    fun `again note, say it again repete`() {
        assertEquals(VoiceCommand.Correct(Ease.AGAIN), parse("again"))
        assertEquals(VoiceCommand.Repeat, parse("say it again"))
        assertEquals(VoiceCommand.Repeat, parse("once more"))
    }

    /**
     * La contrepartie de la tolerance. Ce sont de vraies reponses de fiche : si elles
     * declenchaient une commande, la carte defilerait sans note.
     */
    @Test
    fun `une reponse courte n'est pas prise pour une commande`() {
        listOf(
            "the past participle is done",
            "it is a phrasal verb meaning to continue",
            "back formation",
            "the answer is the present perfect",
            "you pass the salt",
            "a stop consonant",
        ).forEach { assertEquals(it, VoiceCommand.None, parse(it)) }
    }

    /**
     * « done » termine une reponse anglaise bien plus souvent qu'une session. Le mot est
     * volontairement absent du vocabulaire d'arret.
     */
    @Test
    fun `done ne termine pas la session`() {
        assertEquals(VoiceCommand.None, parse("done"))
        assertEquals(VoiceCommand.None, parse("I'm done"))
    }

    @Test
    fun `le vocabulaire francais ne fuit pas dans l'anglais`() {
        // « bien » et « passe » sont des mots anglais improbables, mais surtout : le
        // parseur ne doit consulter qu'un seul vocabulaire a la fois.
        assertEquals(VoiceCommand.None, parse("répète"))
        assertEquals(VoiceCommand.None, VoiceCommandParser.parse("repeat", Langue.FRANCAIS))
    }

    @Test
    fun `le francais reste la langue par defaut`() {
        assertEquals(VoiceCommand.Repeat, VoiceCommandParser.parse("répète"))
    }
}
