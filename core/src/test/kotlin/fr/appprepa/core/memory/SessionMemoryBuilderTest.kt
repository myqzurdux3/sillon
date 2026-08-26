package fr.appprepa.core.memory

import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.Judgement
import fr.appprepa.core.model.SessionMemory
import fr.appprepa.core.model.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMemoryBuilderTest {

    private fun judgement(
        verdict: Verdict = Verdict.CORRECT,
        topic: String? = null,
        formulation: String? = null,
    ) = Judgement(
        verdict = verdict,
        ease = Ease.fromVerdict(verdict),
        spokenFeedback = "peu importe",
        missed = emptyList(),
        formulationNote = formulation,
        topic = topic,
    )

    @Test
    fun `compte les reponses et les bonnes reponses`() {
        var memory = SessionMemory()
        memory = SessionMemoryBuilder.absorb(memory, judgement(Verdict.CORRECT))
        memory = SessionMemoryBuilder.absorb(memory, judgement(Verdict.FAUX))
        assertEquals(2, memory.answered)
        assertEquals(1, memory.correct)
    }

    @Test
    fun `retient le theme uniquement quand la reponse n'est pas correcte`() {
        var memory = SessionMemory()
        memory = SessionMemoryBuilder.absorb(memory, judgement(Verdict.CORRECT, topic = "Rolle"))
        assertEquals(emptyList<String>(), memory.missedTopics)

        memory = SessionMemoryBuilder.absorb(memory, judgement(Verdict.FAUX, topic = "Cauchy"))
        assertEquals(listOf("Cauchy"), memory.missedTopics)
    }

    @Test
    fun `ne repete pas deux fois le meme theme rate`() {
        var memory = SessionMemory()
        memory = SessionMemoryBuilder.absorb(memory, judgement(Verdict.FAUX, topic = "Cauchy"))
        memory = SessionMemoryBuilder.absorb(memory, judgement(Verdict.PARTIEL, topic = "Cauchy"))
        assertEquals(listOf("Cauchy"), memory.missedTopics)
    }

    @Test
    fun `borne les themes rates a huit entrees en gardant les plus recentes`() {
        var memory = SessionMemory()
        repeat(12) { index ->
            memory = SessionMemoryBuilder.absorb(
                memory,
                judgement(Verdict.FAUX, topic = "theme$index"),
            )
        }
        assertEquals(8, memory.missedTopics.size)
        assertEquals("theme11", memory.missedTopics.last())
        assertEquals("theme4", memory.missedTopics.first())
    }

    @Test
    fun `borne les remarques de formulation a cinq entrees`() {
        var memory = SessionMemory()
        repeat(7) { index ->
            memory = SessionMemoryBuilder.absorb(
                memory,
                judgement(formulation = "remarque$index"),
            )
        }
        assertEquals(5, memory.formulationNotes.size)
        assertEquals("remarque6", memory.formulationNotes.last())
    }

    @Test
    fun `ignore un theme vide ou absent`() {
        var memory = SessionMemory()
        memory = SessionMemoryBuilder.absorb(memory, judgement(Verdict.FAUX, topic = null))
        memory = SessionMemoryBuilder.absorb(memory, judgement(Verdict.FAUX, topic = "  "))
        assertEquals(emptyList<String>(), memory.missedTopics)
    }

    @Test
    fun `rend une memoire vide comme chaine vide`() {
        assertEquals("", SessionMemoryBuilder.render(SessionMemory()))
    }

    @Test
    fun `rend une memoire non vide sous 400 caracteres`() {
        var memory = SessionMemory()
        repeat(20) { index ->
            memory = SessionMemoryBuilder.absorb(
                memory,
                judgement(
                    verdict = Verdict.FAUX,
                    topic = "un theme plutot long numero $index",
                    formulation = "une remarque de formulation plutot longue numero $index",
                ),
            )
        }
        val rendered = SessionMemoryBuilder.render(memory)
        assertTrue("rendu de ${rendered.length} caracteres", rendered.length <= 400)
        assertTrue(rendered.contains("theme"))
    }
}
