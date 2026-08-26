package fr.appprepa.core.deck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La hierarchie Anki passe par « :: ». Cocher un parent coche ses enfants : l'application
 * interroge ensuite chaque paquet explicitement, sans parier sur ce que le provider fait
 * d'un parent.
 */
class DeckSelectionTest {

    private fun deck(id: Long, name: String, due: Int = 0) = DeckInfo(id, name, due)

    private val decks = listOf(
        deck(1, "Default", 18),
        deck(2, "Info", 0),
        deck(3, "Info::arbres", 5),
        deck(4, "Info::graphe", 7),
        deck(5, "Maths", 12),
        deck(6, "Maths::analyse", 3),
        deck(7, "Maths::analyse::series", 4),
    )

    @Test
    fun `le nom court retire le chemin du parent`() {
        assertEquals("arbres", deck(3, "Info::arbres").shortName)
        assertEquals("series", deck(7, "Maths::analyse::series").shortName)
        assertEquals("Default", deck(1, "Default").shortName)
    }

    @Test
    fun `la profondeur sert a indenter`() {
        assertEquals(0, deck(1, "Default").depth)
        assertEquals(1, deck(3, "Info::arbres").depth)
        assertEquals(2, deck(7, "Maths::analyse::series").depth)
    }

    @Test
    fun `les paquets sont ordonnes par chemin, enfants sous leur parent`() {
        val ordered = DeckSelection.ordered(decks).map { it.name }
        assertEquals(
            listOf(
                "Default",
                "Info",
                "Info::arbres",
                "Info::graphe",
                "Maths",
                "Maths::analyse",
                "Maths::analyse::series",
            ),
            ordered,
        )
    }

    @Test
    fun `cocher un parent coche tous ses descendants`() {
        val selected = DeckSelection.toggle(decks, selected = emptySet(), deckId = 5)
        assertEquals(setOf(5L, 6L, 7L), selected)
    }

    @Test
    fun `decocher un parent decoche tous ses descendants`() {
        val selected = DeckSelection.toggle(decks, selected = setOf(5L, 6L, 7L), deckId = 5)
        assertTrue(selected.isEmpty())
    }

    @Test
    fun `cocher un enfant ne coche pas le parent`() {
        val selected = DeckSelection.toggle(decks, selected = emptySet(), deckId = 3)
        assertEquals(setOf(3L), selected)
    }

    @Test
    fun `un nom qui commence pareil n'est pas un enfant`() {
        // « Maths2 » n'est pas un sous-paquet de « Maths ».
        val avecPiege = decks + deck(8, "Maths2", 1)
        val selected = DeckSelection.toggle(avecPiege, selected = emptySet(), deckId = 5)
        assertEquals(setOf(5L, 6L, 7L), selected)
    }

    @Test
    fun `le total des cartes dues additionne la selection`() {
        assertEquals(12 + 3 + 4, DeckSelection.dueTotal(decks, setOf(5L, 6L, 7L)))
        assertEquals(0, DeckSelection.dueTotal(decks, emptySet()))
    }

    @Test
    fun `rien de coche veut dire tous les paquets`() {
        assertEquals(decks.map { it.id }.toSet(), DeckSelection.effective(decks, emptySet()))
        assertEquals(setOf(3L), DeckSelection.effective(decks, setOf(3L)))
    }

    @Test
    fun `une selection qui pointe un paquet disparu est ignoree`() {
        assertEquals(setOf(3L), DeckSelection.effective(decks, setOf(3L, 999L)))
    }
}
