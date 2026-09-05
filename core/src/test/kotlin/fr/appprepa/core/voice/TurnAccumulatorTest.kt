package fr.appprepa.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Le seul endroit du chemin vocal que l'on peut eprouver sans micro ni reseau. Les
 * scenarios reproduisent les suites de messages reellement envoyees par le service.
 */
class TurnAccumulatorTest {

    @Test
    fun `les provisoires se remplacent, ils ne s'additionnent pas`() {
        val t = TurnAccumulator()
        assertNull(t.onResult("le theo", isFinal = false, speechFinal = false))
        assertNull(t.onResult("le theoreme", isFinal = false, speechFinal = false))
        assertNull(t.onResult("le theoreme de Rolle", isFinal = false, speechFinal = false))
        assertEquals("le theoreme de Rolle", t.texte)
    }

    @Test
    fun `les definitifs s'additionnent`() {
        val t = TurnAccumulator()
        t.onResult("le theoreme de Rolle", isFinal = true, speechFinal = false)
        t.onResult("s'applique sur un segment", isFinal = true, speechFinal = false)
        assertEquals("le theoreme de Rolle s'applique sur un segment", t.texte)
    }

    /**
     * Le piege principal : un definitif conclut le provisoire qu'il vient de corriger.
     * Garder les deux ferait dire deux fois le meme morceau de phrase au modele.
     */
    @Test
    fun `un definitif chasse le provisoire qu'il conclut`() {
        val t = TurnAccumulator()
        t.onResult("le theo", isFinal = false, speechFinal = false)
        t.onResult("le theoreme de Rolle", isFinal = true, speechFinal = false)
        assertEquals("le theoreme de Rolle", t.texte)
    }

    @Test
    fun `speech_final termine le tour`() {
        val t = TurnAccumulator()
        t.onResult("la derivee s'annule", isFinal = true, speechFinal = false)
        val fini = t.onResult("quelque part", isFinal = true, speechFinal = true)
        assertEquals("la derivee s'annule quelque part", fini)
    }

    /**
     * Le second chemin, celui qui compte en voiture : dans un habitacle bruyant le
     * silence n'arrive jamais, donc `speech_final` non plus. L'ecart entre deux mots,
     * lui, reste mesurable.
     */
    @Test
    fun `UtteranceEnd termine le tour quand le silence ne vient pas`() {
        val t = TurnAccumulator()
        t.onResult("la derivee s'annule quelque part", isFinal = true, speechFinal = false)
        assertEquals("la derivee s'annule quelque part", t.onUtteranceEnd())
    }

    @Test
    fun `un UtteranceEnd sans rien d'entendu ne termine rien`() {
        assertNull(TurnAccumulator().onUtteranceEnd())
    }

    /**
     * Le delai expire ou le reseau coupe alors que l'utilisateur avait commence a
     * repondre. Jeter ce qui a ete entendu le ferait noter faux.
     */
    @Test
    fun `une fermeture rend ce qui a ete entendu`() {
        val t = TurnAccumulator()
        t.onResult("je crois que c'est", isFinal = true, speechFinal = false)
        t.onResult(" le theoreme", isFinal = false, speechFinal = false)
        assertEquals("je crois que c'est le theoreme", t.onClose())
    }

    @Test
    fun `une fermeture sans rien entendu rend null`() {
        assertNull(TurnAccumulator().onClose())
    }

    @Test
    fun `les segments vides ne laissent pas d'espaces parasites`() {
        val t = TurnAccumulator()
        t.onResult("", isFinal = true, speechFinal = false)
        t.onResult("bien", isFinal = true, speechFinal = false)
        t.onResult("   ", isFinal = true, speechFinal = false)
        assertEquals("bien", t.texte)
    }
}
