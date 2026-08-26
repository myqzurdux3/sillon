package fr.appprepa.app.llm

import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.Judgement
import fr.appprepa.core.model.ReformulatedQuestion
import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.model.Verdict
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

object Prompts {

    const val MAX_SPOKEN_WORDS = 40

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val SYSTEM = """
        Tu fais réviser des cartes Anki à l'oral, à quelqu'un qui conduit.
        Tu parles français, en phrases courtes, sans jamais lister ni énumérer à voix haute.
        Tu ne dis jamais « carte », « recto », « verso » : tu poses une question, c'est tout.

        Les cartes contiennent du LaTeX. Tout ce que tu écris sera lu par une synthèse
        vocale : énonce donc les formules en français parlé, jamais la notation brute.
        « \\(\\frac{\\pi}{2}\\) » se dit « pi sur deux », « \\(x^2\\) » se dit
        « x au carré », « \\(\\cos(x)\\) » se dit « cosinus de x ».
        Aucun backslash, aucune accolade, aucun symbole ne doit apparaître dans ta réponse.

        Tu réponds uniquement par un objet JSON, sans texte autour.
    """.trimIndent()

    fun reformulate(card: ReviewCard, memoryText: String): String = buildString {
        appendLine("Deck : ${card.deckName}")
        appendLine("Recto : ${card.question}")
        appendLine("Verso : ${card.answer}")
        if (memoryText.isNotBlank()) appendLine("Contexte de la session : $memoryText")
        appendLine()
        appendLine(
            """
            Transforme le recto en UNE question orale, naturelle, énonçable d'un trait.
            Donne aussi les points attendus dans la réponse, en trois éléments au maximum.
            Réponds par : {"question": "...", "expected_points": ["...", "..."]}
            """.trimIndent(),
        )
    }

    fun judge(
        card: ReviewCard,
        expectedPoints: List<String>,
        transcript: String,
        memoryText: String,
    ): String = buildString {
        appendLine("Question posée : ${card.question}")
        appendLine("Réponse de référence : ${card.answer}")
        if (expectedPoints.isNotEmpty()) {
            appendLine("Points attendus : ${expectedPoints.joinToString(" ; ")}")
        }
        appendLine("Réponse orale de l'élève : $transcript")
        if (memoryText.isNotBlank()) appendLine("Contexte de la session : $memoryText")
        appendLine()
        appendLine(
            """
            Juge le FOND, pas la forme sonore. La réponse vient d'une transcription
            automatique : les termes techniques peuvent être mal orthographiés ou coupés.
            Ne sanctionne jamais une erreur de transcription, seulement une erreur de savoir.

            verdict : "correct", "partiel" ou "faux".
            ease : 1 si faux, 2 si partiel, 3 si correct, 4 si correct ET formulé avec précision.
            spoken_feedback : ce que tu diras à voix haute. $MAX_SPOKEN_WORDS mots maximum.
              Si la réponse est fausse ou partielle, donne l'essentiel de la bonne réponse.
            formulation_note : uniquement si le fond était juste mais la formulation confuse.
              Une remarque courte et actionnable. Sinon null.
            topic : le thème de la carte, deux ou trois mots.

            Réponds par :
            {"verdict":"...","ease":0,"spoken_feedback":"...","missed":["..."],
             "formulation_note":null,"topic":"..."}
            """.trimIndent(),
        )
    }

    fun explain(card: ReviewCard): String =
        """
        Question : ${card.question}
        Réponse : ${card.answer}

        L'élève sèche. Explique-lui la réponse à voix haute, en $MAX_SPOKEN_WORDS mots maximum,
        en allant à l'essentiel. Réponds par : {"spoken_feedback": "..."}
        """.trimIndent()

    fun parseReformulation(raw: String): ReformulatedQuestion {
        val obj = extractObject(raw)
        val question = obj["question"]?.jsonPrimitive?.contentOrNull?.trim()
        require(!question.isNullOrEmpty()) { "reformulation sans question" }
        val points = obj["expected_points"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        return ReformulatedQuestion(question, points)
    }

    fun parseSpokenFeedback(raw: String): String {
        val obj = extractObject(raw)
        val text = obj["spoken_feedback"]?.jsonPrimitive?.contentOrNull?.trim()
        require(!text.isNullOrEmpty()) { "sortie sans spoken_feedback" }
        return truncate(text)
    }

    /**
     * Le code ne fait pas confiance a `ease` : il la borne au nombre de boutons de la carte
     * et, si elle est absente ou aberrante, la recalcule depuis le verdict.
     */
    fun parseJudgement(raw: String, buttonCount: Int): Judgement {
        val obj = extractObject(raw)

        val verdict = when (obj["verdict"]?.jsonPrimitive?.contentOrNull?.lowercase()?.trim()) {
            "correct" -> Verdict.CORRECT
            "partiel" -> Verdict.PARTIEL
            "faux" -> Verdict.FAUX
            else -> throw IllegalArgumentException("verdict absent ou inconnu")
        }

        val ease = obj["ease"]?.jsonPrimitive?.intOrNull
            ?.let { Ease.fromValue(it) }
            ?: Ease.fromVerdict(verdict)

        val feedback = obj["spoken_feedback"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

        return Judgement(
            verdict = verdict,
            ease = ease.clampTo(buttonCount),
            spokenFeedback = truncate(feedback),
            missed = obj["missed"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: emptyList(),
            formulationNote = obj["formulation_note"]?.jsonPrimitive?.contentOrNull
                ?.trim()?.takeIf { it.isNotEmpty() && it != "null" },
            topic = obj["topic"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /** Le modele peut encadrer son JSON de texte ; on recupere le premier objet complet. */
    private fun extractObject(raw: String): JsonObject {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        require(start >= 0 && end > start) { "aucun objet JSON dans la sortie du modele" }
        return runCatching { json.parseToJsonElement(raw.substring(start, end + 1)) as JsonObject }
            .getOrElse { throw IllegalArgumentException("JSON illisible : ${it.message}") }
    }

    /** Coupe a la phrase : un verso de fiche de prepa lu en entier casse le rythme. */
    internal fun truncate(text: String): String {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size <= MAX_SPOKEN_WORDS) return text.trim()
        val cut = words.take(MAX_SPOKEN_WORDS).joinToString(" ")
        val lastStop = cut.lastIndexOfAny(charArrayOf('.', '!', '?'))
        return if (lastStop > cut.length / 2) cut.substring(0, lastStop + 1) else "$cut…"
    }
}
