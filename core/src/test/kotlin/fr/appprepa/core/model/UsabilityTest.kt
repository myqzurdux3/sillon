package fr.appprepa.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * La regle qui a fait echouer l'application sur la collection reelle : elle ecartait
 * 96 % des cartes parce qu'un fichier y etait joint, sans regarder s'il y avait du texte.
 */
class UsabilityTest {

    private fun carte(question: String, answer: String, media: Boolean = false) =
        ReviewCard(1, 0, "Deck", question, answer, 4, media)

    @Test
    fun `un media joint n'ecarte pas une carte dont le texte se tient`() {
        // Le cas majoritaire du journal : 233 cartes de texte parfaitement lisible,
        // ecartees pour la seule raison qu'un fichier etait attache a la note.
        assertNull(Usability.raison(carte("Algo de Dijkstra, complexité ?", "O(n log n)", media = true)))
    }

    @Test
    fun `un verso sans texte est ecarte`() {
        assertEquals(Usability.VERSO_MUET, Usability.raison(carte("Le schéma du cycle ?", "", media = true)))
    }

    @Test
    fun `un verso reduit a de la ponctuation est ecarte`() {
        // Ce que laisse le nettoyage HTML d'un verso qui n'etait qu'une balise image.
        assertEquals(Usability.VERSO_MUET, Usability.raison(carte("question", "  <>  . ", media = true)))
    }

    @Test
    fun `un recto sans texte est ecarte en premier`() {
        assertEquals(Usability.RECTO_MUET, Usability.raison(carte("", "", media = true)))
    }

    @Test
    fun `les reponses tres courtes restent des reponses`() {
        // Elargir le seuil au-dela d'un caractere reviendrait a supposer ce qui fait
        // « assez de texte » — la supposition exacte qui a produit la regle precedente.
        listOf("n", "42", "oui", "n log n", "π/2").forEach {
            assertNull("« $it » est une reponse de fiche", Usability.raison(carte("q", it)))
        }
    }

    @Test
    fun `une carte entierement textuelle passe`() {
        assertNull(Usability.raison(carte("recto", "verso")))
    }
}
