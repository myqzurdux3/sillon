package fr.appprepa.core.deck

import fr.appprepa.core.model.ReviewCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plusieurs paquets se revisent entrelaces. Un trajet coupe en blocs thematiques
 * reviserait mal : on veut alterner.
 */
class DeckMergeTest {

    /** Toujours la meme cle : une carte, c'est une note et un rang. */
    private fun interleave(perDeck: List<List<ReviewCard>>, limit: Int) =
        DeckMerge.interleave(perDeck, limit) { it.noteId to it.cardOrd }


    private fun cards(deck: String, count: Int, from: Long) = (0 until count).map {
        ReviewCard(from + it, 0, deck, "recto", "verso", 4, false)
    }

    @Test
    fun `les paquets alternent une carte a la fois`() {
        val merged = interleave(
            listOf(cards("Maths", 3, 100), cards("Info", 3, 200)),
            limit = 6,
        )
        assertEquals(
            listOf("Maths", "Info", "Maths", "Info", "Maths", "Info"),
            merged.map { it.deckName },
        )
    }

    @Test
    fun `un paquet epuise laisse la place aux autres`() {
        val merged = interleave(
            listOf(cards("Maths", 1, 100), cards("Info", 3, 200)),
            limit = 10,
        )
        assertEquals(listOf("Maths", "Info", "Info", "Info"), merged.map { it.deckName })
    }

    @Test
    fun `la limite tronque le resultat`() {
        val merged = interleave(
            listOf(cards("Maths", 10, 100), cards("Info", 10, 200)),
            limit = 3,
        )
        assertEquals(3, merged.size)
    }

    @Test
    fun `moins de cartes que la limite rend ce qu'il y a`() {
        val merged = interleave(listOf(cards("Maths", 2, 100)), limit = 40)
        assertEquals(2, merged.size)
    }

    @Test
    fun `aucune carte en double`() {
        val merged = interleave(
            listOf(cards("Maths", 5, 100), cards("Info", 5, 100)),
            limit = 20,
        )
        assertEquals(merged.size, merged.map { it.noteId to it.cardOrd }.distinct().size)
    }

    @Test
    fun `une liste vide ne casse rien`() {
        assertTrue(interleave(emptyList(), limit = 10).isEmpty())
        assertTrue(interleave(listOf(emptyList()), limit = 10).isEmpty())
    }

    @Test
    fun `l'ordre a l'interieur d'un paquet est preserve`() {
        val merged = interleave(
            listOf(cards("Maths", 3, 100), cards("Info", 3, 200)),
            limit = 6,
        )
        assertEquals(
            listOf(100L, 101L, 102L),
            merged.filter { it.deckName == "Maths" }.map { it.noteId },
        )
    }
}
