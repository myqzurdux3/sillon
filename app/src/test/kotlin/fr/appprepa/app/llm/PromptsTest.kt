package fr.appprepa.app.llm

import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.model.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptsTest {

    private val card = ReviewCard(
        noteId = 1,
        cardOrd = 0,
        deckName = "Prepa maths",
        question = "Enonce du theoreme de Rolle",
        answer = "Si f est continue sur [a,b], derivable sur ]a,b[ et f(a)=f(b), alors il existe c",
        buttonCount = 4,
        hasMedia = false,
    )

    @Test
    fun `le prompt de reformulation contient recto verso et deck`() {
        val prompt = Prompts.reformulate(card, memoryText = "")
        assertTrue(prompt.contains("Enonce du theoreme de Rolle"))
        assertTrue(prompt.contains("Prepa maths"))
        assertTrue(prompt.contains(card.answer))
    }

    @Test
    fun `le prompt de reformulation injecte la memoire quand elle existe`() {
        val prompt = Prompts.reformulate(card, memoryText = "Themes rates : Cauchy.")
        assertTrue(prompt.contains("Cauchy"))
    }

    @Test
    fun `le prompt de jugement demande la tolerance a la transcription`() {
        val prompt = Prompts.judge(card, listOf("continuite"), "euh f continue sur a b", "")
        assertTrue(prompt.contains("euh f continue sur a b"))
        assertTrue(
            "la consigne de tolerance protege des termes techniques mal transcrits",
            prompt.lowercase().contains("transcription"),
        )
    }

    @Test
    fun `le prompt systeme interdit la notation brute`() {
        val system = Prompts.SYSTEM.lowercase()
        assertTrue("les cartes sont pleines de LaTeX", system.contains("latex"))
        assertTrue(system.contains("synthèse vocale") || system.contains("vocale"))
        assertTrue("il faut interdire explicitement les backslash", system.contains("backslash"))
    }

    @Test
    fun `parse un jugement complet`() {
        val json = """
            {"verdict":"correct","ease":3,"spoken_feedback":"Bien vu.",
             "missed":[],"formulation_note":null,"topic":"Rolle"}
        """.trimIndent()
        val judgement = Prompts.parseJudgement(json, buttonCount = 4)
        assertEquals(Verdict.CORRECT, judgement.verdict)
        assertEquals(Ease.GOOD, judgement.ease)
        assertEquals("Bien vu.", judgement.spokenFeedback)
        assertEquals("Rolle", judgement.topic)
        assertNull(judgement.formulationNote)
    }

    @Test
    fun `borne une ease hors limites sur le button count`() {
        val json = """{"verdict":"correct","ease":4,"spoken_feedback":"ok","missed":[],
            "formulation_note":null,"topic":"t"}"""
        assertEquals(Ease.HARD, Prompts.parseJudgement(json, buttonCount = 2).ease)
    }

    @Test
    fun `recalcule l'ease depuis le verdict quand elle est absente ou aberrante`() {
        val absent = """{"verdict":"partiel","spoken_feedback":"ok","missed":[],"topic":"t"}"""
        assertEquals(Ease.HARD, Prompts.parseJudgement(absent, buttonCount = 4).ease)

        val aberrante = """{"verdict":"faux","ease":99,"spoken_feedback":"ok","missed":[],"topic":"t"}"""
        assertEquals(Ease.AGAIN, Prompts.parseJudgement(aberrante, buttonCount = 4).ease)
    }

    @Test
    fun `tronque un retour parle trop long`() {
        val long = "Alors. " + "un mot ".repeat(200)
        val json = """{"verdict":"correct","ease":3,"spoken_feedback":"$long","missed":[],"topic":"t"}"""
        val judgement = Prompts.parseJudgement(json, buttonCount = 4)
        assertTrue(
            "un verso de fiche de prepa ne doit pas etre lu en entier",
            judgement.spokenFeedback.split(" ").size <= Prompts.MAX_SPOKEN_WORDS + 1,
        )
    }

    @Test
    fun `tolere du texte autour du JSON`() {
        val noisy = """Voici le resultat :
            {"verdict":"faux","ease":1,"spoken_feedback":"Non.","missed":["a"],"topic":"t"}
            Fin."""
        assertEquals(Verdict.FAUX, Prompts.parseJudgement(noisy, buttonCount = 4).verdict)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejette une sortie sans JSON exploitable`() {
        Prompts.parseJudgement("je n'ai pas compris la question", buttonCount = 4)
    }

    @Test
    fun `parse une reformulation`() {
        val json = """{"question":"Que dit le theoreme de Rolle ?","expected_points":["continuite","derivabilite"]}"""
        val reformulated = Prompts.parseReformulation(json)
        assertEquals("Que dit le theoreme de Rolle ?", reformulated.question)
        assertEquals(listOf("continuite", "derivabilite"), reformulated.expectedPoints)
    }

    @Test
    fun `s'arrete au premier objet complet, pas a la derniere accolade`() {
        val bavard = """
            Voici : {"verdict":"correct","ease":3,"spoken_feedback":"Oui."}
            Et si tu veux un exemple : {"verdict":"faux"}
        """.trimIndent()
        assertEquals(Verdict.CORRECT, Prompts.parseJudgement(bavard, buttonCount = 4).verdict)
    }

    @Test
    fun `une accolade dans le texte parle ne casse pas la lecture`() {
        val json = """{"verdict":"partiel","ease":2,"spoken_feedback":"L'ensemble {0} compte.",
                       "topic":"ensembles"}"""
        val judgement = Prompts.parseJudgement(json, buttonCount = 4)
        assertEquals(Verdict.PARTIEL, judgement.verdict)
        assertEquals("L'ensemble {0} compte.", judgement.spokenFeedback)
    }
}
