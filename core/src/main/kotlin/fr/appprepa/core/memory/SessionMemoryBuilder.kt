package fr.appprepa.core.memory

import fr.appprepa.core.model.Judgement
import fr.appprepa.core.model.SessionMemory
import fr.appprepa.core.model.Verdict

object SessionMemoryBuilder {

    private const val MAX_TOPICS = 8
    private const val MAX_NOTES = 5
    private const val MAX_RENDERED = 400

    fun absorb(memory: SessionMemory, judgement: Judgement): SessionMemory {
        val topic = judgement.topic?.trim()?.takeIf { it.isNotEmpty() }
        val missed = if (topic != null && judgement.verdict != Verdict.CORRECT) {
            (memory.missedTopics - topic + topic).takeLast(MAX_TOPICS)
        } else {
            memory.missedTopics
        }

        val note = judgement.formulationNote?.trim()?.takeIf { it.isNotEmpty() }
        val notes = if (note != null) {
            (memory.formulationNotes + note).takeLast(MAX_NOTES)
        } else {
            memory.formulationNotes
        }

        return memory.copy(
            missedTopics = missed,
            formulationNotes = notes,
            answered = memory.answered + 1,
            correct = memory.correct + if (judgement.verdict == Verdict.CORRECT) 1 else 0,
        )
    }

    /**
     * Rendu injecte dans les prompts. Tronque a [MAX_RENDERED] caracteres : le cout du
     * contexte doit rester constant, quelle que soit la longueur de la session.
     */
    fun render(memory: SessionMemory): String {
        if (memory.answered == 0) return ""

        val parts = buildList {
            add("Déjà traité : ${memory.answered} cartes, ${memory.correct} justes.")
            if (memory.missedTopics.isNotEmpty()) {
                add("Thèmes ratés : ${memory.missedTopics.joinToString(", ")}.")
            }
            if (memory.formulationNotes.isNotEmpty()) {
                add("Formulation : ${memory.formulationNotes.joinToString(" / ")}.")
            }
        }

        val rendered = parts.joinToString(" ")
        return if (rendered.length <= MAX_RENDERED) rendered else rendered.take(MAX_RENDERED - 1) + "…"
    }
}
