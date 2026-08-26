package fr.appprepa.core.deck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replier une famille de sous-paquets quand la liste devient trop longue. */
class DeckFoldTest {

    private fun deck(id: Long, name: String, due: Int = 0) = DeckInfo(id, name, due)

    private val decks = listOf(
        deck(1, "Default", 18),
        deck(2, "Maths", 4),
        deck(3, "Maths::analyse", 3),
        deck(4, "Maths::analyse::series", 5),
        deck(5, "Maths::algebre", 2),
        deck(6, "Info", 7),
    )

    @Test
    fun `sans repli tout est visible`() {
        assertEquals(6, DeckSelection.visible(decks, collapsed = emptySet()).size)
    }

    @Test
    fun `replier un paquet cache ses descendants`() {
        val visible = DeckSelection.visible(decks, collapsed = setOf(2L)).map { it.name }
        assertEquals(listOf("Default", "Info", "Maths"), visible.sorted())
    }

    @Test
    fun `replier un paquet cache aussi les petits-enfants`() {
        val visible = DeckSelection.visible(decks, collapsed = setOf(2L)).map { it.name }
        assertFalse(visible.contains("Maths::analyse::series"))
    }

    @Test
    fun `replier un enfant ne cache pas son parent`() {
        val visible = DeckSelection.visible(decks, collapsed = setOf(3L)).map { it.name }
        assertTrue(visible.contains("Maths"))
        assertTrue(visible.contains("Maths::analyse"))
        assertFalse(visible.contains("Maths::analyse::series"))
    }

    @Test
    fun `seuls les paquets a descendance portent un chevron`() {
        assertTrue(DeckSelection.hasChildren(decks, decks.first { it.id == 2L }))
        assertTrue(DeckSelection.hasChildren(decks, decks.first { it.id == 3L }))
        assertFalse(DeckSelection.hasChildren(decks, decks.first { it.id == 6L }))
        assertFalse(DeckSelection.hasChildren(decks, decks.first { it.id == 1L }))
    }

    @Test
    fun `un paquet replie annonce le total de sa famille`() {
        // Replier ne doit pas faire disparaitre les chiffres qui servent a choisir.
        assertEquals(4 + 3 + 5 + 2, DeckSelection.familyDue(decks, decks.first { it.id == 2L }))
        assertEquals(7, DeckSelection.familyDue(decks, decks.first { it.id == 6L }))
    }

    @Test
    fun `replier ne change rien a ce qui est coche`() {
        val selected = DeckSelection.toggle(decks, emptySet(), deckId = 2)
        val visible = DeckSelection.visible(decks, collapsed = setOf(2L))
        assertEquals(setOf(2L, 3L, 4L, 5L), selected)
        assertEquals(1, visible.count { it.id in selected })
    }

    @Test
    fun `un repli qui pointe un paquet disparu est sans effet`() {
        assertEquals(6, DeckSelection.visible(decks, collapsed = setOf(999L)).size)
    }
}
