package fr.appprepa.app.llm

import fr.appprepa.core.model.Langue
import fr.appprepa.core.model.ReviewCard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptsLangueTest {

    private val carte = ReviewCard(
        noteId = 1,
        cardOrd = 0,
        deckName = "Anglais",
        question = "phrasal verb: to put off",
        answer = "to postpone",
        buttonCount = 4,
        hasMedia = false,
        langue = Langue.ANGLAIS,
    )

    /**
     * Le systeme anglais est fabrique par substitution dans le systeme francais. Si la
     * phrase remplacee derive d'un caractere, la substitution devient un non-evenement
     * silencieux : le modele repondrait en francais sur une carte anglaise, et rien ne le
     * signalerait avant le premier trajet.
     */
    @Test
    fun `le systeme anglais differe reellement du francais`() {
        val fr = Prompts.system(Langue.FRANCAIS)
        val en = Prompts.system(Langue.ANGLAIS)
        assertNotEquals("la substitution n'a rien remplace", fr, en)
        assertTrue("le systeme anglais doit exiger l'anglais", en.contains("ANGLAIS"))
        assertFalse(
            "la consigne francaise ne doit pas subsister",
            en.contains("Tu parles français, en phrases courtes"),
        )
    }

    @Test
    fun `le systeme francais reste celui d'origine`() {
        assertTrue(Prompts.system(Langue.FRANCAIS).contains("Tu parles français"))
    }

    @Test
    fun `la consigne de langue du retour est explicite dans les deux sens`() {
        val versFrancais = Prompts.judge(carte, emptyList(), "to delay", "", Langue.FRANCAIS)
        assertTrue(versFrancais.contains("FRANÇAIS"))

        val versAnglais = Prompts.judge(carte, emptyList(), "to delay", "", Langue.ANGLAIS)
        assertTrue(versAnglais.contains("ANGLAIS"))
    }

    @Test
    fun `l'explication porte aussi la consigne de langue`() {
        assertTrue(Prompts.explain(carte, Langue.ANGLAIS).contains("ANGLAIS"))
        assertTrue(Prompts.explain(carte, Langue.FRANCAIS).contains("FRANÇAIS"))
    }
}
