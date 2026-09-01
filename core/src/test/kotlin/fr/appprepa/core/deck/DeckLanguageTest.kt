package fr.appprepa.core.deck

import fr.appprepa.core.model.Langue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckLanguageTest {

    private val decks = listOf(
        DeckInfo(1, "Maths", 10),
        DeckInfo(2, "Maths::analyse", 5),
        DeckInfo(3, "Anglais", 20),
        DeckInfo(4, "Anglais::irregular verbs", 8),
        DeckInfo(5, "Anglais::thème::presse", 3),
        DeckInfo(6, "Histoire", 4),
    )

    @Test
    fun `devine les paquets anglophones par leur nom, descendance comprise`() {
        assertEquals(setOf(3L, 4L, 5L), DeckLanguage.devines(decks))
    }

    @Test
    fun `un paquet francais dont un mot ressemble ne bascule pas`() {
        // « Vocabulaire anglais » compte ; « anglaise » aussi, c'est le meme radical.
        // Mais rien dans « Maths » ou « Histoire » ne doit basculer le micro.
        val langue = DeckLanguage.langueDe(1, DeckLanguage.devines(decks))
        assertEquals(Langue.FRANCAIS, langue)
        assertEquals(Langue.FRANCAIS, DeckLanguage.langueDe(6, DeckLanguage.devines(decks)))
    }

    @Test
    fun `le choix explicite l'emporte sur le nom`() {
        // L'utilisateur bascule Maths en anglais et sort Anglais de la liste.
        val choisis = setOf(1L, 2L)
        val effectifs = DeckLanguage.anglophones(decks, choisis)
        assertEquals(setOf(1L, 2L), effectifs)
        assertEquals(Langue.ANGLAIS, DeckLanguage.langueDe(1, effectifs))
        assertEquals(Langue.FRANCAIS, DeckLanguage.langueDe(3, effectifs))
    }

    /**
     * Le cas que l'heuristique seule ne saurait pas exprimer : un paquet nomme « Anglais »
     * ou l'on revise du vocabulaire *depuis* le francais, et qu'on veut donc en francais.
     */
    @Test
    fun `un ensemble vide choisi explicitement est respecte`() {
        val effectifs = DeckLanguage.anglophones(decks, emptySet())
        assertTrue(effectifs.isEmpty())
        assertEquals(Langue.FRANCAIS, DeckLanguage.langueDe(3, effectifs))
    }

    @Test
    fun `sans choix, on devine`() {
        assertEquals(setOf(3L, 4L, 5L), DeckLanguage.anglophones(decks, null))
    }

    @Test
    fun `un identifiant perime disparait de la selection`() {
        assertEquals(setOf(3L), DeckLanguage.anglophones(decks, setOf(3L, 999L)))
    }

    @Test
    fun `un paquet dont un ancetre seulement porte le nom bascule aussi`() {
        // « Anglais::thème::presse » : ni « thème » ni « presse » ne trahit l'anglais,
        // c'est l'ancetre qui decide. Sans heritage, ce paquet serait ecoute en francais.
        assertEquals(Langue.ANGLAIS, DeckLanguage.langueDe(5, DeckLanguage.devines(decks)))
    }
}
